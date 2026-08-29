#!/usr/bin/env bash
# Smoke-test the JNI seam (Kotlin↔Rust) on the host JVM — no emulator
# needed. Builds the cdylib for the host, compiles the Java twin of
# NativeCore, and runs the same verify→open→lookup sequence the app does,
# against the exact assets bundled in the APK.
set -euo pipefail
cd "$(dirname "$0")/.."

JAVA_HOME="${JAVA_HOME:-$HOME/toolchains/jdk17}"

cargo build --release -p opencaller-android
SO="target/release/libopencaller_android.so"

BUILD_DIR="target/jni-smoke"
mkdir -p "$BUILD_DIR"
"$JAVA_HOME/bin/javac" -d "$BUILD_DIR" \
  scripts/jni-smoke/dev/opencaller/app/NativeCore.java scripts/jni-smoke/Smoke.java

"$JAVA_HOME/bin/java" -cp "$BUILD_DIR" Smoke \
  "$(pwd)/$SO" "$(pwd)/android/app/src/main/assets"
