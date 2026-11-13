// Minimal animated GIF writer: per-frame 256-colour median-cut palette + LZW.
#pragma once
#include <cstdint>
#include <string>

// frames: n * w * h * 3 RGB bytes. Returns false if the file can't be written.
bool write_gif(const std::string& path, const uint8_t* frames, int w, int h, int n, int fps);
