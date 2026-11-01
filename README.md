# NotiCam

An experimental, CMF Phone 2 Pro–focused Android camera. The priority is **the
best source image with as little additional processing as the hardware allows**.
A later phase will explore adaptive, specialized-AI post-processing; it is not
part of the default capture path today.

## Current capture contract

| Mode / feature | Behavior |
| --- | --- |
| Photo | Selected sensor’s largest advertised standard 4:3 JPEG (up to 20 MP); quality 100; original HAL bytes preserved. 12 MP YUV/Vulkan processing only with portrait effect on |
| Video | H.264 MP4 at the largest advertised 16:9 size up to 1080p/30; AAC audio with microphone permission; start/stop and timer |
| Portrait button | Optional 12 MP depth/matte bokeh inside Photo; displayed aperture is **simulated**, not a movable lens iris |
| Camera processing | Request noise reduction OFF (or MINIMAL if advertised) and edge enhancement OFF when supported |
| Lenses | Main `0` (stereo `5` for portrait), ultra-wide `2`, optical tele `3`. The 2× chip selects tele automatically, without another 2× digital crop; no tele-assist setting |
| Framing | Photos preserve native 4:3. Video is 16:9, with corresponding side masks on the portrait preview; electronic stabilization is off to avoid hidden crop |
| Sensor RAW / DNG | **Not implemented.** The old RAW toggle only dumped a Y plane and has been removed |
| 50 MP | Separate main-camera JPEG path using advertised ordinary/high-resolution or maximum-resolution sensor-pixel-mode sizes; JPEG quality 100; no upscaling or second JPEG encode |
| Orientation | Portrait UI; JPEG/recording orientation uses sensor metadata; original JPEG EXIF is preserved and the viewer honors rotation/mirroring |
| Saving | Pending MediaStore row published only after a valid JPEG/MP4 is written; failed/empty rows are deleted |
| Viewer | In-app Photos-inspired viewer for NotiCam-owned images/videos: paging, full-image fit, pinch/double-tap zoom, filmstrip, favorites, sharing, confirmed deletion and video playback |

**YUV is not unprocessed sensor RAW.** Camera ISP demosaic, color conversion,
white balance and potentially other vendor processing still apply. JPEG 100 is
also not lossless. The app does not yet have a Bayer `RAW_SENSOR` + capture
metadata + `DngCreator` path, and must not claim otherwise.

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
- `DualMatteSegmenter.kt`: upright MediaPipe inference; output mapped back into sensor space.
- `jni/src/`: Vulkan/YUV pipeline and a synchronized JNI lifetime boundary.
- `jni/shaders/`: CoC, matte refinement, gather and composition.
- `src/com/zenith/auxcam/ui/`: Compose camera UI, thumbnail atmosphere, floating controls, settings and shutter.

The [BitChord-inspired UI](design/DESIGN.md) has only Photo/Video modes, a white
sliding selection pill, floating borderless glass controls and a title/metadata
hierarchy. Soft background color comes from a tiny saved-thumbnail palette,
computed off the main thread; the viewer follows the selected capture's color.
The portrait-effect button remains in the viewfinder corner, not the mode selector.
This is an independent visual adaptation, not copied BitChord code, fonts or branding.
Translucent fills and restrained interior gradients provide glass depth without
rims. Native controls do not claim live SurfaceView backdrop blur or refraction.
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
- Prepared inference tier is held across preprocessing/inference so a thermal
  tier change cannot make inference consume an unprepared general-model tensor.
- Preview and still CoC use the same 720-pixel reference width. Zero blur really
  means zero, and Photo without the portrait effect selects the sharp original.
- Missing depth no longer creates a fabricated central oval. A real matte can
  support matte-only blur; with neither signal, output stays sharp.
- The current uploaded matte is sampled, not the previous double-buffer image.
- Still gather retains 32 taps; preview uses 16. Artificial highlight boost is off.
- Native preview drops a frame rather than racing a still or engine teardown.
  Serialization favors correctness over the previous unverified interleaving
  performance claims; measure its latency and preview gaps on-device.

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
