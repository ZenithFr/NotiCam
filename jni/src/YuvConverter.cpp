#include "YuvConverter.h"
#include <android/log.h>
#include <cstring>

#define LOG_TAG "YuvConverter"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

YuvConverter::YuvConverter(VkDevice device, VkPhysicalDevice physicalDevice)
    : mDevice(device), mPhysicalDevice(physicalDevice) {}

YuvConverter::~YuvConverter() {
    if (mSampler != VK_NULL_HANDLE) {
        vkDestroySampler(mDevice, mSampler, nullptr);
    }
    if (mConversion != VK_NULL_HANDLE) {
        vkDestroySamplerYcbcrConversion(mDevice, mConversion, nullptr);
    }
}

bool YuvConverter::initConversionSampler() {
    if (mSampler != VK_NULL_HANDLE) return true;

    AHardwareBuffer_Desc desc{};
    desc.width = 64;
    desc.height = 64;
    desc.layers = 1;
    desc.format = AHARDWAREBUFFER_FORMAT_Y8Cb8Cr8_420;
    desc.usage = AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE | AHARDWAREBUFFER_USAGE_CPU_READ_NEVER;
    AHardwareBuffer* dummyBuffer = nullptr;
    if (AHardwareBuffer_allocate(&desc, &dummyBuffer) != 0) {
        LOGE("Failed to allocate dummy YUV AHardwareBuffer");
        return false;
    }

    VkAndroidHardwareBufferPropertiesANDROID hwbProps{};
    hwbProps.sType = VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_PROPERTIES_ANDROID;
    VkAndroidHardwareBufferFormatPropertiesANDROID hwbFormatProps{};
    hwbFormatProps.sType = VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_FORMAT_PROPERTIES_ANDROID;
    hwbProps.pNext = &hwbFormatProps;

    auto pfnGetAndroidHardwareBufferPropertiesANDROID =
        (PFN_vkGetAndroidHardwareBufferPropertiesANDROID)vkGetDeviceProcAddr(mDevice, "vkGetAndroidHardwareBufferPropertiesANDROID");
    if (!pfnGetAndroidHardwareBufferPropertiesANDROID) {
        LOGE("Missing vkGetAndroidHardwareBufferPropertiesANDROID");
        AHardwareBuffer_release(dummyBuffer);
        return false;
    }

    if (pfnGetAndroidHardwareBufferPropertiesANDROID(mDevice, dummyBuffer, &hwbProps) != VK_SUCCESS) {
        LOGE("Failed to get dummy AHB properties");
        AHardwareBuffer_release(dummyBuffer);
        return false;
    }

    VkExternalFormatANDROID extFormat{};
    extFormat.sType = VK_STRUCTURE_TYPE_EXTERNAL_FORMAT_ANDROID;
    extFormat.externalFormat = hwbFormatProps.externalFormat;

    VkSamplerYcbcrConversionCreateInfo convInfo{};
    convInfo.sType = VK_STRUCTURE_TYPE_SAMPLER_YCBCR_CONVERSION_CREATE_INFO;
    convInfo.pNext = &extFormat;
    convInfo.format = VK_FORMAT_UNDEFINED;
    convInfo.ycbcrModel = VK_SAMPLER_YCBCR_MODEL_CONVERSION_YCBCR_709;
    convInfo.ycbcrRange = VK_SAMPLER_YCBCR_RANGE_ITU_NARROW;
    convInfo.components.r = VK_COMPONENT_SWIZZLE_IDENTITY;
    convInfo.components.g = VK_COMPONENT_SWIZZLE_IDENTITY;
    convInfo.components.b = VK_COMPONENT_SWIZZLE_IDENTITY;
    convInfo.components.a = VK_COMPONENT_SWIZZLE_IDENTITY;
    convInfo.xChromaOffset = VK_CHROMA_LOCATION_MIDPOINT;
    convInfo.yChromaOffset = VK_CHROMA_LOCATION_MIDPOINT;
    convInfo.chromaFilter = VK_FILTER_LINEAR;
    convInfo.forceExplicitReconstruction = VK_FALSE;

    if (vkCreateSamplerYcbcrConversion(mDevice, &convInfo, nullptr, &mConversion) != VK_SUCCESS) {
        LOGE("Failed to create eager VkSamplerYcbcrConversion with externalFormat %llu", (unsigned long long)hwbFormatProps.externalFormat);
        AHardwareBuffer_release(dummyBuffer);
        return false;
    }

    VkSamplerYcbcrConversionInfo samplerConvInfo{};
    samplerConvInfo.sType = VK_STRUCTURE_TYPE_SAMPLER_YCBCR_CONVERSION_INFO;
    samplerConvInfo.conversion = mConversion;

    VkSamplerCreateInfo samplerInfo{};
    samplerInfo.sType = VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO;
    samplerInfo.pNext = &samplerConvInfo;
    samplerInfo.magFilter = VK_FILTER_LINEAR;
    samplerInfo.minFilter = VK_FILTER_LINEAR;
    samplerInfo.mipmapMode = VK_SAMPLER_MIPMAP_MODE_NEAREST;
    samplerInfo.addressModeU = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    samplerInfo.addressModeV = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    samplerInfo.addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    samplerInfo.borderColor = VK_BORDER_COLOR_FLOAT_OPAQUE_BLACK;
    samplerInfo.unnormalizedCoordinates = VK_FALSE;

    if (vkCreateSampler(mDevice, &samplerInfo, nullptr, &mSampler) != VK_SUCCESS) {
        LOGE("Failed to create eager YCbCr VkSampler");
        AHardwareBuffer_release(dummyBuffer);
        return false;
    }

    LOGI("YuvConverter initialized eager conversion & sampler with externalFormat %llu", (unsigned long long)hwbFormatProps.externalFormat);
    AHardwareBuffer_release(dummyBuffer);
    return true;
}

