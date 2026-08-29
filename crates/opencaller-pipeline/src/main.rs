//! OpenCaller data pipeline (PRD §8): public complaint datasets → signed
//! OCDB shards. Runs in CI; the same `opencaller-core` code that builds the
//! shard is what reads it on-phone, so the format cannot drift.
//!
//! Ingests the FTC Do-Not-Call daily complaint CSVs
//! (`DNC_Complaint_Numbers_YYYY-MM-DD.csv`, columns: Company_Phone_Number,
//! Created_Date, Violation_Date, ..., Subject, Recorded_Message_Or_Robocall).
//!
//! Commands:
//!   keygen  --out-dir <dir>
//!   build   --country US --today YYYY-MM-DD [--max-age-days N]
//!           --out <shard.ocdb> [--sign <key file>] <in.csv>...
//!   verify  --pubkey <pub file> <shard.ocdb>
//!   lookup  <shard.ocdb> <number>
//!
//! Aging is a correctness/ethics requirement (numbers get reassigned):
//! complaints older than --max-age-days (default 180) are dropped, and
//! `last_seen_days` is stored so the phone can apply its own decay.

use std::collections::HashMap;
use std::fs;
use std::path::{Path, PathBuf};
use std::process::ExitCode;

use ed25519_dalek::{Signature, Signer, SigningKey, Verifier, VerifyingKey};
use sha2::{Digest, Sha256};

use opencaller_core::db::{DbBuilder, DbEntry, SpamDb};
use opencaller_core::star::Category;

// ---------------------------------------------------------------- dates --

/// Days since 1970-01-01 for a civil date (Howard Hinnant's algorithm).
fn days_from_civil(y: i64, m: u32, d: u32) -> i64 {
  let y = if m <= 2 { y - 1 } else { y };
  let era = if y >= 0 { y } else { y - 399 } / 400;
  let yoe = y - era * 400;
  let mp = if m > 2 { m - 3 } else { m + 9 } as i64;
  let doy = (153 * mp + 2) / 5 + d as i64 - 1;
  let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
  era * 146_097 + doe - 719_468
}

/// Parse the leading `YYYY-MM-DD` of an FTC timestamp to epoch days.
fn parse_date_days(s: &str) -> Option<i64> {
  let s = s.get(0..10)?;
  let mut it = s.split('-');
  let y: i64 = it.next()?.parse().ok()?;
  let m: u32 = it.next()?.parse().ok()?;
  let d: u32 = it.next()?.parse().ok()?;
  if !(1..=12).contains(&m) || !(1..=31).contains(&d) {
    return None;
  }
  Some(days_from_civil(y, m, d))
}

// --------------------------------------------------------------- ingest --

/// Map an FTC complaint `Subject` (+ robocall flag) to our category.
fn map_subject(subject: &str, robocall: bool) -> Category {
  let s = subject.to_ascii_lowercase();
  if s.contains("pretending") || s.contains("imposter") {
    Category::Scam
  } else if s.contains("debt") {
    Category::DebtCollection
  } else if s.contains("survey") {
    Category::Survey
  } else if s.contains("warrant")
    || s.contains("vacation")
    || s.contains("timeshare")
    || s.contains("home improvement")
    || s.contains("energy")
    || s.contains("medical")
    || s.contains("prescription")
    || s.contains("charit")
    || s.contains("work from home")
    || s.contains("lotter")
    || s.contains("computer")
  {
    Category::Telemarketing
  } else if robocall || s.contains("dropped call") || s.contains("no message") {
    Category::Robocall
  } else {
    Category::Other
  }
}

/// Normalize an FTC phone field to a NANP E.164 u64 (leading country code 1).
fn normalize_nanp(raw: &str) -> Option<u64> {
  let digits: String = raw.chars().filter(|c| c.is_ascii_digit()).collect();
  match digits.len() {
    10 => Some(10_000_000_000 + digits.parse::<u64>().ok()?),
    11 if digits.starts_with('1') => digits.parse().ok(),
    _ => None,
  }
}

#[derive(Default)]
struct NumberAgg {
  count: u64,
  latest_days: i64,
  votes: HashMap<Category, u32>,
}

struct IngestStats {
  rows: u64,
  skipped_bad_number: u64,
  skipped_bad_date: u64,
  aged_out: u64,
}

