#!/usr/bin/env bash
# Prepares everything project.yml expects before `xcodegen generate`:
# the Rust static lib for iOS devices and the bundled seed resources.
# Run on macOS (CI: build-ios.yml).
set -euo pipefail
cd "$(dirname "$0")/.."

rustup target add aarch64-apple-ios
cargo build --release -p opencaller-ios --target aarch64-apple-ios
mkdir -p ios/rust/lib
cp target/aarch64-apple-ios/release/libopencaller_ios.a ios/rust/lib/

mkdir -p ios/OpenCaller/Resources
cp android/app/src/main/assets/us.ocdb ios/OpenCaller/Resources/
cp android/app/src/main/assets/us.ocdb.sig ios/OpenCaller/Resources/
cp android/app/src/main/assets/shard_signing.pub ios/OpenCaller/Resources/

echo "ios: rust lib + seed resources ready"
