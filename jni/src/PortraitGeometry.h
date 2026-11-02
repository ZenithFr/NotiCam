#pragma once
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstddef>

// Geometry shared by preview/still staging. The 14:9 disparity field occupies
// the central 6/7 of the 4:3 color field. Apply this transform exactly once.
namespace portrait {
constexpr float kDepthCropScale = 7.0f / 6.0f;
inline bool hasForeground(const uint8_t* alpha, uint32_t width, uint32_t height) {
    if (!alpha || !width || !height) return false;
    const size_t count = static_cast<size_t>(width) * height;
    const size_t required = std::max<size_t>(1, count / 1000);
    size_t found = 0;
    for (size_t i = 0; i < count; ++i) if (alpha[i] >= 128 && ++found >= required) return true;
    return false;
}
inline bool validDisparity(uint8_t value) { return value > 0 && value < 255; }
inline float colorVFromDepth(float depthV) {
    return (depthV - 0.5f) / kDepthCropScale + 0.5f;
}
inline int depthRowForColor(uint32_t row, uint32_t colorHeight, uint32_t depthHeight) {
    if (!colorHeight || !depthHeight) return -1;
    float v = ((row + 0.5f) / colorHeight - 0.5f) * kDepthCropScale + 0.5f;
    if (v < 0.0f || v >= 1.0f) return -1; // Unmeasured top/bottom, never clamp-copy an edge.
    return std::min(static_cast<int>(v * depthHeight), static_cast<int>(depthHeight) - 1);
}
inline uint32_t matteRowForDepth(uint32_t row, uint32_t depthHeight, uint32_t matteHeight) {
    if (!depthHeight || !matteHeight) return 0;
    return std::min(static_cast<uint32_t>(colorVFromDepth((row + 0.5f) / depthHeight) * matteHeight), matteHeight - 1);
}
inline float foregroundAtDepth(float depth, float focus) {
    if (!std::isfinite(depth) || depth <= 0.05f || depth >= 9.95f || !std::isfinite(focus) || focus <= 0.05f) return 0.0f;
    const float start = std::max(focus * 1.02f, 0.1f);
    const float end = std::max(focus * 1.15f, start + 0.05f);
    const float t = std::clamp((depth - start) / (end - start), 0.0f, 1.0f);
    return 1.0f - t * t * (3.0f - 2.0f * t);
}
}
