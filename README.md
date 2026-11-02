# NotiCam

An experimental, CMF Phone 2 Pro–focused Android camera. The priority is **the
best source image with as little additional processing as the hardware allows**.
A later phase will explore adaptive, specialized-AI post-processing; it is not
part of the default capture path today.

## Current capture contract

| Mode / feature | Behavior |
| --- | --- |
| Photo | Selected sensor’s largest advertised standard 4:3 JPEG (up to 20 MP); quality 100; original HAL bytes preserved. 12 MP YUV/Vulkan processing only with portrait effect on |
| Video | H.264 MP4 at the largest advertised native-sensor-aspect recorder size supported by a hardware surface-input AVC encoder at 30 fps; AAC audio with microphone permission; start/stop and timer |
| Portrait button | Optional 12 MP depth/matte bokeh inside Photo; displayed aperture is **simulated**, not a movable lens iris |
| Camera processing | Request noise reduction OFF (or MINIMAL if advertised) and edge enhancement OFF when supported |
| Lenses | Main `0` (stereo `5` for portrait), ultra-wide `2`, optical tele `3`. The 2× chip selects tele automatically, without another 2× digital crop; no tele-assist setting |
| Framing | Native-aspect framing (4:3 on the target phone); no imposed 16:9 video mask/fallback. Electronic stabilization off; rotate-and-crop NONE when advertised. Native aspect is not a claim of unbinned/full-pixel sensor video |
| Sensor RAW / DNG | **Not implemented.** The old RAW toggle only dumped a Y plane and has been removed |
| 50 MP | Separate main-camera JPEG path using advertised ordinary/high-resolution or maximum-resolution sensor-pixel-mode sizes; JPEG quality 100; no upscaling or second JPEG encode |
| Orientation | Portrait UI; JPEG/recording orientation uses sensor metadata; original JPEG EXIF is preserved and the viewer honors rotation/mirroring |
| Saving | Pending MediaStore row published only after a valid JPEG/MP4 is written; failed/empty rows are deleted |
| Viewer | In-app Photos-inspired viewer for NotiCam-owned images/videos: paging, full-image fit, pinch/double-tap zoom, filmstrip, favorites, sharing, confirmed deletion and rotated-metadata-fit video playback; actions lifted above physical dialog system-bar/cutout insets |

**YUV is not unprocessed sensor RAW.** Camera ISP demosaic, color conversion,
white balance and potentially other vendor processing still apply. JPEG 100 is
also not lossless. The app does not yet have a Bayer `RAW_SENSOR` + capture
metadata + `DngCreator` path, and must not claim otherwise.

**Log / RAW footage:** the recorder is SDR H.264, not Log or RAW. Camera report now
includes RAW formats/sizes/minimum frame durations, 10-bit/HDR profiles (Android
13+), P010 sizes and AVC/HEVC encoder capabilities. Shizuku can access permitted
camera metadata/devices; it cannot manufacture a hidden HAL readout or OEM Log
mode. See [native video, teardown and RAW/Log feasibility](design/NATIVE_VIDEO_AND_RAW.md).

On pause, camera sessions and native preview resources are released. On controller
exit or client-process death, only NotiCam's dedicated Shizuku user-service process
is terminated after orderly cleanup (with a stuck-HAL watchdog). Already queued
file saves drain in the app process; cameraserver and the app are not killed.

50 MP is a **main camera, 1×, sharp-capture** option, only when an exposed regular
Camera2 stream supports it. Discovery checks queryable rear main-field-of-view
cameras, including the existing auxiliary routes if enumeration omits them, not just ID 0.
Metadata eligibility is not proof of a successful camera/session open. Vendor/physical-camera and extension metadata are available
through **Camera controls → Camera report**. A stock-camera 50 MP mode may need
OEM-only session/remosaic controls; this implementation does **not** claim to
have unlocked those without a device report. See [50 MP investigation](design/CAMERA_DISCOVERY.md).

When an advertised 50 MP path is available, turning on the portrait
effect or changing zoom/lenses returns to 12 MP. The app validates the encoded
JPEG dimensions against the selected HAL size before publication. A rejected
50 MP stream combination rolls back to 12 MP; unsupported devices never get an
upscaled image labeled 50 MP.

Auxiliary-lens stream combinations, flash timing and vendor behavior require
on-device verification. This project is not a general-purpose camera for other
phones. Permission to use the privileged camera service is provided by Shizuku.

## Architecture

- `MainActivity.kt`: Compose host, lifecycle bridge, JNI entry points and APK asset extraction.
- `CameraController.kt`: Shizuku binding, ImageReaders, capture admission, frame matching and storage.
- `FrameData.kt`: pure buffer-packing, timestamp-matching and matte-orientation rules.
- `CameraUserService.java`: privileged Camera2 session and controls, serialized on its camera handler.
- `BokehProcessor.kt` / `BokehEngines.kt`: worker-owned Auto MediaPipe with
  validated NNAPI/CPU fallback, optional MODNet quality, hash validation and checks.
- `InferenceBackend.kt`: bounded accelerator attempts with warmup and cleanup.
- `jni/src/`: Vulkan/YUV pipeline and a synchronized JNI lifetime boundary.
- `jni/shaders/`: CoC, matte refinement, gather and composition.
- `src/com/zenith/auxcam/ui/`: Compose camera UI, neutral matte surfaces, floating controls, settings and shutter.

