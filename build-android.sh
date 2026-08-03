#!/bin/bash
# Builds the Android AAR: Rust cross-compile -> Kotlin bindgen -> 16 KB gate -> Gradle test + assemble.
set -euo pipefail
cd "$(dirname "$0")"

export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
NDK_DIR="$(ls -d "$ANDROID_HOME"/ndk/* | sort -V | tail -1)"
READELF="$NDK_DIR/toolchains/llvm/prebuilt/darwin-x86_64/bin/llvm-readelf"

CRATE="tantivy-kt"
LIB_NAME="libtantivy.so"

echo "==> cargo ndk (arm64-v8a, x86_64; API 24)"
(cd rust && cargo ndk -t arm64-v8a -t x86_64 --platform 24 -o ./target/jniLibs build --release -p "$CRATE")

echo "==> 16 KB page-size gate"
for so in rust/target/jniLibs/*/"$LIB_NAME"; do
  aligns=$("$READELF" -l "$so" | awk '/LOAD/ {print $NF}' | sort -u)
  if [ "$aligns" != "0x4000" ]; then
    echo "FAIL: $so has PT_LOAD alignment(s): $aligns (expected 0x4000)" >&2
    exit 1
  fi
  echo "OK: $so (0x4000)"
done

echo "==> gradle test + assembleRelease"
(cd android && ./gradlew --console=plain test assembleRelease)

echo "==> AAR:"
ls -la android/lib/build/outputs/aar/
