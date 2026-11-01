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
        JNIEnv* env, jobject thiz, jobject hardwareBufferObj,
        jbyteArray depthY8Array, jint depthW, jint depthH,
        jbyteArray alphaMatteArray, jint alphaW, jint alphaH,
        jfloat fStop, jfloat targetFarFieldPx) {
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
    if (gPipeline) {
        gPipeline->setCameraOpticalParams(focalMm, sensorWidthMm, baseFStop);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_zenith_auxcam_MainActivity_nativeSetFocusDistance(
        JNIEnv* env, jclass clazz, jfloat s1FocusM) {
    if (gPipeline) {
        gPipeline->setS1FocusM(s1FocusM);
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_zenith_auxcam_MainActivity_nativeProcessStillBokeh(
        JNIEnv* env, jobject thiz, jobject hardwareBufferObj, jbyteArray depthY8Array, jint depthW, jint depthH, jbyteArray alphaMatteArray, jint alphaW, jint alphaH, jfloat fStop, jfloat targetFarFieldPx, jstring outPathStr, jlongArray timestampsOut) {
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
    bool ok = gPipeline->processStillBokeh(
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
    if (gPipeline) {
        gPipeline->setThermalThrottled(throttled == JNI_TRUE);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_zenith_auxcam_MainActivity_nativeSetDisableGuided(JNIEnv* env, jclass clazz, jboolean disable) {
    if (gPipeline) {
        gPipeline->setDisableGuided(disable == JNI_TRUE);
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

extern "C" JNIEXPORT void JNICALL
Java_com_zenith_auxcam_MainActivity_nativeAcquireHardwareBuffer(JNIEnv* env, jobject thiz, jobject hbObj) {
    if (!hbObj) return;
    AHardwareBuffer* hb = AHardwareBuffer_fromHardwareBuffer(env, hbObj);
    if (hb) {
        AHardwareBuffer_acquire(hb);
        if (gPipeline) gPipeline->incrementAhbRef();
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_zenith_auxcam_MainActivity_nativeReleaseHardwareBuffer(JNIEnv* env, jobject thiz, jobject hbObj) {
    if (!hbObj) return;
    AHardwareBuffer* hb = AHardwareBuffer_fromHardwareBuffer(env, hbObj);
    if (hb) {
        AHardwareBuffer_release(hb);
        if (gPipeline) gPipeline->decrementAhbRef();
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_zenith_auxcam_MainActivity_nativeNotifyMatteDrop(JNIEnv* env, jobject thiz) {
    if (gPipeline) {
        gPipeline->notifyMatteDrop();
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_zenith_auxcam_MainActivity_nativeGetAhbRefcount(JNIEnv* env, jobject thiz) {
    return gPipeline ? gPipeline->getAhbRefcount() : 0;
}
