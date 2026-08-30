//! The offline spam database: the `OCDB` on-disk format and its lookup
//! engine (PRD §7/§8, M0 spike).
//!
//! Design goals, in order:
//! 1. **Miss fast path**: ~99% of incoming calls are not in the DB. A Bloom
//!    filter answers those in a few memory probes before touching the index.
//! 2. **mmap only**: the file is never loaded into heap. This keeps RAM flat
//!    on low-end phones and fits the iOS Call Directory extension's memory
//!    budget by construction.
//! 3. **Compact**: numbers are sorted and delta-encoded (varint) in fixed
//!    blocks; real spam numbers cluster in prefixes, so deltas stay small.
//!    zstd over the whole file handles transport compression separately.
//!
//! Layout (all little-endian):
//! ```text
//! [ 0..64   header: magic "OCDB0001", entry_count u64, bloom_bits u64,
//!           bloom_hashes u32, block_count u32, built_days u16, reserved ]
//! [ bloom   bit array, (bloom_bits+7)/8 bytes ]
//! [ index   block_count × (first_number u64, block_offset u64) ]
//! [ blocks  per entry: varint delta ++ category u8 ++
//!           report_count u16 ++ last_seen_days u16 ]
//! ```
//! Each block holds up to [`ENTRIES_PER_BLOCK`] entries; `block_offset` is
//! relative to the blocks section. Within a block the first delta is against
//! the index's `first_number` (i.e. zero).

use std::collections::HashMap;
use std::fs::File;
use std::io::{self, BufWriter, Write};
use std::path::Path;

use memmap2::Mmap;

use crate::star::Category;

const MAGIC: &[u8; 8] = b"OCDB0001";
/// v2 appends a prefix-entry section (spam blocks that survive number
/// rotation — see PRD §13 / the Indonesia problem). v1 files stay valid;
/// the builder emits v2 only when prefix entries exist.
const MAGIC_V2: &[u8; 8] = b"OCDB0002";
const HEADER_LEN: usize = 64;
/// Prefix entry wire size: value u64 + len u8 + category u8 +
/// report_count u16 + last_seen u16.
const PREFIX_ENTRY_LEN: usize = 14;
const POW10: [u64; 16] = [
  1,
  10,
  100,
  1_000,
  10_000,
  100_000,
  1_000_000,
  10_000_000,
  100_000_000,
  1_000_000_000,
  10_000_000_000,
  100_000_000_000,
  1_000_000_000_000,
  10_000_000_000_000,
  100_000_000_000_000,
  1_000_000_000_000_000,
];

fn ndigits(n: u64) -> u32 {
  if n == 0 { 1 } else { n.ilog10() + 1 }
}
pub const ENTRIES_PER_BLOCK: usize = 256;
/// ~9.6 bits/entry ⇒ ≈1% Bloom false-positive rate; k=7 hashes is optimal.
const BLOOM_BITS_PER_ENTRY: f64 = 9.585;
const BLOOM_HASHES: u32 = 7;

/// One database record. `number` is the E.164 digits as an integer
/// (max 15 digits, fits u64). `last_seen_days` is days since the Unix epoch
/// (u16 reaches 2149) and drives the aging policy (PRD §8).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct DbEntry {
  pub number: u64,
  pub category: Category,
  pub report_count: u16,
  pub last_seen_days: u16,
}

/// Payload returned by lookups.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct DbEntryInfo {
  pub category: Category,
  pub report_count: u16,
  pub last_seen_days: u16,
}

/// A spam *block*: any number starting with these digits matches. Emitted
/// by the pipeline when many reported numbers cluster in one allocation
/// block — the defense that survives spammers rotating fresh SIMs from
/// the same batch.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct PrefixEntry {
  /// The prefix digits as an integer (e.g. 62819062 for "62819062…").
  pub value: u64,
  /// How many digits `value` has (leading zeros cannot occur in E.164).
  pub len: u8,
  pub category: Category,
  pub report_count: u16,
  pub last_seen_days: u16,
}