bool YuvConverter::importHardwareBuffer(AHardwareBuffer* buffer, VkImage* outImage, VkDeviceMemory* outMemory, VkImageView* outView) {
    AHardwareBuffer_Desc desc;
    AHardwareBuffer_describe(buffer, &desc);

    VkAndroidHardwareBufferPropertiesANDROID hwbProps{};
    hwbProps.sType = VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_PROPERTIES_ANDROID;

    auto pfnGetAndroidHardwareBufferPropertiesANDROID =
        (PFN_vkGetAndroidHardwareBufferPropertiesANDROID)vkGetDeviceProcAddr(mDevice, "vkGetAndroidHardwareBufferPropertiesANDROID");
    if (!pfnGetAndroidHardwareBufferPropertiesANDROID) {
        LOGE("Missing vkGetAndroidHardwareBufferPropertiesANDROID");
        return false;
    }

    if (pfnGetAndroidHardwareBufferPropertiesANDROID(mDevice, buffer, &hwbProps) != VK_SUCCESS) {
        LOGE("Failed to get AHB properties");
        return false;
    }

    VkAndroidHardwareBufferFormatPropertiesANDROID hwbFormatProps{};
    hwbFormatProps.sType = VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_FORMAT_PROPERTIES_ANDROID;
    hwbProps.pNext = &hwbFormatProps;
    pfnGetAndroidHardwareBufferPropertiesANDROID(mDevice, buffer, &hwbProps);

    VkExternalFormatANDROID extFormat{};
    extFormat.sType = VK_STRUCTURE_TYPE_EXTERNAL_FORMAT_ANDROID;
    extFormat.externalFormat = hwbFormatProps.externalFormat;

    VkExternalMemoryImageCreateInfo extMemImageInfo{};
    extMemImageInfo.sType = VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO;
    extMemImageInfo.pNext = &extFormat;
    extMemImageInfo.handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID;

    VkImageCreateInfo imgInfo{};
    imgInfo.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
    imgInfo.pNext = &extMemImageInfo;
    imgInfo.imageType = VK_IMAGE_TYPE_2D;
    imgInfo.format = VK_FORMAT_UNDEFINED;
    imgInfo.extent = { desc.width, desc.height, 1 };
    imgInfo.mipLevels = 1;
    imgInfo.arrayLayers = 1;
    imgInfo.samples = VK_SAMPLE_COUNT_1_BIT;
    imgInfo.tiling = VK_IMAGE_TILING_OPTIMAL;
    imgInfo.usage = VK_IMAGE_USAGE_SAMPLED_BIT;
    imgInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    imgInfo.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;

    if (vkCreateImage(mDevice, &imgInfo, nullptr, outImage) != VK_SUCCESS) {
        LOGE("Failed to create VkImage for AHB");
        return false;
    }

    VkImportAndroidHardwareBufferInfoANDROID importInfo{};
    importInfo.sType = VK_STRUCTURE_TYPE_IMPORT_ANDROID_HARDWARE_BUFFER_INFO_ANDROID;
    importInfo.buffer = buffer;

    VkMemoryDedicatedAllocateInfo dedicatedInfo{};
    dedicatedInfo.sType = VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO;
    dedicatedInfo.pNext = &importInfo;
    dedicatedInfo.image = *outImage;

    VkMemoryAllocateInfo allocInfo{};
    allocInfo.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    allocInfo.pNext = &dedicatedInfo;
    allocInfo.allocationSize = hwbProps.allocationSize;

    // Find suitable memory type index
    uint32_t memTypeBits = hwbProps.memoryTypeBits;
    uint32_t memTypeIndex = 0;
    for (uint32_t i = 0; i < 32; ++i) {
        if ((memTypeBits & (1 << i)) != 0) {
            memTypeIndex = i;
            break;
        }
    }
    allocInfo.memoryTypeIndex = memTypeIndex;

    if (vkAllocateMemory(mDevice, &allocInfo, nullptr, outMemory) != VK_SUCCESS) {
        LOGE("Failed to allocate device memory for AHB");
        vkDestroyImage(mDevice, *outImage, nullptr);
        return false;
    }

    if (vkBindImageMemory(mDevice, *outImage, *outMemory, 0) != VK_SUCCESS) {
        LOGE("Failed to bind AHB memory to VkImage");
        vkFreeMemory(mDevice, *outMemory, nullptr);
        vkDestroyImage(mDevice, *outImage, nullptr);
        return false;
    }

    if (mConversion == VK_NULL_HANDLE) {
        VkSamplerYcbcrConversionCreateInfo convInfo{};
        convInfo.sType = VK_STRUCTURE_TYPE_SAMPLER_YCBCR_CONVERSION_CREATE_INFO;
        convInfo.pNext = &extFormat;
        convInfo.format = VK_FORMAT_UNDEFINED;
        convInfo.ycbcrModel = VK_SAMPLER_YCBCR_MODEL_CONVERSION_YCBCR_709;
        convInfo.ycbcrRange = VK_SAMPLER_YCBCR_RANGE_ITU_NARROW;
        convInfo.components.r = VK_COMPONENT_SWIZZLE_IDENTITY;
        convInfo.components.g = VK_COMPONENT_SWIZZLE_IDENTITY;
        convInfo.components.b = VK_COMPONENT_SWIZZLE_IDENTITY;
        convInfo.components.a = VK_COMPONENT_SWIZZLE_IDENTITY;
        convInfo.xChromaOffset = VK_CHROMA_LOCATION_MIDPOINT;
        convInfo.yChromaOffset = VK_CHROMA_LOCATION_MIDPOINT;
        convInfo.chromaFilter = VK_FILTER_LINEAR;
        convInfo.forceExplicitReconstruction = VK_FALSE;

        if (vkCreateSamplerYcbcrConversion(mDevice, &convInfo, nullptr, &mConversion) != VK_SUCCESS) {
            LOGE("Failed to create VkSamplerYcbcrConversion with externalFormat %llu", (unsigned long long)hwbFormatProps.externalFormat);
            return false;
        }

        VkSamplerYcbcrConversionInfo samplerConvInfo{};
        samplerConvInfo.sType = VK_STRUCTURE_TYPE_SAMPLER_YCBCR_CONVERSION_INFO;
        samplerConvInfo.conversion = mConversion;

        VkSamplerCreateInfo samplerInfo{};
        samplerInfo.sType = VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO;
        samplerInfo.pNext = &samplerConvInfo;
        samplerInfo.magFilter = VK_FILTER_LINEAR;
        samplerInfo.minFilter = VK_FILTER_LINEAR;
        samplerInfo.mipmapMode = VK_SAMPLER_MIPMAP_MODE_NEAREST;
        samplerInfo.addressModeU = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
        samplerInfo.addressModeV = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
        samplerInfo.addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
        samplerInfo.borderColor = VK_BORDER_COLOR_FLOAT_OPAQUE_BLACK;
        samplerInfo.unnormalizedCoordinates = VK_FALSE;

        if (vkCreateSampler(mDevice, &samplerInfo, nullptr, &mSampler) != VK_SUCCESS) {
            LOGE("Failed to create YCbCr VkSampler");
            return false;
        }
        LOGI("Vulkan YCbCr conversion and sampler initialized with externalFormat %llu", (unsigned long long)hwbFormatProps.externalFormat);
    }

    VkSamplerYcbcrConversionInfo viewConvInfo{};
    viewConvInfo.sType = VK_STRUCTURE_TYPE_SAMPLER_YCBCR_CONVERSION_INFO;
    viewConvInfo.conversion = mConversion;

    VkImageViewCreateInfo viewInfo{};
    viewInfo.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
    viewInfo.pNext = &viewConvInfo;
    viewInfo.image = *outImage;
    viewInfo.viewType = VK_IMAGE_VIEW_TYPE_2D;
    viewInfo.format = VK_FORMAT_UNDEFINED;
    viewInfo.components.r = VK_COMPONENT_SWIZZLE_IDENTITY;
    viewInfo.components.g = VK_COMPONENT_SWIZZLE_IDENTITY;
    viewInfo.components.b = VK_COMPONENT_SWIZZLE_IDENTITY;
    viewInfo.components.a = VK_COMPONENT_SWIZZLE_IDENTITY;
    viewInfo.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    viewInfo.subresourceRange.baseMipLevel = 0;
    viewInfo.subresourceRange.levelCount = 1;
    viewInfo.subresourceRange.baseArrayLayer = 0;
    viewInfo.subresourceRange.layerCount = 1;

    if (vkCreateImageView(mDevice, &viewInfo, nullptr, outView) != VK_SUCCESS) {
        LOGE("Failed to create VkImageView for AHB");
        vkFreeMemory(mDevice, *outMemory, nullptr);
        vkDestroyImage(mDevice, *outImage, nullptr);
        return false;
    }

    return true;
}

void YuvConverter::releaseImportedImage(VkImage image, VkDeviceMemory memory, VkImageView view) {
    if (view != VK_NULL_HANDLE) vkDestroyImageView(mDevice, view, nullptr);
    if (image != VK_NULL_HANDLE) vkDestroyImage(mDevice, image, nullptr);
    if (memory != VK_NULL_HANDLE) vkFreeMemory(mDevice, memory, nullptr);
}