fn ingest_csvs(
  paths: &[PathBuf],
  min_days: i64,
) -> Result<(HashMap<u64, NumberAgg>, IngestStats), String> {
  let mut agg: HashMap<u64, NumberAgg> = HashMap::new();
  let mut stats =
    IngestStats { rows: 0, skipped_bad_number: 0, skipped_bad_date: 0, aged_out: 0 };

  for path in paths {
    let mut rdr = csv::ReaderBuilder::new()
      .flexible(true)
      .from_path(path)
      .map_err(|e| format!("{}: {e}", path.display()))?;
    let headers = rdr.headers().map_err(|e| e.to_string())?.clone();
    let col = |name: &str| {
      headers.iter().position(|h| h.eq_ignore_ascii_case(name))
    };
    let (Some(c_phone), Some(c_subject)) =
      (col("Company_Phone_Number"), col("Subject"))
    else {
      return Err(format!(
        "{}: missing Company_Phone_Number/Subject columns",
        path.display()
      ));
    };
    let c_violation = col("Violation_Date");
    let c_created = col("Created_Date");
    let c_robocall = col("Recorded_Message_Or_Robocall");

    for record in rdr.records() {
      let record = record.map_err(|e| e.to_string())?;
      stats.rows += 1;
      let Some(number) = record.get(c_phone).and_then(normalize_nanp) else {
        stats.skipped_bad_number += 1;
        continue;
      };
      // Violation date preferred; fall back to complaint creation date.
      let days = c_violation
        .and_then(|c| record.get(c))
        .and_then(parse_date_days)
        .or_else(|| c_created.and_then(|c| record.get(c)).and_then(parse_date_days));
      let Some(days) = days else {
        stats.skipped_bad_date += 1;
        continue;
      };
      if days < min_days {
        stats.aged_out += 1;
        continue;
      }
      let robocall = c_robocall
        .and_then(|c| record.get(c))
        .map(|v| v.eq_ignore_ascii_case("y"))
        .unwrap_or(false);
      let category =
        map_subject(record.get(c_subject).unwrap_or_default(), robocall);

      let e = agg.entry(number).or_default();
      e.count += 1;
      e.latest_days = e.latest_days.max(days);
      *e.votes.entry(category).or_default() += 1;
    }
  }
  Ok((agg, stats))
}

fn majority_category(votes: &HashMap<Category, u32>) -> Category {
  // Highest vote count; ties break toward the more severe category
  // (Scam=0 < Robocall=1 < ... — lower discriminant = more severe).
  votes
    .iter()
    .max_by(|a, b| a.1.cmp(b.1).then_with(|| (*b.0 as u8).cmp(&(*a.0 as u8))))
    .map(|(c, _)| *c)
    .unwrap_or(Category::Other)
}

// ------------------------------------------------------------- commands --

fn cmd_keygen(out_dir: &Path) -> Result<(), String> {
  fs::create_dir_all(out_dir).map_err(|e| e.to_string())?;
  let key = SigningKey::generate(&mut rand::rngs::OsRng);
  let priv_path = out_dir.join("shard_signing.key");
  let pub_path = out_dir.join("shard_signing.pub");
  fs::write(&priv_path, key.to_bytes()).map_err(|e| e.to_string())?;
  fs::write(&pub_path, key.verifying_key().to_bytes()).map_err(|e| e.to_string())?;
  println!("wrote {} (KEEP OFFLINE) and {}", priv_path.display(), pub_path.display());
  Ok(())
}

