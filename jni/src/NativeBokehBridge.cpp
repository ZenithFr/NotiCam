#include <jni.h>
#include <android/native_window_jni.h>
#include <android/hardware_buffer_jni.h>
#include <android/log.h>
#include <memory>
#include <mutex>
#include <cmath>
#include "VulkanPipeline.h"

#define LOG_TAG "NativeBokehBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static std::unique_ptr<VulkanPipeline> gPipeline;
// Pipeline resources and the YUV importer are shared by preview, still and UI
// callbacks. A queue-submit mutex alone does not protect descriptor updates or
// destruction. Drop a preview frame rather than blocking behind a still.
static std::mutex gPipelineMutex;

static bool validPlane(JNIEnv* env, jbyteArray data, jint w, jint h) {
    return !data || (w > 0 && h > 0 &&
        static_cast<int64_t>(w) * h <= env->GetArrayLength(data));
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_zenith_auxcam_MainActivity_nativeInitEngine(
        JNIEnv* env, jobject thiz, jobject surface, jint width, jint height) {
    std::lock_guard<std::mutex> lock(gPipelineMutex);
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
        JNIEnv* env, jobject thiz, jobject hardwareBufferObj,
        jbyteArray depthY8Array, jint depthW, jint depthH,
        jbyteArray alphaMatteArray, jint alphaW, jint alphaH,
        jfloat fStop, jfloat targetFarFieldPx) {
    std::unique_lock<std::mutex> lock(gPipelineMutex, std::try_to_lock);
    if (!lock.owns_lock()) return JNI_FALSE;
    if (!validPlane(env, depthY8Array, depthW, depthH) ||
        !validPlane(env, alphaMatteArray, alphaW, alphaH) ||
        !std::isfinite(fStop) || !std::isfinite(targetFarFieldPx)) return JNI_FALSE;

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

    jbyte* alphaBytes = nullptr;
    if (alphaMatteArray != nullptr) {
        alphaBytes = env->GetByteArrayElements(alphaMatteArray, nullptr);
    }

    bool ok = gPipeline->renderFrame(
            hardwareBuffer,
            reinterpret_cast<const uint8_t*>(depthBytes),
            static_cast<uint32_t>(depthW),
            static_cast<uint32_t>(depthH),
            reinterpret_cast<const uint8_t*>(alphaBytes),
            static_cast<uint32_t>(alphaW),
            static_cast<uint32_t>(alphaH),
            fStop,
            targetFarFieldPx
    );

    jclass cls = env->FindClass("com/zenith/auxcam/CameraUserService");
    if (cls) {
        jfieldID fid = env->GetStaticFieldID(cls, "sLatestS1Matte", "F");
        if (fid) env->SetStaticFloatField(cls, fid, gPipeline->getS1FocusM());
        env->DeleteLocalRef(cls);
    }

    if (depthBytes != nullptr) {
        env->ReleaseByteArrayElements(depthY8Array, depthBytes, JNI_ABORT);
    }
    if (alphaBytes != nullptr) {
        env->ReleaseByteArrayElements(alphaMatteArray, alphaBytes, JNI_ABORT);
    }

    return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_zenith_auxcam_MainActivity_nativeSetCameraOpticalParams(
        JNIEnv* env, jobject thiz, jfloat focalMm, jfloat sensorWidthMm, jfloat baseFStop) {
    std::lock_guard<std::mutex> lock(gPipelineMutex);
    if (gPipeline) {
        gPipeline->setCameraOpticalParams(focalMm, sensorWidthMm, baseFStop);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_zenith_auxcam_MainActivity_nativeSetFocusDistance(
        JNIEnv* env, jclass clazz, jfloat s1FocusM) {
    std::lock_guard<std::mutex> lock(gPipelineMutex);
    if (gPipeline) {
        gPipeline->setS1FocusM(s1FocusM);
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_zenith_auxcam_MainActivity_nativeProcessStillBokeh(
        JNIEnv* env, jobject thiz, jobject hardwareBufferObj, jbyteArray depthY8Array, jint depthW, jint depthH, jbyteArray alphaMatteArray, jint alphaW, jint alphaH, jfloat fStop, jfloat targetFarFieldPx, jstring outPathStr, jlongArray timestampsOut) {
    std::lock_guard<std::mutex> lock(gPipelineMutex);
    if (!validPlane(env, depthY8Array, depthW, depthH) ||
        !validPlane(env, alphaMatteArray, alphaW, alphaH) ||
        !std::isfinite(fStop) || !std::isfinite(targetFarFieldPx)) return JNI_FALSE;

    // A received still may finish after pause destroyed the preview. Keep that
    // work, but don't resurrect a persistent global GPU pipeline in background.
    // This local owner's destructor frees even on early failure, while the
    // pipeline mutex is still held. VulkanPipeline::~VulkanPipeline calls destroy.
    std::unique_ptr<VulkanPipeline> headlessStillPipeline;
    VulkanPipeline* stillPipeline = gPipeline.get();
    if (!stillPipeline) {
        headlessStillPipeline = std::make_unique<VulkanPipeline>();
        if (!headlessStillPipeline->init(nullptr, 1440, 1080)) {
            LOGE("Failed to init temporary VulkanPipeline for still bokeh");
            return JNI_FALSE;
        }
        stillPipeline = headlessStillPipeline.get();
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

    jbyte* alphaBytes = nullptr;
    if (alphaMatteArray != nullptr) {
        alphaBytes = env->GetByteArrayElements(alphaMatteArray, nullptr);
    }

    const char* outPath = nullptr;
    if (outPathStr != nullptr) {
        outPath = env->GetStringUTFChars(outPathStr, nullptr);
    }

    int64_t ts[4] = {0, 0, 0, 0};
    LOGI("nativeProcessStillBokeh: calling processStillBokeh with outPath=%s, alphaMatte=%p (%dx%d)", outPath ? outPath : "null", alphaBytes, alphaW, alphaH);
    bool ok = stillPipeline->processStillBokeh(
            hb,
            reinterpret_cast<const uint8_t*>(depthBytes),
            static_cast<uint32_t>(depthW),
            static_cast<uint32_t>(depthH),
            reinterpret_cast<const uint8_t*>(alphaBytes),
            static_cast<uint32_t>(alphaW),
            static_cast<uint32_t>(alphaH),
            fStop,
            targetFarFieldPx,
            outPath,
            ts
    );
    LOGI("nativeProcessStillBokeh: processStillBokeh returned ok=%d", ok);

    if (depthBytes != nullptr) {
        env->ReleaseByteArrayElements(depthY8Array, depthBytes, JNI_ABORT);
    }
    if (alphaBytes != nullptr) {
        env->ReleaseByteArrayElements(alphaMatteArray, alphaBytes, JNI_ABORT);
    }
    if (outPath != nullptr) {
        env->ReleaseStringUTFChars(outPathStr, outPath);
    }

    if (timestampsOut != nullptr) {
        jsize len = env->GetArrayLength(timestampsOut);
        jlong jts[4] = { static_cast<jlong>(ts[0]), static_cast<jlong>(ts[1]), static_cast<jlong>(ts[2]), static_cast<jlong>(ts[3]) };
        jsize toCopy = len < 4 ? len : 4;
        env->SetLongArrayRegion(timestampsOut, 0, toCopy, jts);
    }

    return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_zenith_auxcam_MainActivity_nativeSetThermalThrottled(JNIEnv* env, jclass clazz, jboolean throttled) {
    std::lock_guard<std::mutex> lock(gPipelineMutex);
    if (gPipeline) {
        gPipeline->setThermalThrottled(throttled == JNI_TRUE);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_zenith_auxcam_MainActivity_nativeSetDisableGuided(JNIEnv* env, jclass clazz, jboolean disable) {
    std::lock_guard<std::mutex> lock(gPipelineMutex);
    if (gPipeline) {
        gPipeline->setDisableGuided(disable == JNI_TRUE);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_zenith_auxcam_MainActivity_nativeSetEdgeRefineOverride(JNIEnv* env, jclass clazz, jint edgeRefine) {
    std::lock_guard<std::mutex> lock(gPipelineMutex);
    VulkanPipeline::setEdgeRefineOverride(edgeRefine);
}

extern "C" JNIEXPORT void JNICALL
Java_com_zenith_auxcam_MainActivity_nativeDestroyEngine(JNIEnv* env, jobject thiz) {
    std::lock_guard<std::mutex> lock(gPipelineMutex);
    LOGI("nativeDestroyEngine called");
    if (gPipeline) {
        gPipeline->destroy();
        gPipeline.reset();
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_zenith_auxcam_MainActivity_nativeAcquireHardwareBuffer(JNIEnv* env, jobject thiz, jobject hbObj) {
    std::lock_guard<std::mutex> lock(gPipelineMutex);
    if (!hbObj) return;
    AHardwareBuffer* hb = AHardwareBuffer_fromHardwareBuffer(env, hbObj);
    if (hb) {
        AHardwareBuffer_acquire(hb);
        if (gPipeline) gPipeline->incrementAhbRef();
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_zenith_auxcam_MainActivity_nativeReleaseHardwareBuffer(JNIEnv* env, jobject thiz, jobject hbObj) {
    std::lock_guard<std::mutex> lock(gPipelineMutex);
    if (!hbObj) return;
    AHardwareBuffer* hb = AHardwareBuffer_fromHardwareBuffer(env, hbObj);
    if (hb) {
        AHardwareBuffer_release(hb);
        if (gPipeline) gPipeline->decrementAhbRef();
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_zenith_auxcam_MainActivity_nativeNotifyMatteDrop(JNIEnv* env, jobject thiz) {
    std::lock_guard<std::mutex> lock(gPipelineMutex);
    if (gPipeline) {
        gPipeline->notifyMatteDrop();
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_zenith_auxcam_MainActivity_nativeGetAhbRefcount(JNIEnv* env, jobject thiz) {
    std::lock_guard<std::mutex> lock(gPipelineMutex);
    return gPipeline ? gPipeline->getAhbRefcount() : 0;
}
