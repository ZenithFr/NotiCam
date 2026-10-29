#include <vulkan/vulkan.h>
#include <cstdio>
#include <cstdlib>
#include <vector>
#include <chrono>
#include <cmath>
#include <fstream>
#include <cstring>
#include <algorithm>

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

struct CompositePushConstants {
    uint32_t outWidth;
    uint32_t outHeight;
    int32_t rotate90;
};

static uint32_t findMemType(VkPhysicalDevice physDev, uint32_t typeFilter, VkMemoryPropertyFlags props) {
    VkPhysicalDeviceMemoryProperties memProperties;
    vkGetPhysicalDeviceMemoryProperties(physDev, &memProperties);
    for (uint32_t i = 0; i < memProperties.memoryTypeCount; i++) {
        if ((typeFilter & (1 << i)) && (memProperties.memoryTypes[i].propertyFlags & props) == props) {
            return i;
        }
    }
    return 0;
}

static std::vector<char> loadSpv(const char* path) {
    std::ifstream file(path, std::ios::binary | std::ios::ate);
    if (!file.is_open()) return {};
    size_t size = (size_t)file.tellg();
    std::vector<char> buffer(size);
    file.seekg(0);
    file.read(buffer.data(), size);
    return buffer;
}

int main(int argc, char** argv) {
    const uint32_t W_work = (argc > 1) ? std::atoi(argv[1]) : 1020;
    const uint32_t H_work = (argc > 2) ? std::atoi(argv[2]) : 768;
    const uint32_t W_full = 4080;
    const uint32_t H_full = 3072;

    printf("===================================================================\n");
    printf("=== STILL BOKEH PHYSICAL GPU BENCHMARK & HARDWARE TELEMETRY ===\n");
    printf("Working Resolution: %ux%u | Composite Full Resolution: %ux%u\n", W_work, H_work, W_full, H_full);
    printf("===================================================================\n");

    // 1. Initialize Vulkan 1.3
    VkApplicationInfo appInfo{ VK_STRUCTURE_TYPE_APPLICATION_INFO };
    appInfo.pApplicationName = "StillBokehBenchmark";
    appInfo.apiVersion = VK_API_VERSION_1_3;

    VkInstanceCreateInfo instInfo{ VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO };
    instInfo.pApplicationInfo = &appInfo;

    VkInstance instance;
    if (vkCreateInstance(&instInfo, nullptr, &instance) != VK_SUCCESS) {
        printf("FAIL: vkCreateInstance\n");
        return 1;
    }

    uint32_t devCount = 0;
    vkEnumeratePhysicalDevices(instance, &devCount, nullptr);
    std::vector<VkPhysicalDevice> devs(devCount);
    vkEnumeratePhysicalDevices(instance, &devCount, devs.data());
    VkPhysicalDevice physDev = devs[0];

    VkPhysicalDeviceProperties props;
    vkGetPhysicalDeviceProperties(physDev, &props);
    const double timestampPeriodNs = props.limits.timestampPeriod;
    printf("Target GPU: %s (Driver: %u.%u.%u)\n", props.deviceName,
           VK_VERSION_MAJOR(props.driverVersion), VK_VERSION_MINOR(props.driverVersion), VK_VERSION_PATCH(props.driverVersion));
    printf("Timestamp Timer Period: %.5f ns/tick (Valid Bits: %u)\n", timestampPeriodNs, props.limits.timestampComputeAndGraphics);

    uint32_t qfCount = 0;
    vkGetPhysicalDeviceQueueFamilyProperties(physDev, &qfCount, nullptr);
    std::vector<VkQueueFamilyProperties> qfProps(qfCount);
    vkGetPhysicalDeviceQueueFamilyProperties(physDev, &qfCount, qfProps.data());
    uint32_t qf = 0;
    for (uint32_t i = 0; i < qfCount; ++i) {
        if (qfProps[i].queueFlags & VK_QUEUE_COMPUTE_BIT) { qf = i; break; }
    }

    float qPriority = 1.0f;
    VkDeviceQueueCreateInfo qInfo{ VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO };
    qInfo.queueFamilyIndex = qf;
    qInfo.queueCount = 1;
    qInfo.pQueuePriorities = &qPriority;

    VkDeviceCreateInfo devInfo{ VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO };
    devInfo.queueCreateInfoCount = 1;
    devInfo.pQueueCreateInfos = &qInfo;

    VkDevice device;
    if (vkCreateDevice(physDev, &devInfo, nullptr, &device) != VK_SUCCESS) {
        printf("FAIL: vkCreateDevice\n");
        return 1;
    }

    VkQueue queue;
    vkGetDeviceQueue(device, qf, 0, &queue);

    // 2. Load Shaders
    auto cocSpv = loadSpv("/data/local/tmp/coc.spv");
    auto gatherSpv = loadSpv("/data/local/tmp/gather.spv");
    auto compositeSpv = loadSpv("/data/local/tmp/composite.spv");

    if (cocSpv.empty() || gatherSpv.empty() || compositeSpv.empty()) {
        printf("FAIL: Failed to load one or more SPIR-V shaders from /data/local/tmp\n");
        return 1;
    }

    auto createShaderModule = [&](const std::vector<char>& spv) -> VkShaderModule {
        VkShaderModuleCreateInfo smInfo{ VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO };
        smInfo.codeSize = spv.size();
        smInfo.pCode = reinterpret_cast<const uint32_t*>(spv.data());
        VkShaderModule sm;
        vkCreateShaderModule(device, &smInfo, nullptr, &sm);
        return sm;
    };

    VkShaderModule smCoc = createShaderModule(cocSpv);
    VkShaderModule smGather = createShaderModule(gatherSpv);
    VkShaderModule smComposite = createShaderModule(compositeSpv);

    // 3. Allocate Images:
    // Working Images (1020x768):
    // imgDepth (R32F), imgCoc (R16F), imgAlpha (R8), imgBlurred (RGBA8)
    // Full Images (4080x3072):
    // imgColorFull (RGBA8), imgOutFull (RGBA8)
    auto createImage = [&](uint32_t w, uint32_t h, VkFormat fmt, VkImageUsageFlags usage, VkImage* outImg, VkDeviceMemory* outMem) {
        VkImageCreateInfo imgInfo{ VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO };
        imgInfo.imageType = VK_IMAGE_TYPE_2D;
        imgInfo.extent = { w, h, 1 };
        imgInfo.mipLevels = 1;
        imgInfo.arrayLayers = 1;
        imgInfo.format = fmt;
        imgInfo.tiling = VK_IMAGE_TILING_OPTIMAL;
        imgInfo.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        imgInfo.usage = usage;
        imgInfo.samples = VK_SAMPLE_COUNT_1_BIT;
        vkCreateImage(device, &imgInfo, nullptr, outImg);

        VkMemoryRequirements mr;
        vkGetImageMemoryRequirements(device, *outImg, &mr);
        VkMemoryAllocateInfo allocInfo{ VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO };
        allocInfo.allocationSize = mr.size;
        allocInfo.memoryTypeIndex = findMemType(physDev, mr.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        vkAllocateMemory(device, &allocInfo, nullptr, outMem);
        vkBindImageMemory(device, *outImg, *outMem, 0);
    };

    VkImage imgDepth, imgCoc, imgAlpha, imgBlurred, imgColorFull, imgOutFull;
    VkDeviceMemory memDepth, memCoc, memAlpha, memBlurred, memColorFull, memOutFull;

    createImage(W_work, H_work, VK_FORMAT_R32_SFLOAT, VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT, &imgDepth, &memDepth);
    createImage(W_work, H_work, VK_FORMAT_R16_SFLOAT, VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_STORAGE_BIT, &imgCoc, &memCoc);
    createImage(W_work, H_work, VK_FORMAT_R8_UNORM, VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT, &imgAlpha, &memAlpha);
    createImage(W_work, H_work, VK_FORMAT_R8G8B8A8_UNORM, VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_STORAGE_BIT, &imgBlurred, &memBlurred);

    createImage(W_full, H_full, VK_FORMAT_R8G8B8A8_UNORM, VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT, &imgColorFull, &memColorFull);
    createImage(W_full, H_full, VK_FORMAT_R8G8B8A8_UNORM, VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT, &imgOutFull, &memOutFull);

    auto createView = [&](VkImage img, VkFormat fmt) -> VkImageView {
        VkImageViewCreateInfo vi{ VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO };
        vi.image = img;
        vi.viewType = VK_IMAGE_VIEW_TYPE_2D;
        vi.format = fmt;
        vi.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        vi.subresourceRange.levelCount = 1;
        vi.subresourceRange.layerCount = 1;
        VkImageView v;
        vkCreateImageView(device, &vi, nullptr, &v);
        return v;
    };

    VkImageView viewDepth = createView(imgDepth, VK_FORMAT_R32_SFLOAT);
    VkImageView viewCoc = createView(imgCoc, VK_FORMAT_R16_SFLOAT);
    VkImageView viewAlpha = createView(imgAlpha, VK_FORMAT_R8_UNORM);
    VkImageView viewBlurred = createView(imgBlurred, VK_FORMAT_R8G8B8A8_UNORM);
    VkImageView viewColorFull = createView(imgColorFull, VK_FORMAT_R8G8B8A8_UNORM);
    VkImageView viewOutFull = createView(imgOutFull, VK_FORMAT_R8G8B8A8_UNORM);

    VkSampler sampler;
    VkSamplerCreateInfo sampInfo{ VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO };
    sampInfo.magFilter = VK_FILTER_LINEAR;
    sampInfo.minFilter = VK_FILTER_LINEAR;
    sampInfo.addressModeU = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    sampInfo.addressModeV = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    sampInfo.addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    vkCreateSampler(device, &sampInfo, nullptr, &sampler);

    // Staging buffer for depth & alpha upload (1020x768 float depth + 1020x768 uint8 alpha + color)
    const size_t stagingUploadSize = W_work * H_work * sizeof(float) + W_work * H_work + W_full * H_full * 4;
    VkBuffer uploadStagingBuffer;
    VkDeviceMemory uploadStagingMem;
    {
        VkBufferCreateInfo bi{ VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO };
        bi.size = stagingUploadSize;
        bi.usage = VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
        vkCreateBuffer(device, &bi, nullptr, &uploadStagingBuffer);

        VkMemoryRequirements mr;
        vkGetBufferMemoryRequirements(device, uploadStagingBuffer, &mr);
        VkMemoryAllocateInfo ai{ VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO };
        ai.allocationSize = mr.size;
        ai.memoryTypeIndex = findMemType(physDev, mr.memoryTypeBits, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
        vkAllocateMemory(device, &ai, nullptr, &uploadStagingMem);
        vkBindBufferMemory(device, uploadStagingBuffer, uploadStagingMem, 0);
    }

    // Populate staging buffer with realistic portrait scene:
    // Foreground subject: centered person at S1 = 0.6 m, Alpha = 1.0 (sharp)
    // Background scene: high-frequency texture at S2 = 3.5 m, Alpha = 0.0 (blurred)
    void* mappedUpload = nullptr;
    vkMapMemory(device, uploadStagingMem, 0, stagingUploadSize, 0, &mappedUpload);
    float* depthPtr = reinterpret_cast<float*>(mappedUpload);
    uint8_t* alphaPtr = reinterpret_cast<uint8_t*>(depthPtr + (W_work * H_work));
    uint8_t* colorFullPtr = alphaPtr + (W_work * H_work);

    const float cx = W_work * 0.5f;
    const float cy = H_work * 0.5f;
    const float radiusFg = W_work * 0.22f; // subject ellipse

    for (uint32_t y = 0; y < H_work; ++y) {
        for (uint32_t x = 0; x < W_work; ++x) {
            float dx = (x - cx);
            float dy = (y - cy) * 1.3f; // vertical portrait aspect
            float dist = std::sqrt(dx * dx + dy * dy);
            uint32_t idx = y * W_work + x;
            if (dist < radiusFg) {
                depthPtr[idx] = 0.60f; // Subject in focus at S1 = 0.6 m
                alphaPtr[idx] = 255;   // Foreground alpha = 1.0
            } else {
                depthPtr[idx] = 3.50f; // Background at S2 = 3.5 m
                alphaPtr[idx] = 0;     // Background alpha = 0.0
            }
        }
    }

    // Create high-texture background for colorFull (foliage/bookshelf high-frequency grid)
    for (uint32_t y = 0; y < H_full; ++y) {
        for (uint32_t x = 0; x < W_full; ++x) {
            uint32_t idx = (y * W_full + x) * 4;
            uint8_t pat = ((x ^ y) & 0x1F) < 16 ? 220 : 40;
            colorFullPtr[idx + 0] = pat;
            colorFullPtr[idx + 1] = (x * 255) / W_full;
            colorFullPtr[idx + 2] = (y * 255) / H_full;
            colorFullPtr[idx + 3] = 255;
        }
    }
    vkUnmapMemory(device, uploadStagingMem);

    // Readback buffer for 4080x3072 RGBA (50.1 MB)
    const size_t readbackSize = W_full * H_full * 4;
    VkBuffer readbackBuffer;
    VkDeviceMemory readbackMem;
    {
        VkBufferCreateInfo bi{ VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO };
        bi.size = readbackSize;
        bi.usage = VK_BUFFER_USAGE_TRANSFER_DST_BIT;
        vkCreateBuffer(device, &bi, nullptr, &readbackBuffer);

        VkMemoryRequirements mr;
        vkGetBufferMemoryRequirements(device, readbackBuffer, &mr);
        VkMemoryAllocateInfo ai{ VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO };
        ai.allocationSize = mr.size;
        ai.memoryTypeIndex = findMemType(physDev, mr.memoryTypeBits, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
        vkAllocateMemory(device, &ai, nullptr, &readbackMem);
        vkBindBufferMemory(device, readbackBuffer, readbackMem, 0);
    }

    // Command pool & buffer
    VkCommandPool cmdPool;
    VkCommandPoolCreateInfo cpInfo{ VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO };
    cpInfo.queueFamilyIndex = qf;
    vkCreateCommandPool(device, &cpInfo, nullptr, &cmdPool);

    VkCommandBuffer cmd;
    VkCommandBufferAllocateInfo cbAlloc{ VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO };
    cbAlloc.commandPool = cmdPool;
    cbAlloc.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    cbAlloc.commandBufferCount = 1;
    vkAllocateCommandBuffers(device, &cbAlloc, &cmd);

    // 4. Create Query Pool for Per-Pass Hardware Timestamps (5 queries)
    VkQueryPool queryPool;
    VkQueryPoolCreateInfo qpInfo{ VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO };
    qpInfo.queryType = VK_QUERY_TYPE_TIMESTAMP;
    qpInfo.queryCount = 5;
    vkCreateQueryPool(device, &qpInfo, nullptr, &queryPool);

    // Create Fence for Explicit Hardware Signaling
    VkFence fence;
    VkFenceCreateInfo fenceInfo{ VK_STRUCTURE_TYPE_FENCE_CREATE_INFO };
    vkCreateFence(device, &fenceInfo, nullptr, &fence);

    // 5. Pipeline Layouts & Compute Pipelines
    // CoC Layout: Binding 0: Depth, Binding 1: CocOut
    VkDescriptorSetLayout dslCoc;
    {
        VkDescriptorSetLayoutBinding b[2]{};
        b[0] = { 0, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1, VK_SHADER_STAGE_COMPUTE_BIT, &sampler };
        b[1] = { 1, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 1, VK_SHADER_STAGE_COMPUTE_BIT, nullptr };
        VkDescriptorSetLayoutCreateInfo dli{ VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO, nullptr, 0, 2, b };
        vkCreateDescriptorSetLayout(device, &dli, nullptr, &dslCoc);
    }
    VkPushConstantRange pcrCoc{ VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(CocPushConstants) };
    VkPipelineLayout plCoc;
    {
        VkPipelineLayoutCreateInfo pli{ VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO, nullptr, 0, 1, &dslCoc, 1, &pcrCoc };
        vkCreatePipelineLayout(device, &pli, nullptr, &plCoc);
    }
    VkPipeline pipeCoc;
    {
        VkComputePipelineCreateInfo cpi{ VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO };
        cpi.stage = { VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO, nullptr, 0, VK_SHADER_STAGE_COMPUTE_BIT, smCoc, "main", nullptr };
        cpi.layout = plCoc;
        vkCreateComputePipelines(device, VK_NULL_HANDLE, 1, &cpi, nullptr, &pipeCoc);
    }

    // Gather Layout: 0: Color, 1: CoC, 2: Alpha, 3: OutBlurred
    VkDescriptorSetLayout dslGather;
    {
        VkDescriptorSetLayoutBinding b[4]{};
        for (int i = 0; i < 3; ++i) b[i] = { (uint32_t)i, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1, VK_SHADER_STAGE_COMPUTE_BIT, &sampler };
        b[3] = { 3, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 1, VK_SHADER_STAGE_COMPUTE_BIT, nullptr };
        VkDescriptorSetLayoutCreateInfo dli{ VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO, nullptr, 0, 4, b };
        vkCreateDescriptorSetLayout(device, &dli, nullptr, &dslGather);
    }
    VkPushConstantRange pcrGather{ VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(GatherPushConstants) };
    VkPipelineLayout plGather;
    {
        VkPipelineLayoutCreateInfo pli{ VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO, nullptr, 0, 1, &dslGather, 1, &pcrGather };
        vkCreatePipelineLayout(device, &pli, nullptr, &plGather);
    }
    VkPipeline pipeGather;
    {
        VkComputePipelineCreateInfo cpi{ VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO };
        cpi.stage = { VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO, nullptr, 0, VK_SHADER_STAGE_COMPUTE_BIT, smGather, "main", nullptr };
        cpi.layout = plGather;
        vkCreateComputePipelines(device, VK_NULL_HANDLE, 1, &cpi, nullptr, &pipeGather);
    }

    // Composite Layout: 0: OriginalColorFull, 1: BlurredWork, 2: AlphaWork, 3: OutFull
    VkDescriptorSetLayout dslComp;
    {
        VkDescriptorSetLayoutBinding b[4]{};
        for (int i = 0; i < 3; ++i) b[i] = { (uint32_t)i, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1, VK_SHADER_STAGE_COMPUTE_BIT, &sampler };
        b[3] = { 3, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 1, VK_SHADER_STAGE_COMPUTE_BIT, nullptr };
        VkDescriptorSetLayoutCreateInfo dli{ VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO, nullptr, 0, 4, b };
        vkCreateDescriptorSetLayout(device, &dli, nullptr, &dslComp);
    }
    VkPushConstantRange pcrComp{ VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(CompositePushConstants) };
    VkPipelineLayout plComp;
    {
        VkPipelineLayoutCreateInfo pli{ VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO, nullptr, 0, 1, &dslComp, 1, &pcrComp };
        vkCreatePipelineLayout(device, &pli, nullptr, &plComp);
    }
    VkPipeline pipeComp;
    {
        VkComputePipelineCreateInfo cpi{ VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO };
        cpi.stage = { VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO, nullptr, 0, VK_SHADER_STAGE_COMPUTE_BIT, smComposite, "main", nullptr };
        cpi.layout = plComp;
        vkCreateComputePipelines(device, VK_NULL_HANDLE, 1, &cpi, nullptr, &pipeComp);
    }

    // Descriptor Pool & Sets
    VkDescriptorPool pool;
    {
        VkDescriptorPoolSize ps[2]{};
        ps[0] = { VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 15 };
        ps[1] = { VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 5 };
        VkDescriptorPoolCreateInfo dpi{ VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO, nullptr, 0, 5, 2, ps };
        vkCreateDescriptorPool(device, &dpi, nullptr, &pool);
    }

    VkDescriptorSet setCoc, setGather, setComp;
    {
        VkDescriptorSetAllocateInfo ai{ VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO, nullptr, pool, 1, &dslCoc };
        vkAllocateDescriptorSets(device, &ai, &setCoc);
        ai.pSetLayouts = &dslGather;
        vkAllocateDescriptorSets(device, &ai, &setGather);
        ai.pSetLayouts = &dslComp;
        vkAllocateDescriptorSets(device, &ai, &setComp);
    }

    // Update Descriptors
    VkDescriptorImageInfo diDepth{ sampler, viewDepth, VK_IMAGE_LAYOUT_GENERAL };
    VkDescriptorImageInfo diCoc{ sampler, viewCoc, VK_IMAGE_LAYOUT_GENERAL };
    VkDescriptorImageInfo diCocStore{ VK_NULL_HANDLE, viewCoc, VK_IMAGE_LAYOUT_GENERAL };
    VkDescriptorImageInfo diAlpha{ sampler, viewAlpha, VK_IMAGE_LAYOUT_GENERAL };
    VkDescriptorImageInfo diColorFull{ sampler, viewColorFull, VK_IMAGE_LAYOUT_GENERAL };
    VkDescriptorImageInfo diBlurredSample{ sampler, viewBlurred, VK_IMAGE_LAYOUT_GENERAL };
    VkDescriptorImageInfo diBlurredStore{ VK_NULL_HANDLE, viewBlurred, VK_IMAGE_LAYOUT_GENERAL };
    VkDescriptorImageInfo diOutFullStore{ VK_NULL_HANDLE, viewOutFull, VK_IMAGE_LAYOUT_GENERAL };

    VkWriteDescriptorSet writes[10]{};
    // CoC
    writes[0] = { VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET, nullptr, setCoc, 0, 0, 1, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, &diDepth, nullptr, nullptr };
    writes[1] = { VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET, nullptr, setCoc, 1, 0, 1, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, &diCocStore, nullptr, nullptr };
    // Gather (samples ColorFull bilinearly, CoC, Alpha; stores BlurredWork)
    writes[2] = { VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET, nullptr, setGather, 0, 0, 1, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, &diColorFull, nullptr, nullptr };
    writes[3] = { VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET, nullptr, setGather, 1, 0, 1, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, &diCoc, nullptr, nullptr };
    writes[4] = { VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET, nullptr, setGather, 2, 0, 1, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, &diAlpha, nullptr, nullptr };
    writes[5] = { VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET, nullptr, setGather, 3, 0, 1, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, &diBlurredStore, nullptr, nullptr };
    // Composite (samples ColorFull, BlurredWork, Alpha; stores OutFull)
    writes[6] = { VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET, nullptr, setComp, 0, 0, 1, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, &diColorFull, nullptr, nullptr };
    writes[7] = { VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET, nullptr, setComp, 1, 0, 1, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, &diBlurredSample, nullptr, nullptr };
    writes[8] = { VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET, nullptr, setComp, 2, 0, 1, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, &diAlpha, nullptr, nullptr };
    writes[9] = { VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET, nullptr, setComp, 3, 0, 1, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, &diOutFullStore, nullptr, nullptr };
    vkUpdateDescriptorSets(device, 10, writes, 0, nullptr);

    // Initial Staging Upload: Copy Depth, Alpha, and ColorFull into GPU Images
    {
        VkCommandBufferBeginInfo bi{ VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO };
        vkBeginCommandBuffer(cmd, &bi);

        auto transitionLayout = [&](VkImage img, VkImageLayout oldL, VkImageLayout newL) {
            VkImageMemoryBarrier imb{ VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER };
            imb.oldLayout = oldL;
            imb.newLayout = newL;
            imb.image = img;
            imb.subresourceRange = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1 };
            imb.srcAccessMask = 0;
            imb.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
            vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, nullptr, 0, nullptr, 1, &imb);
        };

        transitionLayout(imgDepth, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
        transitionLayout(imgAlpha, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
        transitionLayout(imgColorFull, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
        transitionLayout(imgCoc, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL);
        transitionLayout(imgBlurred, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL);
        transitionLayout(imgOutFull, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL);

        VkBufferImageCopy bicDepth{ 0, 0, 0, { VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1 }, {0, 0, 0}, { W_work, H_work, 1 } };
        vkCmdCopyBufferToImage(cmd, uploadStagingBuffer, imgDepth, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &bicDepth);

        VkBufferImageCopy bicAlpha{ W_work * H_work * sizeof(float), 0, 0, { VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1 }, {0, 0, 0}, { W_work, H_work, 1 } };
        vkCmdCopyBufferToImage(cmd, uploadStagingBuffer, imgAlpha, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &bicAlpha);

        VkBufferImageCopy bicColor{ W_work * H_work * sizeof(float) + W_work * H_work, 0, 0, { VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1 }, {0, 0, 0}, { W_full, H_full, 1 } };
        vkCmdCopyBufferToImage(cmd, uploadStagingBuffer, imgColorFull, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &bicColor);

        // Transition back to GENERAL for compute shader execution
        auto transitionToGeneral = [&](VkImage img) {
            VkImageMemoryBarrier imb{ VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER };
            imb.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
            imb.newLayout = VK_IMAGE_LAYOUT_GENERAL;
            imb.image = img;
            imb.subresourceRange = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1 };
            imb.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
            imb.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
            vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 0, nullptr, 0, nullptr, 1, &imb);
        };
        transitionToGeneral(imgDepth);
        transitionToGeneral(imgAlpha);
        transitionToGeneral(imgColorFull);

        vkEndCommandBuffer(cmd);
        VkSubmitInfo si{ VK_STRUCTURE_TYPE_SUBMIT_INFO, nullptr, 0, nullptr, nullptr, 1, &cmd, 0, nullptr };
        vkQueueSubmit(queue, 1, &si, VK_NULL_HANDLE);
        vkQueueWaitIdle(queue);
    }

    printf("Pre-flight: Texture upload and layout transitions successfully finalized on Mali-G615.\n");

    const uint32_t gx_work = (W_work + 15) / 16;
    const uint32_t gy_work = (H_work + 15) / 16;
    const uint32_t gx_full = (W_full + 15) / 16;
    const uint32_t gy_full = (H_full + 15) / 16;

    CocPushConstants pcCoc{ W_work, H_work, 5.56f, 1.8f, 8.16f, 0.6f, 15.0f, 720.0f };
    GatherPushConstants pcGather{ W_work, H_work, 32, 1.2f, 40.0f }; // 32 taps with specular boost
    CompositePushConstants pcComp{ W_full, H_full, 0 };              // Full resolution 4080x3072

    struct PerfSample {
        double tWallSubmitToFenceMs;
        double tGpuTotalMs;
        double tGpuCocMs;
        double tGpuGatherMs;
        double tGpuCompMs;
        double tGpuTransferMs;
    };
    std::vector<PerfSample> samples;

    const int totalRuns = 10;
    for (int run = 0; run < totalRuns; ++run) {
        vkResetCommandBuffer(cmd, 0);
        vkResetFences(device, 1, &fence);

        VkCommandBufferBeginInfo bi{ VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO };
        vkBeginCommandBuffer(cmd, &bi);

        vkCmdResetQueryPool(cmd, queryPool, 0, 5);

        // Query 0: Before CoC Pass
        vkCmdWriteTimestamp(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, queryPool, 0);

        // 1. CoC Pass (1020x768)
        vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipeCoc);
        vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, plCoc, 0, 1, &setCoc, 0, nullptr);
        vkCmdPushConstants(cmd, plCoc, VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(CocPushConstants), &pcCoc);
        vkCmdDispatch(cmd, gx_work, gy_work, 1);

        VkMemoryBarrier mb{ VK_STRUCTURE_TYPE_MEMORY_BARRIER, nullptr, VK_ACCESS_SHADER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT };
        vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 1, &mb, 0, nullptr, 0, nullptr);

        // Query 1: End of CoC / Start of Gather
        vkCmdWriteTimestamp(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, queryPool, 1);

        // 2. 32-tap Gather Pass (1020x768)
        vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipeGather);
        vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, plGather, 0, 1, &setGather, 0, nullptr);
        vkCmdPushConstants(cmd, plGather, VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(GatherPushConstants), &pcGather);
        vkCmdDispatch(cmd, gx_work, gy_work, 1);

        vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 1, &mb, 0, nullptr, 0, nullptr);

        // Query 2: End of Gather / Start of Composite
        vkCmdWriteTimestamp(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, queryPool, 2);

        // 3. Composite Pass (4080x3072 Full Resolution)
        vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipeComp);
        vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, plComp, 0, 1, &setComp, 0, nullptr);
        vkCmdPushConstants(cmd, plComp, VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(CompositePushConstants), &pcComp);
        vkCmdDispatch(cmd, gx_full, gy_full, 1);

        // Query 3: End of Composite / Start of Readback Transfer
        vkCmdWriteTimestamp(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, queryPool, 3);

        // 4. Readback Transfer: 4080x3072 OutFull -> Readback Buffer (50.1 MB)
        VkImageMemoryBarrier imb{ VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER };
        imb.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
        imb.dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
        imb.oldLayout = VK_IMAGE_LAYOUT_GENERAL;
        imb.newLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
        imb.image = imgOutFull;
        imb.subresourceRange = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1 };
        vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, nullptr, 0, nullptr, 1, &imb);

        VkBufferImageCopy bic{ 0, 0, 0, { VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1 }, { 0, 0, 0 }, { W_full, H_full, 1 } };
        vkCmdCopyImageToBuffer(cmd, imgOutFull, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, readbackBuffer, 1, &bic);

        // Transition back to GENERAL for next run
        imb.srcAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
        imb.dstAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
        imb.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
        imb.newLayout = VK_IMAGE_LAYOUT_GENERAL;
        vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 0, nullptr, 0, nullptr, 1, &imb);

        // Query 4: End of Readback Transfer
        vkCmdWriteTimestamp(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, queryPool, 4);

        vkEndCommandBuffer(cmd);

        // Submit & Measure Shutter -> Composite Buffer Complete (Fence Signal)
        auto tSubmit = std::chrono::high_resolution_clock::now();
        VkSubmitInfo si{ VK_STRUCTURE_TYPE_SUBMIT_INFO, nullptr, 0, nullptr, nullptr, 1, &cmd, 0, nullptr };
        vkQueueSubmit(queue, 1, &si, fence);

        // DEFINITION OF COMPOSITE COMPLETE: vkWaitForFences returning VK_SUCCESS
        vkWaitForFences(device, 1, &fence, VK_TRUE, UINT64_MAX);
        auto tFence = std::chrono::high_resolution_clock::now();

        double wallMs = std::chrono::duration<double, std::milli>(tFence - tSubmit).count();

        // Retrieve Exact Hardware Timestamps
        uint64_t q[5]{0};
        vkGetQueryPoolResults(device, queryPool, 0, 5, sizeof(q), q, sizeof(uint64_t), VK_QUERY_RESULT_64_BIT | VK_QUERY_RESULT_WAIT_BIT);

        double cocGpuMs      = (q[1] - q[0]) * timestampPeriodNs / 1e6;
        double gatherGpuMs   = (q[2] - q[1]) * timestampPeriodNs / 1e6;
        double compGpuMs     = (q[3] - q[2]) * timestampPeriodNs / 1e6;
        double transferGpuMs = (q[4] - q[3]) * timestampPeriodNs / 1e6;
        double totalGpuMs    = (q[4] - q[0]) * timestampPeriodNs / 1e6;

        samples.push_back({ wallMs, totalGpuMs, cocGpuMs, gatherGpuMs, compGpuMs, transferGpuMs });

        printf("Run #%02d | Wall (Submit->Fence): %6.2f ms | GPU Total: %6.2f ms [CoC: %4.2f ms | Gather: %5.2f ms | Comp 4K: %5.2f ms | Readback 50MB: %5.2f ms]\n",
               run, wallMs, totalGpuMs, cocGpuMs, gatherGpuMs, compGpuMs, transferGpuMs);
    }

    // 6. Write Full 4080x3072 RGBA Readback Buffer to File & Time File Close
    printf("\nWriting 4080x3072 RGBA buffer (50.1 MB) to /data/local/tmp/bench_still_composite.rgba...\n");
    auto tWriteStart = std::chrono::high_resolution_clock::now();
    void* mappedOut = nullptr;
    vkMapMemory(device, readbackMem, 0, readbackSize, 0, &mappedOut);

    const char* outPath = "/data/local/tmp/bench_still_composite.rgba";
    FILE* fp = fopen(outPath, "wb");
    if (fp) {
        fwrite(mappedOut, 1, readbackSize, fp);
        fclose(fp);
    }
    vkUnmapMemory(device, readbackMem);
    auto tWriteEnd = std::chrono::high_resolution_clock::now();
    double writeMs = std::chrono::duration<double, std::milli>(tWriteEnd - tWriteStart).count();
    printf("File Write & Close Completed: %.2f ms (Throughput: %.1f MB/s)\n", writeMs, (readbackSize / (1024.0 * 1024.0)) / (writeMs / 1000.0));

    // Summary Statistics: Cold vs Warm
    std::vector<double> warmWall, warmGather, warmComp, warmGpuTot;
    for (size_t i = 1; i < samples.size(); ++i) {
        warmWall.push_back(samples[i].tWallSubmitToFenceMs);
        warmGather.push_back(samples[i].tGpuGatherMs);
        warmComp.push_back(samples[i].tGpuCompMs);
        warmGpuTot.push_back(samples[i].tGpuTotalMs);
    }
    std::sort(warmWall.begin(), warmWall.end());
    std::sort(warmGather.begin(), warmGather.end());
    std::sort(warmComp.begin(), warmComp.end());
    std::sort(warmGpuTot.begin(), warmGpuTot.end());

    printf("\n===================================================================\n");
    printf("=== HARDWARE RECONCILIATION SUMMARY (Mali-G615 MC2) ===\n");
    printf("Cold Run #0: Wall = %.2f ms | GPU Total = %.2f ms (CoC: %.2f ms, Gather: %.2f ms, Comp: %.2f ms, Copy: %.2f ms)\n",
           samples[0].tWallSubmitToFenceMs, samples[0].tGpuTotalMs, samples[0].tGpuCocMs, samples[0].tGpuGatherMs, samples[0].tGpuCompMs, samples[0].tGpuTransferMs);
    printf("Warm Steady-State (p50):\n");
    printf("  CoC Pass (1020x768):               %5.2f ms\n", samples[1].tGpuCocMs);
    printf("  Gather Pass (32-tap @ 1020x768):   %5.2f ms\n", warmGather[warmGather.size() / 2]);
    printf("  Full-Res Composite (4080x3072):    %5.2f ms\n", warmComp[warmComp.size() / 2]);
    printf("  50 MB Readback Transfer:           %5.2f ms\n", samples[1].tGpuTransferMs);
    printf("  Total GPU Hardware Time:           %5.2f ms\n", warmGpuTot[warmGpuTot.size() / 2]);
    printf("  Wall Shutter->Fence Complete (t4): %5.2f ms (p50)\n", warmWall[warmWall.size() / 2]);
    printf("  File Write & Storage Close (t5):   %5.2f ms\n", writeMs);
    printf("===================================================================\n");

    return 0;
}
