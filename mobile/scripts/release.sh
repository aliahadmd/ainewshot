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

./gradlew -q assembleRelease
IN=app/build/outputs/apk/release/app-release.apk
OUT="$ROOT/build/AINewsHot-$VERSION.apk"
mkdir -p "$ROOT/build"
cp "$IN" "$OUT"

# v1/v2 signed by the old key (what existing installs trust), v3 by the new key with the lineage proving the hand-over.
"$BT/apksigner" sign \
  --ks signing/$(prop legacyStoreFile) --ks-pass pass:$(prop legacyStorePassword) \
  --ks-key-alias $(prop legacyKeyAlias) --key-pass pass:$(prop legacyKeyPassword) \
  --next-signer \
  --ks signing/$(prop storeFile) --ks-pass pass:$(prop storePassword) \
  --ks-key-alias $(prop keyAlias) --key-pass pass:$(prop keyPassword) \
  --lineage signing/lineage.bin \
  --rotation-min-sdk-version 28 \
  "$OUT" 2>&1 | grep -v "^WARNING" || true

"$BT/apksigner" verify --verbose "$OUT" 2>/dev/null | grep -E "Verified using|Number of signers" | head -6
echo "Built $OUT ($VERSION)"

if [ "${1:-}" = "--install" ]; then
  "$SDK/platform-tools/adb" install -r "$OUT"
fi
