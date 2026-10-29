# NotiCam — Apple Camera design system

The capture surface was rebuilt around the iOS Camera language (iOS 18/26
dark appearance). This document is the contract for anyone editing the UI: the
tokens, the components, and the interaction map back to code.

Open **`design/index.html`** for a clickable mock of the exact same composition
(served statically; no build step).

---

## 1. Principles

| Principle | What it means in code |
|---|---|
| **The scene is the interface.** | The 4:3 Vulkan viewfinder is no longer pushed down by a header — `<FrameLayout>` `top_bar` floats *over* it. Only the lens chips touch the frame. |
| **One accent colour.** | `ios_yellow` (`#FFD60A`) marks selection (active mode name, zoom needle) and never decorates anything else. Red (`#FF453A`) is reserved for recording. |
| **Glass, not slabs.** | Controls are `ios_circle_glass` / `ios_capsule_glass`: 55 % `#1C1C1E` with a 0.5 dp `#1FFFFFFF` hairline. The old opaque `#D00B0B0E` toolbar and acrylic cards are gone. |
| **Motion has meaning.** | Everything springs from `DecelerateInterpolator`/`OvershootInterpolator`, 110–330 ms. Capture = bloom + ring pulse + shutter-to-gallery flight. |
| **Thumb-reachable.** | Shutter, mode carousel and lens chips sit in the bottom third; flash/Pro are top corners; everything ≥ 44 dp. |

---

## 2. Tokens (`res/values/`)

### Colour — `colors.xml`
| Token | Value | Use |
|---|---|---|
| `ios_yellow` | `#FFD60A` | selected mode, zoom pointer, "Done", focus reticle |
| `ios_red` | `#FF453A` | video shutter / recording |
| `ios_label` | `#FFFFFF` | primary glyphs and values |
| `ios_label_secondary` | `65 %` | status pill text |
| `ios_label_tertiary` | `40 %` | unselected modes, section headers, captions |
| `ios_surface_glass` | `55 % #1C1C1E` | circular buttons, capsules |
| `ios_surface_glass_panel` | `95 % #1C1C1E` | pro sheet |
| `ios_separator` | `12 % white` | 0.5 dp hairlines |

### Spacing & size — `dimens.xml`
8-pt grid. Gutters 16 dp (chrome) / 24 dp (toolbar). Controls 44 dp touch,
36 dp visual. Shutter 72 dp (4 dp ring, 3.5 dp gap, 57 dp disc). Gallery 52 dp.
Type scale: 12 sp mode names · 13 sp labels · 11 sp captions · 10 sp telemetry.

### Type — `styles.xml`
`Ios.Text.Mode` (uppercase, `letterSpacing 0.075`, medium) ·
`Ios.Text.Lens` · `Ios.Text.Section` (11 sp, uppercase, `0.12` tracking) ·
`Ios.Text.Value` · `Ios.Text.Caption` · `Ios.Text.Telemetry` (monospace) ·
`Ios.IconButton` · `Ios.Pill`.

---

## 3. Components

| Component | File | Notes |
|---|---|---|
| **Shutter** | `ShutterButton.java` | Custom `View`. Draws ring + disc and compresses to 0.86 on press with an overshoot release. `playCaptureFeedback()` emits the expanding ring and a `VIRTUAL_KEY` haptic. Modes: photo/portrait (white), video (red disc → red rounded square when recording). |
| **Focus overlay** | `FocusOverlayView.java` | Rule-of-thirds grid (0.5 dp, 12 % white) drawn on demand, plus the yellow AE/AF reticle: springs in with overshoot, then fades after 1.4 s. |
| **Zoom dial** | `ZoomDialView.java` | 48-tick glass dial; needle recoloured to systemYellow; shows `1.0×` + `24 MM` equivalent focal length. |
| **Lens chips** | layout `zoom_row` | 1× / 2× / 5× discs; selected state = solid white with black glyph. |
| **Pro sheet** | layout `drawer_pro_controls` | Glass card that grows inside the toolbar (no dialog). Aperture ladder, EV, Grid/RAW/Timer toggles, live telemetry. |
| **Status pill** | `tv_status` | Merges Shizuku / session / capture state into one 12 dp-radius capsule. |
| **Gallery** | `gallery_button` | 52 dp rounded tile fed by the real capture pipeline (see §5). |

