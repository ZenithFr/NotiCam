# Phase-one stabilization

## Scope

Make existing behavior safer, separate opt-in bokeh from sharp capture, remove
misleading controls, refresh the Compose chrome, and add regression coverage.
This is a source-level stabilization pass, not a claim that all device defects
have been fixed or that phase-two AI post-processing exists.

## Validation in the coding sandbox

- `./build.sh check`: UI/resource reference checks, existing optical CoC math,
  and 45 Python source-contract regression tests passed.
- Seven compute shaders compiled successfully to SPIR-V using the npm
  `@webgpu/glslang` WebAssembly compiler (validation only; generated binaries are
  not committed). This is separate from the standard `glslc` Android build step.
- Kotlin, Java and C++ source parsed with Tree-sitter; JNI export macros were
  removed only from parser input, not from source.
- `git diff --check` passed.
- Twenty-nine JVM tests authored for `FrameData`, `CaptureOutput`, `CameraZoom` and UI policy: padded rows,
  pixel stride, buffer position, malformed geometry, timestamp selection/age
  boundaries, empty inputs, inverse matte rotation, full-resolution output
  validation, two-mode behavior, optical/digital zoom separation and bokeh/video/shutter gating.
- **Not run:** Gradle Android build, JVM tests, NDK compile, instrumentation or
  physical camera tests. This sandbox has no JDK, Gradle, Android SDK/NDK or
  connected phone, and its network does not include the Android dependency hosts.
  Source parsing and source-contract tests do not establish Android type/API
  correctness or runtime safety.

## Device test matrix — required before a release

Use the CMF Phone 2 Pro with Shizuku authorized; inspect logcat and compare the
resulting JPEGs at full resolution. Record OS/build fingerprint and app commit.

1. **Startup and permissions:** Shizuku stopped, denied, granted, revoked and
   killed while streaming. Confirm a useful visible state, no crash, and a
   successful reconnect/relaunch.
2. **Lifecycle:** home/resume, lock/unlock, open gallery/return, activity recreation,
   rapid foreground/background transitions. Camera indicator must stop in the
   background; surface recreation must not render into a destroyed window.
3. **Capture admission:** rapid shutter taps, 3/10-second timer, background during
   countdown and during encoding. Only one capture/save at a time; no duplicated
   timer chains or late surprise shots.
4. **Sharp output:** Photo with the portrait button off, especially hair, fine text,
   dark texture and point highlights. Verify no software blur or highlight boost.
   Verify 4080 × 3072 JPEG pixel dimensions and correct portrait EXIF rendering.
5. **Portrait button:** near/far subjects, hair, glasses, hands, movement, multiple people,
   no person, low light, no valid depth and f/1.4 → f/16. Verify upright/matching
   mattes, no central oval fallback, no stale mask on lens/mode changes, and
   equivalent relative blur between preview and saved photo.
6. **Lenses/zoom:** 0.6×, 1×, 2× digital with tele assist off, 2× tele with assist
   on, repeated switches and a HAL-rejected lens/stream combination. Check actual
   field of view and that fallback updates the displayed lens. Auxiliary cameras
   may not accept the fixed main-camera still resolution; failure must be visible.
7. **Controls:** auto/tap/manual focus, AE/AF lock/unlock, EV extremes, flash
   off/auto/on (including dark scenes), grid and timer. Confirm actual Camera2
   results, not just UI state. Verify flash precapture behavior and each lens's
   EV step/range; these paths still need a hardware-backed capability model.
8. **Storage:** low/full storage, encoder/output failures, repeated captures and
   app background during saving. No empty published MediaStore rows, no success
   badge before publication, no persistent RGBA files after success/failure.
9. **Thermal/performance:** sustained preview/capture for 10 minutes; collect FPS,
   frame gaps, capture-to-save latency, memory and temperature at each tier. JNI
   serialization intentionally prevents shared-resource races but may introduce
   a preview gap during still processing. Do not reuse the old README's timings.

## Known limits / next milestones

- Sensor RAW/DNG is still unimplemented. Discover RAW capabilities and capture
  metadata from the **privileged service**, per lens, before adding a DNG writer.
- 50 MP now has a separate HAL JPEG path: query ordinary and high-resolution
  outputs first, then the API 31+ maximum-resolution stream map. In maximum
  sensor mode, declare the still output pixel mode and set it on the still
  request. Sensor dimensions alone are not evidence of a valid JPEG output.
  Main-camera JPEG + preview combinations and remosaic behavior still require
  CMF Phone 2 Pro validation. Failures visibly restore the 12 MP session.
