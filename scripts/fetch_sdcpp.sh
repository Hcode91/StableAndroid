#!/usr/bin/env bash
# Fetches native dependencies into third_party/:
#  - stable-diffusion.cpp at a pinned commit (its API changes often; bump deliberately)
#  - Vulkan-Headers (vulkan.hpp, not shipped in the NDK) and SPIRV-Headers for the GPU backend
set -euo pipefail
SDCPP_COMMIT="${SDCPP_COMMIT:-e1ca5e8bc7f1b5b8e2cbbbefc7fdaad1caffbd32}"
VULKAN_SDK_TAG="${VULKAN_SDK_TAG:-vulkan-sdk-1.4.363.0}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TP="$ROOT/third_party"
mkdir -p "$TP"

# --- stable-diffusion.cpp -----------------------------------------------------
DEST="$TP/stable-diffusion.cpp"
if [ -d "$DEST/.git" ] && [ "$(git -C "$DEST" rev-parse HEAD)" = "$SDCPP_COMMIT" ] \
   && [ -f "$DEST/ggml/CMakeLists.txt" ]; then
  echo "stable-diffusion.cpp already at $SDCPP_COMMIT"
else
  rm -rf "$DEST"
  mkdir -p "$DEST"
  git -C "$DEST" init -q
  git -C "$DEST" remote add origin https://github.com/leejet/stable-diffusion.cpp
  git -C "$DEST" fetch -q --depth 1 origin "$SDCPP_COMMIT"
  git -C "$DEST" checkout -q FETCH_HEAD
  # Only ggml is needed (skip webui, libwebp, libwebm, cutlass).
  git -C "$DEST" submodule update -q --init --depth 1 ggml
  echo "stable-diffusion.cpp ready at $SDCPP_COMMIT"
fi

# --- Vulkan-Headers -----------------------------------------------------------
VH="$TP/Vulkan-Headers"
if [ -f "$VH/.tag" ] && [ "$(cat "$VH/.tag")" = "$VULKAN_SDK_TAG" ]; then
  echo "Vulkan-Headers already at $VULKAN_SDK_TAG"
else
  rm -rf "$VH"
  git -c advice.detachedHead=false clone -q --depth 1 --branch "$VULKAN_SDK_TAG" https://github.com/KhronosGroup/Vulkan-Headers "$VH"
  echo "$VULKAN_SDK_TAG" > "$VH/.tag"
  echo "Vulkan-Headers ready at $VULKAN_SDK_TAG"
fi

# --- SPIRV-Headers (installed so CMake's find_package(SPIRV-Headers CONFIG) works)
SH_SRC="$TP/SPIRV-Headers-src"
SH="$TP/spirv-headers"
if [ -f "$SH/.tag" ] && [ "$(cat "$SH/.tag")" = "$VULKAN_SDK_TAG" ]; then
  echo "SPIRV-Headers already at $VULKAN_SDK_TAG"
else
  rm -rf "$SH_SRC" "$SH"
  git -c advice.detachedHead=false clone -q --depth 1 --branch "$VULKAN_SDK_TAG" https://github.com/KhronosGroup/SPIRV-Headers "$SH_SRC"
  cmake -S "$SH_SRC" -B "$SH_SRC/build" -DCMAKE_INSTALL_PREFIX="$SH" -DSPIRV_HEADERS_ENABLE_TESTS=OFF > /dev/null
  cmake --install "$SH_SRC/build" > /dev/null
  rm -rf "$SH_SRC"
  echo "$VULKAN_SDK_TAG" > "$SH/.tag"
  echo "SPIRV-Headers ready at $VULKAN_SDK_TAG"
fi

# --- TAESD tiny VAE (MIT, ~10 MB) bundled into the APK as the fast VAE ---------
TAESD="$ROOT/app/src/main/assets/taesd.safetensors"
TAESD_SHA="db169d69145ec4ff064e49d99c95fa05d3eb04ee453de35824a6d0f325513549"
if [ -f "$TAESD" ] && echo "$TAESD_SHA  $TAESD" | sha256sum -c --status; then
  echo "TAESD already present"
else
  mkdir -p "$(dirname "$TAESD")"
  curl -fsSL -o "$TAESD" https://huggingface.co/madebyollin/taesd/resolve/main/diffusion_pytorch_model.safetensors
  echo "$TAESD_SHA  $TAESD" | sha256sum -c --status || { echo "TAESD checksum mismatch"; rm -f "$TAESD"; exit 1; }
  echo "TAESD ready"
fi

# --- libadrenotools (BSD-2): lets the app load a custom Adreno driver such as Turnip
ADRENO_COMMIT="8fae8ce254dfc1344527e05301e43f37dea2df80"
AT="$TP/libadrenotools"
if [ -d "$AT/.git" ] && [ "$(git -C "$AT" rev-parse HEAD)" = "$ADRENO_COMMIT" ] \
   && [ -f "$AT/lib/linkernsbypass/CMakeLists.txt" ]; then
  echo "libadrenotools already at $ADRENO_COMMIT"
else
  rm -rf "$AT"
  mkdir -p "$AT"
  git -C "$AT" init -q
  git -C "$AT" remote add origin https://github.com/bylaws/libadrenotools
  git -C "$AT" fetch -q --depth 1 origin "$ADRENO_COMMIT"
  git -C "$AT" checkout -q FETCH_HEAD
  git -C "$AT" submodule update -q --init --depth 1
  echo "libadrenotools ready at $ADRENO_COMMIT"
fi
