#include <jni.h>
#include <android/native_window_jni.h>
#include <android/hardware_buffer_jni.h>
#include <android/log.h>
#include <memory>
#include "VulkanPipeline.h"

#define LOG_TAG "NativeBokehBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static std::unique_ptr<VulkanPipeline> gPipeline;

extern "C" JNIEXPORT jboolean JNICALL
Java_com_zenith_auxcam_MainActivity_nativeInitEngine(
        JNIEnv* env, jobject thiz, jobject surface, jint width, jint height) {
    LOGI("nativeInitEngine called with Surface %p (%dx%d)", surface, width, height);

    ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
    if (!window) {
        LOGE("Failed to get ANativeWindow from surface");
        return JNI_FALSE;
    }

    if (!gPipeline) {
        gPipeline = std::make_unique<VulkanPipeline>();
        if (!gPipeline->init(window, static_cast<uint32_t>(width), static_cast<uint32_t>(height))) {
            LOGE("VulkanPipeline initialization failed");
            gPipeline.reset();
            ANativeWindow_release(window);
            return JNI_FALSE;
        }
    } else {
        gPipeline->updateSurface(window, static_cast<uint32_t>(width), static_cast<uint32_t>(height));
    }

    ANativeWindow_release(window);
    LOGI("Native bokeh engine initialized/updated successfully");
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_zenith_auxcam_MainActivity_nativeRenderFrame(
        JNIEnv* env, jobject thiz, jobject hardwareBufferObj, jbyteArray depthY8Array, jint depthW, jint depthH, jfloat fStop, jfloat targetFarFieldPx) {
    if (!gPipeline) return JNI_FALSE;

    AHardwareBuffer* hardwareBuffer = AHardwareBuffer_fromHardwareBuffer(env, hardwareBufferObj);
    if (!hardwareBuffer) {
        LOGE("Failed to extract AHardwareBuffer from object");
        return JNI_FALSE;
    }

    jbyte* depthBytes = nullptr;
    if (depthY8Array != nullptr) {
        depthBytes = env->GetByteArrayElements(depthY8Array, nullptr);
    }

    bool ok = gPipeline->renderFrame(
            hardwareBuffer,
            reinterpret_cast<const uint8_t*>(depthBytes),
            static_cast<uint32_t>(depthW),
            static_cast<uint32_t>(depthH),
            fStop,
            targetFarFieldPx
    );

    if (depthBytes != nullptr) {
        env->ReleaseByteArrayElements(depthY8Array, depthBytes, JNI_ABORT);
    }

    return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_zenith_auxcam_MainActivity_nativeSetCameraOpticalParams(
        JNIEnv* env, jobject thiz, jfloat focalMm, jfloat sensorWidthMm, jfloat baseFStop) {
    if (gPipeline) {
        gPipeline->setCameraOpticalParams(focalMm, sensorWidthMm, baseFStop);
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_zenith_auxcam_MainActivity_nativeProcessStillBokeh(
        JNIEnv* env, jobject thiz, jobject hardwareBufferObj, jbyteArray depthY8Array, jint depthW, jint depthH, jfloat fStop, jfloat targetFarFieldPx, jstring outPathStr, jlongArray timestampsOut) {
    if (!gPipeline) {
        gPipeline = std::make_unique<VulkanPipeline>();
        if (!gPipeline->init(nullptr, 1440, 1080)) {
            LOGE("Failed to auto-init VulkanPipeline for still bokeh");
            gPipeline.reset();
            return JNI_FALSE;
        }
    }

    AHardwareBuffer* hb = AHardwareBuffer_fromHardwareBuffer(env, hardwareBufferObj);
    if (!hb) {
        LOGE("Failed to get AHardwareBuffer from object");
        return JNI_FALSE;
    }

    jbyte* depthBytes = nullptr;
    if (depthY8Array != nullptr) {
        depthBytes = env->GetByteArrayElements(depthY8Array, nullptr);
    }

    const char* outPath = nullptr;
    if (outPathStr != nullptr) {
        outPath = env->GetStringUTFChars(outPathStr, nullptr);
    }

    int64_t ts[3] = {0, 0, 0};
    LOGI("nativeProcessStillBokeh: calling processStillBokeh with outPath=%s", outPath ? outPath : "null");
    bool ok = gPipeline->processStillBokeh(
            hb,
            reinterpret_cast<const uint8_t*>(depthBytes),
            static_cast<uint32_t>(depthW),
            static_cast<uint32_t>(depthH),
            fStop,
            targetFarFieldPx,
            outPath,
            ts
    );
    LOGI("nativeProcessStillBokeh: processStillBokeh returned ok=%d", ok);

    if (depthBytes != nullptr) {
        env->ReleaseByteArrayElements(depthY8Array, depthBytes, JNI_ABORT);
    }
    if (outPath != nullptr) {
        env->ReleaseStringUTFChars(outPathStr, outPath);
    }

    if (timestampsOut != nullptr && env->GetArrayLength(timestampsOut) >= 3) {
        jlong jts[3] = { static_cast<jlong>(ts[0]), static_cast<jlong>(ts[1]), static_cast<jlong>(ts[2]) };
        env->SetLongArrayRegion(timestampsOut, 0, 3, jts);
    }

    return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_zenith_auxcam_MainActivity_nativeSetThermalThrottled(JNIEnv* env, jclass clazz, jboolean throttled) {
    if (gPipeline) {
        gPipeline->setThermalThrottled(throttled == JNI_TRUE);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_zenith_auxcam_MainActivity_nativeDestroyEngine(JNIEnv* env, jobject thiz) {
    LOGI("nativeDestroyEngine called");
    if (gPipeline) {
        gPipeline->destroy();
        gPipeline.reset();
    }
}
