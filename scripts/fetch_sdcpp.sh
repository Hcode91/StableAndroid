#!/usr/bin/env bash
# Fetches stable-diffusion.cpp at a pinned commit into third_party/.
# The upstream API changes often, so bump SDCPP_COMMIT deliberately.
set -euo pipefail
SDCPP_COMMIT="${SDCPP_COMMIT:-e1ca5e8bc7f1b5b8e2cbbbefc7fdaad1caffbd32}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$ROOT/third_party/stable-diffusion.cpp"

if [ -d "$DEST/.git" ] && [ "$(git -C "$DEST" rev-parse HEAD)" = "$SDCPP_COMMIT" ] \
   && [ -f "$DEST/ggml/CMakeLists.txt" ]; then
  echo "stable-diffusion.cpp already at $SDCPP_COMMIT"
  exit 0
fi

rm -rf "$DEST"
mkdir -p "$DEST"
git -C "$DEST" init -q
git -C "$DEST" remote add origin https://github.com/leejet/stable-diffusion.cpp
git -C "$DEST" fetch -q --depth 1 origin "$SDCPP_COMMIT"
git -C "$DEST" checkout -q FETCH_HEAD
# Only ggml is needed (skip webui, libwebp, libwebm, cutlass).
git -C "$DEST" submodule update -q --init --depth 1 ggml
echo "stable-diffusion.cpp ready at $SDCPP_COMMIT"
