LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)

LOCAL_MODULE    := auxcam_engine
LOCAL_SRC_FILES := src/YuvConverter.cpp \
                   src/NpuWorker.cpp \
                   src/VulkanPipeline.cpp \
                   src/NativeBokehBridge.cpp

LOCAL_C_INCLUDES := $(LOCAL_PATH)/src

LOCAL_CFLAGS    := -std=c++17 -O3 -DANDROID_NDK
LOCAL_LDLIBS    := -llog -landroid -lvulkan

include $(BUILD_SHARED_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE    := coc_gpu_test
LOCAL_SRC_FILES := src/coc_gpu_test.cpp
LOCAL_CFLAGS    := -std=c++17 -O3 -DANDROID_NDK
LOCAL_LDLIBS    := -llog -landroid -lvulkan
include $(BUILD_EXECUTABLE)

include $(CLEAR_VARS)
LOCAL_MODULE    := still_bokeh_gpu_benchmark
LOCAL_SRC_FILES := src/still_bokeh_gpu_benchmark.cpp
LOCAL_CFLAGS    := -std=c++17 -O3 -DANDROID_NDK
LOCAL_LDLIBS    := -llog -landroid -lvulkan
include $(BUILD_EXECUTABLE)

include $(CLEAR_VARS)
LOCAL_MODULE    := gpu_clock_probe
LOCAL_SRC_FILES := src/gpu_clock_probe.cpp
LOCAL_CFLAGS    := -std=c++17 -O3 -DANDROID_NDK
LOCAL_LDLIBS    := -llog -landroid -lvulkan
include $(BUILD_EXECUTABLE)

include $(CLEAR_VARS)
LOCAL_MODULE    := gpu_freq_meter
LOCAL_SRC_FILES := src/gpu_freq_meter.cpp
LOCAL_CFLAGS    := -std=c++17 -O3 -DANDROID_NDK
LOCAL_LDLIBS    := -llog -landroid -lvulkan
include $(BUILD_EXECUTABLE)

