# OpenCaller

A free, open-source spam-call shield that works **entirely on-device** — no
accounts, no ads, no contact harvesting, no user data collected, ever. Also a
research project: collective spam intelligence where the collector
*cryptographically cannot* read individual reports (STAR threshold
aggregation).

> TrueCaller's spam shield, without TrueCaller's surveillance.

## Documents

| Doc | What |
|---|---|
| [PRD.md](PRD.md) | Product requirements: scope, features, architecture, roadmap |
| [docs/collection-mechanisms.md](docs/collection-mechanisms.md) | The research core: how an offline shield collects spam numbers collectively (M0–M4, STAR threat model, client lifecycle, cold-start math) |
| [docs/partner-brief.md](docs/partner-brief.md) | One-pager for a prospective randomness-server operating partner |

## Code (`crates/opencaller-core`)

The shared Rust core (PRD §7) — the same crate will sit behind UniFFI/JNI on
Android/iOS and power the CI data pipeline.

| Module | What | Try it |
|---|---|---|
| `db` | `OCDB` offline database: Bloom front-end + mmap'd delta-encoded blocks. 10M entries → 77 MB, **133 ns** miss / 1.5 µs hit, RAM-flat | `cargo run --release --bin db_bench` |
| `star` | M3 collection: full STAR flow (`sta-rs` + direct `ppoprf`), threshold decryption, epoch puncturing | `cargo run --release --bin star_sim` |
| `lifecycle` | Client report state machine: explicit-tap-only, token-gated, jittered, tombstoned | `cargo test` |

## Status

Research phase. Protocol and DB engine validated in simulation/benchmarks;
Android shell (Kotlin `CallScreeningService` + Compose) is the next milestone
(PRD §14, M0/M2).
