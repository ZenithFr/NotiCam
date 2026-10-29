#pragma once

#include <vulkan/vulkan.h>
#include <vulkan/vulkan_android.h>
#include <android/hardware_buffer.h>

class YuvConverter {
public:
    YuvConverter(VkDevice device, VkPhysicalDevice physicalDevice);
    ~YuvConverter();

    bool initConversionSampler();
    bool importHardwareBuffer(AHardwareBuffer* buffer, VkImage* outImage, VkDeviceMemory* outMemory, VkImageView* outView);
    void releaseImportedImage(VkImage image, VkDeviceMemory memory, VkImageView view);

    VkSampler getSampler() const { return mSampler; }
    VkSamplerYcbcrConversion getConversion() const { return mConversion; }

private:
    VkDevice mDevice;
    VkPhysicalDevice mPhysicalDevice;
    VkSamplerYcbcrConversion mConversion{VK_NULL_HANDLE};
    VkSampler mSampler{VK_NULL_HANDLE};
};
