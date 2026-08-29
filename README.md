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

## Layout

```
crates/opencaller-core       shared Rust core (behind JNI on Android; CI pipeline)
  db          OCDB offline database: Bloom front-end + mmap'd delta blocks
              10M entries → 77 MB, 133 ns miss / 1.5 µs hit, RAM-flat
              → cargo run --release --bin db_bench
  star        M3 collection: full STAR flow (sta-rs + direct ppoprf),
              threshold decryption, epoch puncturing
              → cargo run --release --bin star_sim
  lifecycle   client report state machine (explicit-tap-only, token-gated,
              jittered, tombstoned)
  update      Ed25519 shard verification (pinned pubkey)

crates/opencaller-pipeline   CI: FTC DNC complaint CSVs → signed OCDB shards
              → opencaller-pipeline keygen|build|verify|lookup

crates/opencaller-android    JNI bridge (cdylib) for the app
              → cargo ndk -t arm64-v8a -o android/app/src/main/jniLibs \
                  build --release -p opencaller-android

android/                     Kotlin shell: CallScreeningService (silences
                             scam/robocalls via local lookup only) + Compose
                             status/lookup UI. No INTERNET permission in M0.
              → cd android && ./gradlew assembleDebug
```

## Status

Research/M0 phase — all validated in this repo:
- STAR collection protocol: simulated 10k-user epoch, threshold/probe/puncture demos.
- OCDB engine: 10M-entry benchmark hits PRD targets with ~650× headroom.
- Pipeline: real FTC daily data (11.6k complaints → 10k-entry signed shard, 90 KB).
- Android app: builds with the real signed shard bundled; screening policy
  silences scam/robocall numbers from the offline DB.

Signing keys for the demo shard live outside the repo (`~/opencaller-keys`).
