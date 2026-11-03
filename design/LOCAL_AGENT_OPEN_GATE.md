# Local agent task — verify & finish Open gate RAW on device

This file is a hand-off for a **local coding agent that has the Android SDK, a
physical CMF Phone 2 Pro, and Shizuku**. The cloud agent implemented the open-gate
RAW path and fixed 50 MP RAW discovery, but could not build an APK or touch the
camera hardware. Your job is to build, run on the device, verify the capture, and
fix any HAL-specific rejection — without inventing capabilities.

---

## Copy-paste prompt for the local agent

```
You are working in the NotiCam repo (ZenithFr/NotiCam), branch
`arena/48a9cd56-noticam`. Pull the latest commit on that branch first.

Goal: make "Open gate RAW" capture a full-sensor DNG on a CMF Phone 2 Pro, and
confirm the previously-broken 50 MP RAW now works. Open gate = the full active
sensor readout at the full field of view (no digital crop), written as a DNG.

What already changed (do not redo, verify it):
- CameraCapabilities.rawSize() now also searches the maximum-resolution stream
  map (SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION, API 31+) for the
  full-sensor RAW size; new rawPixelMode() reports MAXIMUM_RESOLUTION only for a
  size genuinely advertised there.
- CameraUserService declares SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION on the still
  OutputConfiguration (addSensorPixelModeUsed) and on each RAW burst request,
  forces a full-active-array crop for open gate, and logs "OPEN_GATE_RAW config".
- CameraController defaults RAW to open gate, locks zoom while RAW is on, sizes
  the client RAW ImageReader to the payload budget, and bumps the Shizuku user
  service to version(12) so the new sidecar dex is redeployed.
- UI/companion renamed to "Open gate RAW" with a Binned / Open gate readout choice.

Environment: JDK 17, Gradle 8.9 (no committed wrapper), Android SDK compile 35 /
min 29, NDK 27.1.12297006. Shizuku must be running and authorized.

Steps:
1. `./build.sh check` must stay green (source contracts + 137 Python tests).
2. Build and install: `./build.sh debug` (or `gradle assembleDebug`), then
   `adb install -r build/outputs/apk/debug/*.apk`. Force-stop the app and restart
   Shizuku if it caches the old sidecar (version 12 should invalidate it).
3. Launch NotiCam, grant Shizuku, open Settings (gear) → enable "Open gate RAW".
   Watch the status line and logcat:
     adb logcat -s CameraUserService NotiCam/Controller
   Expect a line like:
     OPEN_GATE_RAW config still=8160x6144 pixelMode=1 lens=0
   (pixelMode 0 = DEFAULT/binned, 1 = MAXIMUM_RESOLUTION/full sensor; the exact
   size is whatever the HAL advertises — do not assume 8160x6144.)
4. Press the shutter. Pull the DNG from /sdcard/Pictures/NotiCam/ and verify:
   - dimensions == the advertised full-sensor RAW size (~50 MP on the main lens),
   - it opens in a raw developer (Lightroom / rawpy / dcraw),
   - full field of view (not a 2x crop), correct orientation,
   - zoom chips are inert while RAW is on.
5. Share the Camera report (Settings → System → Camera report) and save it.

If the session is rejected ("RAW session rejected", or it falls back to JPEG):
- Capture the full logcat around the failure and the Camera report.
- Inspect the maximum-resolution RAW map in the report. Likely causes:
  (a) the HAL requires the PREVIEW stream to also run in maximum-resolution mode
      for a max-res RAW session — try configuring the preview from the max map;
  (b) the Nothing vendor remosaic path is needed instead of SENSOR_PIXEL_MODE
      (the 50 MP JPEG path uses com.nothing.camera.remosaic.enable);
  (c) the mixed default-preview + max-res-still session is unsupported for RAW.
- Fix fail-closed: if open gate cannot be configured, fall back to the binned RAW
  class and say so in the UI; never upscale or label a binned file as 50 MP.

If the DNG opens but has wrong crop/origin metadata: DngCreator is built from the
default CameraCharacteristics; for max-res mode it may need the maximum-resolution
characteristics or an explicit DefaultCropOrigin/Size override. Verify with rawpy
and patch only if the metadata is actually wrong.

Report back: advertised default + maximum-resolution RAW sizes, the pixelMode
used, session success/failure with the exact error, the DNG dimensions and whether
the field of view is full-frame, and any code changes you made. Keep
`./build.sh check` green and add/adjust a contract test for anything you change.
Do not fabricate capabilities, do not add a fake HDR/merged mode.
```

---

## Changed files (cloud side)

| File | Change |
| --- | --- |
| `src/com/zenith/auxcam/CameraCapabilities.java` | `rawSize()` searches the maximum-resolution RAW map for open gate; new `rawPixelMode()` |
| `src/com/zenith/auxcam/CameraUserService.java` | open-gate flag, pixel-mode declaration on session + burst, full-field crop, `OPEN_GATE_RAW` log |
| `src/com/zenith/auxcam/CameraController.kt` | open-gate default, zoom lock, budget-sized client reader, service `version(12)` |
| `src/com/zenith/auxcam/ui/components/CameraSettings.kt` | "Open gate RAW" + "Sensor readout" (Binned / Open gate) |
| `src/com/zenith/auxcam/ui/components/RawCaptureButton.kt` | open-gate chooser labels + description |
| `design/index.html` | companion mirrors open-gate naming/behavior (simulation) |
| `tests/test_open_gate_raw.py` | new open-gate contracts; version assertions bumped to 12 |
| `design/CONTROLS_RAW_HDR_STATUS.md` | documents the implemented max-resolution path |

## Why 50 MP RAW was broken

`rawSize()` only read the **default** pixel-mode `SCALER_STREAM_CONFIGURATION_MAP`.
On the CMF's quad-bayer main sensor the default RAW map exposes the binned ~12 MP
class; the full 50 MP RAW is advertised only in the **maximum-resolution** map and
requires `SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION` plus the maximum active-array crop
domain. The old RAW path never set either, so the 50 MP option was disabled
("not advertised for this lens") and, even if forced, would have used the wrong
pixel mode/crop. The client also allocated two ~100 MiB RAW buffers for a 50 MP
frame. All four are addressed here; on-device HAL acceptance is the remaining
unknown this task verifies.
