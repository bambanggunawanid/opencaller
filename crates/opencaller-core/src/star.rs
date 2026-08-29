//! M3 collection leg: STAR threshold-encrypted reporting.
//! Design & threat model: docs/collection-mechanisms.md §5.
//!
//! Client side: [`create_report_message`] turns a [`Report`] into opaque wire
//! bytes `(ciphertext, share, tag)`. Server side: [`aggregate`] buckets
//! messages by tag and can decrypt a bucket ONLY when it holds ≥ K shares —
//! k-anonymity enforced by Shamir secret sharing, not policy.
//!
//! Two randomness modes ([`RandomnessMode`]):
//! - `Local` (STARLite): randomness derived from the measurement itself.
//!   Only safe for high-entropy measurements — phone numbers are NOT
//!   (≈10¹⁰ enumerable space), so this mode is for tests/simulation only.
//! - `Oprf` (full STAR): randomness from the randomness server via a
//!   verifiable puncturable partially-oblivious PRF (`ppoprf`). The server
//!   never sees the number (blinded), clients verify a DLEQ proof against
//!   its public key, and punctured epochs become permanently unqueryable.
//!   In production the `eval` round-trip travels over an OHTTP relay with a
//!   Privacy Pass token; blind/verify/unblind/finalize stay client-side.

use std::collections::HashMap;
use std::error::Error;
use std::fmt;

use ppoprf::ppoprf::{Client as OprfClient, Server as RandomnessServer};
use sta_rs::{
  derive_ske_key, load_bytes, share_recover, AssociatedData, Message,
  MessageGenerator, Share, SingleMeasurement,
};

/// Spam category — the only aux data a report carries besides country.
/// Every field here is revealed once a bucket crosses K; keep it minimal.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash, PartialOrd, Ord)]
#[repr(u8)]
pub enum Category {
  Scam = 0,
  Robocall = 1,
  Telemarketing = 2,
  DebtCollection = 3,
  Survey = 4,
  Other = 5,
}

impl Category {
  pub fn from_u8(b: u8) -> Option<Self> {
    match b {
      0 => Some(Self::Scam),
      1 => Some(Self::Robocall),
      2 => Some(Self::Telemarketing),
      3 => Some(Self::DebtCollection),
      4 => Some(Self::Survey),
      5 => Some(Self::Other),
      _ => None,
    }
  }

  pub fn label(&self) -> &'static str {
    match self {
      Self::Scam => "scam",
      Self::Robocall => "robocall",
      Self::Telemarketing => "telemarketing",
      Self::DebtCollection => "debt-collection",
      Self::Survey => "survey",
      Self::Other => "other",
    }
  }
}

/// A single user report, created only by an explicit tap (PRD §8).
#[derive(Clone, Debug)]
pub struct Report {
  /// E.164, e.g. "+15551234567". This is the STAR measurement: identical
  /// across clients reporting the same number, which is what makes shares
  /// of the same bucket combine.
  pub number: String,
  pub category: Category,
  /// ISO 3166-1 alpha-2, e.g. "US".
  pub country: String,
}

/// Where report randomness comes from. See module docs.
pub enum RandomnessMode<'a> {
  /// STARLite — tests/simulation ONLY (dictionary-attackable for phone
  /// numbers).
  Local,
  /// Full STAR against a randomness server. `epoch_md` is the single-byte
  /// epoch tag the ppoprf key schedule is defined over (e.g. ISO week
  /// number); the server punctures it when the epoch closes.
  Oprf {
    server: &'a RandomnessServer,
    epoch_md: u8,
  },
}

#[derive(Debug)]
pub enum StarError {
  /// Randomness server refused/failed the evaluation (e.g. punctured epoch).
  Oprf(String),
  /// DLEQ proof did not verify — a misbehaving randomness server.
  OprfProofInvalid,
  Message(String),
}

impl fmt::Display for StarError {
  fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
    match self {
      Self::Oprf(e) => write!(f, "randomness server error: {e}"),
      Self::OprfProofInvalid => write!(f, "randomness server DLEQ proof invalid"),
      Self::Message(e) => write!(f, "message generation error: {e}"),
    }
  }
}

impl Error for StarError {}

