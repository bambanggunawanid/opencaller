//! Phone-side verification of downloaded DB shards (PRD §9).
//!
//! The pipeline signs every shard with Ed25519; the app refuses to open a
//! shard whose signature does not verify against the pinned public key.
//! (Rollback protection — rejecting older `built` versions — is enforced by
//! the shell against the manifest; this module is just the crypto.)

use ed25519_dalek::{Signature, Verifier, VerifyingKey};

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

#[cfg(test)]
mod tests {
  use super::*;
  use ed25519_dalek::{Signer, SigningKey};

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