#[derive(Debug)]
pub enum DbError {
  Io(io::Error),
  Format(&'static str),
}

impl From<io::Error> for DbError {
  fn from(e: io::Error) -> Self {
    Self::Io(e)
  }
}

impl std::fmt::Display for DbError {
  fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
    match self {
      Self::Io(e) => write!(f, "io error: {e}"),
      Self::Format(m) => write!(f, "bad OCDB file: {m}"),
    }
  }
}

impl std::error::Error for DbError {}

/// Normalize a dialable string to its u64 number: strips `+`, spaces,
/// dashes, dots and parentheses. Returns `None` for anything that is not
/// 1–15 digits after stripping.
pub fn parse_number(s: &str) -> Option<u64> {
  let mut n: u64 = 0;
  let mut digits = 0usize;
  for c in s.chars() {
    match c {
      '0'..='9' => {
        digits += 1;
        if digits > 15 {
          return None;
        }
        n = n * 10 + (c as u64 - '0' as u64);
      }
      '+' | ' ' | '-' | '.' | '(' | ')' => {}
      _ => return None,
    }
  }
  if digits == 0 {
    None
  } else {
    Some(n)
  }
}

/// SplitMix64 finalizer — fast, deterministic across platforms/versions
/// (an on-disk format must never depend on `DefaultHasher`).
pub(crate) fn splitmix64(mut x: u64) -> u64 {
  x = x.wrapping_add(0x9E37_79B9_7F4A_7C15);
  let mut z = x;
  z = (z ^ (z >> 30)).wrapping_mul(0xBF58_476D_1CE4_E5B9);
  z = (z ^ (z >> 27)).wrapping_mul(0x94D0_49BB_1331_11EB);
  z ^ (z >> 31)
}

#[inline]
fn bloom_probes(number: u64, bits: u64) -> impl Iterator<Item = u64> {
  let h1 = splitmix64(number ^ 0xA5A5_A5A5_A5A5_A5A5);
  let h2 = splitmix64(number ^ 0x5A5A_5A5A_5A5A_5A5A) | 1;
  (0..BLOOM_HASHES as u64).map(move |i| h1.wrapping_add(i.wrapping_mul(h2)) % bits)
}

fn encode_varint(mut v: u64, out: &mut Vec<u8>) {
  loop {
    let byte = (v & 0x7F) as u8;
    v >>= 7;
    if v == 0 {
      out.push(byte);
      return;
    }
    out.push(byte | 0x80);
  }
}

#[inline]
fn decode_varint(buf: &[u8], pos: &mut usize) -> Option<u64> {
  let mut v: u64 = 0;
  let mut shift = 0u32;
  loop {
    let byte = *buf.get(*pos)?;
    *pos += 1;
    v |= u64::from(byte & 0x7F) << shift;
    if byte & 0x80 == 0 {
      return Some(v);
    }
    shift += 7;
    if shift >= 64 {
      return None;
    }
  }
}

#[derive(Debug)]
pub struct BuildStats {
  pub entries: usize,
  pub prefix_entries: usize,
  pub file_bytes: u64,
  pub bloom_bytes: usize,
  pub index_bytes: usize,
  pub blocks_bytes: usize,
}

/// Builds an OCDB file. Used by the CI pipeline/CLI — the same code path
/// the phone reads with [`SpamDb`], so the format cannot drift.
#[derive(Default)]
pub struct DbBuilder {
  entries: Vec<DbEntry>,
  prefixes: Vec<PrefixEntry>,
  built_days: u16,
}

impl DbBuilder {
  pub fn new() -> Self {
    Self::default()
  }

  /// Build date (days since epoch). Part of the signed header — the phone
  /// refuses updates whose `built_days` regresses (rollback protection).
  pub fn set_built_days(&mut self, days: u16) {
    self.built_days = days;
  }

  pub fn add(&mut self, entry: DbEntry) {
    self.entries.push(entry);
  }

  /// Adding any prefix entry switches the file to format v2 (older readers
  /// reject v2 shards and keep their installed DB until the app updates).
  pub fn add_prefix(&mut self, entry: PrefixEntry) {
    self.prefixes.push(entry);
  }