---

## 4. Interaction map

| Gesture / control | Behaviour | Wired to |
|---|---|---|
| Tap viewfinder | Yellow reticle + hardware AF/AE scan at the tapped point | `IAuxCamService.focusAt()` → `CONTROL_AF_REGIONS` + one-shot `AF_TRIGGER_START` |
| Pinch | Continuous zoom 1×–5× | `IAuxCamService.setZoom()` → `SCALER_CROP_REGION` |
| Lens chips 1×/2×/5× | Snap zoom + orbit dial | `setZoom()` |
| Flash button | Cycles off → auto → on (icon + haptic + status) | `IAuxCamService.setFlashMode()` → `CONTROL_AE_MODE` on the still request |
| Pro button | Sheet slides up, button becomes a white ✕ | — |
| ƒ ladder (sheet + Portrait quick row) | 22-stop aperture, both sliders stay in sync | prop `fStop` → `nativeRenderFrame()` / `nativeProcessStillBokeh()` → `coc.comp` |
| EV slider | −4…+4 EV in ⅓ steps | `setEvCompensation()` → `CONTROL_AE_EXPOSURE_COMPENSATION` |
| Grid toggle | Rule-of-thirds overlay | `FocusOverlayView.setGridEnabled()` |
| RAW toggle | Also dumps the Y plane and depth map on capture | `StillImageListener` |
| Timer toggle | 0 s → 3 s → 10 s countdown | `startTimerCapture()` |
| Switch lens | Cycles physical sensors of camera 5 and re-opens the session | `setPhysicalLens()` → session restart (auto-reverts on failure) |
| Shutter | Compress → haptic → bloom → ring pulse → flyer → gallery | `triggerStillWithZsl()` |

Mode carousel drives the pipeline, not just the label:

* **PHOTO** — fast NR, ƒ from the sheet.
* **PORTRAIT** — reveals the ƒ ladder in the toolbar; bokeh strength follows aperture.
* **PRO** — still capture uses `NOISE_REDUCTION_MODE_HIGH_QUALITY` + `EDGE_MODE_HIGH_QUALITY`.

---

## 5. Gallery thumbnail pipeline

The engine writes the composited still as raw `BGRA` (50 MB) to
`getExternalFilesDir("bokeh_raw")`. To avoid decoding 50 MB on the UI thread,
`MainActivity.buildThumbnailFromRaw()` seeks through the file with a
`RandomAccessFile`, samples a 168 × 126 grid, rotates it 90° (matching the
presenter's rotation) and produces a `Bitmap`. That bitmap powers both the
flying capture animation and the gallery tile — no placeholder art, no extra
dependency.

---

## 6. Verifying a UI change

```bash
python3 tools/verify_ui.py      # XML well-formedness, @ref graph, R.* usage, Java syntax
aapt2 compile --dir res -o /tmp/res.zip   # resource link sanity
./build.sh                      # full APK (also runs verify_ui.py)
```

`tools/verify_ui.py` fails the build if a `@drawable/@string/@color/@dimen/@style`
reference or an `R.id.*` lookup does not exist — the usual failure mode when
hand-editing this project's bare AAPT2 pipeline.

---

## 7. Known follow-ups

* **Fill the frame instead of letterboxing.** `composite.comp` maps the whole
  sensor onto the swapchain, so the 4:3 preview is shown letterboxed on tall
  phones. Adding a `cropScale` push-constant (and centring `AutoFitSurfaceView`)
  would let the preview fill the screen with a centre crop, like iPhone 16:9.
* **JPEG export.** The still path still writes `.rgba`; an encoder would make the
  gallery tile launchable into a real viewer.
* **Live-progress haptics** on the ƒ ladder (currently one tick per release).
