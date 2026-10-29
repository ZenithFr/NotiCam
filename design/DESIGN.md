# NotiCam — Apple Camera design system

The capture surface was rebuilt in the iOS Camera language (iOS 18/26 dark
appearance) on **Jetpack Compose**, and is tuned for exactly one device: the
**CMF Phone 2 Pro**.

Open **`design/index.html`** for a clickable mock of the same composition
(served statically, no build step). This document is the contract for anyone
editing the UI: the layout rules, the tokens, the components, and the mapping
back to code.

---

## 1. Principles

| Principle | What it means in code |
|---|---|
| **Nothing is painted over the framebuffer.** | `CameraScreen` is one `Column`: top bar → `weight(1f)` frame slot → lens chips → ƒ row → bottom bar. The chrome is *outside* the 4:3 region by construction, so no control can overlap the live image. Only the transient zoom dial floats on top, and only while the user is rotating it. |
| **The frame is never stretched.** | The sensor streams 1440 × 1080. `CameraScreen` fits a box of exactly `DeviceProfile.previewAspect` inside the leftover slot (`Modifier.size(w, h)`), that box *is* the `SurfaceView` extent, and `composite.comp` aspect-fits the frame one more time. |
| **One accent colour.** | `CameraColors.Yellow` (`#FFD60A`) marks selection (active mode, ƒ needle, "Done") and nothing else. `Red` (`#FF453A`) is reserved for recording. |
| **Glass, not slabs.** | `CameraColors.Glass` (55 % `#1C1C1E`) capsules with a 0.5 dp `#1FFFFFFF` hairline. The old opaque `#D00B0B0E` toolbar and acrylic cards are gone. |
| **Motion has meaning.** | Everything is a Compose spring (`dampingRatio` 0.6–0.9, `StiffnessLow`…`StiffnessHigh`), 110–460 ms. No `LinearInterpolator`, no `ValueAnimator`. |
| **Nothing clips.** | Every animated control composes at its *maximum* size and sets `clip = false` on its `graphicsLayer`; expansion animations run with `clip = false`. The shutter's pulse ring is drawn inside its own 96 dp box. |
| **Thumb-reachable.** | Shutter, modes, lens chips and the ƒ row sit in the bottom half; flash/Pro in the top corners; everything ≥ 48 dp of touch target. |

---

## 2. Layout contract

```
Column(windowInsetsPadding(safeDrawing))
 ├── TopBar            56 dp    flash · status pill · Pro
 ├── BoxWithConstraints weight(1f)   ← the ONLY flexible row
 │     └── Viewfinder   measured to exactly 4:3 (1440 × 1080)
 ├── LensChips         44 dp    1× · 2× · 5×
 ├── QuickApertureRow  ~34 dp   Portrait only, expandVertically(clip = false)
 └── BottomBar                 Pro sheet (animated) · modes · shutter row
       GalleryTile 52 dp · ShutterButton 96 dp box / 72 dp visual · flip 48 dp
```

Consequences that the old `FrameLayout` could not give us:

* The 4:3 box can never be covered — it is a sibling in normal flow, not a
  backdrop under absolute chrome.
* When the Pro sheet or the ƒ row expands, the *frame* gives up height
  (`weight(1f)`), instead of the sheet sliding over the picture.
* Nothing needs manual `translationY`, so there are no magic offsets to keep in
  sync with the status bar / cutout.

The zoom dial and the shutter→gallery hand-off are the only overlays; they are
siblings of the `Column` inside the root `Box`, so they can travel across the
whole screen without being clipped by the toolbar or the frame.

---

## 3. Tokens — `ui/theme/Theme.kt`

