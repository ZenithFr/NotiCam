#include <vulkan/vulkan.h>
#include <stdio.h>
#include <stdlib.h>
#include <vector>
#include <string.h>

int main() {
    VkApplicationInfo appInfo{};
    appInfo.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    appInfo.pApplicationName = "GpuClockProbe";
    appInfo.apiVersion = VK_API_VERSION_1_3;

    VkInstanceCreateInfo instInfo{};
    instInfo.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    instInfo.pApplicationInfo = &appInfo;

    VkInstance instance = VK_NULL_HANDLE;
    if (vkCreateInstance(&instInfo, nullptr, &instance) != VK_SUCCESS) {
        printf("Failed to create Vulkan instance\n");
        return 1;
    }

    uint32_t devCount = 0;
    vkEnumeratePhysicalDevices(instance, &devCount, nullptr);
    if (devCount == 0) {
        printf("No physical devices found\n");
        return 1;
    }

    std::vector<VkPhysicalDevice> devs(devCount);
    vkEnumeratePhysicalDevices(instance, &devCount, devs.data());
    VkPhysicalDevice phys = devs[0];

    VkPhysicalDeviceProperties props;
    vkGetPhysicalDeviceProperties(phys, &props);
    printf("Device: %s\n", props.deviceName);
    printf("Driver: %u.%u.%u\n", VK_VERSION_MAJOR(props.driverVersion), VK_VERSION_MINOR(props.driverVersion), VK_VERSION_PATCH(props.driverVersion));
    printf("Timestamp Timer Period: %.5f ns/tick\n", props.limits.timestampPeriod);

    uint32_t extCount = 0;
    vkEnumerateDeviceExtensionProperties(phys, nullptr, &extCount, nullptr);
    std::vector<VkExtensionProperties> exts(extCount);
    vkEnumerateDeviceExtensionProperties(phys, nullptr, &extCount, exts.data());

    printf("\nSupported Extensions (%u total):\n", extCount);
    bool hasPerfQuery = false;
    bool hasCalibratedTimestamps = false;
    for (const auto& e : exts) {
        if (strstr(e.extensionName, "performance") || strstr(e.extensionName, "query") || strstr(e.extensionName, "time") || strstr(e.extensionName, "clock")) {
            printf("  -> %s (spec %u)\n", e.extensionName, e.specVersion);
        }
        if (strcmp(e.extensionName, "VK_KHR_performance_query") == 0) hasPerfQuery = true;
        if (strcmp(e.extensionName, "VK_EXT_calibrated_timestamps") == 0) hasCalibratedTimestamps = true;
    }
    printf("VK_KHR_performance_query: %s\n", hasPerfQuery ? "SUPPORTED" : "UNSUPPORTED");
    printf("VK_EXT_calibrated_timestamps: %s\n", hasCalibratedTimestamps ? "SUPPORTED" : "UNSUPPORTED");

    vkDestroyInstance(instance, nullptr);
    return 0;
}
