#!/bin/bash
# DEPRECATED (macOS app is no longer maintained; see ../mobile).
# Builds AINewsHot.app (menu-bar agent, universal: Apple Silicon + Intel) into ./build.
#   scripts/build-app.sh            # build only
#   scripts/build-app.sh --install  # build, copy to ~/Applications, relaunch
#   scripts/build-app.sh --zip      # build and create build/AINewsHot-<version>.zip for a GitHub release
set -euo pipefail
cd "$(dirname "$0")/.."

VERSION="$(cat VERSION 2>/dev/null || echo 1.0.0)"
BUILD_NUMBER="$(git rev-list --count HEAD 2>/dev/null || echo 1)"

# SwiftUI macros need the full Xcode toolchain, not just Command Line Tools.
if [ -d /Applications/Xcode.app ]; then export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer; fi

swift build -c release --arch arm64 --arch x86_64
BIN="$(swift build -c release --arch arm64 --arch x86_64 --show-bin-path)/AINewsHot"

APP=build/AINewsHot.app
rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"
cp "$BIN" "$APP/Contents/MacOS/AINewsHot"
# Default API keys, copied into the user's private settings on first launch.
[ -f ../Resources/secrets.json ] && cp ../Resources/secrets.json "$APP/Contents/Resources/secrets.json"
cat > "$APP/Contents/Info.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>CFBundleIdentifier</key><string>news.aihot.mac.reader</string>
  <key>CFBundleName</key><string>AI News Hot</string>
  <key>CFBundleDisplayName</key><string>AI News Hot</string>
  <key>CFBundleExecutable</key><string>AINewsHot</string>
  <key>CFBundlePackageType</key><string>APPL</string>
  <key>CFBundleShortVersionString</key><string>${VERSION}</string>
  <key>CFBundleVersion</key><string>${BUILD_NUMBER}</string>
  <key>LSMinimumSystemVersion</key><string>26.0</string>
  <key>LSUIElement</key><true/>
  <key>NSHighResolutionCapable</key><true/>
</dict></plist>
PLIST
# Ad-hoc signature (no Apple Developer account): fine locally; downloaded copies need the quarantine flag removed.
codesign --force --deep --sign - "$APP" >/dev/null
echo "Built $APP $VERSION ($(lipo -archs "$APP/Contents/MacOS/AINewsHot"))"

case "${1:-}" in
  --install)
    pkill -x AINewsHot 2>/dev/null || true
    mkdir -p ~/Applications
    rm -rf ~/Applications/AINewsHot.app
    cp -R "$APP" ~/Applications/
    open ~/Applications/AINewsHot.app
    echo "Installed and launched ~/Applications/AINewsHot.app"
    ;;
  --zip)
    ZIP="build/AINewsHot-${VERSION}.zip"
    rm -f "$ZIP"
    ditto -c -k --sequesterRsrc --keepParent "$APP" "$ZIP"
    echo "Packaged $ZIP"
    ;;
esac