/// Derive the 32 bytes of report randomness for `number` in `epoch`.
fn derive_randomness(
  number: &[u8],
  k: u32,
  epoch: &[u8],
  mode: &RandomnessMode,
) -> Result<[u8; 32], StarError> {
  let mut rnd = [0u8; 32];
  match mode {
    RandomnessMode::Local => {
      let mg =
        MessageGenerator::new(SingleMeasurement::new(number), k, epoch);
      mg.sample_local_randomness(&mut rnd);
    }
    RandomnessMode::Oprf { server, epoch_md } => {
      // Blind the number so the randomness server never sees it…
      let (blinded, blinding_factor) = OprfClient::blind(number);
      // …have the server evaluate under its per-epoch key (network hop +
      // Privacy Pass token in production)…
      let eval = server
        .eval(&blinded, *epoch_md, true)
        .map_err(|e| StarError::Oprf(format!("{e:?}")))?;
      // …verify it used the committed key (misbehaving server → tagging
      // individual clients would otherwise be possible)…
      if !OprfClient::verify(&server.get_public_key(), &blinded, &eval, *epoch_md)
      {
        return Err(StarError::OprfProofInvalid);
      }
      // …then unblind and hash down to the report randomness.
      let unblinded = OprfClient::unblind(&eval.output, &blinding_factor);
      OprfClient::finalize(number, *epoch_md, &unblinded, &mut rnd);
    }
  }
  Ok(rnd)
}

/// Client: build the opaque wire message for one report.
pub fn create_report_message(
  report: &Report,
  k: u32,
  epoch: &[u8],
  mode: &RandomnessMode,
) -> Result<Vec<u8>, StarError> {
  let rnd = derive_randomness(report.number.as_bytes(), k, epoch, mode)?;
  let mg = MessageGenerator::new(
    SingleMeasurement::new(report.number.as_bytes()),
    k,
    epoch,
  );
  let mut aux = Vec::with_capacity(1 + report.country.len());
  aux.push(report.category as u8);
  aux.extend_from_slice(report.country.as_bytes());
  let msg = Message::generate(&mg, &rnd, Some(AssociatedData::new(&aux)))
    .map_err(|e| StarError::Message(e.to_string()))?;
  Ok(msg.to_bytes())
}

/// Attacker/diagnostic helper: the bucket tag for a number (requires the
/// same randomness access a client has — this is exactly the targeted
/// dictionary probe of docs/collection-mechanisms.md §5.5.1).
pub fn probe_tag(
  number: &str,
  k: u32,
  epoch: &[u8],
  mode: &RandomnessMode,
) -> Result<Vec<u8>, StarError> {
  let report = Report {
    number: number.to_owned(),
    category: Category::Other,
    country: String::new(),
  };
  let bytes = create_report_message(&report, k, epoch, mode)?;
  let msg = Message::from_bytes(&bytes)
    .ok_or_else(|| StarError::Message("self-parse failed".into()))?;
  Ok(msg.tag)
}

/// A bucket that crossed K and was decrypted.
#[derive(Debug)]
pub struct RecoveredBucket {
  pub number: String,
  pub count: usize,
  /// (category, occurrences) sorted by occurrences desc.
  pub categories: Vec<(Category, usize)>,
  /// (country, occurrences) sorted by occurrences desc.
  pub countries: Vec<(String, usize)>,
}

/// A sub-threshold bucket: ALL the server sees is an opaque tag and a size.
#[derive(Debug)]
pub struct SealedBucket {
  pub tag: Vec<u8>,
  pub count: usize,
}

#[derive(Debug, Default)]
pub struct AggregationOutcome {
  pub recovered: Vec<RecoveredBucket>,
  pub sealed: Vec<SealedBucket>,
  /// Wire messages that failed to parse, or payloads that failed to decode
  /// after recovery (garbage from malicious clients — dropped).
  pub malformed: usize,
}

