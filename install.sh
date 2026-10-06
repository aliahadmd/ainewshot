#!/bin/bash
# Install the latest AI News Hot release on this Mac.
#
#   gh auth login            # once, if you haven't (the repo is private)
#   bash install.sh
#
# The app is not notarized (no paid Apple Developer account), so macOS would block a
# browser-downloaded copy. This script removes the quarantine flag so it opens normally.
set -euo pipefail

REPO="aliahadmd/ainewshot"
DEST="$HOME/Applications"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

if [ "$(sw_vers -productVersion | cut -d. -f1)" -lt 26 ]; then
  echo "AI News Hot needs macOS 26 or later." >&2; exit 1
fi

if command -v gh >/dev/null; then
  gh release download --repo "$REPO" --pattern 'AINewsHot-*.zip' --dir "$TMP"
elif ls "$HOME"/Downloads/AINewsHot-*.zip >/dev/null 2>&1; then
  cp "$(ls -t "$HOME"/Downloads/AINewsHot-*.zip | head -1)" "$TMP/"
else
  echo "Install the GitHub CLI (brew install gh && gh auth login), or download AINewsHot-<version>.zip" >&2
  echo "from https://github.com/$REPO/releases into ~/Downloads and run this again." >&2
  exit 1
fi

pkill -x AINewsHot 2>/dev/null || true
mkdir -p "$DEST"
rm -rf "$DEST/AINewsHot.app"
ditto -x -k "$TMP"/AINewsHot-*.zip "$DEST"
xattr -dr com.apple.quarantine "$DEST/AINewsHot.app" 2>/dev/null || true
open "$DEST/AINewsHot.app"
echo "Installed $DEST/AINewsHot.app — look for the ⚡ icon in the menu bar."
echo "Tip: Settings › General › Launch at login."