#[allow(clippy::too_many_arguments)]
fn cmd_build(
  inputs: &[PathBuf],
  out: &Path,
  country: &str,
  today: &str,
  max_age_days: i64,
  sign_key: Option<&Path>,
) -> Result<(), String> {
  let today_days =
    parse_date_days(today).ok_or_else(|| format!("bad --today date: {today}"))?;
  let min_days = today_days - max_age_days;

  let (agg, stats) = ingest_csvs(inputs, min_days)?;
  if agg.is_empty() {
    return Err("no usable rows — refusing to build an empty shard".into());
  }

  let mut builder = DbBuilder::new();
  for (number, a) in &agg {
    builder.add(DbEntry {
      number: *number,
      category: majority_category(&a.votes),
      report_count: a.count.min(u16::MAX as u64) as u16,
      last_seen_days: a.latest_days.clamp(0, u16::MAX as i64) as u16,
    });
  }
  let build = builder.build_to(out).map_err(|e| e.to_string())?;

  let shard_bytes = fs::read(out).map_err(|e| e.to_string())?;
  let sha256 = hex(&Sha256::digest(&shard_bytes));

  let mut signed = false;
  if let Some(key_path) = sign_key {
    let key_bytes: [u8; 32] = fs::read(key_path)
      .map_err(|e| e.to_string())?
      .try_into()
      .map_err(|_| "signing key must be 32 bytes".to_string())?;
    let key = SigningKey::from_bytes(&key_bytes);
    let sig = key.sign(&shard_bytes);
    fs::write(format!("{}.sig", out.display()), sig.to_bytes())
      .map_err(|e| e.to_string())?;
    signed = true;
  }

  // Tiny hand-rolled manifest (all values are simple; no JSON dep needed).
  let manifest = format!(
    "{{\n  \"format\": \"OCDB0001\",\n  \"country\": \"{country}\",\n  \"built\": \"{today}\",\n  \"entries\": {},\n  \"source_rows\": {},\n  \"skipped_bad_number\": {},\n  \"skipped_bad_date\": {},\n  \"aged_out\": {},\n  \"max_age_days\": {max_age_days},\n  \"sha256\": \"{sha256}\",\n  \"signed\": {signed}\n}}\n",
    build.entries, stats.rows, stats.skipped_bad_number, stats.skipped_bad_date, stats.aged_out,
  );
  fs::write(format!("{}.manifest.json", out.display()), &manifest)
    .map_err(|e| e.to_string())?;

  println!(
    "built {} — {} entries from {} rows ({} bad numbers, {} bad dates, {} aged out), {:.1} KB{}",
    out.display(),
    build.entries,
    stats.rows,
    stats.skipped_bad_number,
    stats.skipped_bad_date,
    stats.aged_out,
    build.file_bytes as f64 / 1e3,
    if signed { ", signed" } else { ", UNSIGNED" },
  );
  Ok(())
}

fn cmd_verify(shard: &Path, pubkey: &Path) -> Result<(), String> {
  let shard_bytes = fs::read(shard).map_err(|e| e.to_string())?;
  let sig_bytes: [u8; 64] = fs::read(format!("{}.sig", shard.display()))
    .map_err(|e| format!("missing .sig: {e}"))?
    .try_into()
    .map_err(|_| "signature must be 64 bytes".to_string())?;
  let pub_bytes: [u8; 32] = fs::read(pubkey)
    .map_err(|e| e.to_string())?
    .try_into()
    .map_err(|_| "public key must be 32 bytes".to_string())?;
  let key =
    VerifyingKey::from_bytes(&pub_bytes).map_err(|e| e.to_string())?;
  key
    .verify(&shard_bytes, &Signature::from_bytes(&sig_bytes))
    .map_err(|_| "SIGNATURE INVALID".to_string())?;

  let db = SpamDb::open(shard).map_err(|e| e.to_string())?;
  println!(
    "signature OK — {} entries, {:.1} KB",
    db.len(),
    db.file_bytes() as f64 / 1e3
  );
  Ok(())
}

fn cmd_lookup(shard: &Path, number: &str) -> Result<(), String> {
  let db = SpamDb::open(shard).map_err(|e| e.to_string())?;
  match db.lookup_str(number) {
    Some(info) => println!(
      "{number}: {} — {} report(s), last seen day {}",
      info.category.label(),
      info.report_count,
      info.last_seen_days
    ),
    None => println!("{number}: not in database"),
  }
  Ok(())
}

fn hex(bytes: &[u8]) -> String {
  bytes.iter().map(|b| format!("{b:02x}")).collect()
}

// ------------------------------------------------------------------ cli --

fn run() -> Result<(), String> {
  let args: Vec<String> = std::env::args().skip(1).collect();
  let mut flags: HashMap<String, String> = HashMap::new();
  let mut positional: Vec<String> = Vec::new();
  let mut it = args.iter().peekable();
  let cmd = it.next().cloned().unwrap_or_default();
  while let Some(a) = it.next() {
    if let Some(name) = a.strip_prefix("--") {
      let val = it.next().cloned().ok_or_else(|| format!("--{name} needs a value"))?;
      flags.insert(name.to_owned(), val);
    } else {
      positional.push(a.clone());
    }
  }
  let flag = |n: &str| flags.get(n).cloned();

  match cmd.as_str() {
    "keygen" => cmd_keygen(Path::new(
      &flag("out-dir").ok_or("keygen needs --out-dir")?,
    )),
    "build" => {
      let inputs: Vec<PathBuf> = positional.iter().map(PathBuf::from).collect();
      if inputs.is_empty() {
        return Err("build needs at least one input CSV".into());
      }
      cmd_build(
        &inputs,
        Path::new(&flag("out").ok_or("build needs --out")?),
        &flag("country").unwrap_or_else(|| "US".into()),
        &flag("today").ok_or("build needs --today YYYY-MM-DD (deterministic CI)")?,
        flag("max-age-days").map(|v| v.parse().unwrap_or(180)).unwrap_or(180),
        flag("sign").map(PathBuf::from).as_deref(),
      )
    }
    "verify" => cmd_verify(
      Path::new(positional.first().ok_or("verify needs a shard path")?),
      Path::new(&flag("pubkey").ok_or("verify needs --pubkey")?),
    ),
    "lookup" => cmd_lookup(
      Path::new(positional.first().ok_or("lookup needs a shard path")?),
      positional.get(1).ok_or("lookup needs a number")?,
    ),
    _ => Err("usage: opencaller-pipeline <keygen|build|verify|lookup> ...".into()),
  }
}

