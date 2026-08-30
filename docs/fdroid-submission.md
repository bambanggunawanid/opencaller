# F-Droid submission guide

F-Droid builds every app from source on their servers — which is exactly
the verifiability story this project wants. Submission is a merge request
to their metadata repo (on **GitLab**, so this needs your account):

## Steps

1. Create a GitLab account and fork https://gitlab.com/fdroid/fdroiddata
2. Add the file `metadata/dev.opencaller.app.yml` (template below)
3. Open a merge request titled "New app: OpenCaller"
4. Respond to reviewer feedback (they are thorough about build
   reproducibility and anti-features; we have none of the usual ones —
   no trackers, no proprietary deps, no prebuilt blobs except `jniLibs`,
   which the recipe builds from source)

## Metadata template (`metadata/dev.opencaller.app.yml`)

```yaml
Categories:
  - Phone & SMS
  - Security
License: GPL-3.0-or-later
SourceCode: https://github.com/bambanggunawanid/opencaller
IssueTracker: https://github.com/bambanggunawanid/opencaller/issues
Changelog: https://github.com/bambanggunawanid/opencaller/releases

AutoName: OpenCaller

RepoType: git
Repo: https://github.com/bambanggunawanid/opencaller.git

Builds:
  - versionName: 0.2.1
    versionCode: 3
    commit: v0.2.1
    subdir: android/app
    sudo:
      - apt-get update
      - apt-get install -y curl
    init:
      - curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y
      - source $HOME/.cargo/env
      - rustup target add aarch64-linux-android x86_64-linux-android
      - cargo install cargo-ndk --locked
    gradle:
      - yes
    prebuild:
      - cd ../.. && source $HOME/.cargo/env && ANDROID_NDK_HOME=$$NDK$$
        cargo ndk -t arm64-v8a -t x86_64
        -o android/app/src/main/jniLibs build --release -p opencaller-android
    ndk: r27c

AutoUpdateMode: Version
UpdateCheckMode: Tags ^v[0-9.]+$
CurrentVersion: 0.2.1
CurrentVersionCode: 3
```

Notes for the reviewer conversation:
- `jniLibs` is gitignored — the Rust core is always built from source in
  `prebuild`; nothing binary is checked in except the demo DB shard
  (`assets/us.ocdb`), which is generated from public US government data by
  `crates/opencaller-pipeline` (they may ask; the build recipe for it is
  `.github/workflows/build-shards.yml`).
- The app auto-downloads DB updates from GitHub releases; this is
  user-controlled, signature-verified content, not code — but disclose it
  proactively; some reviewers flag any network fetch.
- Fastlane metadata (descriptions/changelogs) is already in the repo at
  `fastlane/metadata/android/` and F-Droid picks it up automatically.

## Reproducible builds (later, optional but ideal)

To get the green "reproducible" badge, the CI APK and F-Droid's build must
match bit-for-bit. Main obstacles: Rust toolchain version pinning (add a
`rust-toolchain.toml`) and NDK version pinning (already pinned in CI).
Worth doing after the first listing is accepted.
