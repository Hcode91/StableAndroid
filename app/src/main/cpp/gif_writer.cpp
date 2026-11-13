#include "gif_writer.h"

#include <algorithm>
#include <cstdio>
#include <cstring>
#include <vector>

namespace {

struct Box {
    int lo, hi;  // range in the sample index array
};

// Median cut over a sample of the frame's pixels. Returns up to 256 colours.
std::vector<uint8_t> build_palette(const uint8_t* rgb, int npx) {
    int stride = std::max(1, npx / 20000);
    std::vector<uint32_t> px;
    for (int i = 0; i < npx; i += stride) {
        px.push_back((uint32_t)rgb[i * 3] << 16 | (uint32_t)rgb[i * 3 + 1] << 8 | rgb[i * 3 + 2]);
    }
    std::vector<Box> boxes{{0, (int)px.size()}};
    while (boxes.size() < 256) {
        // split the box with the largest channel range
        int best = -1, best_range = 0, best_ch = 0;
        for (size_t b = 0; b < boxes.size(); b++) {
            if (boxes[b].hi - boxes[b].lo < 2) continue;
            int mn[3] = {255, 255, 255}, mx[3] = {0, 0, 0};
            for (int i = boxes[b].lo; i < boxes[b].hi; i++) {
                int c[3] = {(int)(px[i] >> 16) & 255, (int)(px[i] >> 8) & 255, (int)px[i] & 255};
                for (int k = 0; k < 3; k++) { mn[k] = std::min(mn[k], c[k]); mx[k] = std::max(mx[k], c[k]); }
            }
            for (int k = 0; k < 3; k++) {
                if (mx[k] - mn[k] > best_range) { best_range = mx[k] - mn[k]; best = (int)b; best_ch = k; }
            }
        }
        if (best < 0 || best_range == 0) break;
        int shift = 16 - 8 * best_ch;
        Box bx = boxes[best];
        int mid = (bx.lo + bx.hi) / 2;
        std::nth_element(px.begin() + bx.lo, px.begin() + mid, px.begin() + bx.hi,
                         [shift](uint32_t a, uint32_t b) { return ((a >> shift) & 255) < ((b >> shift) & 255); });
        boxes[best] = {bx.lo, mid};
        boxes.push_back({mid, bx.hi});
    }
    std::vector<uint8_t> pal;
    for (auto& b : boxes) {
        uint64_t s[3] = {0, 0, 0};
        int cnt = std::max(1, b.hi - b.lo);
        for (int i = b.lo; i < b.hi; i++) { s[0] += (px[i] >> 16) & 255; s[1] += (px[i] >> 8) & 255; s[2] += px[i] & 255; }
        for (int k = 0; k < 3; k++) pal.push_back((uint8_t)(s[k] / cnt));
    }
    return pal;
}

struct BitWriter {
    std::vector<uint8_t>& out;
    uint32_t acc = 0;
    int bits = 0;
    void put(uint32_t code, int size) {
        acc |= code << bits;
        bits += size;
        while (bits >= 8) { out.push_back(acc & 255); acc >>= 8; bits -= 8; }
    }
    void flush() { if (bits > 0) { out.push_back(acc & 255); acc = 0; bits = 0; } }
};

// Standard GIF LZW with 8-bit minimum code size.
std::vector<uint8_t> lzw(const std::vector<uint8_t>& idx) {
    const int min_size = 8, clear = 256, eoi = 257;
    std::vector<uint8_t> out;
    BitWriter bw{out};
    std::vector<int32_t> table(4096 * 256, -1);  // (prefix code, byte) -> code
    int next = eoi + 1, size = min_size + 1;
    bw.put(clear, size);
    int prefix = idx.empty() ? -1 : idx[0];
    for (size_t i = 1; i < idx.size(); i++) {
        uint8_t c = idx[i];
        int32_t& slot = table[(size_t)prefix * 256 + c];
        if (slot >= 0) { prefix = slot; continue; }
        bw.put(prefix, size);
        if (next < 4096) {
            slot = next++;
            if (next - 1 == (1 << size) && size < 12) size++;
        } else {
            bw.put(clear, size);
            std::fill(table.begin(), table.end(), -1);
            next = eoi + 1;
            size = min_size + 1;
        }
        prefix = c;
    }
    if (prefix >= 0) bw.put(prefix, size);
    bw.put(eoi, size);
    bw.flush();
    return out;
}

void put16(FILE* f, int v) { fputc(v & 255, f); fputc((v >> 8) & 255, f); }

}  // namespace

bool write_gif(const std::string& path, const uint8_t* frames, int w, int h, int n, int fps) {
    FILE* f = fopen(path.c_str(), "wb");
    if (!f) return false;
    const int npx = w * h;
    const int delay = std::max(2, (int)(100.0 / std::max(1, fps) + 0.5));  // 1/100 s
    fwrite("GIF89a", 1, 6, f);
    put16(f, w); put16(f, h);
    fputc(0x70, f); fputc(0, f); fputc(0, f);                       // no global colour table
    fwrite("\x21\xFF\x0BNETSCAPE2.0\x03\x01\x00\x00\x00", 1, 19, f);  // loop forever
    std::vector<uint8_t> idx(npx);
    std::vector<int16_t> cache(32768);
    for (int k = 0; k < n; k++) {
        const uint8_t* rgb = frames + (size_t)k * npx * 3;
        std::vector<uint8_t> pal = build_palette(rgb, npx);
        int ncol = (int)pal.size() / 3;
        std::fill(cache.begin(), cache.end(), (int16_t)-1);
        for (int i = 0; i < npx; i++) {
            int r = rgb[i * 3], g = rgb[i * 3 + 1], b = rgb[i * 3 + 2];
            int key = (r >> 3) << 10 | (g >> 3) << 5 | (b >> 3);
            if (cache[key] < 0) {
                int best = 0, bd = 1 << 30;
                for (int c = 0; c < ncol; c++) {
                    int dr = r - pal[c * 3], dg = g - pal[c * 3 + 1], db = b - pal[c * 3 + 2];
                    int d = dr * dr * 3 + dg * dg * 4 + db * db * 2;
                    if (d < bd) { bd = d; best = c; }
                }
                cache[key] = (int16_t)best;
            }
            idx[i] = (uint8_t)cache[key];
        }
        fwrite("\x21\xF9\x04\x04", 1, 4, f); put16(f, delay); fputc(0, f); fputc(0, f);  // graphic control
        fputc(0x2C, f); put16(f, 0); put16(f, 0); put16(f, w); put16(f, h);
        fputc(0x87, f);                                             // local colour table, 256 entries
        pal.resize(768, 0);
        fwrite(pal.data(), 1, 768, f);
        fputc(8, f);
        std::vector<uint8_t> data = lzw(idx);
        for (size_t p = 0; p < data.size(); p += 255) {
            size_t len = std::min<size_t>(255, data.size() - p);
            fputc((int)len, f);
            fwrite(data.data() + p, 1, len, f);
        }
        fputc(0, f);
    }
    fputc(0x3B, f);
    bool ok = !ferror(f);
    fclose(f);
    return ok;
}
