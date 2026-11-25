#!/usr/bin/env bash
# Downloads the sherpa-onnx Android library (not committed: ~50 MB) and verifies its checksum.
# Usage: scripts/fetch-deps.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
VERSION="2.0.3"
SHA256="4c766e2e74c9d33d595338c21fe3b305c73ba45d47e96a235675c33142a2ec50"
URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/v$VERSION/sherpa-onnx-$VERSION.aar"
DEST="$ROOT/app/libs/sherpa-onnx-$VERSION.aar"

mkdir -p "$(dirname "$DEST")"
if [[ ! -s "$DEST" ]] || ! echo "$SHA256  $DEST" | sha256sum -c --quiet - 2>/dev/null; then
  echo "get  $URL"
  curl -fL --retry 3 -o "$DEST.part" "$URL"
  mv "$DEST.part" "$DEST"
fi
echo "$SHA256  $DEST" | sha256sum -c -