fn main() -> ExitCode {
  match run() {
    Ok(()) => ExitCode::SUCCESS,
    Err(e) => {
      eprintln!("error: {e}");
      ExitCode::FAILURE
    }
  }
}

#[cfg(test)]
mod tests {
  use super::*;

  #[test]
  fn civil_dates() {
    assert_eq!(days_from_civil(1970, 1, 1), 0);
    assert_eq!(days_from_civil(2026, 8, 29), 20_694);
    assert_eq!(parse_date_days("2026-08-24 14:04:00"), Some(20_689));
    assert_eq!(parse_date_days("garbage"), None);
  }

  #[test]
  fn nanp_normalization() {
    assert_eq!(normalize_nanp("8283003919"), Some(18_283_003_919));
    assert_eq!(normalize_nanp("1-828-300-3919"), Some(18_283_003_919));
    assert_eq!(normalize_nanp("911"), None);
    assert_eq!(normalize_nanp(""), None);
  }

  #[test]
  fn subject_mapping() {
    assert_eq!(
      map_subject("Calls pretending to be government, businesses, or family and friends", true),
      Category::Scam
    );
    assert_eq!(
      map_subject("Reducing your debt (credit cards, mortgage, student loans)", true),
      Category::DebtCollection
    );
    assert_eq!(map_subject("Dropped call or no message", false), Category::Robocall);
    assert_eq!(map_subject("Something unrecognized", true), Category::Robocall);
    assert_eq!(map_subject("Something unrecognized", false), Category::Other);
  }

  #[test]
  fn end_to_end_build_verify_lookup() {
    let dir = std::env::temp_dir().join(format!("ocp-{}", std::process::id()));
    fs::create_dir_all(&dir).unwrap();
    let csv_path = dir.join("day.csv");
    fs::write(
      &csv_path,
      "Company_Phone_Number,Created_Date,Violation_Date,Consumer_City,Consumer_State,Consumer_Area_Code,Subject,Recorded_Message_Or_Robocall\n\
       8283003919,2026-08-25 00:04:31,2026-08-01 19:07:00,,Texas,512,\"Reducing your debt (credit cards, mortgage, student loans)\",Y\n\
       8283003919,2026-08-25 09:00:00,2026-08-20 10:00:00,,Texas,512,\"Reducing your debt (credit cards, mortgage, student loans)\",Y\n\
       9518514805,2026-08-25 00:04:48,2026-08-24 14:04:00,,California,951,Dropped call or no message,N\n\
       0000,2026-08-25 00:00:00,2026-08-24 00:00:00,,,,bad number row,N\n\
       4136011047,2026-08-25 00:05:12,2020-01-01 00:00:00,,Georgia,770,old aged-out row,Y\n",
    )
    .unwrap();

    cmd_keygen(&dir).unwrap();
    let shard = dir.join("us.ocdb");
    cmd_build(
      &[csv_path],
      &shard,
      "US",
      "2026-08-29",
      180,
      Some(&dir.join("shard_signing.key")),
    )
    .unwrap();
    cmd_verify(&shard, &dir.join("shard_signing.pub")).unwrap();

    let db = SpamDb::open(&shard).unwrap();
    assert_eq!(db.len(), 2, "bad + aged rows must be excluded");
    let hit = db.lookup(18_283_003_919).unwrap();
    assert_eq!(hit.report_count, 2);
    assert_eq!(hit.category, Category::DebtCollection);
    assert_eq!(hit.last_seen_days, 20_685); // 2026-08-20
    assert!(db.lookup(14_136_011_047).is_none());

    // Tampering must fail verification.
    let mut bytes = fs::read(&shard).unwrap();
    let last = bytes.len() - 1;
    bytes[last] ^= 0xFF;
    fs::write(&shard, &bytes).unwrap();
    assert!(cmd_verify(&shard, &dir.join("shard_signing.pub")).is_err());

    fs::remove_dir_all(&dir).ok();
  }
}
