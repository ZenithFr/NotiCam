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
| **The frame is never stretched *or* sideways.** | The sensor streams 1440 × 1080; `composite.comp` rotates it **90° clockwise** into the swapchain, so the box is 3:4 portrait and the scene is upright in the hand. `CameraScreen` fits a box of exactly `DeviceProfile.previewAspect` inside the leftover slot and that box *is* the `SurfaceView` extent. |
| **Pinch is the only zoom input.** | The wheel is a read-out, not a control: it has no pointer input, so nothing can be dragged sideways and no part of the framebuffer can be occluded by a touch target. |
| **One accent colour.** | `CameraColors.Yellow` (`#FFD60A`) marks selection (active mode, ƒ needle, "Done") and nothing else. `Red` (`#FF453A`) is reserved for recording. |
| **Glass, not slabs.** | `CameraColors.Glass` capsules with a hairline **plus a frosted texture** (`ui/effects/MatteGlass.kt`): a sheen along the top edge and a 64×64 grain tile repeated by the GPU. Flat translucency looked like a tinted rectangle. |
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
 ├── ZoomChips         44 dp    0.6× · 1× · 2×   (collapse into the wheel)
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
| `ZoomChips` | `ZoomChips.kt` | **UW** (+ `0.6×` beneath) / 1× / 2× with a single white disc travelling between positions; `zoom` is the live engine value, so a pinch moves it too. Between stops the disc fades and the nearest label brightens. While the wheel is up the row fades to 0 and shrinks to 0.84 — it *becomes* the wheel. |
| `ModeCarousel` | `Controls.kt` | `PHOTO · PORTRAIT · PRO`, `Yellow` for the selection, `animateColorAsState`. |
| `IosSlider` | `Controls.kt` | 4 dp track, 20 dp white knob, tap or drag, integer snapping for the ƒ/EV ladders, `CLOCK_TICK` haptic on drag. |
| `TogglePill` | `Controls.kt` | White when active — used for Grid / RAW / Timer. |
| `GalleryTile` | `Controls.kt` | 52 dp tile showing the real decoded bokeh frame, else the gallery glyph. |
| `ProSheet` | `Controls.kt` | Settings panel with no title and no Done button: framing segments (1:1 · **4:3** · 16:9 · Full), aperture (Portrait/Pro only), continuous ±4 EV, 12MP/50MP, Grid, Timer, telephoto assist, live telemetry. Rendered by `ProMenuOverlay`, which hangs it under the top bar and slides it **down**; tapping the scrim or the top-bar Pro button closes it. |
| `TopBar` | `Controls.kt` | Flash + RAW on the left, status centred, Pro on the right. The whole header is a HUD: it fades out 2 s after the last interaction and returns on the next touch. |
| `BottomBar` | `Controls.kt` | Pro sheet → modes → shutter row. Publishes the shutter and gallery centres via `onGloballyPositioned` for the hand-off animation. |
| `ZoomDial` | `ZoomDial.kt` | Zoom wheel, **read-only**: top arc (161°→379°, 218° of travel at a 196 dp diameter), 56 ticks whose alpha follows `sin()`, radial backdrop fading to nothing before the bottom, travelled thread + needle + glow knob, callout centred and lifted into the arc's opening. No pointer input — zoom is pinch-only. |
| `NotiIcons` | `ui/icons/NotiIcons.kt` | Nine hand-built `ImageVector`s on a 24 unit grid, 1.7 stroke, round caps. Resolution-independent, so scaling can never resample or crop a glyph. |

---

## 5. Interaction map

| Gesture | Result | Engine call |
|---|---|---|
| Tap the frame | Reticle springs in at the tap point | `focusAt(sensorX, sensorY)` on the privileged session |
| **Hold** the frame | AE/AF locks: the reticle morphs into a padlock badge and stays until tapped | `focusAt(...)` + locked AE/AF |
| Tap while locked | Releases the lock | — |
| Pinch past 2× with TELE on | Session hands over to the physical telephoto lens | `setPhysicalLens(tele)` |
| Pinch the frame | Chips morph into the wheel, zoom follows the pinch | `setZoom(z)`, throttled to 0.02 steps |
| Tap a lens chip | Jump to 0.6× / 1× / 2× (chips stay on screen) | `setZoom(z)` |
| Tap the shutter | Bloom + ring pulse + shutter→gallery flight | capture request with the current ƒ and EV |
| Tap Pro | Settings sheet slides down from under the top bar; button becomes a white ✕ | — |
| Tap outside the sheet | Closes it (there is no Done button) | — |
| Tap RAW | Toggles RAW capture (beside the flash, not in the sheet) | raw plane dump |
| Tap flash | Off → Auto → On, icon + status pill update | `setFlashMode(mode)` |
| Portrait mode | ƒ ladder appears in the bottom bar | — |
| Tap gallery | Tile pops on arrival; opens the system gallery | `ACTION_VIEW` |
| Framing 1:1 / 4:3 / 16:9 / Full | Crops the viewfinder box (never stretches) | — |
| 12MP / 50MP | Rebuilds the still stream at 8160 × 6120 | session restart |
| RAW press | Badge morphs into a bayer CFA sensor icon, then back | raw plane dump |