- YUV and JPEG are not zero-processing/lossless. Phase one requests advertised
  NR/edge OFF; vendor ISP behavior must be measured. Do not assume exposure,
  tonemap, white balance or lens correction can all be bypassed.
- Native bokeh resources remain fixed at 4080 × 3072 and are guarded against
  other sizes. Full-resolution JPEG bypasses that fixed readback entirely and is
  written byte-for-byte, after header-only size validation. Thumbnails are
  subsampled. Custom framing remains unavailable rather than presenting a crop
  that the saved photo does not honor.
- The current logical stereo disparity calibration is device-specific and not
  validated for the ultra-wide/tele cameras. They must never reuse main stereo
  depth. Matte-only fallback still needs qualitative testing.
- The model preprocessor retains the existing BT.601 full-range approximation;
  verify actual HAL YUV range/color space with a chart before changing it.
- Preview/matte timestamp tolerances are safety bounds, not exact depth/color
  synchronization. Capture metadata, ZSL matching and a calibrated sensor-space
  alignment model are needed for production portrait quality.
- EV slider still requests thirds of an EV, clamped by the service's advertised
  integer range. A per-lens capability/metadata callback should expose real EV
  steps, manual-focus distances and flash support instead of UI assumptions.
- Phase two should keep the source capture immutable and attach explicit
  provenance/settings to derived AI-processed outputs. It should not silently
  replace or overwrite the phase-one source photo.


## Follow-up: two-mode camera UI and 50 MP capture

- Removed Pro/Portrait mode enums. Photo is the default; the person-in-frame
  button controls the bokeh engine. Its aperture slider appears in a reserved
  strip without resizing the viewfinder.
- Video shows a disabled red shutter and an explicit coming-later message. No
  recording permissions, recorder, file output, or working-recording claim was
  added in this phase.
- 50 MP is main-camera-only at 1×. Switching it on disables bokeh and resets zoom;
  enabling bokeh or zooming/switching lenses rebuilds the 12 MP session. Old
  session callbacks are rejected with a controller session token in addition
  to the service-side generation guard.
- Added an interactive browser design companion (`design/index.html`), clearly
  labeled as simulated. Chromium checks passed for mode selection, the disabled
  Video shutter, bokeh/50 MP exclusivity, settings/grid interaction, mobile
  horizontal fit, and absence of JavaScript errors. This does not test Android.
- This follow-up does not modify the compute shaders. Repository checks and
  syntax parsing passed; Android build/JVM tests/device tests remain unrun.

### Additional device release gates

1. In Photo, enter 50 MP and inspect the log and saved JPEG header: configured and
   actual dimensions must agree (including a physically rotated JPEG). Inspect
   EXIF orientation in multiple gallery apps. Compare detail against 12 MP.
2. Exercise both ordinary/high-resolution and maximum sensor-pixel-mode paths if
   advertised. Confirm preview + JPEG is a supported combination; unsupported
   combinations must roll back to a working 12 MP session.
3. In 50 MP, try 0.6× / 2×, pinch/scrub and the portrait button: each must switch
   out of 50 MP explicitly. Verify no digitally cropped/upscaled output gets a
   50 MP badge.
4. Test background/resume, Shizuku death and low storage during 50 MP capture.
   Confirm no empty published rows and no ~200 MB bitmap allocation.
5. In Video, the legacy still-capture broadcast must not save a JPEG. Verify the
   red shutter starts MP4 recording, stop finalizes it, and Photo still works
   after changing modes. Also test the release gates below.


## Earlier matte and motion refinement (surface styling superseded below)

- Retained the controls but removed their decorative glass rims, sheen and blur;
  uniformly shaded matte fills now back the toolbar, zoom and mode capsules.
- Removed the dedicated bottom lens-cycle button and its action. A noninteractive
  spacer preserves shutter centering; the zoom controls still select lenses.
- Added continuous log-space zoom indication, an animated mode-selection pill,
  direct-drag/spring-settle sliders with accessibility progress actions, animated
  busy/availability feedback, gallery crossfade and a successful-save thumbnail
  flight. Grid, status and overlay transitions remain clear of native sizing.
- Added six UI source-contract checks. Chromium validation additionally covers
  borderless controls, zoom and range inputs, fixed scene geometry, capture
  re-entry protection, thumbnail cleanup, narrow widths and reduced motion.
- These checks do not establish native frame rate. Before release, check on the
  phone that rapid pinch/scrub, slider resets, repeated capture, failed saves,
  orientation/resume and system animator scale 0 behave correctly. Verify that
  no animation causes extra lens switches or SurfaceView/swapchain resizes.


