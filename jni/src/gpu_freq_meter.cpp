#include <vulkan/vulkan.h>
#include <stdio.h>
#include <stdlib.h>
#include <vector>
#include <chrono>
#include <thread>

// 50,000 loop iterations * 100 dependent FMAs = 5,000,000 FMAs
// On Mali 5th-gen (Valhall/5th Gen Architecture), dependent FMA forwarding has 1-cycle latency
// Total cycles = 5,000,000 cycles
// At peak clock 1000 MHz (Dimensity 7350 spec): duration = 5.00 ms
static const uint32_t kAluCycles = 5000000;

int main(int argc, char** argv) {
    printf("=== MALI-G615 HARDWARE GPU CLOCK & FREQUENCY PROBE ===\n");

    VkApplicationInfo appInfo{};
    appInfo.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    appInfo.pApplicationName = "GpuClockMeter";
    appInfo.apiVersion = VK_API_VERSION_1_3;

    VkInstanceCreateInfo instInfo{};
    instInfo.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    instInfo.pApplicationInfo = &appInfo;

    VkInstance instance = VK_NULL_HANDLE;
    vkCreateInstance(&instInfo, nullptr, &instance);

    uint32_t devCount = 1;
    VkPhysicalDevice phys = VK_NULL_HANDLE;
    vkEnumeratePhysicalDevices(instance, &devCount, &phys);

    VkPhysicalDeviceProperties props;
    vkGetPhysicalDeviceProperties(phys, &props);
    double nsPerTick = props.limits.timestampPeriod; // 76.92308 ns/tick on Mali-G615

    float qPri = 1.0f;
    VkDeviceQueueCreateInfo qInfo{};
    qInfo.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
    qInfo.queueFamilyIndex = 0;
    qInfo.queueCount = 1;
    qInfo.pQueuePriorities = &qPri;

    VkDeviceCreateInfo devInfo{};
    devInfo.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;
    devInfo.queueCreateInfoCount = 1;
    devInfo.pQueueCreateInfos = &qInfo;

    VkDevice device = VK_NULL_HANDLE;
    vkCreateDevice(phys, &devInfo, nullptr, &device);

    VkQueue queue = VK_NULL_HANDLE;
    vkGetDeviceQueue(device, 0, 0, &queue);

    // Query pool for hardware timestamps
    VkQueryPoolCreateInfo qpInfo{};
    qpInfo.sType = VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO;
    qpInfo.queryType = VK_QUERY_TYPE_TIMESTAMP;
    qpInfo.queryCount = 2;
    VkQueryPool queryPool = VK_NULL_HANDLE;
    vkCreateQueryPool(device, &qpInfo, nullptr, &queryPool);

    // Command pool
    VkCommandPoolCreateInfo cpInfo{};
    cpInfo.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
    cpInfo.queueFamilyIndex = 0;
    VkCommandPool cmdPool = VK_NULL_HANDLE;
    vkCreateCommandPool(device, &cpInfo, nullptr, &cmdPool);

    VkCommandBufferAllocateInfo cbInfo{};
    cbInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
    cbInfo.commandPool = cmdPool;
    cbInfo.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    cbInfo.commandBufferCount = 1;
    VkCommandBuffer cmd = VK_NULL_HANDLE;
    vkAllocateCommandBuffers(device, &cbInfo, &cmd);

    // Read SPIR-V shader from /data/local/tmp/alu_clock.spv
    FILE* sf = fopen("/data/local/tmp/alu_clock.spv", "rb");
    if (!sf) {
        printf("Error: /data/local/tmp/alu_clock.spv not found\n");
        return 1;
    }
    fseek(sf, 0, SEEK_END);
    size_t sz = ftell(sf);
    fseek(sf, 0, SEEK_SET);
    std::vector<uint32_t> spv(sz / 4);
    fread(spv.data(), 1, sz, sf);
    fclose(sf);

    VkShaderModuleCreateInfo smInfo{};
    smInfo.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
    smInfo.codeSize = sz;
    smInfo.pCode = spv.data();
    VkShaderModule sMod = VK_NULL_HANDLE;
    vkCreateShaderModule(device, &smInfo, nullptr, &sMod);

    // Storage buffer for output
    VkBufferCreateInfo bInfo{};
    bInfo.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
    bInfo.size = 256;
    bInfo.usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
    VkBuffer buf = VK_NULL_HANDLE;
    vkCreateBuffer(device, &bInfo, nullptr, &buf);

    VkMemoryRequirements memReq;
    vkGetBufferMemoryRequirements(device, buf, &memReq);

    VkPhysicalDeviceMemoryProperties memProps;
    vkGetPhysicalDeviceMemoryProperties(phys, &memProps);
    uint32_t memTypeIdx = 0;
    for (uint32_t i = 0; i < memProps.memoryTypeCount; ++i) {
        if ((memReq.memoryTypeBits & (1 << i)) && (memProps.memoryTypes[i].propertyFlags & (VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT))) {
            memTypeIdx = i; break;
        }
    }

    VkMemoryAllocateInfo allocInfo{};
    allocInfo.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    allocInfo.allocationSize = memReq.size;
    allocInfo.memoryTypeIndex = memTypeIdx;
    VkDeviceMemory bufMem = VK_NULL_HANDLE;
    vkAllocateMemory(device, &allocInfo, nullptr, &bufMem);
    vkBindBufferMemory(device, buf, bufMem, 0);

    // Descriptor setup
    VkDescriptorSetLayoutBinding dslb{};
    dslb.binding = 0;
    dslb.descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    dslb.descriptorCount = 1;
    dslb.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;

    VkDescriptorSetLayoutCreateInfo dslInfo{};
    dslInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
    dslInfo.bindingCount = 1;
    dslInfo.pBindings = &dslb;
    VkDescriptorSetLayout dsl = VK_NULL_HANDLE;
    vkCreateDescriptorSetLayout(device, &dslInfo, nullptr, &dsl);

    VkDescriptorPoolSize dps{VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 1};
    VkDescriptorPoolCreateInfo dpInfo{};
    dpInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
    dpInfo.maxSets = 1;
    dpInfo.poolSizeCount = 1;
    dpInfo.pPoolSizes = &dps;
    VkDescriptorPool descPool = VK_NULL_HANDLE;
    vkCreateDescriptorPool(device, &dpInfo, nullptr, &descPool);

    VkDescriptorSetAllocateInfo dsAlloc{};
    dsAlloc.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
    dsAlloc.descriptorPool = descPool;
    dsAlloc.descriptorSetCount = 1;
    dsAlloc.pSetLayouts = &dsl;
    VkDescriptorSet descSet = VK_NULL_HANDLE;
    vkAllocateDescriptorSets(device, &dsAlloc, &descSet);

    VkDescriptorBufferInfo dbi{buf, 0, 256};
    VkWriteDescriptorSet wds{};
    wds.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    wds.dstSet = descSet;
    wds.dstBinding = 0;
    wds.descriptorCount = 1;
    wds.descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    wds.pBufferInfo = &dbi;
    vkUpdateDescriptorSets(device, 1, &wds, 0, nullptr);

    VkPipelineLayoutCreateInfo plInfo{};
    plInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
    plInfo.setLayoutCount = 1;
    plInfo.pSetLayouts = &dsl;
    VkPipelineLayout pipeLayout = VK_NULL_HANDLE;
    vkCreatePipelineLayout(device, &plInfo, nullptr, &pipeLayout);

    VkComputePipelineCreateInfo cpipeInfo{};
    cpipeInfo.sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO;
    cpipeInfo.stage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    cpipeInfo.stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
    cpipeInfo.stage.module = sMod;
    cpipeInfo.stage.pName = "main";
    cpipeInfo.layout = pipeLayout;
    VkPipeline pipe = VK_NULL_HANDLE;
    vkCreateComputePipelines(device, VK_NULL_HANDLE, 1, &cpipeInfo, nullptr, &pipe);

    VkFenceCreateInfo fInfo{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO, nullptr, 0};
    VkFence fence = VK_NULL_HANDLE;
    vkCreateFence(device, &fInfo, nullptr, &fence);

    printf("Executing 10 consecutive clock measurement runs...\n");
    for (int run = 0; run < 10; ++run) {
        vkResetCommandBuffer(cmd, 0);
        vkResetQueryPool(device, queryPool, 0, 2);

        VkCommandBufferBeginInfo beginInfo{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
        vkBeginCommandBuffer(cmd, &beginInfo);

        vkCmdResetQueryPool(cmd, queryPool, 0, 2);
        vkCmdWriteTimestamp(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, queryPool, 0);

        vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipe);
        vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipeLayout, 0, 1, &descSet, 0, nullptr);
        vkCmdDispatch(cmd, 1, 1, 1);

        vkCmdWriteTimestamp(cmd, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, queryPool, 1);
        vkEndCommandBuffer(cmd);

        vkResetFences(device, 1, &fence);
        VkSubmitInfo sub{VK_STRUCTURE_TYPE_SUBMIT_INFO, nullptr, 0, nullptr, nullptr, 1, &cmd, 0, nullptr};
        vkQueueSubmit(queue, 1, &sub, fence);
        vkWaitForFences(device, 1, &fence, VK_TRUE, UINT64_MAX);

        uint64_t ts[2] = {0, 0};
        vkGetQueryPoolResults(device, queryPool, 0, 2, sizeof(ts), ts, sizeof(uint64_t), VK_QUERY_RESULT_64_BIT);

        double gpuTimeMs = (ts[1] - ts[0]) * nsPerTick / 1e6;
        double estimatedClockMHz = (kAluCycles / (gpuTimeMs * 1e-3)) / 1e6;

        printf("Run #%02d: GPU Latency = %6.2f ms | Measured GPU Core Clock = %6.1f MHz\n",
               run, gpuTimeMs, estimatedClockMHz);
        std::this_thread::sleep_for(std::chrono::milliseconds(200));
    }

    return 0;
}
