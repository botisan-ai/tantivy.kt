#!/bin/bash
# Publishes a GitHub Release: zipped Maven repo (AAR + POM + module metadata) + bare AAR, each with SHA-256.
# Version comes from android/gradle.properties.
#
# Zip layout: the zip root IS the Maven repository root (ai/botisan/...), so
# consumers can point Gradle straight at the unzip directory.
set -euo pipefail
cd "$(dirname "$0")"

ARTIFACT="tantivy-android"
VERSION=$(grep '^version=' android/gradle.properties | cut -d= -f2)

# The full build gate, not just the Gradle slice: toolchain pins (NDK,
# cargo-ndk), Rust clippy/test, the 16 KB check, and gradle
# test/lint/assemble incl. the R8 smoke app. Releasing must not skip any.
echo "==> release gates (build-android.sh)"
./build-android.sh

echo "==> publishing $ARTIFACT $VERSION to a fresh local maven layout"
rm -rf android/build/maven-repo
(cd android && ./gradlew --console=plain publishReleasePublicationToBuildDirRepository)

STAGING=$(mktemp -d)
UNZIPPED=$(mktemp -d)
trap 'rm -rf "$STAGING" "$UNZIPPED"' EXIT

(cd android/build/maven-repo && zip -qr "$STAGING/$ARTIFACT-$VERSION-maven.zip" .)
cp "android/lib/build/outputs/aar/lib-release.aar" "$STAGING/$ARTIFACT-$VERSION.aar"

echo "==> asserting zip layout resolves the documented Maven path"
if ! unzip -l "$STAGING/$ARTIFACT-$VERSION-maven.zip" | grep -q "ai/botisan/$ARTIFACT/$VERSION/$ARTIFACT-$VERSION.aar"; then
  echo "FAIL: maven zip does not contain ai/botisan/$ARTIFACT/$VERSION/$ARTIFACT-$VERSION.aar at its root" >&2
  exit 1
fi
if unzip -l "$STAGING/$ARTIFACT-$VERSION-maven.zip" | grep -q " maven-repo/"; then
  echo "FAIL: maven zip contains a maven-repo/ wrapper directory" >&2
  exit 1
fi

# Grep proves the member list; only real Gradle resolution proves the POM,
# module metadata, and repository layout. Resolve the exact zip being shipped
# from a clean consumer before anything is uploaded.
unzip -qo "$STAGING/$ARTIFACT-$VERSION-maven.zip" -d "$UNZIPPED"
./verify-consumer-resolution.sh "ai.botisan:$ARTIFACT:$VERSION" "$UNZIPPED"

(cd "$STAGING" \
  && shasum -a 256 "$ARTIFACT-$VERSION-maven.zip" > "$ARTIFACT-$VERSION-maven.zip.sha256" \
  && shasum -a 256 "$ARTIFACT-$VERSION.aar" > "$ARTIFACT-$VERSION.aar.sha256")

CHECKSUMS=$(cat "$STAGING/$ARTIFACT-$VERSION-maven.zip.sha256" "$STAGING/$ARTIFACT-$VERSION.aar.sha256")

echo "==> creating GitHub release $VERSION"
gh release create "$VERSION" --generate-notes --notes "SHA-256:
\`\`\`
$CHECKSUMS
\`\`\`" || true
gh release upload "$VERSION" \
  "$STAGING/$ARTIFACT-$VERSION-maven.zip" \
  "$STAGING/$ARTIFACT-$VERSION-maven.zip.sha256" \
  "$STAGING/$ARTIFACT-$VERSION.aar" \
  "$STAGING/$ARTIFACT-$VERSION.aar.sha256" \
  --clobber

echo "==> done"