## Optical zoom, quality, borderless glass, viewer and basic video

- Removed the tele-assist settings control, state flag and callback. The 2× stop
  selects camera 3 unconditionally; `CameraZoom.digitalRatio(2, "3", max)` is 1.
  Session opening now carries the requested zoom, avoiding an old crop during
  lens/session handoff. Lens failures are explicit, not silent main-sensor crops.
- Sharp images use per-sensor advertised native JPEG sizes and preserve HAL bytes
  at quality 100. YUV/Vulkan still processing is reserved for portrait effect;
  portrait activation returns to main/1×. Sensor orientation comes from metadata.
- Broadened 50 MP discovery and added read-only vendor/extension/physical-camera
  reports. This is not a claim that OEM-only full-resolution mode is unlocked.
- Restored glass depth using translucent body gradients, without decorative rims.
- Added the app-owned Photos-inspired viewer and foreground-only video recorder.
  Video uses a separate preview/recorder session (no depth/still output), chooses
  advertised 30-fps-compatible sizes up to 1080p, and transactionally publishes MP4.
- Current checks: 31 Python source contracts and optical/UI checks; Java/Kotlin/C++
  syntax parsing; Chromium preview interactions including simulated video and
  viewer controls. Android compilation and all JVM/device tests remain unrun.
- API 29 is the new minimum, so the existing pending-row storage contract is not
  falsely offered on API 28.

### Required phone checks for this pass

1. Compare 1× and 2× against the stock app, including saved image field of view,
   lens ID/focal length and dimensions. At 2×, the tele crop region must be the
   full active array. Exercise UW/main/tele failures and rapid repeated changes.
2. Share **Camera controls → Camera report**. Determine whether the stock 50 MP
   path is another openable main ID, a maximum-resolution map, a physical-only
   output, an extension, or proprietary remosaic/operating-mode control. Validate
   real detail, not just output dimensions, before calling a new vendor path true
   50 MP. Never invent dimensions or enable guessed vendor controls.
3. Compare native JPEG detail/color/EXIF/4:3 framing with the old YUV encode. Test
   portrait on/off, rejected JPEG stream combinations, low light, focus and flash.
4. Record each lens with audio granted/denied, stop after short/long clips, verify
   playback duration/rotation/audio and native-aspect field of view against the
   uncropped photo at the same physical lens/digital zoom. Test storage full,
   Shizuku death, lock/home, recording-start timeout and the 15-minute limit.
5. Verify no microphone survives backgrounding, only valid videos are published,
   repeated start/stop cannot overlap, and Photo works after recording.
6. Verify full-image fit/EXIF, page/pinch gesture arbitration, thumbnail memory,
   sharing, persistent favorites, confirmed deletion and camera return in viewer.
   Review video playback while backgrounding the viewer and returning to it.


## BitChord-inspired styling and consistent Shizuku discovery

- Independently adapted BitChord's white selection pill, floating round glass,
  soft artwork atmosphere and title/secondary-text hierarchy. No upstream code,
  fonts or artwork is included in the app; native controls do not claim real
  SurfaceView refraction. Functional focus/shutter rings remain.
- Added a bounded 12×12 saved-thumbnail palette on a background dispatcher. Only
  chrome is tinted; no live-frame readback, capture shader or JPEG processing was
  added. Hardware thumbnails use a private readable copy, never recycling the
  caller's bitmap. Viewer atmosphere follows the current capture.
- Restyled the viewer with floating actions and a spring-animated filmstrip.
  Original fit/zoom/share/delete/video behavior remains; camera surface geometry
  is fixed while modes and overlays change.
- Reused one cached shell-context CameraManager for capture and every capability
  query. Discovery also checks the existing auxiliary routes if enumeration
  omits them. Reports distinguish enumerated/queried IDs, actual process UID,
  attribution and camera permissions; no global settings or vendor keys changed.
- `./build.sh check`: **38** Python source contracts, CoC and UI/import checks
  passed. **38** Java/Kotlin/C++ source files parsed successfully (not compiled).
  Chromium regression checks passed for photo/bokeh/50 MP simulations, stable
  framing, zoom/sliders, video simulation/timer/guards, viewer actions, reduced
  motion, 320–390 px layouts and no JavaScript errors. A separate narrow-header
  measurement caught and fixed title overlap at 320 px.
- Android build, JVM tests and device capture remain unrun. On the phone, verify
  header/system text scaling, thumbnail palette changes, viewer page/delete
  transitions, Shizuku shell/root report identity and real JPEG/video sessions.
  Obtain the camera report before asserting OEM 50 MP support.


