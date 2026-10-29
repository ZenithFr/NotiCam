#include <vulkan/vulkan.h>
#include <cstdio>
#include <cstdlib>
#include <vector>
#include <cmath>
#include <cassert>
#include <fstream>

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

static uint32_t findMemoryType(VkPhysicalDevice physDev, uint32_t typeFilter, VkMemoryPropertyFlags properties) {
    VkPhysicalDeviceMemoryProperties memProperties;
    vkGetPhysicalDeviceMemoryProperties(physDev, &memProperties);
    for (uint32_t i = 0; i < memProperties.memoryTypeCount; i++) {
        if ((typeFilter & (1 << i)) && (memProperties.memoryTypes[i].propertyFlags & properties) == properties) {
            return i;
        }
    }
    return 0;
}

int main(int argc, char** argv) {
    printf("=== Starting Native Vulkan GPU Readback Test for coc.comp ===\n");
    const char* spvPath = (argc > 1) ? argv[1] : "/data/local/tmp/coc.spv";

    std::ifstream file(spvPath, std::ios::binary | std::ios::ate);
    if (!file.is_open()) {
        printf("FAIL: Could not open SPIR-V file at: %s\n", spvPath);
        return 1;
    }
    size_t fileSize = (size_t)file.tellg();
    std::vector<char> spvBuffer(fileSize);
    file.seekg(0);
    file.read(spvBuffer.data(), fileSize);
    file.close();
    printf("Loaded SPIR-V: %zu bytes from %s\n", fileSize, spvPath);

    // 1. Create Vulkan 1.3 Instance
    VkApplicationInfo appInfo{};
    appInfo.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    appInfo.pApplicationName = "CoCGpuTest";
    appInfo.apiVersion = VK_API_VERSION_1_3;

    VkInstanceCreateInfo instInfo{};
    instInfo.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    instInfo.pApplicationInfo = &appInfo;

    VkInstance instance;
    if (vkCreateInstance(&instInfo, nullptr, &instance) != VK_SUCCESS) {
        printf("FAIL: vkCreateInstance failed\n");
        return 1;
    }

    uint32_t devCount = 0;
    vkEnumeratePhysicalDevices(instance, &devCount, nullptr);
    if (devCount == 0) {
        printf("FAIL: No Vulkan physical devices\n");
        return 1;
    }
    std::vector<VkPhysicalDevice> physDevices(devCount);
    vkEnumeratePhysicalDevices(instance, &devCount, physDevices.data());
    VkPhysicalDevice physDev = physDevices[0];

    VkPhysicalDeviceProperties props;
    vkGetPhysicalDeviceProperties(physDev, &props);
    printf("Selected GPU: %s (Vulkan %d.%d.%d)\n", props.deviceName,
           VK_VERSION_MAJOR(props.apiVersion), VK_VERSION_MINOR(props.apiVersion), VK_VERSION_PATCH(props.apiVersion));

    // Find compute queue
    uint32_t qfCount = 0;
    vkGetPhysicalDeviceQueueFamilyProperties(physDev, &qfCount, nullptr);
    std::vector<VkQueueFamilyProperties> qfProps(qfCount);
    vkGetPhysicalDeviceQueueFamilyProperties(physDev, &qfCount, qfProps.data());
    uint32_t computeQfIndex = 0;
    for (uint32_t i = 0; i < qfCount; ++i) {
        if (qfProps[i].queueFlags & VK_QUEUE_COMPUTE_BIT) {
            computeQfIndex = i;
            break;
        }
    }

    float qPriority = 1.0f;
    VkDeviceQueueCreateInfo qInfo{};
    qInfo.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
    qInfo.queueFamilyIndex = computeQfIndex;
    qInfo.queueCount = 1;
    qInfo.pQueuePriorities = &qPriority;

    VkDeviceCreateInfo devInfo{};
    devInfo.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;
    devInfo.queueCreateInfoCount = 1;
    devInfo.pQueueCreateInfos = &qInfo;

    VkDevice device;
    if (vkCreateDevice(physDev, &devInfo, nullptr, &device) != VK_SUCCESS) {
        printf("FAIL: vkCreateDevice failed\n");
        return 1;
    }

    VkQueue queue;
    vkGetDeviceQueue(device, computeQfIndex, 0, &queue);

    // 2. Create Storage Images:
    // Binding 0: Depth image (R32G32B32A32_SFLOAT or R32_SFLOAT)
    // Binding 1: Output CoC image (R16_SFLOAT)
    const uint32_t W = 16, H = 16;

    VkImage depthImage, cocImage;
    VkDeviceMemory depthMem, cocMem;

    VkImageCreateInfo imgInfo{};
    imgInfo.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
    imgInfo.imageType = VK_IMAGE_TYPE_2D;
    imgInfo.extent.width = W;
    imgInfo.extent.height = H;
    imgInfo.extent.depth = 1;
    imgInfo.mipLevels = 1;
    imgInfo.arrayLayers = 1;
    imgInfo.tiling = VK_IMAGE_TILING_LINEAR;
    imgInfo.initialLayout = VK_IMAGE_LAYOUT_PREINITIALIZED;
    imgInfo.usage = VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_STORAGE_BIT;
    imgInfo.samples = VK_SAMPLE_COUNT_1_BIT;

    // Depth: R32_SFLOAT
    imgInfo.format = VK_FORMAT_R32_SFLOAT;
    vkCreateImage(device, &imgInfo, nullptr, &depthImage);

    VkMemoryRequirements memReqs;
    vkGetImageMemoryRequirements(device, depthImage, &memReqs);
    VkMemoryAllocateInfo allocInfo{};
    allocInfo.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    allocInfo.allocationSize = memReqs.size;
    allocInfo.memoryTypeIndex = findMemoryType(physDev, memReqs.memoryTypeBits,
        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
    vkAllocateMemory(device, &allocInfo, nullptr, &depthMem);
    vkBindImageMemory(device, depthImage, depthMem, 0);

    // CoC: R16_SFLOAT (Storage image)
    imgInfo.format = VK_FORMAT_R16_SFLOAT;
    imgInfo.usage = VK_IMAGE_USAGE_STORAGE_BIT;
    vkCreateImage(device, &imgInfo, nullptr, &cocImage);
    vkGetImageMemoryRequirements(device, cocImage, &memReqs);
    allocInfo.allocationSize = memReqs.size;
    allocInfo.memoryTypeIndex = findMemoryType(physDev, memReqs.memoryTypeBits,
        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
    vkAllocateMemory(device, &allocInfo, nullptr, &cocMem);
    vkBindImageMemory(device, cocImage, cocMem, 0);

    // Populate depth image on host
    // Pixel (0, 0): S2 = 5.0m
    // Pixel (1, 0): S2 = 1000.0m (inf)
    // Pixel (2, 0): S2 = 0.6m (focus plane S1)
    void* data;
    vkMapMemory(device, depthMem, 0, W * H * sizeof(float), 0, &data);
    float* depthPixels = reinterpret_cast<float*>(data);
    for (uint32_t i = 0; i < W * H; ++i) depthPixels[i] = 5.0f;
    depthPixels[0] = 5.0f;    // Background test point (5m)
    depthPixels[1] = 1000.0f; // Asymptotic far-field (inf)
    depthPixels[2] = 0.6f;    // In-focus plane
    depthPixels[3] = 0.3f;    // Foreground test point (S2 < S1 near-field suppression)
    vkUnmapMemory(device, depthMem);

    // Views & Sampler
    VkImageView depthView, cocView;
    VkImageViewCreateInfo viewInfo{};
    viewInfo.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
    viewInfo.viewType = VK_IMAGE_VIEW_TYPE_2D;
    viewInfo.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    viewInfo.subresourceRange.levelCount = 1;
    viewInfo.subresourceRange.layerCount = 1;

    viewInfo.image = depthImage;
    viewInfo.format = VK_FORMAT_R32_SFLOAT;
    vkCreateImageView(device, &viewInfo, nullptr, &depthView);

    viewInfo.image = cocImage;
    viewInfo.format = VK_FORMAT_R16_SFLOAT;
    vkCreateImageView(device, &viewInfo, nullptr, &cocView);

    VkSampler sampler;
    VkSamplerCreateInfo sampInfo{};
    sampInfo.sType = VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO;
    sampInfo.magFilter = VK_FILTER_LINEAR;
    sampInfo.minFilter = VK_FILTER_LINEAR;
    sampInfo.mipmapMode = VK_SAMPLER_MIPMAP_MODE_NEAREST;
    sampInfo.addressModeU = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    sampInfo.addressModeV = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    sampInfo.addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    vkCreateSampler(device, &sampInfo, nullptr, &sampler);

    // Shader Module & Compute Pipeline
    VkShaderModuleCreateInfo smInfo{};
    smInfo.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
    smInfo.codeSize = spvBuffer.size();
    smInfo.pCode = reinterpret_cast<const uint32_t*>(spvBuffer.data());
    VkShaderModule shaderModule;
    if (vkCreateShaderModule(device, &smInfo, nullptr, &shaderModule) != VK_SUCCESS) {
        printf("FAIL: vkCreateShaderModule failed\n");
        return 1;
    }

    // Descriptor Set Layout
    VkDescriptorSetLayoutBinding bindings[2]{};
    bindings[0].binding = 0;
    bindings[0].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    bindings[0].descriptorCount = 1;
    bindings[0].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    bindings[0].pImmutableSamplers = &sampler;

    bindings[1].binding = 1;
    bindings[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    bindings[1].descriptorCount = 1;
    bindings[1].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;

    VkDescriptorSetLayoutCreateInfo dslInfo{};
    dslInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
    dslInfo.bindingCount = 2;
    dslInfo.pBindings = bindings;
    VkDescriptorSetLayout descLayout;
    vkCreateDescriptorSetLayout(device, &dslInfo, nullptr, &descLayout);

    VkPushConstantRange pcRange{};
    pcRange.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    pcRange.offset = 0;
    pcRange.size = sizeof(CocPushConstants);

    VkPipelineLayoutCreateInfo plInfo{};
    plInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
    plInfo.setLayoutCount = 1;
    plInfo.pSetLayouts = &descLayout;
    plInfo.pushConstantRangeCount = 1;
    plInfo.pPushConstantRanges = &pcRange;
    VkPipelineLayout pipelineLayout;
    vkCreatePipelineLayout(device, &plInfo, nullptr, &pipelineLayout);

    VkComputePipelineCreateInfo cpInfo{};
    cpInfo.sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO;
    cpInfo.stage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    cpInfo.stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
    cpInfo.stage.module = shaderModule;
    cpInfo.stage.pName = "main";
    cpInfo.layout = pipelineLayout;
    VkPipeline pipeline;
    vkCreateComputePipelines(device, VK_NULL_HANDLE, 1, &cpInfo, nullptr, &pipeline);

    // Descriptor Pool & Set
    VkDescriptorPoolSize poolSizes[2]{};
    poolSizes[0].type = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    poolSizes[0].descriptorCount = 1;
    poolSizes[1].type = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    poolSizes[1].descriptorCount = 1;

    VkDescriptorPoolCreateInfo dpInfo{};
    dpInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
    dpInfo.maxSets = 1;
    dpInfo.poolSizeCount = 2;
    dpInfo.pPoolSizes = poolSizes;
    VkDescriptorPool descPool;
    vkCreateDescriptorPool(device, &dpInfo, nullptr, &descPool);

    VkDescriptorSetAllocateInfo dsAlloc{};
    dsAlloc.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
    dsAlloc.descriptorPool = descPool;
    dsAlloc.descriptorSetCount = 1;
    dsAlloc.pSetLayouts = &descLayout;
    VkDescriptorSet descSet;
    vkAllocateDescriptorSets(device, &dsAlloc, &descSet);

    VkDescriptorImageInfo descImg0{};
    descImg0.sampler = sampler;
    descImg0.imageView = depthView;
    descImg0.imageLayout = VK_IMAGE_LAYOUT_GENERAL;

    VkDescriptorImageInfo descImg1{};
    descImg1.imageView = cocView;
    descImg1.imageLayout = VK_IMAGE_LAYOUT_GENERAL;

    VkWriteDescriptorSet writes[2]{};
    writes[0].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[0].dstSet = descSet;
    writes[0].dstBinding = 0;
    writes[0].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    writes[0].descriptorCount = 1;
    writes[0].pImageInfo = &descImg0;

    writes[1].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[1].dstSet = descSet;
    writes[1].dstBinding = 1;
    writes[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    writes[1].descriptorCount = 1;
    writes[1].pImageInfo = &descImg1;
    vkUpdateDescriptorSets(device, 2, writes, 0, nullptr);

    // Command Pool & Buffer
    VkCommandPoolCreateInfo cmdPoolInfo{};
    cmdPoolInfo.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
    cmdPoolInfo.queueFamilyIndex = computeQfIndex;
    VkCommandPool cmdPool;
    vkCreateCommandPool(device, &cmdPoolInfo, nullptr, &cmdPool);

    VkCommandBufferAllocateInfo cbAlloc{};
    cbAlloc.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
    cbAlloc.commandPool = cmdPool;
    cbAlloc.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    cbAlloc.commandBufferCount = 1;
    VkCommandBuffer cmd;
    vkAllocateCommandBuffers(device, &cbAlloc, &cmd);

    // Dispatch Shader with Push Constants
    // Test parameters: S1 = 0.6m, targetFarFieldPx = 15.0px @ 720w
    CocPushConstants pc{};
    pc.width = W;
    pc.height = H;
    pc.focalMm = 5.56f;
    pc.fStop = 1.8f;
    pc.sensorWidthMm = 8.16f;
    pc.s1_focus_m = 0.6f;
    pc.targetFarFieldPx = 15.0f;
    pc.previewWidth = 720.0f;

    VkCommandBufferBeginInfo beginInfo{};
    beginInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    vkBeginCommandBuffer(cmd, &beginInfo);

    vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
    vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout, 0, 1, &descSet, 0, nullptr);
    vkCmdPushConstants(cmd, pipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(CocPushConstants), &pc);
    vkCmdDispatch(cmd, 1, 1, 1);

    vkEndCommandBuffer(cmd);

    VkSubmitInfo submitInfo{};
    submitInfo.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    submitInfo.commandBufferCount = 1;
    submitInfo.pCommandBuffers = &cmd;
    vkQueueSubmit(queue, 1, &submitInfo, VK_NULL_HANDLE);
    vkQueueWaitIdle(queue);

    // Readback from GPU Memory
    void* cocData;
    vkMapMemory(device, cocMem, 0, W * H * sizeof(uint16_t), 0, &cocData);
    const uint16_t* halfPixels = reinterpret_cast<const uint16_t*>(cocData);

    // Helper to convert FP16 to FP32
    auto fp16_to_fp32 = [](uint16_t h) -> float {
        uint32_t w = (h & 0x7fff) << 13;
        uint32_t v = (h & 0x8000) << 16;
        if ((h & 0x7c00) == 0x7c00) w = 0x3f800000;
        else if ((h & 0x7c00) != 0) w += 0x38000000;
        uint32_t res = v | w;
        float f;
        memcpy(&f, &res, 4);
        return f;
    };

    float r_norm_5m = fp16_to_fp32(halfPixels[0]);
    float r_norm_inf = fp16_to_fp32(halfPixels[1]);
    float r_norm_focus = fp16_to_fp32(halfPixels[2]);
    float r_norm_foreground = fp16_to_fp32(halfPixels[3]);

    printf("GPU Readback CoC (Normalized Fraction of Frame Width):\n");
    printf("  S2=5.0m       : r_norm = %f (approx %f px @ 720w)\n", r_norm_5m, r_norm_5m * 720.0f);
    printf("  S2=inf        : r_norm = %f (approx %f px @ 720w)\n", r_norm_inf, r_norm_inf * 720.0f);
    printf("  S2=focus      : r_norm = %f (expected 0.0 px)\n", r_norm_focus);
    printf("  S2=0.3m (fore): r_norm = %f (expected 0.0 px - near-field suppressed)\n", r_norm_foreground);

    vkUnmapMemory(device, cocMem);

    // Assertions against shader execution:
    // At S1=0.6m, S2=5.0m: ratio = 4.4 / 5.0 = 0.88.
    // targetFarFieldPx = 15.0px @ 720w => r_inf = 15.0/720 = 0.020833
    // Expected r_norm @ 5.0m = 0.020833 * 0.88 = 0.018333 => 13.20 px @ 720w
    float expected_5m_norm = (15.0f / 720.0f) * (4.4f / 5.0f);
    float expected_inf_norm = 15.0f / 720.0f;

    printf("Verification against GPU Compute Execution:\n");
    printf("  Diff at 5.0m: %f\n", std::abs(r_norm_5m - expected_5m_norm));
    printf("  Diff at inf : %f\n", std::abs(r_norm_inf - expected_inf_norm));

    assert(std::abs(r_norm_5m - expected_5m_norm) < 0.001f);
    assert(std::abs(r_norm_inf - expected_inf_norm) < 0.001f);
    assert(std::abs(r_norm_focus) < 0.001f);
    assert(std::abs(r_norm_foreground) < 0.001f);

    printf("✅ GPU READBACK TEST PASSED: Shader executed directly on %s produces exact expected CoC values!\n", props.deviceName);

    // Cleanup
    vkDestroyPipeline(device, pipeline, nullptr);
    vkDestroyPipelineLayout(device, pipelineLayout, nullptr);
    vkDestroyDescriptorPool(device, descPool, nullptr);
    vkDestroyDescriptorSetLayout(device, descLayout, nullptr);
    vkDestroyShaderModule(device, shaderModule, nullptr);
    vkDestroySampler(device, sampler, nullptr);
    vkDestroyImageView(device, depthView, nullptr);
    vkDestroyImageView(device, cocView, nullptr);
    vkDestroyImage(device, depthImage, nullptr);
    vkDestroyImage(device, cocImage, nullptr);
    vkFreeMemory(device, depthMem, nullptr);
    vkFreeMemory(device, cocMem, nullptr);
    vkDestroyCommandPool(device, cmdPool, nullptr);
    vkDestroyDevice(device, nullptr);
    vkDestroyInstance(instance, nullptr);
    return 0;
}
