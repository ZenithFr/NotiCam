# Compact chrome, sensor controls and HDR status

2026-11-02 · latest increment on `arena/74b91173-noticam`

## Implemented in source

- Removed top-left NotiCam title/subtitle; RAW lives there instead of in Settings.
- Removed the Photo/Video pill entirely. A circular mode button sits to the right
  of the centered shutter, balanced against the gallery on the left. Switching
  remains blocked during capture, recording, startup and lens transitions.
- Resolution/mode/zoom badges restart their two-second timeout when their label
  changes. The recording timer is deliberately persistent.
- The EV/manual rail has a 5 dp top inset. TIME is renamed SHUTTER. Icon faces
  are 36 dp (was 40), glyphs 18 dp (was 21), shutter 68 dp (was 78), gallery
  48 dp (was 52), instrument minimum width 64 dp (was 72). Touch targets remain
  separate from visual faces; the main buttons retain at least 48 dp targets.
- Button fills are 10% opaque (90% see-through), with readable white labels and
  cached tiled matte grain. Selected buttons do not turn into opaque white cards.
  Functional recording/hold indicators remain visible. **True live-camera
  backdrop blur is not implemented:** Compose cannot sample the native SurfaceView
  underneath a button by simply applying `blur()`. That would blur the labels,
  not the background. No such misleading effect or capture-image blur was added.
- Removed Manual from settings/search and deleted the duplicate sensor settings
  component. ISO, Shutter, EV, WB and Focus remain in the main instrument rail.
- ISO and Shutter snap to bounded photographic stops on drag, tap and accessibility
  changes; actual hardware endpoints are retained. Detent ticks/haptics follow
  those stops. Removed the hard-coded standard-resolution-only manual-exposure
  guards in the UI, controller and sidecar. **50 MP manual requests still need
  advertised sensor capabilities and successful HAL acceptance.** Preview exposure
  remains capped to the live frame period without shortening the saved still.

## RAW gesture and genuine resolution choices

- Tap top-left RAW to toggle. A hold draws a filling progress ring for **3 s**;
  completion reveals a sideways chooser for **12 MP / 50 MP** and frame count.
  Early release toggles once, cancelled gestures do neither, and a completed
  hold does not also toggle. Accessibility has a direct labelled long-click.
- Unsupported sizes are disabled. Selecting a size enables RAW in that native
  class. Counts are bounded by the actual selected frame dimensions and the
  existing 128 MiB acquired-payload budget: a 50 MP RAW frame permits only **one**
  frame under this budget; ordinary 12 MP permits up to four. These are separate
  DNGs, not merging/HDR. The payload limit is not total app/HAL memory usage.
- New appended AIDL IDs **23–25**: RAW class query, temperature control and
  lens-specific JPEG capability query. Earlier IDs 0–22 and destroy remain intact;
  sidecar version **11** retires old clients/services.
- RAW selection uses advertised RAW_SENSOR normal/slow output sizes in default
  sensor pixel mode. **Maximum-resolution-map-only RAW is not implemented**, nor
  is any OEM unlock/upscale. Device support for native 50 MP RAW is unverified.
- Removed guessed JPEG capability dimensions and the hard-coded telephoto size
  from client reader selection. Lens-specific metadata is now required, including
  preview discovery. A RAW high-resolution preview does not borrow a JPEG sensor
  path. Failed RAW combinations restore an advertised JPEG choice.

## WB temperature: relative, not calibrated CCT

The preset picker is replaced by a 2000–10000 approximate-temperature/warmth
slider (50-unit increments) and Auto reset. It requires manual-postprocessing,
AWB_OFF and color-correction request keys. It applies Camera2 gains using the
last valid Auto color transform and Auto gains; no RGB photo filter is used.
The scale is **relative to the frozen Auto reference**, not a measured/calibrated
sensor illuminant CCT. The chip is marked `≈…K`. It resets to Auto across session
changes, rejects unsupported requests, and restores the prior selection on error.
Real calibrated Kelvin needs sensor color-calibration/reference-illuminant work.

## Where is HDR? What is missing next?

**HDR is not implemented and is not hidden in another menu.** The settings output
section now says so. Neither repeated DNGs nor the existing compatibility probe
constitutes HDR imaging.

Suggested next implementation order (opt-in; preserve the original-photo default):

1. **Actual HDR capture/merge:** freeze and validate exposure brackets, exact
   frame/result matching, linear RAW development/calibration, registration,
   deghosting and radiance fusion, then an explicit SDR rendering path. Validate
   moving scenes as well as static charts and retained highlight/shadow detail.
2. **Ultra HDR export/display:** real HDR content plus gain-map generation,
   encoding/color metadata, SDR fallback and compatible viewer/device tests.
   This is distinct from HDR merge and from the still-unverified MereFrame XDR name.
3. **Night/Astro and temporal denoising:** build on the tested alignment/merge
   infrastructure with motion, exposure, thermal and memory budgets.
4. **APU portrait replacement:** still blocked on trained INT8 matting weights,
   licensing/quantization details and verified whole-graph APU execution. The
   512² float-alpha/hex compositor pipeline is not complete.
5. Calibrated Kelvin, maximum-resolution-only RAW sessions, RAW development/viewing,
   actual Log/RAW video/10-bit/HDR video and device-specific stream validation.

No fake HDR switch, contrast preset, or fabricated 50 MP RAW unlock was added.

## Validation

- **114 Python tests PASS** and native CoC executable check PASS.
- **124 JVM tests PASS** (six new detent/temperature/RAW-budget tests). Uses the
  documented minimal Compose stand-ins, not real Camera2/device execution.
- Actual generated AIDL/Java sidecar/capability backend and targeted actual Kotlin
  controller/video/input classes compile against Android35 and Shizuku.
- All **37 Kotlin source files** parse with Kotlin PSI. This is not full Compose
  type/layout validation or a built APK.
- No full APK/native build, rendered Android UI/gesture test, real 50 MP manual/RAW
  capture, calibrated temperature proof or HDR quality benchmark is claimed.
  `glslc` is unavailable, so shader compilation remains skipped.
