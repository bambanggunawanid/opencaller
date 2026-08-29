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

# Update-scenario fixtures: three pipeline-built shards (old/current/new
# build dates) signed with a throwaway key, from a tiny synthetic CSV.
UPD_DIR="$BUILD_DIR/update-fixtures"
rm -rf "$UPD_DIR" && mkdir -p "$UPD_DIR"
cat > "$UPD_DIR/rows.csv" <<'CSV'
Company_Phone_Number,Created_Date,Violation_Date,Consumer_City,Consumer_State,Consumer_Area_Code,Subject,Recorded_Message_Or_Robocall
5551234567,2026-08-25 00:00:00,2026-08-24 12:00:00,,Texas,512,Dropped call or no message,Y
CSV
PIPELINE=(cargo run -q --release -p opencaller-pipeline --)
"${PIPELINE[@]}" keygen --out-dir "$UPD_DIR" >/dev/null
for v in vOld:2026-07-01 v1:2026-08-29 v2:2026-09-04; do
  name="${v%%:*}" date="${v##*:}"
  "${PIPELINE[@]}" build --country US --today "$date" --max-age-days 365 \
    --out "$UPD_DIR/$name.ocdb" --sign "$UPD_DIR/shard_signing.key" \
    "$UPD_DIR/rows.csv" >/dev/null
done
rm "$UPD_DIR/shard_signing.key"

"$JAVA_HOME/bin/java" -cp "$BUILD_DIR" Smoke \
  "$(pwd)/$SO" "$(pwd)/android/app/src/main/assets" "$(pwd)/$UPD_DIR"
