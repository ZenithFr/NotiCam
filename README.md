# NotiCam

An experimental, CMF Phone 2 Pro–focused Android camera. The priority is **the
best source image with as little additional processing as the hardware allows**.
A later phase will explore adaptive, specialized-AI post-processing; it is not
part of the default capture path today.

## Latest controls / RAW / HDR status

The top-left title is replaced by RAW; hold it for **3 seconds** for native
12/50 MP choices and bounded frame counts. The mode switch is now a circular
button beside the shutter. ISO/SHUTTER snap to stops, 50 MP no longer has an
artificial manual-control ban, and WB has an approximate temperature slider.
All sensor/RAW choices remain hardware-gated. **HDR merging and Ultra HDR export
are still unimplemented**, not hidden settings. True camera-backdrop blur is also
pending; the new chrome uses translucent matte texture instead. See
[current changes, caveats and next implementations](design/CONTROLS_RAW_HDR_STATUS.md).

## Current increment: video profiles / APU portrait replacement

Video now has separate **1080 / 4K** and **24/30/60** target controls (4K: 24/30),
with exact camera/encoder capability checks and no overclock or 30-fps validation
fallback. Native/full-field framing is retained; unsupported profiles stay
unavailable rather than silently cropping/upscaling.

**Portrait is temporarily unavailable.** The old MediaPipe/MODNet engines have
been removed as requested. The trained INT8 MobileNetV4-Conv-Medium LRASPP/matting
export and verified APU-only runtime were not supplied, so the app cannot enable
a substitute CPU/GPU/depth-only effect. The requested full 512² float-alpha
pipeline is not claimed complete. Sharp photos remain available. See
[implementation, blockers and validation](design/APU_PORTRAIT_VIDEO_PROFILES.md).

## Current capture contract

| Mode / feature | Behavior |
| --- | --- |
| Photo | Selected sensor’s largest advertised standard 4:3 JPEG (up to 20 MP); quality 100; original HAL bytes preserved. 12 MP YUV/Vulkan processing only with portrait effect on |
| Video | H.264 MP4; selectable 1080/4K and 24/30/60 targets (4K: 24/30), gated by native-aspect camera/YUV/AVC support; AAC with microphone permission; start/stop and timer |
| Portrait button | Unavailable pending the trained INT8 MobileNetV4 matting export and verified APU-only runtime; no alternate inference backend |
| Camera processing | Request noise reduction OFF (or MINIMAL if advertised) and edge enhancement OFF when supported |
| Lenses | Main `0` (stereo `5` for portrait), ultra-wide `2`, optical tele `3`. The 2× chip selects tele automatically, without another 2× digital crop; no tele-assist setting |
| Framing | Native-aspect framing (4:3 on the target phone); no imposed 16:9 video mask/fallback. Electronic stabilization off; rotate-and-crop NONE when advertised. Native aspect is not a claim of unbinned/full-pixel sensor video |
| Sensor RAW / DNG | Metadata-gated 1–4 separate RAW_SENSOR DNGs, exact timestamp pairing and bounded payload; no merging |
| 50 MP | Separate main-camera JPEG path using advertised ordinary/high-resolution or maximum-resolution sensor-pixel-mode sizes; JPEG quality 100; no upscaling or second JPEG encode |
| Orientation | Portrait UI; JPEG/recording orientation uses sensor metadata; original JPEG EXIF is preserved and the viewer honors rotation/mirroring |
| Saving | Pending MediaStore row published only after a valid JPEG/MP4 is written; failed/empty rows are deleted |
| Viewer | In-app Photos-inspired viewer for NotiCam-owned images/videos: paging, full-image fit, pinch/double-tap zoom, filmstrip, favorites, sharing, confirmed deletion and rotated-metadata-fit video playback; actions lifted above physical dialog system-bar/cutout insets |

**YUV is not unprocessed sensor RAW.** Camera ISP demosaic, color conversion,
white balance and potentially other vendor processing still apply. JPEG 100 is
also not lossless. The optional DNG path uses advertised `RAW_SENSOR`, matched capture metadata
and `DngCreator`; it does not turn YUV into Bayer RAW or enable RAW video.

**Log / RAW footage:** the recorder is SDR H.264, not Log or RAW. Camera report now
includes RAW formats/sizes/minimum frame durations, 10-bit/HDR profiles (Android
13+), P010 sizes and AVC/HEVC encoder capabilities. Shizuku can access permitted
camera metadata/devices; it cannot manufacture a hidden HAL readout or OEM Log
mode. See [native video, teardown and RAW/Log feasibility](design/NATIVE_VIDEO_AND_RAW.md).

On pause, camera sessions and native preview resources are released. On controller
exit or client-process death, only NotiCam's dedicated Shizuku user-service process
is terminated after orderly cleanup (with a stuck-HAL watchdog). Already queued
file saves drain in the app process; cameraserver and the app are not killed.

50 MP sharp JPEG is metadata-gated per selected lens, using the native optical
zoom stop (1× main / 2× telephoto where genuinely exposed). Discovery checks queryable rear main-field-of-view
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
- `BokehProcessor.kt` / `BokehEngines.kt`: copied-input/worker infrastructure;
  replacement inference fails explicitly until the trained APU runtime exists.
- `PortraitRuntime.kt`: strict unavailable state, required 512² input contract;
  no CPU/GPU or partially delegated inference fallback.
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

## Portrait replacement status

Only the requested `mobilenetv4_conv_medium_int8` ID remains. No trained weights
are bundled, no NNAPI delegate is labeled as proof of APU execution, and portrait
cannot enable until the model/runtime integration is implemented and verified.
The copied-input preparation is 512×512; the saved frame is not downsampled.
Existing native refinement/disc-blur infrastructure is retained, not advertised
as a working 512² float-alpha/hex pipeline. See
[portrait requirements](design/BOKEH_MODELS.md).

## Video and viewer

Choose a lens before recording, select Video and tap the red shutter. Microphone
permission is requested on first use. If denied, an explicit message explains
that another tap records silently. The stop square and clock represent actual
recording state. Camera/mode changes are locked during recording; backgrounding
stops/finalizes the clip and releases the microphone. A 15-minute safety limit
also stops recording. Invalid/too-short clips are discarded, not published as
successful videos. 4K is selectable only when the native-aspect camera/encoder intersection
allows it. Pause/resume recording, EIS and video matting remain unimplemented.

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
