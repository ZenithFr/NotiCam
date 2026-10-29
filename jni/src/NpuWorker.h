#pragma once

#include <cstdint>
#include <vector>
#include <thread>
#include <mutex>
#include <condition_variable>
#include <atomic>

class NpuWorker {
public:
    NpuWorker();
    ~NpuWorker();

    void start(const char* modelPath = nullptr);
    void stop();

    // Set device idle (Doze) state to pause APU execution
    void setDeviceIdleMode(bool idle);

    // Submits new 384x512 RGB frame for 12 Hz async inference
    // Drops frame if worker is currently busy (1-deep queue)
    // Fast-path: immediate dispatch if scene luma histogram shifts > 20%
    bool submitFrame(const uint8_t* rgb384x512);

    // Retrieves latest EMA-smoothed bilateral coefficients [96x128x2] in FP16 / FP32
    bool getLatestCoefficients(std::vector<float>& outCoeffs);

    // Set thermal status policy: when SEVERE (true), throttle APU to 6 Hz (166ms interval)
    void setThermalThrottled(bool throttled);

    // S1 bootstrap focus distance in meters (default 2.0m, alpha downsampled before median)
    float getFocusDistanceM() const { return mS1FocusDistanceM.load(); }
    void updateFocusDistance(const uint8_t* depthY8, uint32_t depthW, uint32_t depthH, const uint8_t* alphaDownsampled, uint32_t alphaW, uint32_t alphaH);

private:
    void workerLoop();
    void updateEma(const uint8_t* rawOut);
    float computeHistogramDiff(const uint8_t* rgb384x512);
    void updateMotionDetection(const uint8_t* rgb384x512);

    std::thread mThread;
    std::mutex mMutex;
    std::condition_variable mCv;
    std::atomic<bool> mRunning{false};
    std::atomic<bool> mDeviceIdleMode{false};
    std::atomic<bool> mThermalThrottled{false};
    std::atomic<float> mS1FocusDistanceM{2.0f};

    // 1-deep input buffer
    std::vector<uint8_t> mInputBuffer;
    bool mHasNewInput{false};

    // Histogram fast-path state (16 bins)
    std::vector<float> mPrevHistogram;

    // 48x64 downsampled luma grid for 8x noise-averaged motion detection
    static constexpr size_t MOTION_W = 64;
    static constexpr size_t MOTION_H = 48;
    std::vector<float> mPrevLumaGrid;
    std::vector<uint8_t> mMotionMask; // 1 = motion, 0 = static

    // Coefficient buffers [96 * 128 * 2]
    std::vector<float> mSmoothedCoeffs;
    std::mutex mCoeffMutex;
    bool mHasCoeffs{false};

    // 12 Hz rate limit clock
    int64_t mLastDispatchTimeMs{0};
};
