# Native-aspect video, teardown, and genuine Log / RAW feasibility

## What “full sensor” means here

The app no longer imposes a 16:9 video crop. It queries the selected physical
lens's default active-array aspect and MediaRecorder output sizes, choosing the
largest matching size (allowing small advertised alignment differences) that:

- has no advertised minimum frame duration longer than a 30 fps frame;
- has an advertised AE range ending at 30 fps;
- fits an available hardware AVC encoder's surface-input dimensions/rate.

Unknown minimum frame duration (`0`) is not proof of throughput. A successful
MediaRecorder prepare and combined Camera2 session are still required, and real
capture/encode performance must be tested. A native stream that cannot be
configured produces an error, not a silently substituted 16:9 crop. Bitrate
scales with the actual encoded pixel count and is clamped to encoder limits.
The recorder remains SDR H.264 + optional AAC in MP4 at a requested 30 fps.

This removes the **app-imposed** wide crop and 1080p ceiling, not HAL limitations.
Native aspect is **not** unbinned 50 MP video, a guarantee of the entire physical
sensor readout, or proof that maximum sensor pixels can be read/encoded at 30 fps.
The default stream map can represent binned/subsampled modes. At base zoom the
request crop is the active array; digital zoom above a lens's native stop still
intentionally crops. EIS is OFF and rotate-and-crop NONE is requested only when
advertised. OEM internal readout, lens correction and ISP choices remain under
HAL control. Check saved field of view against photos at the same lens/zoom.

The preview no longer draws 16:9 masks. The UI reports the selected video
width/height rather than a fixed format claim. Gallery playback fits actual
width/height/rotation metadata into both available dimensions, including older
wide clips. Still size and JPEG quality are independent and unchanged.

## Camera owner and exit

- Pause, gallery entry or Surface loss closes the camera; native Vulkan/window
  preview resources are destroyed. Resume/gallery return initializes an existing
  valid SurfaceHolder as well as handling newly created surfaces. A late received
  portrait still uses a locally owned temporary headless pipeline if preview was
  already destroyed; it cannot resurrect a persistent background global pipeline.
- The app keeps its pending file-save executor draining. It does not kill the
  app, cameraserver, Shizuku manager, or other apps' camera processes. A still
  not yet delivered by the HAL can be aborted on exit; an already queued save
  is not intentionally canceled. Recording finalization is queued before IO shutdown.
- Each controller binds a tagged, non-daemon dedicated Shizuku user service.
  Version 7 retains the original transaction IDs and adds reserved
  `destroy() = 16777114`, following the official Shizuku API demo.
- Destroy is idempotent, unlinks the callback, posts camera cleanup at the front
  of the camera queue and exits **its own PID only**. A 2-second watchdog exits
  that PID if a HAL operation blocks the handler. Client Binder death triggers
  the same cleanup even if Activity.onDestroy never ran.
- Session stop/abort/close, device close and parcelled Surface release are
  independent. A session-close exception cannot skip device close. Existing
  generation checks still close late-opened devices/configured sessions.

Official reserved-hook reference:
https://github.com/RikkaApps/Shizuku-API/blob/master/demo/src/main/aidl/rikka/shizuku/demo/IUserService.aidl

## Log / RAW: possible routes, not enabled modes

| Output | Current app | What a genuine implementation needs |
| --- | --- | --- |
| SDR H.264 MP4 | Implemented | Current camera/MediaRecorder path; ISP already demosaics and processes the source |
| Bayer RAW / DNG still | Not implemented | Advertised RAW capability/RAW_SENSOR stream, matching capture results and DngCreator metadata |
| RAW video / DNG sequence | Not implemented; hardware-dependent | Sustainable RAW stream at target size/rate, timestamped capture metadata, a bounded frame pipeline and a lossless/raw sequence or suitable container/writer |
| 10-bit HDR / HLG | Not implemented; hardware-dependent | Camera dynamic-range profile + compatible output/session constraints and a matching 10-bit encoder, color/transfer metadata and player support |
| Genuine camera Log | No verified exposed path | Documented OEM Log output/session controls, or a correctly color-managed RAW-derived Log pipeline; not an arbitrary flat curve on SDR |

