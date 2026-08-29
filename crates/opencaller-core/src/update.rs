//! Phone-side handling of downloaded DB shards (PRD §9, F4).
//!
//! The pipeline signs every shard with Ed25519; the app refuses to open a
//! shard whose signature does not verify against the pinned public key.
//! [`apply_update`] is the complete update transaction: verify → validate →
//! rollback check (`built_days` regressions are refused — the header is
//! covered by the signature) → atomic swap. The shell only downloads bytes
//! and calls it; there is no partially-applied state a crash can leave
//! behind that verification-on-open won't catch.

use std::fmt;
use std::fs;
use std::io;
use std::path::Path;

use ed25519_dalek::{Signature, Verifier, VerifyingKey};

use crate::db::SpamDb;

/// Verify `shard` bytes against a 64-byte signature and 32-byte public key.
/// Any malformed input is a verification failure, never a panic.
pub fn verify_shard(shard: &[u8], sig: &[u8], pubkey: &[u8]) -> bool {
  let Ok(sig): Result<[u8; 64], _> = sig.try_into() else {
    return false;
  };
  let Ok(pubkey): Result<[u8; 32], _> = pubkey.try_into() else {
    return false;
  };
  let Ok(key) = VerifyingKey::from_bytes(&pubkey) else {
    return false;
  };
  key.verify(shard, &Signature::from_bytes(&sig)).is_ok()
}

#[derive(Debug)]
pub enum UpdateError {
  /// Signature does not verify against the pinned public key.
  BadSignature,
  /// Signature is fine but the payload is not a valid OCDB shard.
  Malformed(String),
  /// Offered shard is older than the installed one — refused. A signed-but-
  /// stale shard replayed by a mirror/CDN must not roll protection back.
  Rollback { current_days: u16, offered_days: u16 },
  Io(io::Error),
}

impl From<io::Error> for UpdateError {
  fn from(e: io::Error) -> Self {
    Self::Io(e)
  }
}

impl fmt::Display for UpdateError {
  fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
    match self {
      Self::BadSignature => write!(f, "signature invalid"),
      Self::Malformed(m) => write!(f, "malformed shard: {m}"),
      Self::Rollback { current_days, offered_days } => write!(
        f,
        "rollback refused: offered build day {offered_days} < installed {current_days}"
      ),
      Self::Io(e) => write!(f, "io error: {e}"),
    }
  }
}

impl std::error::Error for UpdateError {}

#[derive(Debug)]
pub struct AppliedUpdate {
  pub entries: u64,
  pub built_days: u16,
}

/// The full update transaction for `<dir>/<shard_name>`.
///
/// Steps: verify signature → write to `.new` and validate by opening →
/// refuse `built_days` regressions vs the currently installed shard →
/// rename into place (atomic on one filesystem), then swap the `.sig`.
///
/// Crash-safety: the only non-atomic window is between the two renames
/// (new shard + old sig). That state fails verification on next open, and
/// the shell's self-heal path (re-copy bundled shard / re-download) recovers
/// — corrupt-but-accepted state is impossible.
pub fn apply_update(
  dir: &Path,
  shard_name: &str,
  new_shard: &[u8],
  new_sig: &[u8],
  pubkey: &[u8],
) -> Result<AppliedUpdate, UpdateError> {
  if !verify_shard(new_shard, new_sig, pubkey) {
    return Err(UpdateError::BadSignature);
  }

  let tmp_shard = dir.join(format!("{shard_name}.new"));
  fs::write(&tmp_shard, new_shard)?;
  let (entries, built_days) = match SpamDb::open(&tmp_shard) {
    Ok(db) => (db.len(), db.built_days()),
    Err(e) => {
      fs::remove_file(&tmp_shard).ok();
      return Err(UpdateError::Malformed(e.to_string()));
    }
  };

  let installed = dir.join(shard_name);
  if let Ok(current) = SpamDb::open(&installed) {
    if built_days < current.built_days() {
      fs::remove_file(&tmp_shard).ok();
      return Err(UpdateError::Rollback {
        current_days: current.built_days(),
        offered_days: built_days,
      });
    }
  }

  let tmp_sig = dir.join(format!("{shard_name}.sig.new"));
  fs::write(&tmp_sig, new_sig)?;
  fs::rename(&tmp_shard, &installed)?;
  fs::rename(&tmp_sig, dir.join(format!("{shard_name}.sig")))?;

  Ok(AppliedUpdate { entries, built_days })
}