## Smooth zoom and 1080-line/60 fps preview target

- Added one-shot outgoing-frame freeze/zoom/dissolve for physical handoffs,
  fresh-queue/rendered-frame readiness, bounded copy/open/frame timeouts,
  cancellation tokens, coalesced targets and transition shutter gating.
- Same-lens crops ease on the camera handler; full-quality stills snap to the
  final requested zoom. Ultrawide now reaches just below 1× before the main route.
- Preview size/FPS are queried separately from still size. The largest eligible
  4:3 feed up to 1440×1080 prefers advertised 60 fps, otherwise an explicit lower
  rate. A failed 60 fps session retries at <=30 without reducing JPEG quality.
  Recording remains on its existing 30 fps encoder policy. UserService protocol
  version is now 7 for the extended AIDL API and reserved Shizuku destroy hook.
- A fixed upright 1080×1440 surface and API 30+ 60 Hz hint support consistent
  presentation. Rendered/queued-present FPS is measured separately from sensor
  result FPS. Neither a hint nor a requested range proves actual 60 fps output.
- Sharp GPU frames skip unused depth/alpha staging and blur passes entirely;
  safe GPU completion/image lifetime and full-resolution still processing remain.
- Added seven source regression tests (45 total) and six JVM tests (29 authored,
  not executed here). Browser demonstration and documentation explicitly separate
  simulated handoff motion from actual camera performance.
- Native compilation and phone testing remain required. See
  [PREVIEW_SMOOTHNESS.md](PREVIEW_SMOOTHNESS.md) for limitations and the device matrix.


## Selectable bokeh engines and phone checks

- Added persisted MediaPipe Auto/fixed Dual/Landscape/General, MODNet and RVM
  MobileNetV3 selection in Camera controls. Runtime failures are explicit. The
  Auto baseline alone has named, visible thermal/FPS Landscape fallback.
- Check selected/all uses one fresh camera frame, additional warmup and five timed
  cycles, hash/tensor/alpha validation, load/median/p95, mask statistics and a
  copyable report. This is not a quality or sustained-FPS benchmark.
- Serialized model lifecycle, bounded copied inputs, old-result generation guards,
  temporal resets, cancellation/thermal handling and fresh-matte capture gating
  keep native camera buffers and saved-still settings separate.
- Large weights are ignored build inputs fetched from pinned release/blob URLs,
  verified before bundling and again at runtime. All choices ship in the APK.
  Bundled notices identify RVM's GPL-3.0-only redistribution/source obligations.
- Actual model CPU contracts executed on desktop; RVM recurrence/reset exercised.
  Seven model/data/profile Kotlin files compiled against Android 35, local TFLite
  jars and official ORT 1.20 Java API sources. **25 JVM tests executed successfully**
  (BokehMathTest + BokehProcessorTest + FrameDataTest). This does not constitute a full APK/Compose/NDK
  build or execution of all existing Gradle tests.
- Phone selection/preview/capture/delegate/performance and full build validation
  remain required. See [BOKEH_MODELS.md](BOKEH_MODELS.md).


## Native video / safe gallery / dedicated-process teardown

See [NATIVE_VIDEO_AND_RAW.md](NATIVE_VIDEO_AND_RAW.md) for recorder selection,
explicit RAW/Log limitations and the required phone test matrix. Native-aspect
selection intersects advertised camera sizes/frame durations and hardware AVC
surface-input size/rate support. It does not promise unbinned sensor readout,
maximum-resolution video, or real-time throughput from metadata alone.

Viewer video fit uses width/height/rotation metadata rather than 9:16. Dialog
padding takes the maximum of physical root-window system-bar/cutout insets and
Compose safeDrawing, then lifts actions another 20 dp. It does not replace
Compose's window-inset listener. Zoom/secondary visible controls are smaller;
48 dp touch targets and animations are retained.

Pause/surface loss closes the camera and destroys native preview resources;
resume/gallery return reinitializes even a surviving SurfaceHolder. Exit invokes
the reserved Shizuku destroy transaction; service cleanup also runs on client
Binder death, independently closes session/device, drops held Surface references,
and exits only its own process. A late portrait still uses a temporary RAII-owned
headless pipeline, not a resurrected global preview owner. A 2-second watchdog covers a blocked camera
handler. Per-controller service tags prevent one controller's teardown removing
a replacement controller's process. The app's already queued file writes are
not canceled or killed. This is not a guarantee for a still not yet delivered by
the HAL when the user exits.
