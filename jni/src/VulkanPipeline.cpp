#include "VulkanPipeline.h"
#include <android/log.h>
#include <vector>
#include <cstring>
#include <fstream>
#include <chrono>
#include <cmath>
#include <fcntl.h>
#include <unistd.h>
#include <cerrno>

#define LOG_TAG "VulkanPipeline"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

struct CocPushConstants {
    uint32_t width;
    uint32_t height;
    float focalMm;
    float fStop;
    float sensorWidthMm;
    float s1_focus_m;
    float targetFarFieldPx;
    float previewWidth;
};

struct GatherPushConstants {
    uint32_t width;
    uint32_t height;
    uint32_t numTaps;
    float specularBoost;
    float maxRadiusPx;
};

// Presentation orientation of the *preview*.
// The sensor streams landscape (1440x1080) and the presenter copies it 1:1 into
// the swapchain, so when the surface is a portrait box the frame must be rotated.
// Keep this in sync with SensorProfile.PREVIEW_ROTATION_90 on the Kotlin side.
static constexpr int kPreviewRotate90 = 0;

struct CompositePushConstants {
    uint32_t outWidth;
    uint32_t outHeight;
    int32_t rotate90;
};

static std::vector<char> loadSpvFile(const char* path) {
    std::ifstream file(path, std::ios::binary | std::ios::ate);
    if (!file.is_open()) return {};
    size_t size = (size_t)file.tellg();
    std::vector<char> buffer(size);
    file.seekg(0);
    file.read(buffer.data(), size);
    return buffer;
}

VulkanPipeline::VulkanPipeline() = default;

VulkanPipeline::~VulkanPipeline() {
    destroy();
}

bool VulkanPipeline::init(ANativeWindow* window, uint32_t previewWidth, uint32_t previewHeight) {
    if (mInitialized) return true;

    mWindow = window;
    mWidth = previewWidth;
    mHeight = previewHeight;

    if (!createInstance()) return false;
    if (!selectPhysicalDevice()) return false;
    if (!createLogicalDevice()) return false;

    mYuvConverter = std::make_unique<YuvConverter>(mDevice, mPhysicalDevice);
    if (!mYuvConverter->initConversionSampler()) {
        LOGE("Failed to initialize YuvConverter sampler");
        return false;
    }

    mNpuWorker = std::make_unique<NpuWorker>();
    mNpuWorker->start();

    mInitialized = true;
    LOGI("VulkanPipeline initialized successfully (%ux%u)", mWidth, mHeight);

    if (mWindow != nullptr) {
        if (!initStillBokehResources(720, 540)) {
            LOGE("Failed to init bokeh resources during init");
        }
        createSwapchain(mWindow);
        initPreviewResources();
    }
    return true;
}

bool VulkanPipeline::createInstance() {
    VkApplicationInfo appInfo{};
    appInfo.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    appInfo.pApplicationName = "AuxCam Portrait Engine";
    appInfo.applicationVersion = VK_MAKE_VERSION(1, 0, 0);
    appInfo.pEngineName = "AuxCamVulkan";
    appInfo.engineVersion = VK_MAKE_VERSION(1, 0, 0);
    appInfo.apiVersion = VK_API_VERSION_1_3;

    std::vector<const char*> extensions = {
        "VK_KHR_surface",
        "VK_KHR_android_surface",
        "VK_KHR_get_physical_device_properties2"
    };

    VkInstanceCreateInfo createInfo{};
    createInfo.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    createInfo.pApplicationInfo = &appInfo;
    createInfo.enabledExtensionCount = static_cast<uint32_t>(extensions.size());
    createInfo.ppEnabledExtensionNames = extensions.data();

    if (vkCreateInstance(&createInfo, nullptr, &mInstance) != VK_SUCCESS) {
        LOGE("Failed to create Vulkan 1.3 instance");
        return false;
    }
    return true;
}

bool VulkanPipeline::selectPhysicalDevice() {
    uint32_t deviceCount = 0;
    vkEnumeratePhysicalDevices(mInstance, &deviceCount, nullptr);
    if (deviceCount == 0) {
        LOGE("No Vulkan physical devices found");
        return false;
    }

    std::vector<VkPhysicalDevice> devices(deviceCount);
    vkEnumeratePhysicalDevices(mInstance, &deviceCount, devices.data());
    mPhysicalDevice = devices[0];

    VkPhysicalDeviceProperties props;
    vkGetPhysicalDeviceProperties(mPhysicalDevice, &props);
    LOGI("Selected Vulkan GPU: %s (Driver: %u.%u.%u)", props.deviceName,
         VK_VERSION_MAJOR(props.driverVersion),
         VK_VERSION_MINOR(props.driverVersion),
         VK_VERSION_PATCH(props.driverVersion));

    // Find compute queue family
    uint32_t queueFamilyCount = 0;
    vkGetPhysicalDeviceQueueFamilyProperties(mPhysicalDevice, &queueFamilyCount, nullptr);
    std::vector<VkQueueFamilyProperties> queueFamilies(queueFamilyCount);
    vkGetPhysicalDeviceQueueFamilyProperties(mPhysicalDevice, &queueFamilyCount, queueFamilies.data());

    for (uint32_t i = 0; i < queueFamilyCount; ++i) {
        if (queueFamilies[i].queueFlags & VK_QUEUE_COMPUTE_BIT) {
            mComputeQueueFamilyIndex = i;
            break;
        }
    }

    return true;
}

bool VulkanPipeline::createLogicalDevice() {
    float queuePriority = 1.0f;
    VkDeviceQueueCreateInfo queueCreateInfo{};
    queueCreateInfo.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
    queueCreateInfo.queueFamilyIndex = mComputeQueueFamilyIndex;
    queueCreateInfo.queueCount = 1;
    queueCreateInfo.pQueuePriorities = &queuePriority;

    std::vector<const char*> deviceExtensions = {
        "VK_KHR_swapchain",
        "VK_ANDROID_external_memory_android_hardware_buffer",
        "VK_KHR_sampler_ycbcr_conversion",
        "VK_KHR_external_memory"
    };

    VkPhysicalDeviceSamplerYcbcrConversionFeatures ycbcrFeatures{};
    ycbcrFeatures.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SAMPLER_YCBCR_CONVERSION_FEATURES;
    ycbcrFeatures.samplerYcbcrConversion = VK_TRUE;

    VkDeviceCreateInfo deviceCreateInfo{};
    deviceCreateInfo.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;
    deviceCreateInfo.pNext = &ycbcrFeatures;
    deviceCreateInfo.queueCreateInfoCount = 1;
    deviceCreateInfo.pQueueCreateInfos = &queueCreateInfo;
    deviceCreateInfo.enabledExtensionCount = static_cast<uint32_t>(deviceExtensions.size());
    deviceCreateInfo.ppEnabledExtensionNames = deviceExtensions.data();

    if (vkCreateDevice(mPhysicalDevice, &deviceCreateInfo, nullptr, &mDevice) != VK_SUCCESS) {
        LOGE("Failed to create Vulkan logical device with YCbCr & AHB extensions");
        return false;
    }

    vkGetDeviceQueue(mDevice, mComputeQueueFamilyIndex, 0, &mComputeQueue);
    return true;
}

bool VulkanPipeline::createSwapchain(ANativeWindow* window) {
    if (!window || !mInstance || !mDevice) return false;
    mWindow = window;

    if (mSurface == VK_NULL_HANDLE) {
        VkAndroidSurfaceCreateInfoKHR sci{};
        sci.sType = VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR;
        sci.window = window;

        if (vkCreateAndroidSurfaceKHR(mInstance, &sci, nullptr, &mSurface) != VK_SUCCESS) {
            LOGE("Failed to create Android Vulkan surface");
            return false;
        }
    }

    VkSurfaceCapabilitiesKHR caps{};
    vkGetPhysicalDeviceSurfaceCapabilitiesKHR(mPhysicalDevice, mSurface, &caps);

    uint32_t formatCount = 0;
    vkGetPhysicalDeviceSurfaceFormatsKHR(mPhysicalDevice, mSurface, &formatCount, nullptr);
    if (formatCount == 0) {
        LOGE("No surface formats supported");
        return false;
    }
    std::vector<VkSurfaceFormatKHR> formats(formatCount);
    vkGetPhysicalDeviceSurfaceFormatsKHR(mPhysicalDevice, mSurface, &formatCount, formats.data());

    VkSurfaceFormatKHR chosenFormat = formats[0];
    for (const auto& f : formats) {
        if (f.format == VK_FORMAT_R8G8B8A8_UNORM || f.format == VK_FORMAT_B8G8R8A8_UNORM) {
            chosenFormat = f;
            break;
        }
    }
    mSwapchainFormat = chosenFormat.format;

    uint32_t imageCount = caps.minImageCount + 1;
    if (caps.maxImageCount > 0 && imageCount > caps.maxImageCount) {
        imageCount = caps.maxImageCount;
    }

    mSwapchainExtent = caps.currentExtent;
    if (mSwapchainExtent.width == 0xFFFFFFFF || mSwapchainExtent.width == 0) {
        mSwapchainExtent = { mWidth, mHeight };
    }

    VkSwapchainCreateInfoKHR swInfo{};
    swInfo.sType = VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR;
    swInfo.surface = mSurface;
    swInfo.minImageCount = imageCount;
    swInfo.imageFormat = chosenFormat.format;
    swInfo.imageColorSpace = chosenFormat.colorSpace;
    swInfo.imageExtent = mSwapchainExtent;
    swInfo.imageArrayLayers = 1;
    swInfo.imageUsage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
    swInfo.imageSharingMode = VK_SHARING_MODE_EXCLUSIVE;
    swInfo.preTransform = caps.currentTransform;
    swInfo.compositeAlpha = VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR;
    swInfo.presentMode = VK_PRESENT_MODE_FIFO_KHR;
    swInfo.clipped = VK_TRUE;

    if (vkCreateSwapchainKHR(mDevice, &swInfo, nullptr, &mSwapchain) != VK_SUCCESS) {
        LOGE("Failed to create VkSwapchainKHR");
        return false;
    }

    vkGetSwapchainImagesKHR(mDevice, mSwapchain, &imageCount, nullptr);
    mSwapchainImages.resize(imageCount);
    vkGetSwapchainImagesKHR(mDevice, mSwapchain, &imageCount, mSwapchainImages.data());

    VkSemaphoreCreateInfo semInfo{ VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO };
    VkFenceCreateInfo fInfo{ VK_STRUCTURE_TYPE_FENCE_CREATE_INFO, nullptr, VK_FENCE_CREATE_SIGNALED_BIT };
    for (int i = 0; i < MAX_FRAMES_IN_FLIGHT; ++i) {
        if (mImageAvailableSemaphores[i] != VK_NULL_HANDLE) vkDestroySemaphore(mDevice, mImageAvailableSemaphores[i], nullptr);
        if (mRenderFinishedSemaphores[i] != VK_NULL_HANDLE) vkDestroySemaphore(mDevice, mRenderFinishedSemaphores[i], nullptr);
        if (mRenderFences[i] != VK_NULL_HANDLE) vkDestroyFence(mDevice, mRenderFences[i], nullptr);
        vkCreateSemaphore(mDevice, &semInfo, nullptr, &mImageAvailableSemaphores[i]);
        vkCreateSemaphore(mDevice, &semInfo, nullptr, &mRenderFinishedSemaphores[i]);
        vkCreateFence(mDevice, &fInfo, nullptr, &mRenderFences[i]);
    }
    mCurrentFrame = 0;

    mSwapchainInitialized = true;
    LOGI("Swapchain created successfully: %ux%u format=%d images=%zu",
         mSwapchainExtent.width, mSwapchainExtent.height, mSwapchainFormat, mSwapchainImages.size());
    return true;
}