  pub fn build_to(mut self, path: &Path) -> Result<BuildStats, DbError> {
    self.entries.sort_by_key(|e| e.number);
    // Last write wins on duplicate numbers (stable sort keeps insertion
    // order within a number; the pipeline merges upstream).
    let mut deduped: Vec<DbEntry> = Vec::with_capacity(self.entries.len());
    for e in self.entries.drain(..) {
      match deduped.last_mut() {
        Some(last) if last.number == e.number => *last = e,
        _ => deduped.push(e),
      }
    }
    self.entries = deduped;
    let n = self.entries.len();
    if n == 0 {
      return Err(DbError::Format("refusing to build an empty database"));
    }

    let bloom_bits = ((n as f64 * BLOOM_BITS_PER_ENTRY).ceil() as u64).max(64);
    let bloom_bytes = ((bloom_bits + 7) / 8) as usize;
    let mut bloom = vec![0u8; bloom_bytes];
    for e in &self.entries {
      for bit in bloom_probes(e.number, bloom_bits) {
        bloom[(bit / 8) as usize] |= 1 << (bit % 8);
      }
    }

    let block_count = n.div_ceil(ENTRIES_PER_BLOCK);
    let mut index = Vec::with_capacity(block_count * 16);
    let mut blocks: Vec<u8> = Vec::with_capacity(n * 8);
    for chunk in self.entries.chunks(ENTRIES_PER_BLOCK) {
      let first = chunk[0].number;
      index.extend_from_slice(&first.to_le_bytes());
      index.extend_from_slice(&(blocks.len() as u64).to_le_bytes());
      let mut prev = first;
      for e in chunk {
        encode_varint(e.number - prev, &mut blocks);
        blocks.push(e.category as u8);
        blocks.extend_from_slice(&e.report_count.to_le_bytes());
        blocks.extend_from_slice(&e.last_seen_days.to_le_bytes());
        prev = e.number;
      }
    }

    // Prefix section (v2): sorted by (len, value); last-added wins on dup.
    self.prefixes.sort_by_key(|p| (p.len, p.value));
    let mut prefixes: Vec<PrefixEntry> = Vec::with_capacity(self.prefixes.len());
    for p in self.prefixes.drain(..) {
      match prefixes.last_mut() {
        Some(last) if last.len == p.len && last.value == p.value => *last = p,
        _ => prefixes.push(p),
      }
    }
    let mut prefix_bytes: Vec<u8> = Vec::with_capacity(prefixes.len() * PREFIX_ENTRY_LEN);
    for p in &prefixes {
      prefix_bytes.extend_from_slice(&p.value.to_le_bytes());
      prefix_bytes.push(p.len);
      prefix_bytes.push(p.category as u8);
      prefix_bytes.extend_from_slice(&p.report_count.to_le_bytes());
      prefix_bytes.extend_from_slice(&p.last_seen_days.to_le_bytes());
    }
    let v2 = !prefixes.is_empty();
    let prefix_off = (HEADER_LEN + bloom.len() + index.len() + blocks.len()) as u64;

    let mut header = [0u8; HEADER_LEN];
    header[0..8].copy_from_slice(if v2 { MAGIC_V2 } else { MAGIC });
    header[8..16].copy_from_slice(&(n as u64).to_le_bytes());
    header[16..24].copy_from_slice(&bloom_bits.to_le_bytes());
    header[24..28].copy_from_slice(&BLOOM_HASHES.to_le_bytes());
    header[28..32].copy_from_slice(&(block_count as u32).to_le_bytes());
    header[32..34].copy_from_slice(&self.built_days.to_le_bytes());
    if v2 {
      header[34..38].copy_from_slice(&(prefixes.len() as u32).to_le_bytes());
      header[38..46].copy_from_slice(&prefix_off.to_le_bytes());
    }

    let file = File::create(path)?;
    let mut w = BufWriter::new(file);
    w.write_all(&header)?;
    w.write_all(&bloom)?;
    w.write_all(&index)?;
    w.write_all(&blocks)?;
    w.write_all(&prefix_bytes)?;
    w.flush()?;

    Ok(BuildStats {
      entries: n,
      prefix_entries: prefixes.len(),
      file_bytes: (HEADER_LEN + bloom.len() + index.len() + blocks.len() + prefix_bytes.len())
        as u64,
      bloom_bytes: bloom.len(),
      index_bytes: index.len(),
      blocks_bytes: blocks.len(),
    })
  }
}

