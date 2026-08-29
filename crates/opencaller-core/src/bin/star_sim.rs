//! Research simulation for the M3 collection leg (docs/collection-mechanisms.md).
//!
//! Simulates one weekly epoch of an OpenCaller deployment: spam campaigns,
//! one-off wrong-number reports, and a poisoning attempt — first in STARLite
//! mode (local randomness), then in full STAR mode with an in-process
//! ppoprf randomness server, ending with the targeted-dictionary-probe and
//! epoch-puncturing demos from §5.5.1.
//!
//! Run: cargo run --release --bin star_sim

use std::time::Instant;

use ppoprf::ppoprf::Server as RandomnessServer;
use rand::rngs::StdRng;
use rand::{Rng, SeedableRng};

use opencaller_core::star::{
  aggregate, create_report_message, probe_tag, Category, RandomnessMode,
  Report,
};

const USERS: usize = 10_000;
const K: u32 = 10;
const EPOCH: &[u8] = b"2026-W35";
const EPOCH_MD: u8 = 35; // ISO week byte for the ppoprf key schedule
const SEED: u64 = 42;

const CAMPAIGN_A: &str = "+15551234567"; // aggressive robocall campaign
const CAMPAIGN_B: &str = "+15559876543"; // small scam campaign (should stay sealed)
const VICTIM: &str = "+15550001111"; // innocent number targeted by a poisoner

fn build_reports(rng: &mut StdRng) -> Vec<Report> {
  let mut reports = Vec::new();

  // Campaign A: calls 2% of users, 10% of called users tap "report".
  let called_a = (USERS as f64 * 0.02) as usize;
  for _ in 0..called_a {
    if rng.gen_bool(0.10) {
      reports.push(Report {
        number: CAMPAIGN_A.into(),
        category: if rng.gen_bool(0.8) { Category::Robocall } else { Category::Telemarketing },
        country: "US".into(),
      });
    }
  }

  // Campaign B: calls 0.5% of users — expected ~5 reports, below K=10.
  let called_b = (USERS as f64 * 0.005) as usize;
  for _ in 0..called_b {
    if rng.gen_bool(0.10) {
      reports.push(Report {
        number: CAMPAIGN_B.into(),
        category: Category::Scam,
        country: "US".into(),
      });
    }
  }

  // Background noise: 300 users report a unique number once (wrong numbers,
  // personal grudges, misc). Each forms a size-1 bucket.
  for _ in 0..300 {
    let n: u64 = rng.gen_range(2_000_000_000..9_999_999_999);
    reports.push(Report {
      number: format!("+1{n}"),
      category: Category::Other,
      country: "US".into(),
    });
  }

  // Poisoning attempt: attacker burns 6 Privacy Pass tokens on an innocent
  // number. 6 < K → sealed; forcing a listing needs ≥ K distinct authorized
  // installs sustained across epochs (docs §5.5.3).
  for _ in 0..6 {
    reports.push(Report {
      number: VICTIM.into(),
      category: Category::Scam,
      country: "US".into(),
    });
  }

  reports
}

fn run_epoch(label: &str, reports: &[Report], mode: &RandomnessMode) {
  println!("━━━ {label} ━━━");

  let t0 = Instant::now();
  let wire: Vec<Vec<u8>> = reports
    .iter()
    .map(|r| create_report_message(r, K, EPOCH, mode).expect("client message"))
    .collect();
  let client_time = t0.elapsed();
  let avg_wire = wire.iter().map(Vec::len).sum::<usize>() / wire.len();

  let t1 = Instant::now();
  let outcome = aggregate(&wire, K, EPOCH);
  let agg_time = t1.elapsed();

  println!(
    "  {} reports │ client avg {:?}/report │ wire avg {avg_wire} B │ aggregation {:?}",
    wire.len(),
    client_time / wire.len() as u32,
    agg_time
  );

  println!("  ── what the aggregation server LEARNED (≥ K={K}) ──");
  for b in &outcome.recovered {
    let cats: Vec<String> =
      b.categories.iter().map(|(c, n)| format!("{} ×{n}", c.label())).collect();
    println!("    {}  ×{}  [{}]", b.number, b.count, cats.join(", "));
  }
  if outcome.recovered.is_empty() {
    println!("    (nothing crossed the threshold)");
  }

  let sealed_reports: usize = outcome.sealed.iter().map(|s| s.count).sum();
  let biggest = outcome.sealed.iter().map(|s| s.count).max().unwrap_or(0);
  println!("  ── what stays CRYPTOGRAPHICALLY SEALED ──");
  println!(
    "    {} buckets / {sealed_reports} reports unreadable (largest sealed bucket: {biggest})",
    outcome.sealed.len()
  );
  println!("    malformed dropped: {}", outcome.malformed);
  println!();
}

fn main() {
  println!("OpenCaller M3 simulation — one weekly epoch, {USERS} users, K={K}\n");
  let mut rng = StdRng::seed_from_u64(SEED);
  let reports = build_reports(&mut rng);

  // Mode 1: STARLite. Same aggregation mechanics, but randomness is derived
  // from the number itself — dictionary-attackable for low-entropy inputs
  // like phone numbers. Baseline only.
  run_epoch("STARLite (local randomness — simulation baseline)", &reports, &RandomnessMode::Local);

  // Mode 2: full STAR with a ppoprf randomness server.
  let mut server = RandomnessServer::new(vec![EPOCH_MD]).expect("randomness server");
  {
    let mode = RandomnessMode::Oprf { server: &server, epoch_md: EPOCH_MD };
    run_epoch("Full STAR (ppoprf randomness server)", &reports, &mode);

    // §5.5.1 — targeted dictionary probe DURING the epoch. Attacker =
    // compromised aggregator + one client token. Learns bucket COUNT only;
    // reporter identities and sub-threshold contents stay protected.
    println!("━━━ Attack demo: targeted dictionary probe (during epoch) ━━━");
    let outcome = aggregate(
      &reports
        .iter()
        .map(|r| create_report_message(r, K, EPOCH, &mode).unwrap())
        .collect::<Vec<_>>(),
      K,
      EPOCH,
    );
    for target in [CAMPAIGN_B, "+15558887777"] {
      let tag = probe_tag(target, K, EPOCH, &mode).expect("probe");
      let hit = outcome.sealed.iter().find(|s| s.tag == tag);
      match hit {
        Some(s) => println!(
          "  probe {target}: bucket EXISTS, {} report(s) — content sealed, reporters unknown",
          s.count
        ),
        None => println!("  probe {target}: no sealed bucket with this tag"),
      }
    }
    println!();
  }

  // Epoch ends: the randomness server punctures the epoch tag. Even a full
  // key compromise afterwards cannot evaluate the OPRF for closed epochs —
  // retroactive dictionary attacks are dead.
  println!("━━━ Epoch close: puncture W{EPOCH_MD} ━━━");
  server.puncture(EPOCH_MD).expect("puncture");
  let mode = RandomnessMode::Oprf { server: &server, epoch_md: EPOCH_MD };
  match probe_tag(CAMPAIGN_A, K, EPOCH, &mode) {
    Err(e) => println!("  retroactive probe of {CAMPAIGN_A}: REFUSED ({e})"),
    Ok(_) => println!("  !! retroactive probe succeeded — puncturing failed"),
  }
}