// Tears down every resource that is sized to the swapchain extent and rebuilds
// them for the surface's current size. Called from renderFrame() when the
// window changes; safe to call at any time because it waits for the device.
bool VulkanPipeline::recreateSwapchain() {
    if (mDevice == VK_NULL_HANDLE || mSurface == VK_NULL_HANDLE) return false;

    vkDeviceWaitIdle(mDevice);

    if (mSetCompPreview != VK_NULL_HANDLE && mStillDescPool != VK_NULL_HANDLE) {
        vkFreeDescriptorSets(mDevice, mStillDescPool, 1, &mSetCompPreview);
        mSetCompPreview = VK_NULL_HANDLE;
    }
    if (mViewPreviewOut != VK_NULL_HANDLE) {
        vkDestroyImageView(mDevice, mViewPreviewOut, nullptr);
        mViewPreviewOut = VK_NULL_HANDLE;
    }
    if (mImgPreviewOut != VK_NULL_HANDLE) {
        vkDestroyImage(mDevice, mImgPreviewOut, nullptr);
        mImgPreviewOut = VK_NULL_HANDLE;
    }
    if (mMemPreviewOut != VK_NULL_HANDLE) {
        vkFreeMemory(mDevice, mMemPreviewOut, nullptr);
        mMemPreviewOut = VK_NULL_HANDLE;
    }
    if (mPreviewCmdPool != VK_NULL_HANDLE) {
        vkDestroyCommandPool(mDevice, mPreviewCmdPool, nullptr);
        mPreviewCmdPool = VK_NULL_HANDLE;
    }
    for (int i = 0; i < MAX_FRAMES_IN_FLIGHT; ++i) {
        if (mImageAvailableSemaphores[i] != VK_NULL_HANDLE) {
            vkDestroySemaphore(mDevice, mImageAvailableSemaphores[i], nullptr);
            mImageAvailableSemaphores[i] = VK_NULL_HANDLE;
        }
        if (mRenderFinishedSemaphores[i] != VK_NULL_HANDLE) {
            vkDestroySemaphore(mDevice, mRenderFinishedSemaphores[i], nullptr);
            mRenderFinishedSemaphores[i] = VK_NULL_HANDLE;
        }
        if (mRenderFences[i] != VK_NULL_HANDLE) {
            vkDestroyFence(mDevice, mRenderFences[i], nullptr);
            mRenderFences[i] = VK_NULL_HANDLE;
        }
        mPreviewCmds[i] = VK_NULL_HANDLE;
    }
    if (mSwapchain != VK_NULL_HANDLE) {
        vkDestroySwapchainKHR(mDevice, mSwapchain, nullptr);
        mSwapchain = VK_NULL_HANDLE;
    }
    mSwapchainInitialized = false;
    mCurrentFrame = 0;

    if (!createSwapchain(mWindow)) {
        LOGE("recreateSwapchain: createSwapchain failed");
        return false;
    }
    if (!initPreviewResources()) {
        LOGE("recreateSwapchain: initPreviewResources failed");
        return false;
    }

    LOGI("Swapchain rebuilt for new surface size %ux%u", mSwapchainExtent.width, mSwapchainExtent.height);
    return true;
}