/// Server: bucket wire messages by tag; decrypt exactly those buckets with
/// ≥ `k` shares. Everything below `k` stays cryptographically sealed.
pub fn aggregate(
  wire_messages: &[Vec<u8>],
  k: u32,
  epoch: &[u8],
) -> AggregationOutcome {
  let mut out = AggregationOutcome::default();

  let mut buckets: HashMap<Vec<u8>, Vec<Message>> = HashMap::new();
  for bytes in wire_messages {
    match Message::from_bytes(bytes) {
      Some(m) => buckets.entry(m.tag.clone()).or_default().push(m),
      None => out.malformed += 1,
    }
  }

  for (tag, msgs) in buckets {
    if (msgs.len() as u32) < k {
      out.sealed.push(SealedBucket { tag, count: msgs.len() });
      continue;
    }

    // Threshold met: shares interpolate back to the key seed.
    let shares: Vec<Share> = msgs.iter().map(|m| m.share.clone()).collect();
    let key_seed = match share_recover(&shares) {
      Ok(commune) => commune.get_message(),
      Err(_) => {
        // Should not happen with honest clients; treat as sealed.
        out.sealed.push(SealedBucket { tag, count: msgs.len() });
        continue;
      }
    };
    let mut key = vec![0u8; 16];
    derive_ske_key(&key_seed, epoch, &mut key);

    let mut number: Option<String> = None;
    let mut count = 0usize;
    let mut categories: HashMap<Category, usize> = HashMap::new();
    let mut countries: HashMap<String, usize> = HashMap::new();

    for m in &msgs {
      let data = m.ciphertext.decrypt(&key, "star_encrypt");
      // Payload layout (see Message::generate): len-prefixed measurement,
      // then len-prefixed aux = [category u8][country utf8].
      let Some(measurement) = load_bytes(&data) else {
        out.malformed += 1;
        continue;
      };
      let Ok(num) = std::str::from_utf8(measurement) else {
        out.malformed += 1;
        continue;
      };
      match &number {
        None => number = Some(num.to_owned()),
        // A tag collision with a different number would be a protocol
        // break; count and drop rather than corrupt the bucket.
        Some(n) if n != num => {
          out.malformed += 1;
          continue;
        }
        Some(_) => {}
      }
      let rest = &data[4 + measurement.len()..];
      if let Some(aux) = load_bytes(rest) {
        if let Some(cat) = aux.first().copied().and_then(Category::from_u8) {
          *categories.entry(cat).or_default() += 1;
        }
        if let Ok(country) = std::str::from_utf8(&aux[1.min(aux.len())..]) {
          if !country.is_empty() {
            *countries.entry(country.to_owned()).or_default() += 1;
          }
        }
      }
      count += 1;
    }

    let Some(number) = number else {
      out.sealed.push(SealedBucket { tag, count: msgs.len() });
      continue;
    };
    let mut categories: Vec<_> = categories.into_iter().collect();
    categories.sort_by(|a, b| b.1.cmp(&a.1).then(a.0.cmp(&b.0)));
    let mut countries: Vec<_> = countries.into_iter().collect();
    countries.sort_by(|a, b| b.1.cmp(&a.1).then(a.0.cmp(&b.0)));
    out.recovered.push(RecoveredBucket { number, count, categories, countries });
  }

  out.recovered.sort_by(|a, b| b.count.cmp(&a.count).then(a.number.cmp(&b.number)));
  out.sealed.sort_by(|a, b| b.count.cmp(&a.count));
  out
}

#[cfg(test)]
mod tests {
  use super::*;

  const EPOCH: &[u8] = b"2026-W35";
  const K: u32 = 3;

  fn report(number: &str, category: Category) -> Report {
    Report { number: number.into(), category, country: "US".into() }
  }

  #[test]
  fn below_threshold_stays_sealed_at_threshold_reveals() {
    let mode = RandomnessMode::Local;
    let mut wire: Vec<Vec<u8>> = (0..2)
      .map(|_| {
        create_report_message(&report("+15551234567", Category::Scam), K, EPOCH, &mode)
          .unwrap()
      })
      .collect();

    let out = aggregate(&wire, K, EPOCH);
    assert!(out.recovered.is_empty());
    assert_eq!(out.sealed.len(), 1);
    assert_eq!(out.sealed[0].count, 2);

    wire.push(
      create_report_message(&report("+15551234567", Category::Robocall), K, EPOCH, &mode)
        .unwrap(),
    );
    let out = aggregate(&wire, K, EPOCH);
    assert_eq!(out.recovered.len(), 1);
    let bucket = &out.recovered[0];
    assert_eq!(bucket.number, "+15551234567");
    assert_eq!(bucket.count, 3);
    assert_eq!(bucket.categories[0], (Category::Scam, 2));
    assert_eq!(bucket.countries[0], ("US".into(), 3));
    assert_eq!(out.malformed, 0);
  }

  #[test]
  fn oprf_mode_roundtrip_and_puncture() {
    let epoch_md = 35u8;
    let mut server = RandomnessServer::new(vec![epoch_md]).unwrap();
    {
      let mode = RandomnessMode::Oprf { server: &server, epoch_md };
      let wire: Vec<Vec<u8>> = (0..3)
        .map(|_| {
          create_report_message(&report("+15559990000", Category::Scam), K, EPOCH, &mode)
            .unwrap()
        })
        .collect();
      let out = aggregate(&wire, K, EPOCH);
      assert_eq!(out.recovered.len(), 1);
      assert_eq!(out.recovered[0].number, "+15559990000");
    }

    // Epoch closes: puncture. Retroactive dictionary probes must fail.
    server.puncture(epoch_md).unwrap();
    let mode = RandomnessMode::Oprf { server: &server, epoch_md };
    assert!(matches!(
      probe_tag("+15559990000", K, EPOCH, &mode),
      Err(StarError::Oprf(_))
    ));
  }
}
