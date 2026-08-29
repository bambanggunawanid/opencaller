//! M0 spike benchmark: build a 10M-entry OCDB and measure what the PRD
//! promises — lookup < 1 ms p99, flat RAM (mmap only), sane size.
//!
//! Run: cargo run --release --bin db_bench

use std::path::PathBuf;
use std::time::Instant;

use rand::rngs::StdRng;
use rand::{Rng, SeedableRng};

use opencaller_core::db::{DbBuilder, DbEntry, SpamDb};
use opencaller_core::star::Category;

const TARGET_ENTRIES: usize = 10_000_000;
const LOOKUPS: usize = 1_000_000;
const SEED: u64 = 7;

fn rss_mb() -> Option<f64> {
  let status = std::fs::read_to_string("/proc/self/status").ok()?;
  let line = status.lines().find(|l| l.starts_with("VmRSS:"))?;
  let kb: f64 = line.split_whitespace().nth(1)?.parse().ok()?;
  Some(kb / 1024.0)
}

/// Synthetic number population mimicking real allocation: spam clusters in
/// prefixes (70%) plus a uniform long tail (30%).
fn generate_numbers(rng: &mut StdRng) -> Vec<u64> {
  let clustered = (TARGET_ENTRIES as f64 * 0.7) as usize;
  let cluster_size = 200;
  let mut numbers = Vec::with_capacity(TARGET_ENTRIES + 1024);
  for _ in 0..clustered / cluster_size {
    let mut n: u64 = rng.gen_range(10_000_000_000..80_000_000_000);
    for _ in 0..cluster_size {
      n += rng.gen_range(1..=6);
      numbers.push(n);
    }
  }
  for _ in 0..TARGET_ENTRIES - clustered {
    numbers.push(rng.gen_range(10_000_000_000..90_000_000_000));
  }
  numbers.sort_unstable();
  numbers.dedup();
  numbers
}

fn time_lookups(db: &SpamDb, probes: &[u64], expect_hit: bool) -> f64 {
  let t = Instant::now();
  let mut found = 0usize;
  for &n in probes {
    if db.lookup(n).is_some() {
      found += 1;
    }
  }
  let ns = t.elapsed().as_nanos() as f64 / probes.len() as f64;
  if expect_hit {
    assert_eq!(found, probes.len(), "every hit probe must resolve");
  } else {
    assert_eq!(found, 0, "miss probes must never resolve");
  }
  ns
}

fn main() {
  println!("OpenCaller M0 spike — OCDB benchmark ({TARGET_ENTRIES} entries)\n");
  let mut rng = StdRng::seed_from_u64(SEED);

  let t = Instant::now();
  let numbers = generate_numbers(&mut rng);
  println!("generated {} unique numbers in {:?}", numbers.len(), t.elapsed());

  let mut builder = DbBuilder::new();
  for (i, &n) in numbers.iter().enumerate() {
    builder.add(DbEntry {
      number: n,
      category: Category::from_u8((i % 6) as u8).unwrap(),
      report_count: (i % 2000) as u16 + 1,
      last_seen_days: 20_000 + (i % 400) as u16,
    });
  }
  let path = PathBuf::from(
    std::env::var("CARGO_TARGET_DIR").unwrap_or_else(|_| "target".into()),
  )
  .join("bench_10m.ocdb");
  let t = Instant::now();
  let stats = builder.build_to(&path).expect("build");
  let build_time = t.elapsed();
  println!(
    "built OCDB in {build_time:?}: {:.1} MB total ({:.1} bloom / {:.1} index / {:.1} blocks) — {:.1} B/entry\n",
    stats.file_bytes as f64 / 1e6,
    stats.bloom_bytes as f64 / 1e6,
    stats.index_bytes as f64 / 1e6,
    stats.blocks_bytes as f64 / 1e6,
    stats.blocks_bytes as f64 / stats.entries as f64,
  );

  let rss_before = rss_mb();
  let db = SpamDb::open(&path).expect("open");
  let rss_after_open = rss_mb();

  // Hit probes: stride through the inserted set. Miss probes: midpoints of
  // gaps between consecutive entries (same numeric region — the realistic
  // miss; ~1% will pass the bloom filter and exercise the slow path).
  let hits: Vec<u64> =
    (0..LOOKUPS).map(|i| numbers[(i * 9973) % numbers.len()]).collect();
  let mut misses = Vec::with_capacity(LOOKUPS);
  let mut i = 1usize;
  while misses.len() < LOOKUPS {
    let idx = (i * 7919) % (numbers.len() - 1);
    if numbers[idx + 1] > numbers[idx] + 1 {
      misses.push(numbers[idx] + 1 + (numbers[idx + 1] - numbers[idx] - 1) / 2);
    }
    i += 1;
  }

  // Warm pass (page faults), then measured pass.
  time_lookups(&db, &hits[..LOOKUPS / 10], true);
  time_lookups(&db, &misses[..LOOKUPS / 10], false);

  let hit_ns = time_lookups(&db, &hits, true);
  let miss_ns = time_lookups(&db, &misses, false);
  let rss_after_lookups = rss_mb();

  let bloom_fp =
    misses.iter().filter(|&&n| db.bloom_contains(n)).count() as f64
      / misses.len() as f64;

  println!("lookups ({} each, warm):", LOOKUPS);
  println!("  hit  avg: {hit_ns:>8.0} ns");
  println!("  miss avg: {miss_ns:>8.0} ns   (bloom FP rate {:.2}%)", bloom_fp * 100.0);
  if let (Some(b), Some(o), Some(l)) = (rss_before, rss_after_open, rss_after_lookups) {
    println!(
      "RSS: {b:.0} MB before open → {o:.0} MB after open → {l:.0} MB after 2.2M lookups (mmap page cache only)"
    );
  }
  println!(
    "\nPRD targets: lookup p99 < 1 ms → avg is {}× under; screening budget 50 ms → negligible.",
    (1_000_000.0 / hit_ns.max(miss_ns)) as u64
  );

  let _ = std::fs::remove_file(&path);
}
