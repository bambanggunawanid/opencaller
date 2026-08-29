//! `opencaller-core` — the shared Rust core for OpenCaller (PRD §7).
//!
//! Everything performance- or privacy-critical lives here, behind
//! UniFFI/JNI bindings on the app side and a CLI in CI:
//!
//! - [`star`]: the M3 collection leg — STAR threshold-encrypted spam
//!   reporting (docs/collection-mechanisms.md §5). A report for a phone
//!   number is only decryptable by the aggregation server once ≥ K distinct
//!   clients reported the same number in the same epoch.
//! - DB format / lookup engine: lands with the PRD M0 spike (next).
//!
//! Run the research simulation: `cargo run --release --bin star_sim`

pub mod star;
