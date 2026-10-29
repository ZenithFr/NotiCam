#!/usr/bin/env bash
set -e

BUILD_TOOLS_DIR="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/home/zenith/Android/Sdk}}"
NDK_DIR="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-/home/zenith/Work/android-ndk-r27b}}"
BUILD_TOOLS="$BUILD_TOOLS_DIR/build-tools/34.0.0"
PLATFORM="$BUILD_TOOLS_DIR/platforms/android-35/android.jar"
FRAMEWORK_AIDL="$BUILD_TOOLS_DIR/platforms/android-35/framework.aidl"
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "=== Cleaning previous build artifacts ==="
rm -rf "$ROOT_DIR/build"
rm -rf "$ROOT_DIR/gen"
mkdir -p "$ROOT_DIR/build/classes"
mkdir -p "$ROOT_DIR/build/dex"
mkdir -p "$ROOT_DIR/gen"

echo "=== Compiling AIDL interfaces ==="
"$BUILD_TOOLS/aidl" -I"$ROOT_DIR/src" -p"$FRAMEWORK_AIDL" -o"$ROOT_DIR/gen" "$ROOT_DIR/src/com/zenith/auxcam/IAuxCamCallback.aidl"
"$BUILD_TOOLS/aidl" -I"$ROOT_DIR/src" -p"$FRAMEWORK_AIDL" -o"$ROOT_DIR/gen" "$ROOT_DIR/src/com/zenith/auxcam/IAuxCamService.aidl"

echo "=== Compiling Android Resources ==="
"$BUILD_TOOLS/aapt2" compile --dir "$ROOT_DIR/res" -o "$ROOT_DIR/build/res.zip"
"$BUILD_TOOLS/aapt2" link -I "$PLATFORM" \
    --manifest "$ROOT_DIR/AndroidManifest.xml" \
    -o "$ROOT_DIR/build/auxcam_unaligned.apk" \
    --java "$ROOT_DIR/gen" \
    "$ROOT_DIR/build/res.zip" \
    --auto-add-overlay

echo "=== Compiling SPIR-V Compute Shaders ==="
mkdir -p "$ROOT_DIR/assets/shaders"
for f in "$ROOT_DIR"/jni/shaders/*.comp; do
    if [ -f "$f" ]; then
        base=$(basename "$f" .comp)
        /usr/bin/glslc "$f" -o "$ROOT_DIR/assets/shaders/$base.spv"
    fi
done

echo "=== Running CoC Unit Hazard & Calibration Verification ==="
python3 "$ROOT_DIR/tests/test_coc_units.py"

echo "=== Compiling Native C++ with NDK ==="
if [ -d "$ROOT_DIR/jni" ]; then
    "$NDK_DIR/ndk-build" -C "$ROOT_DIR/jni" NDK_OUT="$ROOT_DIR/build/ndk-out" NDK_LIBS_OUT="$ROOT_DIR/lib"
    # Ensure TFLite C/JNI binaries remain present alongside engine
    if [ -d "$ROOT_DIR/tflite_extracted/jni/arm64-v8a" ]; then
        cp -n "$ROOT_DIR"/tflite_extracted/jni/arm64-v8a/*.so "$ROOT_DIR/lib/arm64-v8a/" 2>/dev/null || true
    fi
fi

echo "=== Compiling Java Source Files ==="
javac -source 17 -target 17 -encoding UTF-8 \
    -cp "$PLATFORM:$ROOT_DIR/libs/*" \
    -d "$ROOT_DIR/build/classes" \
    $(find "$ROOT_DIR/src" "$ROOT_DIR/gen" -name "*.java")

echo "=== Running D8 DEX compiler ==="
"$BUILD_TOOLS/d8" \
    --lib "$PLATFORM" \
    --output "$ROOT_DIR/build/dex" \
    $(find "$ROOT_DIR/build/classes" -name "*.class") \
    "$ROOT_DIR"/libs/*.jar

echo "=== Packaging APK ==="
cd "$ROOT_DIR/build/dex"
zip -u "$ROOT_DIR/build/auxcam_unaligned.apk" classes*.dex
cd "$ROOT_DIR"
if [ -d "$ROOT_DIR/lib" ]; then
    zip -u -r "$ROOT_DIR/build/auxcam_unaligned.apk" lib/
fi
if [ -d "$ROOT_DIR/assets" ]; then
    zip -0 -u -r "$ROOT_DIR/build/auxcam_unaligned.apk" assets/
fi

echo "=== Zipalign ==="
"$BUILD_TOOLS/zipalign" -f -p 4 "$ROOT_DIR/build/auxcam_unaligned.apk" "$ROOT_DIR/build/auxcam_aligned.apk"

echo "=== Signing APK ==="
KEYSTORE="${DEBUG_KEYSTORE:-$HOME/.android/debug.keystore}"
if [ ! -f "$KEYSTORE" ]; then
    mkdir -p "$(dirname "$KEYSTORE")"
    keytool -genkeypair -v -keystore "$KEYSTORE" -alias androiddebugkey -dname "CN=Android Debug,O=Android,C=US" -validity 10000 -keypass android -storepass android -storetype JKS
fi
"$BUILD_TOOLS/apksigner" sign \
    --ks "$KEYSTORE" \
    --ks-pass pass:android \
    --key-pass pass:android \
    "$ROOT_DIR/build/auxcam_aligned.apk"

echo "=== Build Complete: $ROOT_DIR/build/auxcam_aligned.apk ==="