#[cfg(test)]
mod tests {
  use super::*;
  use crate::db::{DbBuilder, DbEntry};
  use crate::star::Category;
  use ed25519_dalek::{Signer, SigningKey};

  #[test]
  fn apply_upgrade_rollback_tamper() {
    let dir = std::env::temp_dir().join(format!("oc-upd-{}", std::process::id()));
    fs::create_dir_all(&dir).unwrap();
    let key = SigningKey::from_bytes(&[9u8; 32]);
    let pubkey = key.verifying_key().to_bytes();

    let build = |days: u16| {
      let mut b = DbBuilder::new();
      b.set_built_days(days);
      b.add(DbEntry {
        number: 15551234567,
        category: Category::Scam,
        report_count: days,
        last_seen_days: days,
      });
      let p = dir.join("tmp-build.ocdb");
      b.build_to(&p).unwrap();
      let bytes = fs::read(&p).unwrap();
      fs::remove_file(&p).unwrap();
      let sig = key.sign(&bytes).to_bytes().to_vec();
      (bytes, sig)
    };

    // Fresh install (no current shard).
    let (v1, v1sig) = build(20_000);
    let applied = apply_update(&dir, "us.ocdb", &v1, &v1sig, &pubkey).unwrap();
    assert_eq!(applied.built_days, 20_000);
    let db = SpamDb::open(&dir.join("us.ocdb")).unwrap();
    assert_eq!(db.lookup(15551234567).unwrap().report_count, 20_000);
    drop(db);

    // Upgrade applies atomically and sig file matches.
    let (v2, v2sig) = build(20_100);
    apply_update(&dir, "us.ocdb", &v2, &v2sig, &pubkey).unwrap();
    let installed = fs::read(dir.join("us.ocdb")).unwrap();
    let installed_sig = fs::read(dir.join("us.ocdb.sig")).unwrap();
    assert!(verify_shard(&installed, &installed_sig, &pubkey));
    assert_eq!(SpamDb::open(&dir.join("us.ocdb")).unwrap().built_days(), 20_100);

    // Replayed older shard: valid signature, refused as rollback.
    let err = apply_update(&dir, "us.ocdb", &v1, &v1sig, &pubkey).unwrap_err();
    assert!(matches!(err, UpdateError::Rollback { current_days: 20_100, offered_days: 20_000 }));

    // Tampered payload: refused, installed shard untouched.
    let mut evil = v2.clone();
    evil[40] ^= 0xFF;
    assert!(matches!(
      apply_update(&dir, "us.ocdb", &evil, &v2sig, &pubkey),
      Err(UpdateError::BadSignature)
    ));

    // Signed garbage: bad payload with a VALID signature must be rejected
    // as malformed, and must not leave a .new file behind.
    let garbage = b"not an ocdb file at all".to_vec();
    let gsig = key.sign(&garbage).to_bytes().to_vec();
    assert!(matches!(
      apply_update(&dir, "us.ocdb", &garbage, &gsig, &pubkey),
      Err(UpdateError::Malformed(_))
    ));
    assert!(!dir.join("us.ocdb.new").exists());
    assert_eq!(SpamDb::open(&dir.join("us.ocdb")).unwrap().built_days(), 20_100);

    fs::remove_dir_all(&dir).ok();
  }

  #[test]
  fn verify_roundtrip_and_tamper() {
    let key = SigningKey::from_bytes(&[7u8; 32]); // deterministic test key
    let shard = b"OCDB0001-pretend-shard-bytes".to_vec();
    let sig = key.sign(&shard).to_bytes();
    let pubkey = key.verifying_key().to_bytes();

    assert!(verify_shard(&shard, &sig, &pubkey));

    let mut tampered = shard.clone();
    tampered[0] ^= 1;
    assert!(!verify_shard(&tampered, &sig, &pubkey));
    assert!(!verify_shard(&shard, &sig[..63], &pubkey));
    assert!(!verify_shard(&shard, &sig, &pubkey[..31]));
  }
}