### Colour — `CameraColors`
| Token | Value | Use |
|---|---|---|
| `Yellow` | `#FFD60A` | selected mode, ƒ needle, "Done", focus reticle |
| `Red` | `#FF453A` | recording indicator |
| `Label` | `#FFFFFF` | primary glyphs and values |
| `LabelSecondary` | 65 % | status pill text |
| `LabelTertiary` | 40 % | unselected modes, section headers, captions |
| `LabelQuaternary` | 25 % | slider end labels |
| `Glass` | 55 % `#1C1C1E` | circular buttons, capsules |
| `GlassStrong` | 95 % `#1C1C1E` | Pro sheet, zoom dial |
| `Separator` | 12 % white | 0.5 dp hairlines |
| `SeparatorStrong` | 24 % white | panel and gallery-tile outlines |
| `SliderTrack` / `SliderFill` | 24 % / 100 % white | iOS slider |

### Spacing — `CameraSpacing`
8-pt grid. `gutter` 16 dp (chrome), `gutterWide` 24 dp (bottom bar), `touch`
48 dp, `iconSize` 22 dp, `shutterTouch` 96 dp, `shutterVisual` 72 dp, `gallery`
52 dp, `capsuleHeight` 34 dp, `panelRadius` 24 dp.

### Type — `CameraType`
`Caption` 11 sp / 0.04 em · `Section` 11 sp / 0.12 em uppercase · `Mode` 12 sp /
0.075 em uppercase · `Value` 13 sp semibold · `Lens` 12 sp semibold · `Dial`
34 sp · `DialCaption` 11 sp · `Telemetry` 10 sp monospace.

---

## 4. Components (`ui/components/`)

| Component | File | Rules |
|---|---|---|
| `ShutterButton` | `ShutterButton.kt` | 4 dp white ring, 3.5 dp gap, solid disc, red dot while recording. Press = spring to 0.86, release = `DampingRatioMediumBouncy`. The capture pulse ring expands *into the 24 dp margin* of the 96 dp box, so it is never sliced. |
| `Viewfinder` | `Viewfinder.kt` | `AndroidView(SurfaceView)` filling the box the caller sized to `previewAspect`. Rule-of-thirds grid, AE/AF reticle (spring-in, pivot at the tap), capture bloom, tap-to-focus + pinch-to-zoom detectors. |
| `GlassIconButton` | `Controls.kt` | 44 dp glass circle inside a 48 dp touch target; press scales to 0.90 with `clip = false`. |
| `StatusPill` | `Controls.kt` | Black 42 % capsule, `AnimatedContent` crossfade on text change. |
| `LensChip` | `Controls.kt` | 44 dp circle; white when selected; press 0.92. |
| `ModeCarousel` | `Controls.kt` | `PHOTO · PORTRAIT · PRO`, `Yellow` for the selection, `animateColorAsState`. |
| `IosSlider` | `Controls.kt` | 4 dp track, 20 dp white knob, tap or drag, integer snapping for the ƒ/EV ladders, `CLOCK_TICK` haptic on drag. |
| `TogglePill` | `Controls.kt` | White when active — used for Grid / RAW / Timer. |
| `GalleryTile` | `Controls.kt` | 52 dp tile showing the real decoded bokeh frame, else the gallery glyph. |
| `ProSheet` | `Controls.kt` | Glass panel: ƒ ladder, ±4 EV, Grid / RAW / Timer, live telemetry. Opens with `expandVertically(clip = false)` + `spring`, closing the "sliced contents" artefact. |
| `TopBar` | `Controls.kt` | `Box` so the status pill stays optically centred while the corner buttons change width. |
| `BottomBar` | `Controls.kt` | Pro sheet → modes → shutter row. Publishes the shutter and gallery centres via `onGloballyPositioned` for the hand-off animation. |
| `ZoomDial` | `ZoomDial.kt` | 236 dp glass disk, 48 ticks, yellow needle sweeping 160°, drag-to-rotate mapped to real `SCALER_CROP_REGION`. Auto-hides after 2.2 s. |
| `NotiIcons` | `ui/icons/NotiIcons.kt` | Nine hand-built `ImageVector`s on a 24 unit grid, 1.7 stroke, round caps. Resolution-independent, so scaling can never resample or crop a glyph. |

---

## 5. Interaction map