Shizuku changes the permitted caller/access path. It does **not** add HAL stream
formats, dynamic-range profiles, proprietary remosaic controls, sensor readout
bandwidth or codecs. A hidden camera being accessible does not prove RAW, Log,
50 MP video or 10-bit output support. A stock app's feature is not evidence of a
publicly accessible Camera2 mode.

RAW still support does not imply RAW video at useful frame rates or simultaneous
RAW + preview support. For scale, uncompressed 12 MP RAW16 at 30 fps is about
720 MB/s / 43 GB per minute before metadata/container overhead. Real dimensions,
packing/compression, storage and thermal limits determine feasibility.

HLG/HDR are not camera Log. Log is a defined color/transfer workflow, not simply
“low contrast.” Ten-bit is desirable for grading but alone does not make footage
Log; nor is ten-bit a mathematical requirement for every Log encoding. Flattening
already ISP-clipped 8-bit SDR cannot recover discarded sensor highlight data.
The app therefore does not expose a misleading Log/RAW switch or rename its YUV
or processed RGBA diagnostic dumps as sensor RAW.

### Device evidence to collect

**Camera controls → Camera report** now includes, per queryable camera:

- RAW capability; RAW_SENSOR/RAW10/RAW12 sizes and minimum frame duration (0 = unknown).
- MediaRecorder sizes, active array and selected native-aspect AVC/30 candidate.
- Tone-map modes and supported rotate-and-crop modes.
- On Android 13+: 10-bit dynamic-range capability, actual supported dynamic-range
  profiles/capture constraints and P010 output sizes.
- Existing request/session/vendor metadata names, including log/raw/hdr/bitdepth
  candidates; these are diagnostic only, never guessed writable controls.
- Hardware AVC/HEVC encoders, profiles/levels, dimensions, bitrate range and
  whether HEVC Main10 is advertised. Encoder support alone is not a camera mode.

A phone report and real stream test are needed before choosing a feasible RAW,
HDR or OEM Log implementation. No phone report was available for this change.

## Required device checks (not yet executed)

1. Record main/UW/tele at each native lens stop. Verify actual file dimensions,
   rotation, duration, audio and field of view; include digital zoom. Reject a
   native-size session/encoder on purpose and confirm no wide fallback or bad row.
2. Verify FPS under light/low light/thermal load and repeated starts/stops;
   metadata selection is not a real-time performance benchmark.
3. Open gallery on gesture and three-button navigation, cutout devices, larger
   fonts and small/multiwindow displays. Share/favorite/delete must stay above
   system UI. Play both native-aspect and old 16:9 clips without stretch/cropping.
4. Background/resume repeatedly with the Surface destroyed and with it retained.
   Repeat gallery/camera transitions. Confirm preview reinitializes and still
   JPEG sizes/quality remain unchanged.
5. Back/finish, swipe away, force-stop and crash the client while opening,
   recording and idle. Confirm camera/microphone indicators clear, another camera
   app can open, and only the dedicated `auxcam_privileged` PID exits. Simulate a
   blocked handler to exercise the watchdog. Inspect camera-service clients for leaks.
6. Exit while a received photo/video is being saved. Confirm valid queued media
   publishes and incomplete rows are removed. No claim that abrupt OS killing of
   the app itself can preserve a pending save.

## Validation performed in this workspace

- `./build.sh check`: 65 Python repository/source-contract checks passed, plus
  resource/import/Compose-symbol/CoC checks. Shader compilation skipped: no glslc.
- 8 executed JVM VideoPolicy tests: native aspect vs wide crop, sensor-specific
  aspect, invalid dimensions, pixel-scaled bounded bitrate, and rotated playback
  metadata. Existing 25 executed model/frame JVM tests also passed (33 total).
- Real AIDL compiler generated both Binder interfaces, including the reserved
  destroy ID. ECJ compiled the generated Java, CameraUserService,
  CameraCapabilities and CameraZoom against Android 35 API classes.
- Kotlin 2.0.21 compiled VideoPolicy/VideoCapture against Android 35 and checked
  the tagged Shizuku UserServiceArgs API against the packaged Shizuku library.
- Java/Kotlin syntax parsing and `git diff --check` passed.

These targeted checks are not a full Gradle/APK, Compose or NDK build and do not
validate camera HAL behavior, GPU teardown, UI layout or sustained encoder FPS on
an actual phone. The device matrix above remains outstanding.
