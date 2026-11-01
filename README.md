# NotiCam

An experimental, CMF Phone 2 Pro–focused Android camera. The priority is **the
best source image with as little additional processing as the hardware allows**.
A later phase will explore adaptive, specialized-AI post-processing; it is not
part of the default capture path today.

## Current capture contract

| Mode / feature | Behavior |
| --- | --- |
| Photo | Sharp 4080 × 3072 YUV → RGB → JPEG, quality 100; no app bokeh |
| Pro | The same sharp output, with exposure and manual-focus controls; no extra NR/sharpening |
| Portrait | Optional depth/matte bokeh; displayed aperture is **simulated**, not a movable lens iris |
| Camera processing | Request noise reduction OFF (or MINIMAL if advertised) and edge enhancement OFF when supported |
| Lenses | Device-specific main/stereo `5`, ultra-wide `2`, tele `3`; tele assist is an explicit setting |
| Framing | Native sensor 4:3 only; custom crops are not implemented |
| Sensor RAW / DNG | **Not implemented.** The old RAW toggle only dumped a Y plane and has been removed |
| 50 MP | **Not supported by the fixed-size native readback.** The misleading toggle has been removed |
| Orientation | Portrait preview; sensor-landscape JPEG with 90° EXIF orientation |
| Saving | Pending MediaStore row published only after successful encoding; failed rows are deleted |

**YUV is not unprocessed sensor RAW.** Camera ISP demosaic, color conversion,
white balance and potentially other vendor processing still apply. JPEG 100 is
also not lossless. The app does not yet have a Bayer `RAW_SENSOR` + capture
metadata + `DngCreator` path, and must not claim otherwise.

Auxiliary-lens stream combinations, flash timing and vendor behavior require
on-device verification. This project is not a general-purpose camera for other
phones. Permission to use the privileged camera service is provided by Shizuku.

## Architecture

- `MainActivity.kt`: Compose host, lifecycle bridge, JNI entry points and APK asset extraction.
- `CameraController.kt`: Shizuku binding, ImageReaders, capture admission, frame matching and storage.
- `FrameData.kt`: pure buffer-packing, timestamp-matching and matte-orientation rules.
- `CameraUserService.java`: privileged Camera2 session and controls, serialized on its camera handler.
- `DualMatteSegmenter.kt`: upright MediaPipe inference; output mapped back into sensor space.
- `jni/src/`: Vulkan/YUV pipeline and a synchronized JNI lifetime boundary.
- `jni/shaders/`: CoC, matte refinement, gather and composition.
- `src/com/zenith/auxcam/ui/`: Compose camera UI, warm accent, persistent status, settings and shutter.

The UI keeps chrome outside the viewfinder. Capture is single-flight through
saving, not just until a camera buffer arrives. Depth is selected by sensor
timestamp within 100 ms; mattes older than 500 ms are discarded. These are
bounded best-effort matches, **not a claim of hardware-synchronized still depth**.
Lens/mode changes invalidate auxiliary frames.

### Portrait repair notes

- Float model input is RGB `[0, 1]`, matching the upstream
  [MediaPipe Selfie Segmentation graph](https://github.com/google-ai-edge/mediapipe/blob/master/mediapipe/modules/selfie_segmentation/selfie_segmentation_gpu.pbtxt).
  The previous code used `[-1, 1]`.
- Models now receive an upright image; their matte is transformed back to the
  sensor orientation expected by Vulkan.
- Prepared inference tier is held across preprocessing/inference so a thermal
  tier change cannot make inference consume an unprepared general-model tensor.
- Preview and still CoC use the same 720-pixel reference width. Zero blur really
  means zero, and Photo/Pro composition directly selects the sharp original.
- Missing depth no longer creates a fabricated central oval. A real matte can
  support matte-only blur; with neither signal, output stays sharp.
- The current uploaded matte is sampled, not the previous double-buffer image.
- Still gather retains 32 taps; preview uses 16. Artificial highlight boost is off.
- Native preview drops a frame rather than racing a still or engine teardown.
  Serialization favors correctness over the previous unverified interleaving
  performance claims; measure its latency and preview gaps on-device.

## Build

This is a **single root Gradle application module**, using the original
`src/`, `res/`, `assets/`, `jni/` layout. `app/` is not a Gradle module.

Prerequisites:

- JDK 17 and Gradle 8.9 (there is currently no committed Gradle wrapper).
- Android SDK platform 35 and suitable Android build tools.
- NDK `27.1.12297006`; native configuration currently targets Android 35 / arm64.
- `glslc` on PATH, or `GLSLC=/absolute/path/to/glslc`.
- Access to Google Maven, Maven Central and the Gradle plugin repositories.

```bash
export ANDROID_HOME=/path/to/android-sdk
export JAVA_HOME=/path/to/jdk-17
# Optional: export GLSLC=/path/to/glslc

./build.sh check                 # source contracts / optical math; shaders if glslc exists
./build.sh                      # Gradle assembleDebug
# Or with Gradle directly:
gradle testDebugUnitTest         # buffer, matching, orientation unit tests
gradle check                     # Android checks + repository source checks
gradle assembleDebug
adb install -r build/outputs/apk/debug/NotiCam-debug.apk
```

Check the actual filename under `build/outputs/apk/debug/` before installing.
The build script respects your environment; it no longer overwrites SDK/JDK
paths with a developer's home-directory paths. Gradle compiles compute shaders
before building the APK. `-PskipShaders` is only appropriate when you already
have matching generated SPIR-V files locally; those files are not committed.
Shaders are refreshed atomically from the installed APK at launch, including
updates whose byte length happens to be unchanged.

## Verification and remaining work

See [the stabilization checklist](design/STABILIZATION.md) for tests performed,
known limitations, a physical-device test matrix and suggested next milestones.
The existing `design/index.html` is a historical mock, not the current Compose UI.