| Gesture | Result | Engine call |
|---|---|---|
| Tap the frame | Reticle springs in at the tap point | `focusAt(sensorX, sensorY)` on the privileged session |
| Pinch the frame | Zoom dial appears, zoom follows the pinch | `setZoom(z)`, throttled to 0.02 steps |
| Drag the dial rim | Zoom follows rotation (≈ 0.4 × range per half turn) | `setZoom(z)` |
| Tap a lens chip | Jump to 1× / 2× / 5× | `setZoom(z)` |
| Tap the shutter | Bloom + ring pulse + shutter→gallery flight | capture request with the current ƒ and EV |
| Tap Pro | Sheet slides up, corner button becomes a white ✕ | — |
| Tap flash | Off → Auto → On, icon + status pill update | `setFlashMode(mode)` |
| Portrait mode | ƒ ladder appears in the bottom bar | — |
| Tap gallery | Opens the system gallery | `ACTION_VIEW` |

Sensor-space mapping for a tap (`CameraController.onFocusAt`): with the sensor
frame presented 1440 × 1080 landscape and the phone portrait, the normalised tap
`(u, v)` becomes `sensorX = 1 − v`, `sensorY = u`. Flip both that expression and
`DeviceProfile.PREVIEW_ROTATION_90` if a future build presents the frame upright.

---

## 6. Motion reference

| Element | Spec |
|---|---|
| Icon button press | `spring(0.65, Medium)` → 0.90 |
| Lens chip press | `spring(0.70, Medium)` → 0.92 |
| Mode label press | `spring(0.60, Medium)` → 0.94 |
| Shutter press | `spring(0.90, High)` → 0.86; release `MediumBouncy / Low` |
| Shutter pulse | 520 ms `LinearOutSlowInEasing`, ring fades as it grows |
| Capture bloom | 60 ms to 0.85 alpha, 260 ms `FastOutSlowIn` back to 0 |
| Focus reticle | 110 ms fade + `MediumBouncy` spring scale 0.72 → 1.0, 1.2 s hold |
| Pro sheet | `spring(0.90, MediumLow)` expand, 170 ms shrink, both `clip = false` |
| Zoom dial | 150 ms fade + `spring(0.80, MediumLow)` scale 0.9 → 1.0 |
| Gallery flight | 460 ms `FastOutSlowIn`, 96 dp tile, 26 dp arc lift |

---

## 7. Device contract — CMF Phone 2 Pro

| Property | Value | Where |
|---|---|---|
| Marketing name | CMF Phone 2 Pro | `DeviceProfile.MARKETING_NAME` |
| SoC / GPU | Dimensity 7300 Pro · Mali-G615 MC2 | README |
| Display | 1080 × 2392, 120 Hz | `DeviceProfile.DISPLAY_*` |
| Prominent camera | id `5` (privileged session) | `DeviceProfile.CAMERA_ID` |
| Preview colour | 1440 × 1080 (4:3) | `DeviceProfile.COLOR_*` |
| Disparity | 448 × 288 (Y8) | `DeviceProfile.DEPTH_*` |
| Still | 4080 × 3072 | `DeviceProfile.STILL_*` |

`DeviceProfile.isTargetDevice()` matches the model/device strings; anywhere else
the app still runs but the top bar renders a one-line warning, because the
privileged session, the `AHardwareBuffer` imports and the Mali-G615 timings do
not exist on other hardware.

---

## 8. Verification

There is no Android SDK in the design workspace, so the UI is guarded by
`tools/verify_ui.py` (run by `build.sh`, or `python3 tools/verify_ui.py`):

1. every `res/**/*.xml` parses and every `@type/name` / `R.type.name` resolves;
2. every Kotlin/Java file is bracket- and string-balanced;
3. every `com.zenith.auxcam.*` import resolves to a declared symbol;
4. every `actions.onX(...)`, `state.prop`, `NotiIcons.X`, `CameraColors.X`,
   `CameraSpacing.X` and `CameraType.X` reference exists;
5. every project composable used across packages is imported.

`aapt2 compile --dir res` and the glslang SPIR-V check cover the resource and
shader layers. The Kotlin/C++ compile itself needs a real Android SDK + NDK
(`./gradlew assembleDebug`).