/// Read side: mmap'd, zero-copy, no heap allocation per lookup.
pub struct SpamDb {
  mmap: Mmap,
  entry_count: u64,
  bloom_bits: u64,
  block_count: usize,
  built_days: u16,
  bloom_off: usize,
  index_off: usize,
  blocks_off: usize,
  /// End of the blocks section (start of the v2 prefix section, or EOF).
  blocks_end: usize,
  prefix_off: usize,
  prefix_count: usize,
  /// (prefix_len, first_index, one_past_last_index) per distinct length,
  /// ascending — iterated in reverse for longest-prefix-first matching.
  prefix_groups: Vec<(u8, usize, usize)>,
}

impl SpamDb {
  pub fn open(path: &Path) -> Result<Self, DbError> {
    let file = File::open(path)?;
    // SAFETY: read-only mapping of a file we just opened; concurrent
    // truncation by another process would fault, which is acceptable for
    // app-private storage.
    let mmap = unsafe { Mmap::map(&file)? };
    if mmap.len() < HEADER_LEN {
      return Err(DbError::Format("file shorter than header"));
    }
    let v2 = match &mmap[0..8] {
      m if m == MAGIC => false,
      m if m == MAGIC_V2 => true,
      _ => return Err(DbError::Format("bad magic")),
    };
    let entry_count = u64::from_le_bytes(mmap[8..16].try_into().unwrap());
    let bloom_bits = u64::from_le_bytes(mmap[16..24].try_into().unwrap());
    let bloom_hashes = u32::from_le_bytes(mmap[24..28].try_into().unwrap());
    let block_count =
      u32::from_le_bytes(mmap[28..32].try_into().unwrap()) as usize;
    let built_days = u16::from_le_bytes(mmap[32..34].try_into().unwrap());
    if bloom_hashes != BLOOM_HASHES {
      return Err(DbError::Format("unsupported bloom hash count"));
    }
    if bloom_bits == 0 || entry_count == 0 {
      return Err(DbError::Format("empty database"));
    }
    let bloom_off = HEADER_LEN;
    let bloom_bytes = ((bloom_bits + 7) / 8) as usize;
    let index_off = bloom_off + bloom_bytes;
    let blocks_off = index_off + block_count * 16;
    if blocks_off > mmap.len() {
      return Err(DbError::Format("sections exceed file size"));
    }

    let (prefix_count, prefix_off) = if v2 {
      let count = u32::from_le_bytes(mmap[34..38].try_into().unwrap()) as usize;
      let off = u64::from_le_bytes(mmap[38..46].try_into().unwrap()) as usize;
      if off < blocks_off || off.checked_add(count * PREFIX_ENTRY_LEN) != Some(mmap.len()) {
        return Err(DbError::Format("bad prefix section"));
      }
      (count, off)
    } else {
      (0, mmap.len())
    };
    let blocks_end = prefix_off;

    let mut db = Self {
      mmap,
      entry_count,
      bloom_bits,
      block_count,
      built_days,
      bloom_off,
      index_off,
      blocks_off,
      blocks_end,
      prefix_off,
      prefix_count,
      prefix_groups: Vec::new(),
    };
    // Group prefix entries by length once at open (entries are sorted by
    // (len, value); reject disorder so lookups can binary-search safely).
    let mut groups: Vec<(u8, usize, usize)> = Vec::new();
    let mut prev: Option<(u8, u64)> = None;
    for i in 0..db.prefix_count {
      let (value, len, ..) = db.prefix_entry(i);
      if len == 0 || len as usize >= POW10.len() {
        return Err(DbError::Format("bad prefix length"));
      }
      if let Some((pl, pv)) = prev {
        if (len, value) <= (pl, pv) {
          return Err(DbError::Format("prefix section not sorted"));
        }
      }
      prev = Some((len, value));
      match groups.last_mut() {
        Some(g) if g.0 == len => g.2 = i + 1,
        _ => groups.push((len, i, i + 1)),
      }
    }
    db.prefix_groups = groups;
    Ok(db)
  }

