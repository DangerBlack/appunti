#!/usr/bin/env bash
# Downloads the speech models to upload to the CDN (the app fetches them from there on first launch).
# Usage: scripts/fetch-models.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="${1:-$ROOT/build/models}"
PARAKEET="https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8/resolve/main"
SILERO="https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx"

mkdir -p "$DEST/parakeet" "$DEST/vad"

fetch() { # url dest
  if [[ -s "$2" ]]; then echo "ok   $2"; return; fi
  echo "get  $1"
  curl -fL --retry 3 -C - -o "$2.part" "$1"
  mv "$2.part" "$2"
}

for f in encoder.int8.onnx decoder.int8.onnx joiner.int8.onnx tokens.txt; do
  fetch "$PARAKEET/$f" "$DEST/parakeet/$f"
done
fetch "$SILERO" "$DEST/vad/silero_vad.onnx"

du -sh "$DEST"
