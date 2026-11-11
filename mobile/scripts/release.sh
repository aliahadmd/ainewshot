#!/bin/bash
# Build a publishable APK: release build signed with the dedicated key + rotation lineage from the old debug key.
#   mobile/scripts/release.sh            -> build/AINewsHot-<VERSION>.apk (repo root)
#   mobile/scripts/release.sh --install  -> also installs on the USB-connected phone (keeps app data)
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT=..
VERSION="$(cat $ROOT/VERSION)"
export JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
BT="$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)"
prop() { grep "^$1=" signing/signing.properties | cut -d= -f2-; }

# Keep signing passwords out of process arguments. apksigner reads these variables directly.
export AINH_LEGACY_STORE_PASSWORD="$(prop legacyStorePassword)"
export AINH_LEGACY_KEY_PASSWORD="$(prop legacyKeyPassword)"
export AINH_RELEASE_STORE_PASSWORD="$(prop storePassword)"
export AINH_RELEASE_KEY_PASSWORD="$(prop keyPassword)"

./gradlew -q assembleRelease
IN=app/build/outputs/apk/release/app-release.apk
OUT="$ROOT/build/AINewsHot-$VERSION.apk"
mkdir -p "$ROOT/build"
cp "$IN" "$OUT"

# v1/v2 signed by the old key (what existing installs trust), v3 by the new key with the lineage proving the hand-over.
"$BT/apksigner" sign \
  --ks signing/$(prop legacyStoreFile) --ks-pass env:AINH_LEGACY_STORE_PASSWORD \
  --ks-key-alias $(prop legacyKeyAlias) --key-pass env:AINH_LEGACY_KEY_PASSWORD \
  --next-signer \
  --ks signing/$(prop storeFile) --ks-pass env:AINH_RELEASE_STORE_PASSWORD \
  --ks-key-alias $(prop keyAlias) --key-pass env:AINH_RELEASE_KEY_PASSWORD \
  --lineage signing/lineage.bin \
  --rotation-min-sdk-version 28 \
  "$OUT"

"$BT/apksigner" verify --verbose "$OUT" 2>/dev/null | grep -E "Verified using|Number of signers" | head -6
echo "Built $OUT ($VERSION)"

if [ "${1:-}" = "--install" ]; then
  "$SDK/platform-tools/adb" install -r "$OUT"
fi