  pub fn len(&self) -> u64 {
    self.entry_count
  }

  /// Build date of this shard (days since epoch, signed with the file).
  pub fn built_days(&self) -> u16 {
    self.built_days
  }

  pub fn is_empty(&self) -> bool {
    self.entry_count == 0
  }

  pub fn file_bytes(&self) -> usize {
    self.mmap.len()
  }

  /// Bloom-filter membership probe — the miss fast path, also exposed for
  /// benchmarking the false-positive rate.
  #[inline]
  pub fn bloom_contains(&self, number: u64) -> bool {
    let bloom = &self.mmap[self.bloom_off..self.index_off];
    bloom_probes(number, self.bloom_bits)
      .all(|bit| bloom[(bit / 8) as usize] & (1 << (bit % 8)) != 0)
  }

  #[inline]
  fn index_entry(&self, i: usize) -> (u64, u64) {
    let off = self.index_off + i * 16;
    let first =
      u64::from_le_bytes(self.mmap[off..off + 8].try_into().unwrap());
    let block =
      u64::from_le_bytes(self.mmap[off + 8..off + 16].try_into().unwrap());
    (first, block)
  }

  pub fn lookup_str(&self, number: &str) -> Option<DbEntryInfo> {
    self.lookup(parse_number(number)?)
  }

  /// Exact match first; on miss, longest matching prefix entry (spam
  /// blocks that survive number rotation).
  pub fn lookup(&self, number: u64) -> Option<DbEntryInfo> {
    self.lookup_exact(number).or_else(|| self.lookup_prefix(number))
  }

  fn lookup_exact(&self, number: u64) -> Option<DbEntryInfo> {
    if !self.bloom_contains(number) {
      return None;
    }

    // Binary search the block index for the last block starting ≤ number.
    let (mut lo, mut hi) = (0usize, self.block_count);
    while lo < hi {
      let mid = (lo + hi) / 2;
      if self.index_entry(mid).0 <= number {
        lo = mid + 1;
      } else {
        hi = mid;
      }
    }
    if lo == 0 {
      return None; // bloom false positive below the first entry
    }
    let block_idx = lo - 1;
    let (first, block_off) = self.index_entry(block_idx);
    let block_start = self.blocks_off + block_off as usize;
    let block_end = if block_idx + 1 < self.block_count {
      self.blocks_off + self.index_entry(block_idx + 1).1 as usize
    } else {
      self.blocks_end
    };
    // Offsets come from the file; a malformed (pipeline-bug) shard must
    // degrade to a miss on the screening hot path, never a panic.
    if block_start > block_end || block_end > self.blocks_end {
      return None;
    }
    let block = &self.mmap[block_start..block_end];

    // Linear delta-decode; ≤ ENTRIES_PER_BLOCK iterations, all in one or
    // two cache-warm pages.
    let mut pos = 0usize;
    let mut current = first;
    while pos < block.len() {
      let delta = decode_varint(block, &mut pos)?;
      current += delta;
      if pos + 5 > block.len() {
        return None; // truncated entry — treat as absent
      }
      if current == number {
        let category = Category::from_u8(block[pos])?;
        let report_count =
          u16::from_le_bytes([block[pos + 1], block[pos + 2]]);
        let last_seen_days =
          u16::from_le_bytes([block[pos + 3], block[pos + 4]]);
        return Some(DbEntryInfo { category, report_count, last_seen_days });
      }
      if current > number {
        return None; // passed it — bloom false positive
      }
      pos += 5;
    }
    None
  }

