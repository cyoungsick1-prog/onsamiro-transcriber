#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$ROOT/third_party/whisper.cpp"
TAG="v1.9.4"
COMMIT="927cfce34f31707e17f2bff35c349632fb9e2c3a"
if [[ -d "$DEST/.git" ]]; then
  git -C "$DEST" fetch --tags origin
else
  rm -rf "$DEST"
  git clone --filter=blob:none https://github.com/ggml-org/whisper.cpp.git "$DEST"
fi
git -C "$DEST" checkout --detach "$COMMIT"
ACTUAL="$(git -C "$DEST" rev-parse HEAD)"
[[ "$ACTUAL" == "$COMMIT" ]] || { echo "whisper.cpp commit mismatch: $ACTUAL"; exit 2; }
echo "whisper.cpp $TAG pinned at $ACTUAL"