The [Nothing-inspired matte UI](design/DESIGN.md) keeps Photo/Video only, rounded
framing, neutral surfaces, white selections and restrained red recording accents.
Controls have padded >=48 dp hit targets; video format uses separate resolution
and rate lines. Surroundings stay neutral rather than sampling photo colors.
This is an independent interpretation, not official Nothing branding; system fonts
are used instead of redistributing proprietary NType/NDot assets. No native
SurfaceView backdrop blur or refraction is claimed.
Traveling mode/zoom indicators, direct-drag sliders, capture pulses, busy state,
filmstrip selection and a successful-save thumbnail handoff provide motion
without animating camera commands, grading captured pixels or resizing the Vulkan
surface. The separate lens-cycle button is gone; zoom still selects lenses.

The viewfinder now targets a full-field **1440×1080 / 60 fps** feed (upright
1080×1440 buffer) when advertised, independently of the saved photo resolution.
Same-lens zoom eases its crop; physical changes use an outgoing-frame zoom/dissolve
that waits for fresh incoming frames. The sharp GPU preview skips unused blur
passes. Camera controls report target versus rendered FPS and any fallback; this
is not a device-verified promise of sustained 60 fps. See
[preview smoothness and quality isolation](design/PREVIEW_SMOOTHNESS.md).

Capture and discovery now share one cached, shell-context CameraManager inside the
Shizuku UserService. **Camera controls → Camera report** shows the actual process
UID, attribution and camera permissions alongside enumerated/queried IDs and HAL
metadata. Shizuku shell privileges are not root, do not grant an OEM app identity,
and do not guarantee vendor-only 50 MP/remosaic controls. No global app-ops,
permissions, sensor-privacy switches or vendor properties are changed.

Capture is single-flight through
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
- Auto uses one validated General float model, not dual max-union or temporal EMA.
  Accelerator failure retries explicit CPU rather than silently dropping matting.
- Preview and still CoC use the same 720-pixel reference width. Zero blur really
  means zero, and Photo without the portrait effect selects the sharp original.
- Missing depth no longer creates a fabricated central oval. A real matte can
  support matte-only blur; with neither signal, output stays sharp.
- The current uploaded matte is sampled, not the previous double-buffer image.
- Still gather retains 32 taps; preview uses 16. Artificial highlight boost is off.
- Native preview drops a frame rather than racing a still or engine teardown.
  Serialization favors correctness over the previous unverified interleaving
  performance claims; measure its latency and preview gaps on-device.

## Bokeh model comparison

**Camera controls → Portrait** selects **Auto** (MediaPipe General with discovered
NNAPI accelerator requests / explicit CPU fallback) or **Portrait quality**
(MODNet CPU). Removed selections migrate to Auto. Both identify people, not
arbitrary object classes; synchronized main-lens depth can assist foreground
objects. No foreground plus no usable depth means a sharp photo, not whole-frame
blur. Expired masks are rejected. Live masking may pause for a persistently slow
model; the received-still inference remains available.

“Check selected” / “Compare both” check the same frame with one untimed warmup and
five timed runs per model. Reports distinguish backend, load time, latency and
flat masks. A runtime PASS does not prove accuracy, sustained FPS or NPU placement.

`python3 tools/fetch_bokeh_models.py` fetches checksum-pinned MODNet to an ignored
folder for APK bundling; `--verify` checks both retained assets offline. MediaPipe
General float and MODNet are Apache-2.0; ONNX Runtime's MIT notice remains. No
runtime image uploads/downloads. See [portrait details](design/BOKEH_MODELS.md)
and [validation / remaining gates](design/NOTHING_UI_PORTRAIT.md).

## Video and viewer

Choose a lens before recording, select Video and tap the red shutter. Microphone
permission is requested on first use. If denied, an explicit message explains
that another tap records silently. The stop square and clock represent actual
recording state. Camera/mode changes are locked during recording; backgrounding
stops/finalizes the clip and releases the microphone. A 15-minute safety limit
also stops recording. Invalid/too-short clips are discarded, not published as
successful videos. 4K, pause/resume recording, EIS and synthetic video bokeh are
not implemented in this basic pass.

The gallery button opens only this app’s captured photos/videos, not the whole
phone library. The viewer displays the full frame initially; only filmstrip
thumbnails are cropped. Originals are never resized/re-encoded by viewing or
sharing. Display decoding is subsampled to at most 2048 pixels on the long edge
to avoid decoding a 50 MP bitmap into a ~200 MB allocation. Camera streaming is
closed while reviewing and restored on return. Favorites are stored locally;
deleting a capture requires confirmation.

Minimum Android version is now **Android 10 / API 29**, matching the transactional
MediaStore and app-owned storage model used by photos, video and the viewer.
No background recording service or broad photo/video-library access is needed.

## Build

This is a **single root Gradle application module**, using the original
`src/`, `res/`, `assets/`, `jni/` layout. `app/` is not a Gradle module.

Prerequisites:

- JDK 17 and Gradle 8.9 (there is currently no committed Gradle wrapper).
- Android SDK platform 35 and suitable Android build tools.
- NDK `27.1.12297006`; native configuration currently targets Android 35 / arm64.
- `glslc` on PATH, or `GLSLC=/absolute/path/to/glslc`.
- Python 3 for pinned model downloads and repository checks.
- Access to GitHub model URLs on the first build (verified models are then cached).
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
`design/index.html` is a new interactive design companion to the Compose UI. Run
`python3 tools/preview_server.py` to review it. It uses a clearly labeled sample
image and simulated capabilities, not a live camera or an Android emulator.
