# Phase-one stabilization

## Scope

Make existing behavior safer, separate opt-in bokeh from sharp capture, remove
misleading controls, refresh the Compose chrome, and add regression coverage.
This is a source-level stabilization pass, not a claim that all device defects
have been fixed or that phase-two AI post-processing exists.

## Validation in the coding sandbox

- `./build.sh check`: UI/resource reference checks, existing optical CoC math,
  and 15 Python source-contract regression tests passed.
- Seven compute shaders compiled successfully to SPIR-V using the npm
  `@webgpu/glslang` WebAssembly compiler (validation only; generated binaries are
  not committed). This is separate from the standard `glslc` Android build step.
- Kotlin, Java and C++ source parsed with Tree-sitter; JNI export macros were
  removed only from parser input, not from source.
- `git diff --check` passed.
- Nine JVM tests added for the actual `FrameData` production helper: padded rows,
  pixel stride, buffer position, malformed geometry, timestamp selection/age
  boundaries, empty inputs and inverse matte rotation.
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
4. **Sharp output:** Photo and Pro at the same scene, especially hair, fine text,
   dark texture and point highlights. Verify no software blur or highlight boost.
   Verify 4080 × 3072 JPEG pixel dimensions and correct portrait EXIF rendering.
5. **Portrait:** near/far subjects, hair, glasses, hands, movement, multiple people,
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

- No sensor RAW/DNG or genuine 50 MP path. First discover stream configurations,
  RAW capabilities and capture metadata from the **privileged service**, per
  lens. Then implement truthful capability-driven UI and a separate DNG writer.
- YUV and JPEG are not zero-processing/lossless. Phase one requests advertised
  NR/edge OFF; vendor ISP behavior must be measured. Do not assume exposure,
  tonemap, white balance or lens correction can all be bypassed.
- Native full-resolution resources remain fixed at 4080 × 3072 and are guarded
  against other sizes. Custom framing is unavailable rather than previewing a
  crop that the saved photo does not honor.
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