  #[inline]
  fn prefix_entry(&self, i: usize) -> (u64, u8, u8, u16, u16) {
    let off = self.prefix_off + i * PREFIX_ENTRY_LEN;
    let value = u64::from_le_bytes(self.mmap[off..off + 8].try_into().unwrap());
    let len = self.mmap[off + 8];
    let category = self.mmap[off + 9];
    let count = u16::from_le_bytes([self.mmap[off + 10], self.mmap[off + 11]]);
    let last_seen = u16::from_le_bytes([self.mmap[off + 12], self.mmap[off + 13]]);
    (value, len, category, count, last_seen)
  }

  fn lookup_prefix(&self, number: u64) -> Option<DbEntryInfo> {
    if self.prefix_count == 0 {
      return None;
    }
    let nd = ndigits(number);
    for &(len, start, end) in self.prefix_groups.iter().rev() {
      if u32::from(len) >= nd {
        continue; // prefix must be strictly shorter than the number
      }
      let target = number / POW10[(nd - u32::from(len)) as usize];
      let (mut lo, mut hi) = (start, end);
      while lo < hi {
        let mid = (lo + hi) / 2;
        let (value, _, category, count, last_seen) = self.prefix_entry(mid);
        match value.cmp(&target) {
          std::cmp::Ordering::Less => lo = mid + 1,
          std::cmp::Ordering::Greater => hi = mid,
          std::cmp::Ordering::Equal => {
            return Some(DbEntryInfo {
              category: Category::from_u8(category)?,
              report_count: count,
              last_seen_days: last_seen,
            });
          }
        }
      }
    }
    None
  }

  /// Decode every entry (CI verification / debugging; not a phone path).
  pub fn iter_all(&self) -> HashMap<u64, DbEntryInfo> {
    let mut out = HashMap::with_capacity(self.entry_count as usize);
    for b in 0..self.block_count {
      let (first, block_off) = self.index_entry(b);
      let start = self.blocks_off + block_off as usize;
      let end = if b + 1 < self.block_count {
        self.blocks_off + self.index_entry(b + 1).1 as usize
      } else {
        self.blocks_end
      };
      if start > end || end > self.blocks_end {
        continue;
      }
      let block = &self.mmap[start..end];
      let mut pos = 0;
      let mut current = first;
      while pos < block.len() {
        let Some(delta) = decode_varint(block, &mut pos) else { break };
        current += delta;
        if pos + 5 > block.len() {
          break;
        }
        if let Some(category) = Category::from_u8(block[pos]) {
          out.insert(
            current,
            DbEntryInfo {
              category,
              report_count: u16::from_le_bytes([block[pos + 1], block[pos + 2]]),
              last_seen_days: u16::from_le_bytes([block[pos + 3], block[pos + 4]]),
            },
          );
        }
        pos += 5;
      }
    }
    out
  }
}

#[cfg(test)]
mod tests {
  use super::*;

  fn entry(number: u64, category: Category, count: u16) -> DbEntry {
    DbEntry { number, category, report_count: count, last_seen_days: 20_000 }
  }

  fn build_tmp(entries: &[DbEntry]) -> (tempdir::TempPath, SpamDb) {
    let path = tempdir::TempPath::new("ocdb-test");
    let mut b = DbBuilder::new();
    for e in entries {
      b.add(*e);
    }
    b.build_to(&path.0).unwrap();
    let db = SpamDb::open(&path.0).unwrap();
    (path, db)
  }

  // Minimal self-cleaning temp file helper (no extra dev-dependency).
  mod tempdir {
    use std::path::PathBuf;
    use std::sync::atomic::{AtomicU64, Ordering};
    // Tests run in parallel threads of one process: PID alone is not
    // unique enough, so add a per-call counter.
    static SEQ: AtomicU64 = AtomicU64::new(0);
    pub struct TempPath(pub PathBuf);
    impl TempPath {
      pub fn new(tag: &str) -> Self {
        let mut p = std::env::temp_dir();
        let n = SEQ.fetch_add(1, Ordering::Relaxed);
        p.push(format!("{tag}-{}-{n}.ocdb", std::process::id()));
        Self(p)
      }
    }
    impl Drop for TempPath {
      fn drop(&mut self) {
        let _ = std::fs::remove_file(&self.0);
      }
    }
  }

