#!/usr/bin/env bash
# Portable build entry point. Set ANDROID_HOME / JAVA_HOME for your machine.
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TASK="${1:-debug}"
cd "$ROOT_DIR"

python3 tools/verify_ui.py
python3 tests/test_coc_units.py
python3 -m unittest discover -s tests -p 'test_*.py'

if [ "$TASK" = "check" ]; then
    GLSLC_BIN="${GLSLC:-glslc}"
    if command -v "$GLSLC_BIN" >/dev/null 2>&1; then
        mkdir -p assets/shaders
        for shader in jni/shaders/*.comp; do
            "$GLSLC_BIN" "$shader" -o "assets/shaders/$(basename "$shader" .comp).spv"
        done
        echo "Shader compilation passed."
    else
        echo "Shader compilation not run: install glslc or set GLSLC."
    fi
    echo "Source checks passed. This does not replace an Android build or device tests."
    exit 0
fi

if [ -x ./gradlew ]; then
    GRADLE_CMD="$ROOT_DIR/gradlew"
elif command -v gradle >/dev/null 2>&1; then
    GRADLE_CMD=gradle
else
    echo "Gradle is not installed and no wrapper is present. Install Gradle 8.9 + JDK 17." >&2
    exit 1
fi

case "$TASK" in
    debug) "$GRADLE_CMD" assembleDebug ;;
    release) "$GRADLE_CMD" assembleRelease ;;
    *) "$GRADLE_CMD" "$TASK" ;;
esac

echo "APK outputs: $ROOT_DIR/build/outputs/apk/"