Sensor-space mapping for a tap (`CameraController.onFocusAt`) mirrors the shader:
with `PREVIEW_ROTATION_90` the normalised tap `(u, v)` becomes `sensorX = v`,
`sensorY = 1 − u` — the same 90° clockwise mapping `composite.comp` uses.

---

## 6. Motion & haptics reference

All curves live in `ui/theme/Theme.kt` as `CameraMotion` — no composable defines
its own spring, and nothing uses a default `animate*AsState()` spec. The shape is
deliberately asymmetric: **press down is crisp, release is springy**.

| Interaction | Spec |
|---|---|
| Press (icon button, chip, mode, toggle) | `spring(0.62, StiffnessHigh)` → scale 0.86–0.95 |
| Release | `spring(DampingRatioMediumBouncy, StiffnessMediumLow)` → 1.0 |
| Selection (mode/mode colour) | `spring(0.78, MediumLow)`; selected label sits at 1.06× |
| Panels / overlays | `spring(0.9, MediumLow)` — damped so a panel never overshoots a neighbour |
| Zoom wheel | `spring(LowBouncy, StiffnessLow)` — keeps momentum like a lens barrel |
| Fades | 120–180 ms `tween`, always faster than the move they accompany |
| Shutter press | `press()` → 0.86, release `release()` |
| Shutter pulse | 520 ms `LinearOutSlowInEasing`, ring grows into the 96 dp margin |
| Shutter disc | compresses 4 % while the pulse travels |
| Capture bloom | 55 ms to 0.85 α, 280 ms `FastOutSlowIn` back to 0 |
| Focus reticle | 110 ms fade + `release()` spring 0.72 → 1.0, 1.2 s hold |
| Zoom chips | **one disc slides** between positions, `spring(0.72, MediumLow)`; labels cross-fade 180 ms |
| Chips → wheel morph | chips fade 140 ms + shrink to 0.84 + drop 6 dp; wheel scales 0.55 → 1 from `TransformOrigin(0.5, 0)` on `slide()`, so the arc unfurls out of the chip line |
| Settings sheet | slides **down** from under the top bar + fade 160 ms + scale 0.96 → 1 from the top edge; exit 130–160 ms; scrim fades 180 ms |
| Gallery flight | 460 ms `FastOutSlowIn`, 96 dp tile, 26 dp arc lift |
| Gallery landing | tile pops 1.10× and a white flash washes out over 420 ms |
| Capture pulse | two staggered rings (main + chase) on one 560 ms clock, both drawn in the draw phase |
| Header HUD | in 180 ms, out 420 ms, animated alpha read inside `graphicsLayer` |
| AE/AF lock | square contracts to 0.82, corners round, padlock + "AE/AF LOCK" fade in on `slide()`; unlock reverses over 180 ms |

### Layout-safe animation rules

1. **Never animate the height of anything above the viewfinder.** The frame lives
   in a `weight(1f)` slot; changing the column height resizes the `SurfaceView`
   and therefore recreates the Vulkan swapchain on every frame. The ƒ row keeps a
   fixed 36 dp slot and the Pro menu is a bottom overlay — both animate by
   translation only.
2. **Never clip.** Animated parents set `clip = false`; controls compose at their
   maximum size (shutter 96 dp for a 72 dp disc).
3. **One travel per action.** A zoom change moves exactly one thing (the disc);
   a mode change moves exactly one thing (the label scale).

### Haptics — `ui/feedback/Haptics.kt`

`CameraHaptics` maps each interaction to a distinct, very short Android
`VibrationEffect` (falling back to `HapticFeedbackConstants` below Android 10).
Predefined effects respect the system touch-feedback setting.

Haptics are **soft by design**. The old implementation used `VibrationEffect`
predefined effects, which drive the main actuator at full strength — every tap felt
like a notification. The vocabulary now goes through `View.performHapticFeedback`
first, which uses the same tuned path as the keyboard, respects the user's haptic
intensity, and is inherently a tick rather than a pulse.

| Event | Constant (first that is supported wins) |
|---|---|
| Pinch crossing a lens stop | `SEGMENT_TICK` (API 34) → `CLOCK_TICK` |
| Chip / mode / toggle / framing selection | `CONTEXT_CLICK` → `VIRTUAL_KEY` |
| Plain button | `KEYBOARD_TAP` → `VIRTUAL_KEY` |
| Finger down on the shutter | `LONG_PRESS` → `VIRTUAL_KEY` |
| Frame captured | `CONFIRM` → `VIRTUAL_KEY` |
| AE/AF hold to lock | `LONG_PRESS` → `CONTEXT_CLICK` |
| AE/AF released | `CONTEXT_CLICK` → `VIRTUAL_KEY` |
| Gesture end | `GESTURE_END` → `CLOCK_TICK` |

Only if every constant is unavailable (or touch feedback is off system-wide) does it
fall back to a 10 ms, amplitude-30 one-shot — never a full-strength vibration.

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
