#!/usr/bin/env bash
#
# NotiCam build entry point.
#
# The app is now a Gradle + Kotlin + Compose project. This script is a thin,
# opinionated wrapper that runs the checks Gradle cannot run in a bare sandbox
# and then hands over to Gradle:
#
#   1. tools/verify_ui.py     — resources, braces, imports, Compose member refs
#   2. glslc                  — jni/shaders/*.comp → assets/shaders/*.spv
#   3. ./gradlew assembleDebug (or `gradle` if there is no wrapper)
#
# Usage:
#   ./build.sh              debug APK
#   ./build.sh release      release APK (unsigned unless you add a keystore)
#   ./build.sh check        verification only, no APK
set -euo pipefail

if [ -d "/home/zenith/.local/share/mise/installs/java/temurin-17.0.21+9" ]; then
    export JAVA_HOME="/home/zenith/.local/share/mise/installs/java/temurin-17.0.21+9"
    export PATH="$JAVA_HOME/bin:$PATH"
fi

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TASK="${1:-debug}"

cd "$ROOT_DIR"

echo "=== Verifying UI (resources · braces · imports · Compose refs) ==="
python3 "$ROOT_DIR/tools/verify_ui.py"

echo "=== Verifying CoC unit calibration ==="
if [ -f "$ROOT_DIR/tests/test_coc_units.py" ]; then
    python3 "$ROOT_DIR/tests/test_coc_units.py"
fi

echo "=== Compiling SPIR-V compute shaders ==="
GLSLC_BIN="${GLSLC:-glslc}"
mkdir -p "$ROOT_DIR/assets/shaders"
if command -v "$GLSLC_BIN" >/dev/null 2>&1; then
    for f in "$ROOT_DIR"/jni/shaders/*.comp; do
        [ -f "$f" ] || continue
        base="$(basename "$f" .comp)"
        "$GLSLC_BIN" "$f" -o "$ROOT_DIR/assets/shaders/$base.spv"
        echo "  · $base.spv"
    done
else
    echo "  ! glslc not found — set GLSLC=/path/to/glslc or pass -PskipShaders to Gradle"
    echo "    (continuing: committed .spv files, if any, will be used)"
fi

if [ "$TASK" = "check" ]; then
    echo "=== Verification only — done ==="
    exit 0
fi

echo "=== Gradle build ($TASK) ==="
if [ -x "$ROOT_DIR/gradlew" ]; then
    GRADLE_CMD="$ROOT_DIR/gradlew"
elif command -v gradle >/dev/null 2>&1; then
    GRADLE_CMD="gradle"
    echo "  ! no ./gradlew in the repo — falling back to the system gradle."
    echo "    Generate a wrapper with: gradle wrapper --gradle-version 8.9"
else
    echo "  ✗ Neither ./gradlew nor gradle is installed." >&2
    echo "    Install Gradle 8.9+ (or add a wrapper) and re-run." >&2
    exit 1
fi

case "$TASK" in
    debug)   "$GRADLE_CMD" assembleDebug ;;
    release) "$GRADLE_CMD" assembleRelease ;;
    *)       "$GRADLE_CMD" "$TASK" ;;
esac

echo "=== Build Complete ==="
echo "APK: $ROOT_DIR/app/build/outputs/apk/${TASK}/"
