# OpenCaller

A free, open-source spam-call shield that works **entirely on-device** — no
accounts, no ads, no contact harvesting, no user data collected, ever. Also a
research project: collective spam intelligence where the collector
*cryptographically cannot* read individual reports (STAR threshold
aggregation).

> TrueCaller's spam shield, without TrueCaller's surveillance.

## Download

Grab the newest build from
**[Releases](https://github.com/bambanggunawanid/opencaller/releases/latest)**:

| File | Platform |
|---|---|
| `opencaller-vX.Y.Z.apk` | Android 10+ (signed, R8-minified, ~3.5 MB) |
| `opencaller-vX.Y.Z-debug.apk` | Android, with debug/simulation tools |
| `opencaller-ios-unsigned.ipa` | iOS 15+ — sideload with AltStore/Sideloadly ([how](ios/README.md)) |

Open the app once and it arms itself: the call-screening prompt appears
immediately, the spam list auto-syncs, and a short "Finish setup" card walks
through the few grants Android won't let an app give itself. F-Droid
submission is prepared in [docs/fdroid-submission.md](docs/fdroid-submission.md).

## What it does

- **Blocks and labels spam calls offline** — scam/robocall numbers silenced by
  default from a local database; every lookup happens on the phone in ~µs.
- **Survives number rotation** — spam *prefix blocks* condemn whole SIM
  batches, and on-device wangiri learning silences a burner block after two
  bait rings (nothing leaves the phone).
- **SMS spam warn/mute** and **WhatsApp call warnings** (Android, opt-in
  notification access), a Truecaller-style full-screen warning badge, and an
  "Expecting a call" pause for courier moments.
- **iOS port** — CallKit Call Directory labels/blocks + an offline SMS junk
  filter, same Rust core, same signed shards.
- English + Bahasa Indonesia, Material You, dark mode.

## How the data works

A CI pipeline turns public complaint data (FTC Do-Not-Call, FCC consumer
complaints) into compact **OCDB** shards — Bloom-fronted, mmap'd,
delta-compressed (208k numbers ≈ 1.7 MB) — signs them with Ed25519, and
publishes them as release assets. Phones verify every shard against a
**pinned public key** before use, with rollback protection; the transport is
untrusted by design. Updates are weekly, automatic, and user-controlled
(off / Wi-Fi only / any network).

The research half — how a fully offline shield can *collectively* learn new
spammers without anyone being able to read an individual's report — is the
STAR protocol work in
[docs/collection-mechanisms.md](docs/collection-mechanisms.md).

## Documents

| Doc | What |
|---|---|
| [PRD.md](PRD.md) | Product requirements: scope, features, architecture, roadmap |
| [docs/collection-mechanisms.md](docs/collection-mechanisms.md) | The research core: M0–M4, STAR threat model, client lifecycle, cold-start math |
| [docs/partner-brief.md](docs/partner-brief.md) | One-pager for a prospective randomness-server operating partner |
| [SECURITY.md](SECURITY.md) | Security model, audit log, how to report vulnerabilities |
| [CONTRIBUTING.md](CONTRIBUTING.md) | Dev setup, translations, adding a country, PR guidelines |
| [ios/README.md](ios/README.md) | iOS port: what Apple allows, building, sideloading |

## Layout

```
crates/opencaller-core       shared Rust core: OCDB engine (exact + prefix
                             lookup, streaming iterator), STAR flow, update
                             verification, heuristics, client lifecycle
crates/opencaller-pipeline   CI: FTC/FCC CSVs → clustered, signed OCDB shards
crates/opencaller-android    JNI bridge (cdylib) for the Android app
crates/opencaller-ios        C ABI (staticlib) for the iOS app + extensions
android/                     Kotlin/Compose app: CallScreeningService,
                             notification listener (WhatsApp/SMS/wangiri),
                             overlay + full-screen warnings, auto-sync
ios/                         SwiftUI app + CallKit Call Directory + SMS
                             filter extensions (XcodeGen spec, CI-built IPA)
scripts/                     data fetchers, JNI smoke test, iOS prep
```

## Building

```sh
cargo test --workspace                          # Rust core + pipeline
cd android && ./gradlew assembleDebug           # Android (needs SDK/NDK; see CONTRIBUTING)
bash scripts/build-ios-rust.sh && cd ios && xcodegen  # iOS (macOS; or let CI build it)
```

## Contributing

All contributions are welcome — code, **translations** (a single
`strings.xml` / `.lproj` file localizes the whole app), country data
sources, field testing, and documentation. Start with
[CONTRIBUTING.md](CONTRIBUTING.md).

## Sponsoring

OpenCaller is free forever — no ads, no premium tier, no data to sell,
which also means no revenue. If it saved you from a scam call, consider
sponsoring the project via the **Sponsor** button on this repo. Funds go
toward release signing infrastructure, the future STAR randomness-server
pilot, and keeping shard hosting independent.

## Credits

- Built and maintained by **[Wolftagon Studio](https://github.com/bambanggunawanid)**.
- Spam data derived from public **FTC Do-Not-Call** and **FCC consumer
  complaint** datasets.
- The private collection design builds on **[STAR](https://arxiv.org/abs/2109.10074)**
  (Davidson et al., Brave Research) and the
  [`sta-rs`](https://github.com/brave/sta-rs) / `ppoprf` crates.
- Everyone field-testing builds and reporting spam waves — the whole point.

## License

[GPL-3.0](LICENSE) — copyleft, so every fork of this shield stays open.