  #[test]
  fn roundtrip_and_misses() {
    // Adjacent numbers, block boundaries (>256 entries), sparse tail.
    let mut entries: Vec<DbEntry> = (0..600u64)
      .map(|i| entry(15_550_000_000 + i, Category::Robocall, (i + 1) as u16))
      .collect();
    entries.push(entry(19_990_000_000, Category::Scam, 999));
    let (_p, db) = build_tmp(&entries);

    assert_eq!(db.len(), 601);
    for e in &entries {
      let hit = db.lookup(e.number).expect("must be present");
      assert_eq!(hit.report_count, e.report_count);
      assert_eq!(hit.category, e.category);
    }
    // Guaranteed misses around the data.
    assert!(db.lookup(15_550_000_000 + 600).is_none());
    assert!(db.lookup(15_549_999_999).is_none());
    assert!(db.lookup(19_990_000_001).is_none());
    assert!(db.lookup(1).is_none());
    // No false negatives by construction.
    assert!(entries.iter().all(|e| db.bloom_contains(e.number)));
    // Full decode matches input.
    let all = db.iter_all();
    assert_eq!(all.len(), 601);
  }

  #[test]
  fn prefix_entries_v2() {
    let path = tempdir::TempPath::new("ocdb-prefix");
    let mut b = DbBuilder::new();
    b.add(entry(628190624000, Category::Scam, 5)); // exact entry inside the block
    // Spam block 62819062xxxx (len 8) and a longer, more specific block.
    b.add_prefix(PrefixEntry {
      value: 62_819_062,
      len: 8,
      category: Category::Robocall,
      report_count: 120,
      last_seen_days: 20_700,
    });
    b.add_prefix(PrefixEntry {
      value: 6_281_906_299,
      len: 10,
      category: Category::Scam,
      report_count: 44,
      last_seen_days: 20_701,
    });
    let stats = b.build_to(&path.0).unwrap();
    assert_eq!(stats.prefix_entries, 2);

    let db = SpamDb::open(&path.0).unwrap();
    // Exact entry wins over its covering prefix.
    assert_eq!(db.lookup(628190624000).unwrap().report_count, 5);
    // Fresh, never-reported number in the block -> prefix match.
    let hit = db.lookup(628190627777).unwrap();
    assert_eq!(hit.category, Category::Robocall);
    assert_eq!(hit.report_count, 120);
    // Longest prefix wins.
    assert_eq!(db.lookup(628190629911).unwrap().category, Category::Scam);
    // Outside the block: still a miss.
    assert!(db.lookup(628190630000).is_none());
    // Prefix must be strictly shorter than the number.
    assert!(db.lookup(62_819_062).is_none());
  }

  #[test]
  fn parse_number_forms() {
    assert_eq!(parse_number("+1 (555) 123-4567"), Some(15551234567));
    assert_eq!(parse_number("+62 812.3456.789"), Some(628123456789));
    assert_eq!(parse_number("call-me"), None);
    assert_eq!(parse_number(""), None);
    assert_eq!(parse_number("1234567890123456"), None); // 16 digits
  }

  #[test]
  fn rejects_bad_files() {
    let path = tempdir::TempPath::new("ocdb-bad");
    std::fs::write(&path.0, b"NOTOCDB0-and-some-garbage-bytes-here....").unwrap();
    assert!(matches!(SpamDb::open(&path.0), Err(DbError::Format(_))));
  }

  #[test]
  fn duplicate_numbers_last_wins() {
    let entries = vec![
      entry(15551234567, Category::Scam, 1),
      entry(15551234567, Category::Robocall, 7),
    ];
    let (_p, db) = build_tmp(&entries);
    assert_eq!(db.len(), 1);
    assert_eq!(db.lookup(15551234567).unwrap().report_count, 7);
  }
}