bool VulkanPipeline::initPreviewResources() {
    if (!mSwapchainInitialized || !mStillBokehInitialized) return false;

    // Create preview output storage image (matching swapchain extent)
    VkImageCreateInfo imgInfo{};
    imgInfo.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
    imgInfo.imageType = VK_IMAGE_TYPE_2D;
    imgInfo.extent = { mSwapchainExtent.width, mSwapchainExtent.height, 1 };
    imgInfo.mipLevels = 1;
    imgInfo.arrayLayers = 1;
    imgInfo.format = VK_FORMAT_R8G8B8A8_UNORM;
    imgInfo.tiling = VK_IMAGE_TILING_OPTIMAL;
    imgInfo.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    imgInfo.usage = VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
    imgInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    imgInfo.samples = VK_SAMPLE_COUNT_1_BIT;

    if (vkCreateImage(mDevice, &imgInfo, nullptr, &mImgPreviewOut) != VK_SUCCESS) {
        LOGE("Failed to create preview output image");
        return false;
    }

    VkMemoryRequirements memReq;
    vkGetImageMemoryRequirements(mDevice, mImgPreviewOut, &memReq);
    VkMemoryAllocateInfo allocInfo{};
    allocInfo.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    allocInfo.allocationSize = memReq.size;
    allocInfo.memoryTypeIndex = findMemoryType(memReq.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    vkAllocateMemory(mDevice, &allocInfo, nullptr, &mMemPreviewOut);
    vkBindImageMemory(mDevice, mImgPreviewOut, mMemPreviewOut, 0);

    VkImageViewCreateInfo viewInfo{};
    viewInfo.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
    viewInfo.image = mImgPreviewOut;
    viewInfo.viewType = VK_IMAGE_VIEW_TYPE_2D;
    viewInfo.format = VK_FORMAT_R8G8B8A8_UNORM;
    viewInfo.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    viewInfo.subresourceRange.levelCount = 1;
    viewInfo.subresourceRange.layerCount = 1;
    vkCreateImageView(mDevice, &viewInfo, nullptr, &mViewPreviewOut);

    // Create command pool and buffers for preview
    if (mPreviewCmdPool == VK_NULL_HANDLE) {
        VkCommandPoolCreateInfo cpInfo{ VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO };
        cpInfo.queueFamilyIndex = mComputeQueueFamilyIndex;
        cpInfo.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
        vkCreateCommandPool(mDevice, &cpInfo, nullptr, &mPreviewCmdPool);

        VkCommandBufferAllocateInfo cbInfo{ VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO };
        cbInfo.commandPool = mPreviewCmdPool;
        cbInfo.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
        cbInfo.commandBufferCount = MAX_FRAMES_IN_FLIGHT;
        vkAllocateCommandBuffers(mDevice, &cbInfo, mPreviewCmds);
    }

    if (mPreviewQueryPool == VK_NULL_HANDLE) {
        VkQueryPoolCreateInfo qpInfo{ VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO };
        qpInfo.queryType = VK_QUERY_TYPE_TIMESTAMP;
        qpInfo.queryCount = 4 * MAX_FRAMES_IN_FLIGHT;
        vkCreateQueryPool(mDevice, &qpInfo, nullptr, &mPreviewQueryPool);
        LOGI("Created mPreviewQueryPool with %d query slots", 4 * MAX_FRAMES_IN_FLIGHT);
    }

    // Allocate preview composite descriptor set
    VkDescriptorSetAllocateInfo dsAllocInfo{ VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO };
    dsAllocInfo.descriptorPool = mStillDescPool;
    dsAllocInfo.descriptorSetCount = 1;
    dsAllocInfo.pSetLayouts = &mDslComp;
    vkAllocateDescriptorSets(mDevice, &dsAllocInfo, &mSetCompPreview);

    LOGI("Preview Vulkan resources initialized successfully (%ux%u)", mSwapchainExtent.width, mSwapchainExtent.height);
    return true;
}

bool VulkanPipeline::renderFrame(AHardwareBuffer* colorBuffer, const uint8_t* depthY8Buffer, uint32_t depthW, uint32_t depthH, float fStop) {
    if (!mInitialized || !colorBuffer) return false;

    // The SurfaceView can be re-measured at any time (Compose layout pass, inset
    // change, display mode switch). The swapchain extent captured at creation
    // time is what the composite shader squashes the sensor frame into, so a
    // stale extent is exactly what makes a 4:3 preview look stretched.
    if (mSwapchainInitialized && mSurface != VK_NULL_HANDLE) {
        VkSurfaceCapabilitiesKHR liveCaps{};
        if (vkGetPhysicalDeviceSurfaceCapabilitiesKHR(mPhysicalDevice, mSurface, &liveCaps) == VK_SUCCESS) {
            uint32_t liveW = liveCaps.currentExtent.width;
            uint32_t liveH = liveCaps.currentExtent.height;
            bool sizeKnown = liveW != 0xFFFFFFFFu && liveH != 0xFFFFFFFFu && liveW > 0 && liveH > 0;
            if (sizeKnown && (liveW != mSwapchainExtent.width || liveH != mSwapchainExtent.height)) {
                LOGI("Surface resized %ux%u -> %ux%u, rebuilding swapchain",
                     mSwapchainExtent.width, mSwapchainExtent.height, liveW, liveH);
                if (!recreateSwapchain()) {
                    mSwapchainInitialized = false;
                    return false;
                }
            }
        }
    }

    if (!mSwapchainInitialized) {
        if (mWindow != nullptr) {
            if (!mStillBokehInitialized) initStillBokehResources(720, 540);
            createSwapchain(mWindow);
            initPreviewResources();
        }
        if (!mSwapchainInitialized) return false;
    }

    // Wait on fence for current frame in flight
    VkResult fRes = vkWaitForFences(mDevice, 1, &mRenderFences[mCurrentFrame], VK_TRUE, 1000000000ULL);
    if (fRes == VK_SUCCESS) {
        vkResetFences(mDevice, 1, &mRenderFences[mCurrentFrame]);
    } else {
        LOGE("vkWaitForFences returned %d on frame %u", fRes, mCurrentFrame);
        return false;
    }

    // 1. Acquire next swapchain image
    uint32_t imageIndex = 0;
    VkResult res = vkAcquireNextImageKHR(mDevice, mSwapchain, 50000000ULL, mImageAvailableSemaphores[mCurrentFrame], VK_NULL_HANDLE, &imageIndex);
    if (res != VK_SUCCESS) {
        LOGE("vkAcquireNextImageKHR returned %d", res);
        if (res == VK_ERROR_OUT_OF_DATE_KHR || res == VK_SUBOPTIMAL_KHR || res == VK_ERROR_SURFACE_LOST_KHR) {
            mSwapchainInitialized = false;
        }
        return false;
    }

    // 2. Zero-copy import of camera AHardwareBuffer
    VkImage importedImg = VK_NULL_HANDLE;
    VkDeviceMemory importedMem = VK_NULL_HANDLE;
    VkImageView importedView = VK_NULL_HANDLE;

    if (!mYuvConverter->importHardwareBuffer(colorBuffer, &importedImg, &importedMem, &importedView)) {
        LOGE("Failed to import AHardwareBuffer into Vulkan in renderFrame");
        return false;
    }

    // 3. Populate staging buffer with depth and alpha
    void* mappedStaging = nullptr;
    vkMapMemory(mDevice, mUploadStagingMem, 0, mStillW * mStillH * sizeof(float) + mStillW * mStillH, 0, &mappedStaging);
    float* depthDst = reinterpret_cast<float*>(mappedStaging);
    uint8_t* alphaDst = reinterpret_cast<uint8_t*>(depthDst + (mStillW * mStillH));

    bool hasDepthSignal = false;
    if (depthY8Buffer != nullptr && depthW > 0 && depthH > 0) {
        for (uint32_t i = 0; i < depthW * depthH; i += 16) {
            if (depthY8Buffer[i] > 5) {
                hasDepthSignal = true;
                break;
            }
        }
    }

    const float cx = mStillW * 0.5f;
    const float cy = mStillH * 0.5f;
    const float radiusFg = mStillW * 0.24f;

    if (hasDepthSignal) {
        for (uint32_t y = 0; y < mStillH; ++y) {
            uint32_t srcY = (y * depthH) / mStillH;
            for (uint32_t x = 0; x < mStillW; ++x) {
                uint32_t srcX = (x * depthW) / mStillW;
                uint8_t y8 = depthY8Buffer[srcY * depthW + srcX];
                float z_m;
                if (y8 == 0) {
                    z_m = 10.0f;
                } else {
                    float disp = static_cast<float>(y8);
                    z_m = (5.56f * 15.0f) / (disp * 0.8571f + 0.0713f);
                    if (z_m < 0.25f) z_m = 0.25f;
                    if (z_m > 10.0f) z_m = 10.0f;
                }
                depthDst[y * mStillW + x] = z_m;
                float a = (z_m <= 0.9f) ? 1.0f : (z_m >= 1.6f ? 0.0f : (1.6f - z_m) / 0.7f);
                alphaDst[y * mStillW + x] = static_cast<uint8_t>(a * 255.0f);
            }
        }
    } else {
        for (uint32_t y = 0; y < mStillH; ++y) {
            for (uint32_t x = 0; x < mStillW; ++x) {
                float dx = (x - cx);
                float dy = (y - cy) * 1.3f;
                float dist = std::sqrt(dx * dx + dy * dy);
                float normDist = dist / radiusFg;
                float alpha = 1.0f - std::min(1.0f, std::max(0.0f, (normDist - 0.75f) / 0.25f));
                float z_m = (alpha > 0.5f) ? 0.6f : (0.6f + (1.0f - alpha) * 2.9f);
                depthDst[y * mStillW + x] = z_m;
                alphaDst[y * mStillW + x] = static_cast<uint8_t>(alpha * 255.0f);
            }
        }
    }
    vkUnmapMemory(mDevice, mUploadStagingMem);

    // 4. Update descriptor sets
    VkDescriptorImageInfo descYuv{ VK_NULL_HANDLE, importedView, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL };
    VkDescriptorImageInfo descCoc{ VK_NULL_HANDLE, mViewCoc, VK_IMAGE_LAYOUT_GENERAL };
    VkDescriptorImageInfo descAlphaSample{ VK_NULL_HANDLE, mViewAlpha, VK_IMAGE_LAYOUT_GENERAL };
    VkDescriptorImageInfo descBlurredStore{ VK_NULL_HANDLE, mViewBlurred, VK_IMAGE_LAYOUT_GENERAL };
    VkDescriptorImageInfo descBlurredSample{ VK_NULL_HANDLE, mViewBlurred, VK_IMAGE_LAYOUT_GENERAL };
    VkDescriptorImageInfo descPreviewStore{ VK_NULL_HANDLE, mViewPreviewOut, VK_IMAGE_LAYOUT_GENERAL };

    VkWriteDescriptorSet writes[8]{};
    writes[0].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[0].dstSet = mSetGather;
    writes[0].dstBinding = 0;
    writes[0].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    writes[0].descriptorCount = 1;
    writes[0].pImageInfo = &descYuv;

    writes[1].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[1].dstSet = mSetGather;
    writes[1].dstBinding = 1;
    writes[1].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    writes[1].descriptorCount = 1;
    writes[1].pImageInfo = &descCoc;

    writes[2].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[2].dstSet = mSetGather;
    writes[2].dstBinding = 2;
    writes[2].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    writes[2].descriptorCount = 1;
    writes[2].pImageInfo = &descAlphaSample;

    writes[3].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[3].dstSet = mSetGather;
    writes[3].dstBinding = 3;
    writes[3].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    writes[3].descriptorCount = 1;
    writes[3].pImageInfo = &descBlurredStore;

    writes[4].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[4].dstSet = mSetCompPreview;
    writes[4].dstBinding = 0;
    writes[4].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    writes[4].descriptorCount = 1;
    writes[4].pImageInfo = &descYuv;

    writes[5].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[5].dstSet = mSetCompPreview;
    writes[5].dstBinding = 1;
    writes[5].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    writes[5].descriptorCount = 1;
    writes[5].pImageInfo = &descBlurredSample;

    writes[6].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[6].dstSet = mSetCompPreview;
    writes[6].dstBinding = 2;
    writes[6].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    writes[6].descriptorCount = 1;
    writes[6].pImageInfo = &descAlphaSample;

    writes[7].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[7].dstSet = mSetCompPreview;
    writes[7].dstBinding = 3;
    writes[7].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    writes[7].descriptorCount = 1;
    writes[7].pImageInfo = &descPreviewStore;

    vkUpdateDescriptorSets(mDevice, 8, writes, 0, nullptr);

    // 5. Record command buffer
    VkCommandBuffer curCmd = mPreviewCmds[mCurrentFrame];
    vkResetCommandBuffer(curCmd, 0);
    VkCommandBufferBeginInfo bInfo{ VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO };
    vkBeginCommandBuffer(curCmd, &bInfo);

    uint32_t qBase = mCurrentFrame * 4;
    if (mPreviewQueryPool != VK_NULL_HANDLE) {
        vkCmdResetQueryPool(curCmd, mPreviewQueryPool, qBase, 4);
        vkCmdWriteTimestamp(curCmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, mPreviewQueryPool, qBase + 0);
    }

    auto transition = [&](VkImage img, VkImageLayout oldLayout, VkImageLayout newLayout, VkAccessFlags srcAcc, VkAccessFlags dstAcc, VkPipelineStageFlags srcStage, VkPipelineStageFlags dstStage) {
        VkImageMemoryBarrier b{ VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER };
        b.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
        b.oldLayout = oldLayout;
        b.newLayout = newLayout;
        b.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        b.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        b.image = img;
        b.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        b.subresourceRange.levelCount = 1;
        b.subresourceRange.layerCount = 1;
        b.srcAccessMask = srcAcc;
        b.dstAccessMask = dstAcc;
        vkCmdPipelineBarrier(curCmd, srcStage, dstStage, 0, 0, nullptr, 0, nullptr, 1, &b);
    };

    transition(importedImg, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, 0, VK_ACCESS_SHADER_READ_BIT, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
    transition(mImgDepth, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 0, VK_ACCESS_TRANSFER_WRITE_BIT, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);
    transition(mImgAlpha, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 0, VK_ACCESS_TRANSFER_WRITE_BIT, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);
    transition(mImgCoc, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL, 0, VK_ACCESS_SHADER_WRITE_BIT, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
    transition(mImgBlurred, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL, 0, VK_ACCESS_SHADER_WRITE_BIT, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
    transition(mImgPreviewOut, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL, 0, VK_ACCESS_SHADER_WRITE_BIT, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);

    VkBufferImageCopy copyDepthRegion{};
    copyDepthRegion.bufferOffset = 0;
    copyDepthRegion.imageSubresource = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1 };
    copyDepthRegion.imageExtent = { mStillW, mStillH, 1 };
    vkCmdCopyBufferToImage(curCmd, mUploadStagingBuffer, mImgDepth, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &copyDepthRegion);

    VkBufferImageCopy copyAlphaRegion{};
    copyAlphaRegion.bufferOffset = mStillW * mStillH * sizeof(float);
    copyAlphaRegion.imageSubresource = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1 };
    copyAlphaRegion.imageExtent = { mStillW, mStillH, 1 };
    vkCmdCopyBufferToImage(curCmd, mUploadStagingBuffer, mImgAlpha, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &copyAlphaRegion);

    transition(mImgDepth, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_GENERAL, VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
    transition(mImgAlpha, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_GENERAL, VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);

    uint32_t gxWork = (mStillW + 15) / 16;
    uint32_t gyWork = (mStillH + 15) / 16;

    CocPushConstants pcCoc{ mStillW, mStillH, 5.56f, fStop, 8.16f, 0.6f, 15.0f, 720.0f };
    GatherPushConstants pcGather{ mStillW, mStillH, 32, 1.2f, 40.0f };
    CompositePushConstants pcComp{ mSwapchainExtent.width, mSwapchainExtent.height, kPreviewRotate90 };

    VkMemoryBarrier memBarrier{ VK_STRUCTURE_TYPE_MEMORY_BARRIER, nullptr, VK_ACCESS_SHADER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT };

    // CoC Pass
    vkCmdBindPipeline(curCmd, VK_PIPELINE_BIND_POINT_COMPUTE, mPipeCoc);
    vkCmdBindDescriptorSets(curCmd, VK_PIPELINE_BIND_POINT_COMPUTE, mPlCoc, 0, 1, &mSetCoc, 0, nullptr);
    vkCmdPushConstants(curCmd, mPlCoc, VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(CocPushConstants), &pcCoc);
    vkCmdDispatch(curCmd, gxWork, gyWork, 1);

    vkCmdPipelineBarrier(curCmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 1, &memBarrier, 0, nullptr, 0, nullptr);
    if (mPreviewQueryPool != VK_NULL_HANDLE) {
        vkCmdWriteTimestamp(curCmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, mPreviewQueryPool, qBase + 1);
    }

    // 32-tap Gather Pass
    vkCmdBindPipeline(curCmd, VK_PIPELINE_BIND_POINT_COMPUTE, mPipeGather);
    vkCmdBindDescriptorSets(curCmd, VK_PIPELINE_BIND_POINT_COMPUTE, mPlGather, 0, 1, &mSetGather, 0, nullptr);
    vkCmdPushConstants(curCmd, mPlGather, VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(GatherPushConstants), &pcGather);
    vkCmdDispatch(curCmd, gxWork, gyWork, 1);

    vkCmdPipelineBarrier(curCmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 1, &memBarrier, 0, nullptr, 0, nullptr);
    if (mPreviewQueryPool != VK_NULL_HANDLE) {
        vkCmdWriteTimestamp(curCmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, mPreviewQueryPool, qBase + 2);
    }

    // Composite Pass (Preview Viewport)
    uint32_t gxComp = (mSwapchainExtent.width + 15) / 16;
    uint32_t gyComp = (mSwapchainExtent.height + 15) / 16;
    vkCmdBindPipeline(curCmd, VK_PIPELINE_BIND_POINT_COMPUTE, mPipeComp);
    vkCmdBindDescriptorSets(curCmd, VK_PIPELINE_BIND_POINT_COMPUTE, mPlComp, 0, 1, &mSetCompPreview, 0, nullptr);
    vkCmdPushConstants(curCmd, mPlComp, VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(CompositePushConstants), &pcComp);
    vkCmdDispatch(curCmd, gxComp, gyComp, 1);

    // Copy composite to swapchain image
    transition(mImgPreviewOut, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_ACCESS_SHADER_WRITE_BIT, VK_ACCESS_TRANSFER_READ_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);
    transition(mSwapchainImages[imageIndex], VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 0, VK_ACCESS_TRANSFER_WRITE_BIT, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);

    VkImageCopy copyRegion{};
    copyRegion.srcSubresource = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1 };
    copyRegion.dstSubresource = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1 };
    copyRegion.extent = { mSwapchainExtent.width, mSwapchainExtent.height, 1 };
    vkCmdCopyImage(curCmd, mImgPreviewOut, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, mSwapchainImages[imageIndex], VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &copyRegion);

    transition(mSwapchainImages[imageIndex], VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_PRESENT_SRC_KHR, VK_ACCESS_TRANSFER_WRITE_BIT, 0, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT);

    if (mPreviewQueryPool != VK_NULL_HANDLE) {
        vkCmdWriteTimestamp(curCmd, VK_PIPELINE_STAGE_TRANSFER_BIT, mPreviewQueryPool, qBase + 3);
    }

    vkEndCommandBuffer(curCmd);

    // 6. Submit with Semaphore
    VkPipelineStageFlags waitDstStage = VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT;
    VkSubmitInfo submitInfo{ VK_STRUCTURE_TYPE_SUBMIT_INFO };
    submitInfo.waitSemaphoreCount = 1;
    submitInfo.pWaitSemaphores = &mImageAvailableSemaphores[mCurrentFrame];
    submitInfo.pWaitDstStageMask = &waitDstStage;
    submitInfo.commandBufferCount = 1;
    submitInfo.pCommandBuffers = &curCmd;
    submitInfo.signalSemaphoreCount = 1;
    submitInfo.pSignalSemaphores = &mRenderFinishedSemaphores[mCurrentFrame];

    auto tSubmit = std::chrono::high_resolution_clock::now();
    {
        std::lock_guard<std::mutex> lock(mQueueMutex);
        vkQueueSubmit(mComputeQueue, 1, &submitInfo, mRenderFences[mCurrentFrame]);

        // Synchronous wait on fence: guarantees GPU has completely rendered before present
        vkWaitForFences(mDevice, 1, &mRenderFences[mCurrentFrame], VK_TRUE, UINT64_MAX);

        // 7. Present to Android SurfaceView
        VkPresentInfoKHR presentInfo{ VK_STRUCTURE_TYPE_PRESENT_INFO_KHR };
        presentInfo.waitSemaphoreCount = 1;
        presentInfo.pWaitSemaphores = &mRenderFinishedSemaphores[mCurrentFrame];
        presentInfo.swapchainCount = 1;
        presentInfo.pSwapchains = &mSwapchain;
        presentInfo.pImageIndices = &imageIndex;

        vkQueuePresentKHR(mComputeQueue, &presentInfo);
    }
    auto tPresent = std::chrono::high_resolution_clock::now();
    double submitToPresentMs = std::chrono::duration<double, std::milli>(tPresent - tSubmit).count();

    // Read hardware timestamps
    float timestampPeriod = 1.0f;
    VkPhysicalDeviceProperties props;
    vkGetPhysicalDeviceProperties(mPhysicalDevice, &props);
    timestampPeriod = props.limits.timestampPeriod;

    uint64_t ts[4]{};
    double dCoc = 0.0, dGather = 0.0, dComp = 0.0, dTotalGpu = 0.0;
    if (mPreviewQueryPool != VK_NULL_HANDLE) {
        vkGetQueryPoolResults(mDevice, mPreviewQueryPool, qBase, 4, sizeof(ts), ts, sizeof(uint64_t), VK_QUERY_RESULT_64_BIT);
        dCoc = (ts[1] - ts[0]) * timestampPeriod / 1e6;
        dGather = (ts[2] - ts[1]) * timestampPeriod / 1e6;
        dComp = (ts[3] - ts[2]) * timestampPeriod / 1e6;
        dTotalGpu = (ts[3] - ts[0]) * timestampPeriod / 1e6;
    }

    static std::vector<double> sSubmitWindow;
    static std::vector<double> sGpuWindow;
    sSubmitWindow.push_back(submitToPresentMs);
    sGpuWindow.push_back(dTotalGpu);

    if (sSubmitWindow.size() >= 60) {
        std::vector<double> sortedSubmit = sSubmitWindow;
        std::vector<double> sortedGpu = sGpuWindow;
        std::sort(sortedSubmit.begin(), sortedSubmit.end());
        std::sort(sortedGpu.begin(), sortedGpu.end());

        size_t n = sortedSubmit.size();
        double p50_submit = sortedSubmit[n * 50 / 100];
        double p90_submit = sortedSubmit[n * 90 / 100];
        double p95_submit = sortedSubmit[n * 95 / 100];
        double min_submit = sortedSubmit.front();
        double max_submit = sortedSubmit.back();
        double p50_gpu = sortedGpu[n * 50 / 100];
        double effFps = 1000.0 / (p50_submit > 0.1 ? p50_submit : 33.3);

        LOGI("PREVIEW TELEMETRY (N=%zu): Submit->Present p50=%.2f ms, p90=%.2f ms, p95=%.2f ms [min=%.2f, max=%.2f] | Effective FPS=%.1f | GPU: CoC=%.2f ms, 32-tap Gather=%.2f ms, Comp=%.2f ms | Total GPU p50=%.2f ms",
             n, p50_submit, p90_submit, p95_submit, min_submit, max_submit, effFps, dCoc, dGather, dComp, p50_gpu);
        sSubmitWindow.clear();
        sGpuWindow.clear();
    }

    mCurrentFrame = (mCurrentFrame + 1) % MAX_FRAMES_IN_FLIGHT;

    // 8. Release imported image
    mYuvConverter->releaseImportedImage(importedImg, importedMem, importedView);
    return true;
}

uint32_t VulkanPipeline::findMemoryType(uint32_t typeFilter, VkMemoryPropertyFlags properties) {
    VkPhysicalDeviceMemoryProperties memProperties;
    vkGetPhysicalDeviceMemoryProperties(mPhysicalDevice, &memProperties);
    for (uint32_t i = 0; i < memProperties.memoryTypeCount; i++) {
        if ((typeFilter & (1 << i)) && (memProperties.memoryTypes[i].propertyFlags & properties) == properties) {
            return i;
        }
    }
    return 0;
}

bool VulkanPipeline::initStillBokehResources(uint32_t width, uint32_t height) {
    if (mStillBokehInitialized) return true;
    mStillW = width;
    mStillH = height;

    LOGI("Initializing Still Bokeh Vulkan resources (%ux%u)...", mStillW, mStillH);

    auto createShaderModule = [&](const std::vector<char>& spv) -> VkShaderModule {
        if (spv.empty()) return VK_NULL_HANDLE;
        VkShaderModuleCreateInfo smInfo{};
        smInfo.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
        smInfo.codeSize = spv.size();
        smInfo.pCode = reinterpret_cast<const uint32_t*>(spv.data());
        VkShaderModule sm = VK_NULL_HANDLE;
        vkCreateShaderModule(mDevice, &smInfo, nullptr, &sm);
        return sm;
    };

    auto cocSpv = loadSpvFile("/data/local/tmp/coc.spv");
    auto gatherSpv = loadSpvFile("/data/local/tmp/gather.spv");
    auto compSpv = loadSpvFile("/data/local/tmp/composite.spv");

    if (cocSpv.empty() || gatherSpv.empty() || compSpv.empty()) {
        LOGE("Failed to load SPIR-V compute shaders from /data/local/tmp");
        return false;
    }

    VkShaderModule smCoc = createShaderModule(cocSpv);
    VkShaderModule smGather = createShaderModule(gatherSpv);
    VkShaderModule smComp = createShaderModule(compSpv);

    auto createImage = [&](uint32_t w, uint32_t h, VkFormat fmt, VkImageUsageFlags usage, VkImage* outImg, VkDeviceMemory* outMem) {
        VkImageCreateInfo imgInfo{};
        imgInfo.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
        imgInfo.imageType = VK_IMAGE_TYPE_2D;
        imgInfo.extent = { w, h, 1 };
        imgInfo.mipLevels = 1;
        imgInfo.arrayLayers = 1;
        imgInfo.format = fmt;
        imgInfo.tiling = VK_IMAGE_TILING_OPTIMAL;
        imgInfo.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        imgInfo.usage = usage;
        imgInfo.samples = VK_SAMPLE_COUNT_1_BIT;
        vkCreateImage(mDevice, &imgInfo, nullptr, outImg);

        VkMemoryRequirements mr;
        vkGetImageMemoryRequirements(mDevice, *outImg, &mr);
        VkMemoryAllocateInfo allocInfo{};
        allocInfo.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
        allocInfo.allocationSize = mr.size;
        allocInfo.memoryTypeIndex = findMemoryType(mr.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        vkAllocateMemory(mDevice, &allocInfo, nullptr, outMem);
        vkBindImageMemory(mDevice, *outImg, *outMem, 0);
    };

    auto createView = [&](VkImage img, VkFormat fmt) -> VkImageView {
        VkImageViewCreateInfo vi{};
        vi.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
        vi.image = img;
        vi.viewType = VK_IMAGE_VIEW_TYPE_2D;
        vi.format = fmt;
        vi.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        vi.subresourceRange.levelCount = 1;
        vi.subresourceRange.layerCount = 1;
        VkImageView v;
        vkCreateImageView(mDevice, &vi, nullptr, &v);
        return v;
    };

    createImage(mStillW, mStillH, VK_FORMAT_R32_SFLOAT, VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT, &mImgDepth, &mMemDepth);
    createImage(mStillW, mStillH, VK_FORMAT_R16_SFLOAT, VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_STORAGE_BIT, &mImgCoc, &mMemCoc);
    createImage(mStillW, mStillH, VK_FORMAT_R8_UNORM, VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT, &mImgAlpha, &mMemAlpha);
    createImage(mStillW, mStillH, VK_FORMAT_R8G8B8A8_UNORM, VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_STORAGE_BIT, &mImgBlurred, &mMemBlurred);
    createImage(mFullW, mFullH, VK_FORMAT_R8G8B8A8_UNORM, VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT, &mImgOutFull, &mMemOutFull);

    mViewDepth = createView(mImgDepth, VK_FORMAT_R32_SFLOAT);
    mViewCoc = createView(mImgCoc, VK_FORMAT_R16_SFLOAT);
    mViewAlpha = createView(mImgAlpha, VK_FORMAT_R8_UNORM);
    mViewBlurred = createView(mImgBlurred, VK_FORMAT_R8G8B8A8_UNORM);
    mViewOutFull = createView(mImgOutFull, VK_FORMAT_R8G8B8A8_UNORM);

    // Staging buffer for depth & alpha upload (1020x768 float depth + 1020x768 uint8 alpha)
    const size_t uploadStagingSize = mStillW * mStillH * sizeof(float) + mStillW * mStillH;
    {
        VkBufferCreateInfo bi{ VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO };
        bi.size = uploadStagingSize;
        bi.usage = VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
        vkCreateBuffer(mDevice, &bi, nullptr, &mUploadStagingBuffer);

        VkMemoryRequirements mr;
        vkGetBufferMemoryRequirements(mDevice, mUploadStagingBuffer, &mr);
        VkMemoryAllocateInfo ai{ VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO };
        ai.allocationSize = mr.size;
        ai.memoryTypeIndex = findMemoryType(mr.memoryTypeBits, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
        vkAllocateMemory(mDevice, &ai, nullptr, &mUploadStagingMem);
        vkBindBufferMemory(mDevice, mUploadStagingBuffer, mUploadStagingMem, 0);
    }

    // Readback buffer for 4080x3072 RGBA8 (50,135,040 bytes)
    const size_t readbackSize = mFullW * mFullH * 4;
    {
        VkBufferCreateInfo bi{ VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO };
        bi.size = readbackSize;
        bi.usage = VK_BUFFER_USAGE_TRANSFER_DST_BIT;
        vkCreateBuffer(mDevice, &bi, nullptr, &mReadbackBuffer);

        VkMemoryRequirements mr;
        vkGetBufferMemoryRequirements(mDevice, mReadbackBuffer, &mr);
        VkMemoryAllocateInfo ai{ VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO };
        ai.allocationSize = mr.size;
        ai.memoryTypeIndex = findMemoryType(mr.memoryTypeBits, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
        vkAllocateMemory(mDevice, &ai, nullptr, &mReadbackMem);
        vkBindBufferMemory(mDevice, mReadbackBuffer, mReadbackMem, 0);
    }

    // Still completion fence
    VkFenceCreateInfo fInfo{ VK_STRUCTURE_TYPE_FENCE_CREATE_INFO, nullptr, 0 };
    vkCreateFence(mDevice, &fInfo, nullptr, &mStillFence);

    // Timestamp query pool
    VkQueryPoolCreateInfo qpInfo{ VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO, nullptr, 0, VK_QUERY_TYPE_TIMESTAMP, 8, 0 };
    vkCreateQueryPool(mDevice, &qpInfo, nullptr, &mStillQueryPool);

    VkSamplerCreateInfo sampInfo{};
    sampInfo.sType = VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO;
    sampInfo.magFilter = VK_FILTER_LINEAR;
    sampInfo.minFilter = VK_FILTER_LINEAR;
    sampInfo.addressModeU = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    sampInfo.addressModeV = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    sampInfo.addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    vkCreateSampler(mDevice, &sampInfo, nullptr, &mStillSampler);

    // Command pool & buffer
    VkCommandPoolCreateInfo cpInfo{};
    cpInfo.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
    cpInfo.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
    cpInfo.queueFamilyIndex = mComputeQueueFamilyIndex;
    vkCreateCommandPool(mDevice, &cpInfo, nullptr, &mStillCmdPool);

    VkCommandBufferAllocateInfo cbAlloc{};
    cbAlloc.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
    cbAlloc.commandPool = mStillCmdPool;
    cbAlloc.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    cbAlloc.commandBufferCount = 1;
    vkAllocateCommandBuffers(mDevice, &cbAlloc, &mStillCmd);

    // Descriptor Pool
    VkDescriptorPoolSize poolSizes[2]{};
    poolSizes[0].type = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    poolSizes[0].descriptorCount = 30;
    poolSizes[1].type = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    poolSizes[1].descriptorCount = 20;

    VkDescriptorPoolCreateInfo dpInfo{};
    dpInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
    dpInfo.maxSets = 20;
    dpInfo.poolSizeCount = 2;
    dpInfo.pPoolSizes = poolSizes;
    vkCreateDescriptorPool(mDevice, &dpInfo, nullptr, &mStillDescPool);

    // 1. CoC Pipeline
    VkDescriptorSetLayoutBinding cocBindings[2]{};
    cocBindings[0].binding = 0;
    cocBindings[0].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    cocBindings[0].descriptorCount = 1;
    cocBindings[0].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    cocBindings[0].pImmutableSamplers = &mStillSampler;
    cocBindings[1].binding = 1;
    cocBindings[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    cocBindings[1].descriptorCount = 1;
    cocBindings[1].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;

    VkDescriptorSetLayoutCreateInfo dslInfo{};
    dslInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
    dslInfo.bindingCount = 2;
    dslInfo.pBindings = cocBindings;
    vkCreateDescriptorSetLayout(mDevice, &dslInfo, nullptr, &mDslCoc);

    VkPushConstantRange pcRangeCoc{};
    pcRangeCoc.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    pcRangeCoc.offset = 0;
    pcRangeCoc.size = sizeof(CocPushConstants);

    VkPipelineLayoutCreateInfo plInfoCoc{};
    plInfoCoc.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
    plInfoCoc.setLayoutCount = 1;
    plInfoCoc.pSetLayouts = &mDslCoc;
    plInfoCoc.pushConstantRangeCount = 1;
    plInfoCoc.pPushConstantRanges = &pcRangeCoc;
    vkCreatePipelineLayout(mDevice, &plInfoCoc, nullptr, &mPlCoc);

    VkComputePipelineCreateInfo pipeInfoCoc{};
    pipeInfoCoc.sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO;
    pipeInfoCoc.stage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    pipeInfoCoc.stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
    pipeInfoCoc.stage.module = smCoc;
    pipeInfoCoc.stage.pName = "main";
    pipeInfoCoc.layout = mPlCoc;
    vkCreateComputePipelines(mDevice, VK_NULL_HANDLE, 1, &pipeInfoCoc, nullptr, &mPipeCoc);

    VkSampler yuvSampler = mYuvConverter->getSampler();

    // 2. Gather Pipeline (32 taps)
    VkDescriptorSetLayoutBinding gatherBindings[4]{};
    for (int b = 0; b < 3; ++b) {
        gatherBindings[b].binding = b;
        gatherBindings[b].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        gatherBindings[b].descriptorCount = 1;
        gatherBindings[b].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
        gatherBindings[b].pImmutableSamplers = (b == 0) ? &yuvSampler : &mStillSampler;
    }
    gatherBindings[3].binding = 3;
    gatherBindings[3].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    gatherBindings[3].descriptorCount = 1;
    gatherBindings[3].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;

    dslInfo.bindingCount = 4;
    dslInfo.pBindings = gatherBindings;
    vkCreateDescriptorSetLayout(mDevice, &dslInfo, nullptr, &mDslGather);

    VkPushConstantRange pcRangeGather{};
    pcRangeGather.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    pcRangeGather.offset = 0;
    pcRangeGather.size = sizeof(GatherPushConstants);

    VkPipelineLayoutCreateInfo plInfoGather{};
    plInfoGather.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
    plInfoGather.setLayoutCount = 1;
    plInfoGather.pSetLayouts = &mDslGather;
    plInfoGather.pushConstantRangeCount = 1;
    plInfoGather.pPushConstantRanges = &pcRangeGather;
    vkCreatePipelineLayout(mDevice, &plInfoGather, nullptr, &mPlGather);

    VkComputePipelineCreateInfo pipeInfoGather{};
    pipeInfoGather.sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO;
    pipeInfoGather.stage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    pipeInfoGather.stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
    pipeInfoGather.stage.module = smGather;
    pipeInfoGather.stage.pName = "main";
    pipeInfoGather.layout = mPlGather;
    vkCreateComputePipelines(mDevice, VK_NULL_HANDLE, 1, &pipeInfoGather, nullptr, &mPipeGather);

    // 3. Composite Pipeline
    VkDescriptorSetLayoutBinding compBindings[4]{};
    for (int b = 0; b < 3; ++b) {
        compBindings[b].binding = b;
        compBindings[b].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        compBindings[b].descriptorCount = 1;
        compBindings[b].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
        compBindings[b].pImmutableSamplers = (b == 0) ? &yuvSampler : &mStillSampler;
    }
    compBindings[3].binding = 3;
    compBindings[3].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    compBindings[3].descriptorCount = 1;
    compBindings[3].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;

    dslInfo.bindingCount = 4;
    dslInfo.pBindings = compBindings;
    vkCreateDescriptorSetLayout(mDevice, &dslInfo, nullptr, &mDslComp);

    VkPushConstantRange pcRangeComp{};
    pcRangeComp.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    pcRangeComp.offset = 0;
    pcRangeComp.size = sizeof(CompositePushConstants);

    VkPipelineLayoutCreateInfo plInfoComp{};
    plInfoComp.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
    plInfoComp.setLayoutCount = 1;
    plInfoComp.pSetLayouts = &mDslComp;
    plInfoComp.pushConstantRangeCount = 1;
    plInfoComp.pPushConstantRanges = &pcRangeComp;
    vkCreatePipelineLayout(mDevice, &plInfoComp, nullptr, &mPlComp);

    VkComputePipelineCreateInfo pipeInfoComp{};
    pipeInfoComp.sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO;
    pipeInfoComp.stage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    pipeInfoComp.stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
    pipeInfoComp.stage.module = smComp;
    pipeInfoComp.stage.pName = "main";
    pipeInfoComp.layout = mPlComp;
    vkCreateComputePipelines(mDevice, VK_NULL_HANDLE, 1, &pipeInfoComp, nullptr, &mPipeComp);

    // Allocate Descriptor Sets
    VkDescriptorSetAllocateInfo dsAllocInfo{};
    dsAllocInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
    dsAllocInfo.descriptorPool = mStillDescPool;
    dsAllocInfo.descriptorSetCount = 1;

    dsAllocInfo.pSetLayouts = &mDslCoc;
    vkAllocateDescriptorSets(mDevice, &dsAllocInfo, &mSetCoc);

    dsAllocInfo.pSetLayouts = &mDslGather;
    vkAllocateDescriptorSets(mDevice, &dsAllocInfo, &mSetGather);

    dsAllocInfo.pSetLayouts = &mDslComp;
    vkAllocateDescriptorSets(mDevice, &dsAllocInfo, &mSetComp);

    // CoC static writes
    VkDescriptorImageInfo descImgDepth{ VK_NULL_HANDLE, mViewDepth, VK_IMAGE_LAYOUT_GENERAL };
    VkDescriptorImageInfo descImgCoc{ VK_NULL_HANDLE, mViewCoc, VK_IMAGE_LAYOUT_GENERAL };
    VkWriteDescriptorSet writesCoc[2]{};
    writesCoc[0].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writesCoc[0].dstSet = mSetCoc;
    writesCoc[0].dstBinding = 0;
    writesCoc[0].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    writesCoc[0].descriptorCount = 1;
    writesCoc[0].pImageInfo = &descImgDepth;
    writesCoc[1].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writesCoc[1].dstSet = mSetCoc;
    writesCoc[1].dstBinding = 1;
    writesCoc[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    writesCoc[1].descriptorCount = 1;
    writesCoc[1].pImageInfo = &descImgCoc;
    vkUpdateDescriptorSets(mDevice, 2, writesCoc, 0, nullptr);

    // Destroy temp shader modules
    vkDestroyShaderModule(mDevice, smCoc, nullptr);
    vkDestroyShaderModule(mDevice, smGather, nullptr);
    vkDestroyShaderModule(mDevice, smComp, nullptr);

    mStillBokehInitialized = true;
    LOGI("Still Bokeh Vulkan resources initialized successfully!");
    return true;
}

bool VulkanPipeline::processStillBokeh(AHardwareBuffer* colorBuffer, const uint8_t* depthY8Buffer, uint32_t depthW, uint32_t depthH, float fStop, float targetFarFieldPx, const char* outFilePath, int64_t* outTimestamps) {
    LOGI("processStillBokeh ENTERED: colorBuffer=%p, depthY8Buffer=%p (%ux%u), fStop=%.2f, targetFarFieldPx=%.1f, outFilePath=%s",
         colorBuffer, depthY8Buffer, depthW, depthH, fStop, targetFarFieldPx, outFilePath ? outFilePath : "null");
    if (!mInitialized) return false;
    if (!mStillBokehInitialized) {
        if (!initStillBokehResources(1020, 768)) {
            LOGE("Failed to init still bokeh resources");
            return false;
        }
    }

    auto tStart = std::chrono::high_resolution_clock::now();

    // 1. Zero-copy import of 4080x3072 YUV AHardwareBuffer
    VkImage importedImg = VK_NULL_HANDLE;
    VkDeviceMemory importedMem = VK_NULL_HANDLE;
    VkImageView importedView = VK_NULL_HANDLE;
    if (!mYuvConverter->importHardwareBuffer(colorBuffer, &importedImg, &importedMem, &importedView)) {
        LOGE("Failed to import AHardwareBuffer in processStillBokeh");
        return false;
    }

    // 2. Populate staging buffer with depth and alpha
    void* mappedStaging = nullptr;
    vkMapMemory(mDevice, mUploadStagingMem, 0, mStillW * mStillH * sizeof(float) + mStillW * mStillH, 0, &mappedStaging);
    float* depthDst = reinterpret_cast<float*>(mappedStaging);
    uint8_t* alphaDst = reinterpret_cast<uint8_t*>(depthDst + (mStillW * mStillH));

    bool hasDepthSignal = false;
    if (depthY8Buffer != nullptr && depthW > 0 && depthH > 0) {
        for (uint32_t i = 0; i < depthW * depthH; i += 16) {
            if (depthY8Buffer[i] > 5) {
                hasDepthSignal = true;
                break;
            }
        }
    }

    const float cx = mStillW * 0.5f;
    const float cy = mStillH * 0.5f;
    const float radiusFg = mStillW * 0.24f;

    if (hasDepthSignal) {
        for (uint32_t y = 0; y < mStillH; ++y) {
            uint32_t srcY = (y * depthH) / mStillH;
            for (uint32_t x = 0; x < mStillW; ++x) {
                uint32_t srcX = (x * depthW) / mStillW;
                uint8_t y8 = depthY8Buffer[srcY * depthW + srcX];
                float z_m;
                if (y8 == 0) {
                    z_m = 10.0f;
                } else {
                    float disp = static_cast<float>(y8);
                    z_m = (5.56f * 15.0f) / (disp * 0.8571f + 0.0713f);
                    if (z_m < 0.25f) z_m = 0.25f;
                    if (z_m > 10.0f) z_m = 10.0f;
                }
                depthDst[y * mStillW + x] = z_m;
                float a = (z_m <= 0.9f) ? 1.0f : (z_m >= 1.6f ? 0.0f : (1.6f - z_m) / 0.7f);
                alphaDst[y * mStillW + x] = static_cast<uint8_t>(a * 255.0f);
            }
        }
    } else {
        for (uint32_t y = 0; y < mStillH; ++y) {
            for (uint32_t x = 0; x < mStillW; ++x) {
                float dx = (x - cx);
                float dy = (y - cy) * 1.3f;
                float dist = std::sqrt(dx * dx + dy * dy);
                float normDist = dist / radiusFg;
                float alpha = 1.0f - std::min(1.0f, std::max(0.0f, (normDist - 0.75f) / 0.25f));
                float z_m = (alpha > 0.5f) ? 0.6f : (0.6f + (1.0f - alpha) * 2.9f);
                depthDst[y * mStillW + x] = z_m;
                alphaDst[y * mStillW + x] = static_cast<uint8_t>(alpha * 255.0f);
            }
        }
    }
    vkUnmapMemory(mDevice, mUploadStagingMem);

    // 3. Update descriptor sets
    VkDescriptorImageInfo descYuv{ VK_NULL_HANDLE, importedView, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL };
    VkDescriptorImageInfo descCoc{ VK_NULL_HANDLE, mViewCoc, VK_IMAGE_LAYOUT_GENERAL };
    VkDescriptorImageInfo descAlphaSample{ VK_NULL_HANDLE, mViewAlpha, VK_IMAGE_LAYOUT_GENERAL };
    VkDescriptorImageInfo descBlurredStore{ VK_NULL_HANDLE, mViewBlurred, VK_IMAGE_LAYOUT_GENERAL };
    VkDescriptorImageInfo descBlurredSample{ VK_NULL_HANDLE, mViewBlurred, VK_IMAGE_LAYOUT_GENERAL };
    VkDescriptorImageInfo descOutStoreFull{ VK_NULL_HANDLE, mViewOutFull, VK_IMAGE_LAYOUT_GENERAL };

    VkWriteDescriptorSet writes[8]{};
    writes[0].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[0].dstSet = mSetGather;
    writes[0].dstBinding = 0;
    writes[0].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    writes[0].descriptorCount = 1;
    writes[0].pImageInfo = &descYuv;

    writes[1].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[1].dstSet = mSetGather;
    writes[1].dstBinding = 1;
    writes[1].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    writes[1].descriptorCount = 1;
    writes[1].pImageInfo = &descCoc;

    writes[2].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[2].dstSet = mSetGather;
    writes[2].dstBinding = 2;
    writes[2].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    writes[2].descriptorCount = 1;
    writes[2].pImageInfo = &descAlphaSample;

    writes[3].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[3].dstSet = mSetGather;
    writes[3].dstBinding = 3;
    writes[3].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    writes[3].descriptorCount = 1;
    writes[3].pImageInfo = &descBlurredStore;

    writes[4].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[4].dstSet = mSetComp;
    writes[4].dstBinding = 0;
    writes[4].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    writes[4].descriptorCount = 1;
    writes[4].pImageInfo = &descYuv;

    writes[5].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[5].dstSet = mSetComp;
    writes[5].dstBinding = 1;
    writes[5].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    writes[5].descriptorCount = 1;
    writes[5].pImageInfo = &descBlurredSample;

    writes[6].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[6].dstSet = mSetComp;
    writes[6].dstBinding = 2;
    writes[6].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    writes[6].descriptorCount = 1;
    writes[6].pImageInfo = &descAlphaSample;

    writes[7].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[7].dstSet = mSetComp;
    writes[7].dstBinding = 3;
    writes[7].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    writes[7].descriptorCount = 1;
    writes[7].pImageInfo = &descOutStoreFull;

    vkUpdateDescriptorSets(mDevice, 8, writes, 0, nullptr);

    // 4. Record command buffer
    VkResult resReset = vkResetCommandBuffer(mStillCmd, 0);
    if (resReset != VK_SUCCESS) {
        LOGE("mStillCmd vkResetCommandBuffer failed: %d", resReset);
    }
    VkCommandBufferBeginInfo bInfo{ VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO };
    VkResult resBegin = vkBeginCommandBuffer(mStillCmd, &bInfo);
    if (resBegin != VK_SUCCESS) {
        LOGE("mStillCmd vkBeginCommandBuffer failed: %d", resBegin);
    }

    vkCmdResetQueryPool(mStillCmd, mStillQueryPool, 0, 8);
    vkCmdWriteTimestamp(mStillCmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, mStillQueryPool, 0);

    auto transition = [&](VkImage img, VkImageLayout oldLayout, VkImageLayout newLayout, VkAccessFlags srcAcc, VkAccessFlags dstAcc, VkPipelineStageFlags srcStage, VkPipelineStageFlags dstStage) {
        VkImageMemoryBarrier b{ VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER };
        b.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
        b.oldLayout = oldLayout;
        b.newLayout = newLayout;
        b.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        b.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        b.image = img;
        b.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        b.subresourceRange.levelCount = 1;
        b.subresourceRange.layerCount = 1;
        b.srcAccessMask = srcAcc;
        b.dstAccessMask = dstAcc;
        vkCmdPipelineBarrier(mStillCmd, srcStage, dstStage, 0, 0, nullptr, 0, nullptr, 1, &b);
    };

    transition(importedImg, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, 0, VK_ACCESS_SHADER_READ_BIT, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
    transition(mImgDepth, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 0, VK_ACCESS_TRANSFER_WRITE_BIT, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);
    transition(mImgAlpha, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 0, VK_ACCESS_TRANSFER_WRITE_BIT, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);
    transition(mImgCoc, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL, 0, VK_ACCESS_SHADER_WRITE_BIT, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
    transition(mImgBlurred, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL, 0, VK_ACCESS_SHADER_WRITE_BIT, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
    transition(mImgOutFull, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL, 0, VK_ACCESS_SHADER_WRITE_BIT, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);

    VkBufferImageCopy copyDepthRegion{};
    copyDepthRegion.bufferOffset = 0;
    copyDepthRegion.imageSubresource = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1 };
    copyDepthRegion.imageExtent = { mStillW, mStillH, 1 };
    vkCmdCopyBufferToImage(mStillCmd, mUploadStagingBuffer, mImgDepth, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &copyDepthRegion);

    VkBufferImageCopy copyAlphaRegion{};
    copyAlphaRegion.bufferOffset = mStillW * mStillH * sizeof(float);
    copyAlphaRegion.imageSubresource = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1 };
    copyAlphaRegion.imageExtent = { mStillW, mStillH, 1 };
    vkCmdCopyBufferToImage(mStillCmd, mUploadStagingBuffer, mImgAlpha, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &copyAlphaRegion);

    transition(mImgDepth, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_GENERAL, VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
    transition(mImgAlpha, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_GENERAL, VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);

    uint32_t gxWork = (mStillW + 15) / 16;
    uint32_t gyWork = (mStillH + 15) / 16;

    CocPushConstants pcCoc{ mStillW, mStillH, 5.56f, fStop, 8.16f, 0.6f, targetFarFieldPx, 720.0f };
    GatherPushConstants pcGather{ mStillW, mStillH, 32, 1.2f, 40.0f };
    CompositePushConstants pcComp{ mFullW, mFullH, 0 };

    VkMemoryBarrier memBarrier{ VK_STRUCTURE_TYPE_MEMORY_BARRIER, nullptr, VK_ACCESS_SHADER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT };

    // CoC Pass (1020x768)
    vkCmdBindPipeline(mStillCmd, VK_PIPELINE_BIND_POINT_COMPUTE, mPipeCoc);
    vkCmdBindDescriptorSets(mStillCmd, VK_PIPELINE_BIND_POINT_COMPUTE, mPlCoc, 0, 1, &mSetCoc, 0, nullptr);
    vkCmdPushConstants(mStillCmd, mPlCoc, VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(CocPushConstants), &pcCoc);
    vkCmdDispatch(mStillCmd, gxWork, gyWork, 1);

    vkCmdWriteTimestamp(mStillCmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, mStillQueryPool, 1);
    vkCmdPipelineBarrier(mStillCmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 1, &memBarrier, 0, nullptr, 0, nullptr);

    // 32-tap Gather Pass (1020x768)
    vkCmdBindPipeline(mStillCmd, VK_PIPELINE_BIND_POINT_COMPUTE, mPipeGather);
    vkCmdBindDescriptorSets(mStillCmd, VK_PIPELINE_BIND_POINT_COMPUTE, mPlGather, 0, 1, &mSetGather, 0, nullptr);
    vkCmdPushConstants(mStillCmd, mPlGather, VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(GatherPushConstants), &pcGather);
    vkCmdDispatch(mStillCmd, gxWork, gyWork, 1);

    vkCmdWriteTimestamp(mStillCmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, mStillQueryPool, 2);
    vkCmdPipelineBarrier(mStillCmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 1, &memBarrier, 0, nullptr, 0, nullptr);

    // Full-Res Composite Pass (4080x3072)
    uint32_t gxFull = (mFullW + 15) / 16;
    uint32_t gyFull = (mFullH + 15) / 16;
    vkCmdBindPipeline(mStillCmd, VK_PIPELINE_BIND_POINT_COMPUTE, mPipeComp);
    vkCmdBindDescriptorSets(mStillCmd, VK_PIPELINE_BIND_POINT_COMPUTE, mPlComp, 0, 1, &mSetComp, 0, nullptr);
    vkCmdPushConstants(mStillCmd, mPlComp, VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(CompositePushConstants), &pcComp);
    vkCmdDispatch(mStillCmd, gxFull, gyFull, 1);

    vkCmdWriteTimestamp(mStillCmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, mStillQueryPool, 3);

    // Transition mImgOutFull to TRANSFER_SRC and copy to mReadbackBuffer
    transition(mImgOutFull, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_ACCESS_SHADER_WRITE_BIT, VK_ACCESS_TRANSFER_READ_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);

    VkBufferImageCopy copyOutRegion{};
    copyOutRegion.bufferOffset = 0;
    copyOutRegion.imageSubresource = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1 };
    copyOutRegion.imageExtent = { mFullW, mFullH, 1 };
    vkCmdCopyImageToBuffer(mStillCmd, mImgOutFull, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, mReadbackBuffer, 1, &copyOutRegion);

    vkCmdWriteTimestamp(mStillCmd, VK_PIPELINE_STAGE_TRANSFER_BIT, mStillQueryPool, 4);

    VkResult resEnd = vkEndCommandBuffer(mStillCmd);
    if (resEnd != VK_SUCCESS) {
        LOGE("mStillCmd vkEndCommandBuffer failed: %d", resEnd);
    }

    // 5. Submit with mStillFence
    vkResetFences(mDevice, 1, &mStillFence);
    auto tSubmit = std::chrono::high_resolution_clock::now();
    VkSubmitInfo si{ VK_STRUCTURE_TYPE_SUBMIT_INFO, nullptr, 0, nullptr, nullptr, 1, &mStillCmd, 0, nullptr };
    {
        std::lock_guard<std::mutex> lock(mQueueMutex);
        VkResult resSubmit = vkQueueSubmit(mComputeQueue, 1, &si, mStillFence);
        if (resSubmit != VK_SUCCESS) {
            LOGE("mStillCmd vkQueueSubmit failed: %d", resSubmit);
        }
    }

    // Wait on fence: THIS IS EXACTLY "COMPOSITE BUFFER COMPLETE" (Gate <= 500ms)
    VkResult resFence = vkWaitForFences(mDevice, 1, &mStillFence, VK_TRUE, UINT64_MAX);
    if (resFence != VK_SUCCESS) {
        LOGE("mStillFence wait failed: %d", resFence);
    }
    auto tBufferComplete = std::chrono::high_resolution_clock::now();

    // Read timestamps
    uint64_t ts[5]{};
    vkGetQueryPoolResults(mDevice, mStillQueryPool, 0, 5, sizeof(ts), ts, sizeof(uint64_t), VK_QUERY_RESULT_64_BIT);
    float timestampPeriod = 1.0f;
    VkPhysicalDeviceProperties props;
    vkGetPhysicalDeviceProperties(mPhysicalDevice, &props);
    timestampPeriod = props.limits.timestampPeriod;

    double dCoc = (ts[1] - ts[0]) * timestampPeriod / 1e6;
    double dGather = (ts[2] - ts[1]) * timestampPeriod / 1e6;
    double dComp = (ts[3] - ts[2]) * timestampPeriod / 1e6;
    double dCopy = (ts[4] - ts[3]) * timestampPeriod / 1e6;
    double dTotalCompute = (ts[3] - ts[0]) * timestampPeriod / 1e6;
    long submitToFenceMs = std::chrono::duration_cast<std::chrono::milliseconds>(tBufferComplete - tSubmit).count();

    LOGI("GPU Timestamps: CoC=%.2f ms, 32-tap Gather=%.2f ms, 4K Composite=%.2f ms, 50MB Copy=%.2f ms | Total Compute=%.2f ms | Fence Wait=%ld ms",
         dCoc, dGather, dComp, dCopy, dTotalCompute, submitToFenceMs);

    // 6. If outFilePath provided, write 50.1 MB buffer to storage and close (UX Gate)
    auto tFileClosed = tBufferComplete;
    if (outFilePath != nullptr && strlen(outFilePath) > 0) {
        void* mappedOut = nullptr;
        VkResult resMap = vkMapMemory(mDevice, mReadbackMem, 0, mFullW * mFullH * 4, 0, &mappedOut);
        if (resMap != VK_SUCCESS || mappedOut == nullptr) {
            LOGE("vkMapMemory failed on mReadbackMem: %d", resMap);
        }
        int fd = open(outFilePath, O_WRONLY | O_CREAT | O_TRUNC, 0666);
        if (fd >= 0 && mappedOut != nullptr) {
            write(fd, mappedOut, mFullW * mFullH * 4);
            close(fd);
            tFileClosed = std::chrono::high_resolution_clock::now();
            long writeMs = std::chrono::duration_cast<std::chrono::milliseconds>(tFileClosed - tBufferComplete).count();
            LOGI("Wrote %ux%u RGBA (%zu bytes) to %s in %ld ms", mFullW, mFullH, (size_t)(mFullW * mFullH * 4), outFilePath, writeMs);
        } else {
            LOGE("Failed to open %s for writing: %s", outFilePath, strerror(errno));
        }
        vkUnmapMemory(mDevice, mReadbackMem);
    }

    // 7. Release imported image
    mYuvConverter->releaseImportedImage(importedImg, importedMem, importedView);

    if (outTimestamps != nullptr) {
        outTimestamps[0] = std::chrono::duration_cast<std::chrono::nanoseconds>(tBufferComplete - tSubmit).count();
        outTimestamps[1] = std::chrono::duration_cast<std::chrono::nanoseconds>(tBufferComplete.time_since_epoch()).count();
        outTimestamps[2] = std::chrono::duration_cast<std::chrono::nanoseconds>(tFileClosed.time_since_epoch()).count();
    }

    return true;
}

void VulkanPipeline::destroy() {
    if (!mInitialized) return;
    if (mNpuWorker) {
        mNpuWorker->stop();
        mNpuWorker.reset();
    }
    mYuvConverter.reset();

    if (mStillBokehInitialized && mDevice != VK_NULL_HANDLE) {
        vkDestroyPipeline(mDevice, mPipeCoc, nullptr);
        vkDestroyPipeline(mDevice, mPipeGather, nullptr);
        vkDestroyPipeline(mDevice, mPipeComp, nullptr);
        vkDestroyPipelineLayout(mDevice, mPlCoc, nullptr);
        vkDestroyPipelineLayout(mDevice, mPlGather, nullptr);
        vkDestroyPipelineLayout(mDevice, mPlComp, nullptr);
        vkDestroyDescriptorSetLayout(mDevice, mDslCoc, nullptr);
        vkDestroyDescriptorSetLayout(mDevice, mDslGather, nullptr);
        vkDestroyDescriptorSetLayout(mDevice, mDslComp, nullptr);
        vkDestroyDescriptorPool(mDevice, mStillDescPool, nullptr);
        vkDestroyCommandPool(mDevice, mStillCmdPool, nullptr);
        vkDestroySampler(mDevice, mStillSampler, nullptr);
        vkDestroyImageView(mDevice, mViewDepth, nullptr);
        vkDestroyImageView(mDevice, mViewCoc, nullptr);
        vkDestroyImageView(mDevice, mViewAlpha, nullptr);
        vkDestroyImageView(mDevice, mViewBlurred, nullptr);
        vkDestroyImageView(mDevice, mViewOutFull, nullptr);
        vkDestroyImage(mDevice, mImgDepth, nullptr);
        vkDestroyImage(mDevice, mImgCoc, nullptr);
        vkDestroyImage(mDevice, mImgAlpha, nullptr);
        vkDestroyImage(mDevice, mImgBlurred, nullptr);
        vkDestroyImage(mDevice, mImgOutFull, nullptr);
        vkFreeMemory(mDevice, mMemDepth, nullptr);
        vkFreeMemory(mDevice, mMemCoc, nullptr);
        vkFreeMemory(mDevice, mMemAlpha, nullptr);
        vkFreeMemory(mDevice, mMemBlurred, nullptr);
        vkFreeMemory(mDevice, mMemOutFull, nullptr);
        if (mUploadStagingBuffer != VK_NULL_HANDLE) vkDestroyBuffer(mDevice, mUploadStagingBuffer, nullptr);
        if (mUploadStagingMem != VK_NULL_HANDLE) vkFreeMemory(mDevice, mUploadStagingMem, nullptr);
        if (mReadbackBuffer != VK_NULL_HANDLE) vkDestroyBuffer(mDevice, mReadbackBuffer, nullptr);
        if (mReadbackMem != VK_NULL_HANDLE) vkFreeMemory(mDevice, mReadbackMem, nullptr);
        if (mStillFence != VK_NULL_HANDLE) vkDestroyFence(mDevice, mStillFence, nullptr);
        if (mStillQueryPool != VK_NULL_HANDLE) vkDestroyQueryPool(mDevice, mStillQueryPool, nullptr);
        mStillBokehInitialized = false;
    }

    if (mSwapchainInitialized && mDevice != VK_NULL_HANDLE) {
        if (mPreviewQueryPool != VK_NULL_HANDLE) {
            vkDestroyQueryPool(mDevice, mPreviewQueryPool, nullptr);
            mPreviewQueryPool = VK_NULL_HANDLE;
        }
        if (mViewPreviewOut != VK_NULL_HANDLE) vkDestroyImageView(mDevice, mViewPreviewOut, nullptr);
        if (mImgPreviewOut != VK_NULL_HANDLE) vkDestroyImage(mDevice, mImgPreviewOut, nullptr);
        if (mMemPreviewOut != VK_NULL_HANDLE) vkFreeMemory(mDevice, mMemPreviewOut, nullptr);
        if (mPreviewCmdPool != VK_NULL_HANDLE) vkDestroyCommandPool(mDevice, mPreviewCmdPool, nullptr);
        mPreviewCmdPool = VK_NULL_HANDLE;
        for (int i = 0; i < MAX_FRAMES_IN_FLIGHT; ++i) {
            if (mImageAvailableSemaphores[i] != VK_NULL_HANDLE) vkDestroySemaphore(mDevice, mImageAvailableSemaphores[i], nullptr);
            if (mRenderFinishedSemaphores[i] != VK_NULL_HANDLE) vkDestroySemaphore(mDevice, mRenderFinishedSemaphores[i], nullptr);
            if (mRenderFences[i] != VK_NULL_HANDLE) vkDestroyFence(mDevice, mRenderFences[i], nullptr);
            mImageAvailableSemaphores[i] = VK_NULL_HANDLE;
            mRenderFinishedSemaphores[i] = VK_NULL_HANDLE;
            mRenderFences[i] = VK_NULL_HANDLE;
            mPreviewCmds[i] = VK_NULL_HANDLE;
        }
        if (mSwapchain != VK_NULL_HANDLE) vkDestroySwapchainKHR(mDevice, mSwapchain, nullptr);
        if (mSurface != VK_NULL_HANDLE) vkDestroySurfaceKHR(mInstance, mSurface, nullptr);
        mSwapchainInitialized = false;
    }

    if (mDevice != VK_NULL_HANDLE) {
        vkDestroyDevice(mDevice, nullptr);
        mDevice = VK_NULL_HANDLE;
    }
    if (mInstance != VK_NULL_HANDLE) {
        vkDestroyInstance(mInstance, nullptr);
        mInstance = VK_NULL_HANDLE;
    }
    mInitialized = false;
    LOGI("VulkanPipeline destroyed");
}
