//! `opencaller-core` — the shared Rust core for OpenCaller (PRD §7).
//!
//! Everything performance- or privacy-critical lives here, behind
//! UniFFI/JNI bindings on the app side and a CLI in CI:
//!
//! - [`db`]: the offline spam database — `OCDB` binary format with a Bloom
//!   front-end and mmap'd delta-encoded blocks. Same code builds shards in
//!   CI and reads them on-phone. Benchmark: `cargo run --release --bin db_bench`
//! - [`star`]: the M3 collection leg — STAR threshold-encrypted spam
//!   reporting (docs/collection-mechanisms.md §5). A report for a phone
//!   number is only decryptable by the aggregation server once ≥ K distinct
//!   clients reported the same number in the same epoch.
//!   Simulation: `cargo run --release --bin star_sim`

//! - [`lifecycle`]: the §5.9 client report state machine (queue → token →
//!   jitter → OPRF → relay → tombstone) — platform-agnostic; the native
//!   shell drives it from its background scheduler.

pub mod db;
pub mod lifecycle;
pub mod star;
