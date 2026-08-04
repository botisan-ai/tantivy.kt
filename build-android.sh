#!/bin/bash
# Builds the Android AAR: Rust cross-compile -> Kotlin bindgen -> 16 KB gate -> Gradle test + lint + assemble.
set -euo pipefail
cd "$(dirname "$0")"

export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"

# Pinned toolchain: fail loudly instead of floating to whatever is installed.
NDK_PIN="28.2.13676358"
CARGO_NDK_PIN="4.1.2"
BUILD_TOOLS_PIN="36.1.0"
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/$NDK_PIN"
if [ ! -d "$ANDROID_NDK_HOME" ]; then
  echo "FAIL: NDK $NDK_PIN not installed (expected at $ANDROID_NDK_HOME)" >&2
  exit 1
fi
CARGO_NDK_VERSION="$(cargo ndk --version 2>/dev/null | awk '{print $2}')"
if [ "$CARGO_NDK_VERSION" != "$CARGO_NDK_PIN" ]; then
  echo "FAIL: cargo-ndk $CARGO_NDK_PIN required (found ${CARGO_NDK_VERSION:-none})" >&2
  exit 1
fi
READELF="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/darwin-x86_64/bin/llvm-readelf"
ZIPALIGN="$ANDROID_HOME/build-tools/$BUILD_TOOLS_PIN/zipalign"
if [ ! -x "$ZIPALIGN" ]; then
  echo "FAIL: Android build-tools $BUILD_TOOLS_PIN not installed (expected $ZIPALIGN)" >&2
  exit 1
fi

CRATE="tantivy-kt"
LIB_NAME="libtantivy.so"

echo "==> rust gates (clippy -D warnings, cargo test)"
(cd rust && cargo clippy --workspace --all-targets -- -D warnings && cargo test --workspace)

echo "==> cargo ndk (arm64-v8a, x86_64; API 24; NDK $NDK_PIN)"
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

echo "==> gradle test + lintRelease + assembleRelease (includes the R8 minified-smoke app)"
(cd android && ./gradlew --console=plain test lintRelease assembleRelease :lib:assembleDebugAndroidTest :minified-smoke:assembleAndroidTest)

echo "==> APK 16 KB ZIP-alignment gate"
for apk in \
  android/lib/build/outputs/apk/androidTest/debug/lib-debug-androidTest.apk \
  android/minified-smoke/build/outputs/apk/release/minified-smoke-release.apk \
  android/minified-smoke/build/outputs/apk/androidTest/release/minified-smoke-release-androidTest.apk
do
  if [ ! -f "$apk" ]; then
    echo "FAIL: missing packaged smoke APK: $apk" >&2
    exit 1
  fi
  "$ZIPALIGN" -c -P 16 -v 4 "$apk"
done

echo "==> AAR:"
ls -la android/lib/build/outputs/aar/
