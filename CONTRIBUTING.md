# Contributing to OpenCaller

Thanks for helping build a spam shield that respects its users. All kinds
of contributions matter here — code, translations, country data sources,
field testing, and documentation.

## Ground rules

- **Privacy is the product.** No PR that phones home, adds analytics, adds
  an account system, or weakens the pinned-key verification will be merged,
  no matter how convenient. Read [SECURITY.md](SECURITY.md) first — it is
  the contract.
- **Offline is the default.** The only permitted network activity is the
  user-controlled shard download (and, in the future, the STAR report
  path described in [docs/collection-mechanisms.md](docs/collection-mechanisms.md)).
- Security vulnerabilities: please report privately per
  [SECURITY.md](SECURITY.md), not as public issues.

## Dev setup

**Rust core** (any OS):

```sh
cargo test --workspace          # 24 tests, no external services
cargo run --release --bin db_bench   # OCDB performance harness
cargo run --release --bin star_sim   # STAR protocol simulation
```

**Android** (JDK 17, Android SDK 35, NDK 27):

```sh
rustup target add aarch64-linux-android x86_64-linux-android
cargo install cargo-ndk
cargo ndk -t arm64-v8a -t x86_64 -o android/app/src/main/jniLibs \
    build --release -p opencaller-android
cd android && ./gradlew assembleDebug
bash scripts/jni-smoke.sh       # JNI seam test on the host JVM (no emulator needed)
```

Debug builds include simulation buttons (Settings → Debug tools) and log
every silently-skipped decision to the Activity tab — use them before
reporting "X doesn't work".

**iOS** (macOS + Xcode 16, or just let CI do it):

```sh
bash scripts/build-ios-rust.sh
cd ios && brew install xcodegen && xcodegen generate
```

The checked-in truth is `ios/project.yml` (XcodeGen), never a `.pbxproj`.
`.github/workflows/build-ios.yml` produces the unsigned IPA on every tag.

## Translations

A translation touches exactly three files, no code:

- `android/app/src/main/res/values-XX/strings.xml` (copy `values-in/` as a template)
- `ios/OpenCaller/XX.lproj/Localizable.strings`
- `ios/CallDirectoryExtension/XX.lproj/Localizable.strings`

English source strings are the keys. Please keep the tone plain and
non-alarmist — the app explains, it doesn't shout.

## Adding a country data source

The pipeline eats CSVs of complained-about numbers. To add a country:

1. Find a **public, legally redistributable** complaint dataset (like the
   FTC/FCC ones for `us`).
2. Add a fetch script under `scripts/` and the schema mapping in
   `crates/opencaller-pipeline` (see the FTC/FCC auto-detection).
3. Add the country to the matrix in `.github/workflows/build-shards.yml`
   and to `Prefs.AVAILABLE_SHARDS` (Android) / seed handling (iOS).

Open an issue first with a link to the dataset — licensing review happens
before code.

## Pull requests

- Match the style around you (the codebase is comment-light; comments state
  constraints, not narration).
- `cargo test --workspace` must pass; Android changes should build both
  variants; UI changes need a field-test note (device + what you saw).
- Keep commits self-contained with messages explaining *why*.
- By contributing you agree your work is licensed under
  [GPL-3.0](LICENSE).

## Sponsoring

If code isn't your thing, the **Sponsor** button on the repo keeps the
project's infrastructure (release signing, shard hosting, the future STAR
randomness-server pilot) independent and free of any incentive to monetize
users.
