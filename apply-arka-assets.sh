#!/usr/bin/env bash
set -euo pipefail

ROOT="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
cd "$ROOT"

mkdir -p app/src/main/res/drawable-nodpi
cp -f arka_banner.png app/src/main/res/drawable-nodpi/arka_banner.png
cp -f arka_logo.png app/src/main/res/drawable-nodpi/arka_logo.png

# Remove the hand-redrawn vector substitutes so the exact PNGs are used.
rm -f app/src/main/res/drawable/arka_banner.xml
rm -f app/src/main/res/drawable/arka_logo.xml

echo "Original Arka banner and icon installed."
echo "Review with: git status --short"
echo "Then commit and push the asset changes."
