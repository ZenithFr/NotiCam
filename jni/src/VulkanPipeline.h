#pragma once

#include <vulkan/vulkan.h>
#include <android/native_window.h>
#include <android/hardware_buffer.h>
#include <memory>
#include "YuvConverter.h"
#include "NpuWorker.h"
#include <mutex>

class VulkanPipeline {
public:
    VulkanPipeline();
    ~VulkanPipeline();

    bool init(ANativeWindow* window, uint32_t previewWidth = 1440, uint32_t previewHeight = 1080);
    void destroy();

    // Set thermal status policy: when SEVERE (true), throttle APU to 6 Hz (166ms interval)
    void setThermalThrottled(bool throttled) {
        if (mNpuWorker) mNpuWorker->setThermalThrottled(throttled);
    }

    // Renders one full frame: Camera YUV AHB + DPE Disparity Y8 + Real Alpha Matte -> Native Window
    bool renderFrame(AHardwareBuffer* colorBuffer, const uint8_t* depthY8Buffer, uint32_t depthW, uint32_t depthH, const uint8_t* alphaMatte = nullptr, uint32_t alphaW = 0, uint32_t alphaH = 0, float fStop = 1.88f, float targetFarFieldPx = 15.0f);

    struct OpticalParams {
        float focalLengthMm = 5.56f;
        float sensorWidthMm = 8.16f;
        float baseFStop = 1.88f;
        float s1FocusM = 2.0f;
    } mOpticalParams;

    void setCameraOpticalParams(float focalMm, float sensorWidthMm, float baseFStop) {
        mOpticalParams.focalLengthMm = focalMm;
        mOpticalParams.sensorWidthMm = sensorWidthMm;
        mOpticalParams.baseFStop = baseFStop;
    }

    // Process still bokeh on 4080x3072 AHardwareBuffer using 1020x768 gather, 4K guided filter & 4080x3072 composite
    bool processStillBokeh(AHardwareBuffer* colorBuffer, const uint8_t* depthY8Buffer, uint32_t depthW, uint32_t depthH, const uint8_t* alphaMatte = nullptr, uint32_t alphaW = 0, uint32_t alphaH = 0, float fStop = 1.8f, float targetFarFieldPx = 23.3f, const char* outFilePath = nullptr, int64_t* outTimestamps = nullptr);

    void updateSurface(ANativeWindow* newWindow, uint32_t width, uint32_t height);

private:
    bool createInstance();
    bool selectPhysicalDevice();
    bool createLogicalDevice();
    bool createSwapchain(ANativeWindow* window);
    bool recreateSwapchain();
    bool loadShaders();
    bool createStorageImages();
    bool initStillBokehResources(uint32_t width = 1020, uint32_t height = 768);
    uint32_t findMemoryType(uint32_t typeFilter, VkMemoryPropertyFlags properties);

    VkInstance mInstance{VK_NULL_HANDLE};
    VkPhysicalDevice mPhysicalDevice{VK_NULL_HANDLE};
    VkDevice mDevice{VK_NULL_HANDLE};
    VkQueue mComputeQueue{VK_NULL_HANDLE};
    std::mutex mQueueMutex;
    uint32_t mComputeQueueFamilyIndex{0};

    ANativeWindow* mWindow{nullptr};
    VkSurfaceKHR mSurface{VK_NULL_HANDLE};
    VkSwapchainKHR mSwapchain{VK_NULL_HANDLE};

    std::unique_ptr<YuvConverter> mYuvConverter;
    std::unique_ptr<NpuWorker> mNpuWorker;

    uint32_t mWidth{1440};
    uint32_t mHeight{1080};
    bool mInitialized{false};

    // Still Bokeh Resources (1020x768 32-tap Gather + 4080x3072 Full-Res Composite)
    bool mStillBokehInitialized{false};
    uint32_t mStillW{1020};
    uint32_t mStillH{768};
    uint32_t mFullW{4080};
    uint32_t mFullH{3072};
    VkImage mImgDepth{VK_NULL_HANDLE}, mImgCoc{VK_NULL_HANDLE}, mImgAlpha{VK_NULL_HANDLE}, mImgBlurred{VK_NULL_HANDLE}, mImgOutFull{VK_NULL_HANDLE};
    VkDeviceMemory mMemDepth{VK_NULL_HANDLE}, mMemCoc{VK_NULL_HANDLE}, mMemAlpha{VK_NULL_HANDLE}, mMemBlurred{VK_NULL_HANDLE}, mMemOutFull{VK_NULL_HANDLE};
    VkImageView mViewDepth{VK_NULL_HANDLE}, mViewCoc{VK_NULL_HANDLE}, mViewAlpha{VK_NULL_HANDLE}, mViewBlurred{VK_NULL_HANDLE}, mViewOutFull{VK_NULL_HANDLE};
    VkBuffer mUploadStagingBuffer{VK_NULL_HANDLE}, mReadbackBuffer{VK_NULL_HANDLE};
    VkDeviceMemory mUploadStagingMem{VK_NULL_HANDLE}, mReadbackMem{VK_NULL_HANDLE};
    VkQueryPool mStillQueryPool{VK_NULL_HANDLE};
    VkFence mStillFence{VK_NULL_HANDLE};
    VkFence mStillSliceFence{VK_NULL_HANDLE};
    VkSampler mStillSampler{VK_NULL_HANDLE};
    VkCommandPool mStillCmdPool{VK_NULL_HANDLE};
    VkCommandBuffer mStillCmd{VK_NULL_HANDLE};
    VkCommandBuffer mStillCmdSlices[16]{VK_NULL_HANDLE};
    VkDescriptorPool mStillDescPool{VK_NULL_HANDLE};
    VkDescriptorSetLayout mDslCoc{VK_NULL_HANDLE}, mDslGather{VK_NULL_HANDLE}, mDslComp{VK_NULL_HANDLE};
    VkPipelineLayout mPlCoc{VK_NULL_HANDLE}, mPlGather{VK_NULL_HANDLE}, mPlComp{VK_NULL_HANDLE};
    VkPipeline mPipeCoc{VK_NULL_HANDLE}, mPipeGather{VK_NULL_HANDLE}, mPipeComp{VK_NULL_HANDLE};
    VkDescriptorSet mSetCoc{VK_NULL_HANDLE}, mSetGather{VK_NULL_HANDLE}, mSetComp{VK_NULL_HANDLE};

