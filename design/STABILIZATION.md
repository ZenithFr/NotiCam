# Phase-one stabilization

## Scope

Make existing behavior safer, separate opt-in bokeh from sharp capture, remove
misleading controls, refresh the Compose chrome, and add regression coverage.
This is a source-level stabilization pass, not a claim that all device defects
have been fixed or that phase-two AI post-processing exists.

## Validation in the coding sandbox

- `./build.sh check`: UI/resource reference checks, existing optical CoC math,
  and 25 Python source-contract regression tests passed.
- Seven compute shaders compiled successfully to SPIR-V using the npm
  `@webgpu/glslang` WebAssembly compiler (validation only; generated binaries are
  not committed). This is separate from the standard `glslc` Android build step.
- Kotlin, Java and C++ source parsed with Tree-sitter; JNI export macros were
  removed only from parser input, not from source.
- `git diff --check` passed.
- Eighteen JVM tests added for the actual `FrameData`/`CaptureOutput` production helpers: padded rows,
  pixel stride, buffer position, malformed geometry, timestamp selection/age
  boundaries, empty inputs, inverse matte rotation, full-resolution output
  validation, two-mode behavior, and bokeh/video/shutter gating.
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
5. Select Video, press the shutter/accessibility actions and send the legacy
   capture broadcast: no JPEG or recording should start. Return to Photo and
   confirm still capture works. Video recording is intentionally deferred.


## Matte and motion refinement

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
