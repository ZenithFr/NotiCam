#include "../jni/src/PortraitGeometry.h"
#include <cassert>
#include <limits>
#include <vector>
int main() {
    using namespace portrait;
    assert(!validDisparity(0) && !validDisparity(255));
    assert(validDisparity(1) && validDisparity(254));
    assert(depthRowForColor(0, 768, 288) == -1);
    assert(depthRowForColor(767, 768, 288) == -1);
    assert(depthRowForColor(384, 768, 288) == 144);
    assert(depthRowForColor(0, 0, 288) == -1);
    assert(depthRowForColor(0, 768, 0) == -1);
    assert(std::abs(colorVFromDepth(0.0f) - 1.0f/14.0f) < .00001f);
    assert(std::abs(colorVFromDepth(1.0f) - 13.0f/14.0f) < .00001f);
    for (uint32_t row = 0; row < 288; ++row) {
        uint32_t color = static_cast<uint32_t>(colorVFromDepth((row + .5f) / 288) * 3072);
        assert(std::abs(depthRowForColor(color, 3072, 288) - static_cast<int>(row)) <= 1);
        assert(matteRowForDepth(row, 288, 256) >= 18 && matteRowForDepth(row, 288, 256) <= 237);
    }
    assert(foregroundAtDepth(.8f, 1.0f) == 1.0f);
    assert(foregroundAtDepth(1.0f, 1.0f) == 1.0f);
    assert(foregroundAtDepth(1.08f, 1.0f) > 0 && foregroundAtDepth(1.08f, 1.0f) < 1);
    assert(foregroundAtDepth(2.0f, 1.0f) == 0);
    assert(foregroundAtDepth(10.0f, 1.0f) == 0);
    assert(foregroundAtDepth(0.0f, 1.0f) == 0);
    assert(foregroundAtDepth(std::numeric_limits<float>::quiet_NaN(), 1.0f) == 0);
    std::vector<uint8_t> alpha(65536, 0);
    assert(!hasForeground(alpha.data(), 256, 256));
    alpha[0] = 255; assert(!hasForeground(alpha.data(), 256, 256));
    std::fill(alpha.begin(), alpha.begin() + 100, 128);
    assert(hasForeground(alpha.data(), 256, 256));
    assert(!hasForeground(nullptr, 256, 256));
}