    // Guided Filter Pipeline (alpha.comp)
    VkImage mImgAlphaLow{VK_NULL_HANDLE};
    VkDeviceMemory mMemAlphaLow{VK_NULL_HANDLE};
    VkImageView mViewAlphaLow{VK_NULL_HANDLE};
    VkDescriptorSetLayout mDslAlpha{VK_NULL_HANDLE};
    VkPipelineLayout mPlAlpha{VK_NULL_HANDLE};
    VkPipeline mPipeAlpha{VK_NULL_HANDLE};
    VkDescriptorSet mSetAlphaPreview{VK_NULL_HANDLE};

    // Still Bokeh Dedicated Resources (prevents preview contention & eliminates viewfinder freeze)
    uint32_t mStillGatherW{1020};
    uint32_t mStillGatherH{768};
    VkImage mImgDepthStill{VK_NULL_HANDLE}, mImgCocStill{VK_NULL_HANDLE}, mImgAlphaStill{VK_NULL_HANDLE}, mImgBlurredStill{VK_NULL_HANDLE};
    VkDeviceMemory mMemDepthStill{VK_NULL_HANDLE}, mMemCocStill{VK_NULL_HANDLE}, mMemAlphaStill{VK_NULL_HANDLE}, mMemBlurredStill{VK_NULL_HANDLE};
    VkImageView mViewDepthStill{VK_NULL_HANDLE}, mViewCocStill{VK_NULL_HANDLE}, mViewAlphaStill{VK_NULL_HANDLE}, mViewBlurredStill{VK_NULL_HANDLE};
    VkImage mImgAlphaGuidedStill{VK_NULL_HANDLE};
    VkDeviceMemory mMemAlphaGuidedStill{VK_NULL_HANDLE};
    VkImageView mViewAlphaGuidedStill{VK_NULL_HANDLE};
    VkBuffer mUploadStagingBufferStill{VK_NULL_HANDLE};
    VkDeviceMemory mUploadStagingMemStill{VK_NULL_HANDLE};
    VkDescriptorSet mSetCocStill{VK_NULL_HANDLE}, mSetGatherStill{VK_NULL_HANDLE}, mSetCompStill{VK_NULL_HANDLE}, mSetAlphaStill{VK_NULL_HANDLE};

    // Live Viewfinder Swapchain & Presentation Resources:
    bool mSwapchainInitialized{false};
    std::vector<VkImage> mSwapchainImages;
    VkFormat mSwapchainFormat{VK_FORMAT_UNDEFINED};
    VkExtent2D mSwapchainExtent{0, 0};
    static constexpr int MAX_FRAMES_IN_FLIGHT = 2;
    uint32_t mCurrentFrame{0};
    VkSemaphore mImageAvailableSemaphores[MAX_FRAMES_IN_FLIGHT]{VK_NULL_HANDLE, VK_NULL_HANDLE};
    VkSemaphore mRenderFinishedSemaphores[MAX_FRAMES_IN_FLIGHT]{VK_NULL_HANDLE, VK_NULL_HANDLE};
    VkFence mRenderFences[MAX_FRAMES_IN_FLIGHT]{VK_NULL_HANDLE, VK_NULL_HANDLE};
    VkImage mImgPreviewOut{VK_NULL_HANDLE};
    VkDeviceMemory mMemPreviewOut{VK_NULL_HANDLE};
    VkImageView mViewPreviewOut{VK_NULL_HANDLE};
    VkDescriptorSet mSetCompPreview{VK_NULL_HANDLE};
    VkCommandPool mPreviewCmdPool{VK_NULL_HANDLE};
    VkCommandBuffer mPreviewCmds[MAX_FRAMES_IN_FLIGHT]{VK_NULL_HANDLE, VK_NULL_HANDLE};
    VkQueryPool mPreviewQueryPool{VK_NULL_HANDLE};
    bool initPreviewResources();
};
