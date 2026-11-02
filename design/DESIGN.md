# NotiCam camera UI — Photo / Video

> Portrait/model and video-profile details below are historical where they conflict
> with the current [APU-only replacement report](APU_PORTRAIT_VIDEO_PROFILES.md).
> Auto/MODNet inference is now removed; the replacement is blocked pending artifacts.

## Direction

The latest direction is Nothing-inspired neutral matte glass, using the
[community brand reference](https://nothing.wiki/nothing/brand_reference): black,
white, N-Grey `#DCD7D2`, Window Grey `#B1B3B3`, and restrained N-Red `#C8102E`
for functional recording/error accents. This supersedes the earlier warm BitChord
atmosphere. It is an interpretation, not an official Nothing OS 5 specification
or an affiliated Nothing app. System sans/mono fonts are used; no proprietary
NType/NDot fonts or Nothing logos are distributed. Mono readouts have zero tracking;
regular sentence-case body labels have light tracking. Photo/Video, the corner
portrait button, native-quality originals and rounded framing remain.

The Android app remains Kotlin/Jetpack Compose over the native Vulkan viewfinder.
Changing the camera stack just for chrome would add risk without improving
capture quality. A small `cameraGlass` modifier retains the control surfaces with
translucent neutral fills and soft interior light gradients, without decorative
strokes, rim highlights or native SurfaceView backdrop blur. Functional
shutter/focus rings and keyboard focus outlines remain.

### Neutral surroundings, not image processing

`SceneAtmosphere` draws a low-alpha white radial diffusion behind chrome. It no
longer samples or loads thumbnails for scene tint. Matte surfaces have neutral
fills and very low-contrast interior light; no decorative borders. No live-frame
readback, image grading, or native SurfaceView blur is added. The browser companion
uses the same neutral direction, but its CSS blur and sample photo remain a
**simulation**, not an Android rendering or capture test.

## Layout

1. Minimum-72 dp, content-growing header: NotiCam title, Photo/Video framing subtitle,
   an 80 dp resolution control with a separate video-rate line, and flash/settings actions. Text is bounded and ellipsized, never allowed to paint outside its button.
2. Largest fitting native 3:4 portrait viewfinder with rounded corners.
   - Person-in-frame portrait-effect button in the top-right corner.
   - Active-effect or 50 MP / 1× badge near the top.
   - Lens selector centered near the lower edge, on a contrast gradient.
   - Tap-to-focus, pinch/scrub zoom and AE/AF lock retain their camera actions.
3. Reserved strip: status/capture information, the manual rail or bokeh aperture slider. It reserves 48 × fontScale dp for the rail plus a 44 dp slider, stable across selections.
   Toggling bokeh never resizes the native surface.
4. Glass mode capsule with **Video / Photo only**; a single white selection
   pill slides behind dark selected text. Tabs expose selection semantics.
5. Large ring/disc shutter and gallery thumbnail. The rear-lens cycling button
   is removed; an inert, equal-footprint spacer keeps the shutter centered. Lens
   selection remains available through zoom stops, pinch and horizontal scrubbing.
6. Quiet NotiCam wordmark, inside the safe navigation area.

Camera settings open in a scrollable modal bottom sheet with system back and
outside-tap dismissal. Manual focus/exposure are still available as controls;
there is no separate Pro capture mode.

## Interaction contract

- **Photo:** sharp by default. No synthetic blur unless the portrait button is on.
- **Portrait icon:** toggles bokeh inside Photo. White means enabled; an
  aperture slider appears. Starting from 50 MP explicitly returns to 12 MP.
- **50 MP:** use an advertised main-camera JPEG output at 1×. Disable bokeh,
  rebuild the still session and guard the shutter until ready. Unsupported sizes
  or stream combinations must produce a visible message/12 MP recovery, not an
  upscale. Zoom/lens changes explicitly return to 12 MP.
- **2×:** selects physical telephoto directly. Zoom is then relative to that
  lens, so 2× does not mean another 2× crop of the tele sensor. The redundant
  settings toggle and callback are removed. Failed lens configuration must not
  silently crop the main sensor while retaining a 2× badge.
- **Video:** actual MP4 recording in the Android app, largest advertised native-aspect
  size supported by a hardware surface-input AVC encoder at 30 fps, H.264 and
  AAC audio with permission. Red start disc morphs to a stop square; recording
  time is real. Lock lens/mode changes during recording and finish on background.
  No imposed 16:9 mask or wide-crop fallback. Respect active-array aspect (4:3 on
  the target phone), not a promise of full-pixel/unbinned sensor readout. Scale
  bitrate with encoded pixel count, within encoder limits.
- **Gallery:** in-app full-frame viewer with a large date/title hierarchy,
  neutral matte surroundings, horizontal paging, pinch/pan/double-tap zoom,
  spring-animated filmstrip selection, floating share/favorite/delete circles
  and actual rotated-metadata-fit video playback. Physical dialog window insets
  plus a 20 dp lift keep actions clear of navigation/cutouts. Smaller visible
  circles retain >=48 dp touch targets. Only app-owned published media is listed. Delete
  requires confirmation; share grants URI read access to the chosen app.
- **Shutter:** disabled while capturing/saving or the session is unavailable;
  admitted photos alone drive its pulse. During active video the stop control
  remains enabled. Photo/Video changes are rejected during capture or recording.

## Motion

- Mode selection has one traveling pill; icon fills and labels ease between states.
- The zoom disc follows a continuous, logarithmic position between lens stops and
  becomes a faint guide away from exact stops. Readouts use critically damped
  easing. Within-lens preview crop changes now ease on the camera handler; these
  intermediate values never choose physical lenses or rebuild sessions. Still
  capture uses the final requested crop, not an animation step.
- Sliders track the finger directly with matching track/touch insets. Taps and
  programmatic resets spring-settle, the thumb grows on interaction, and haptics
  are limited to detents. Each retains a 44 dp touch target and progress semantics.
- Admitted captures drive shutter pulse rings and a restrained viewfinder flash.
  Actual capture/save busy state contracts the disc and fades an indeterminate
  indicator in/out. The button remains disabled until the real operation ends.
- A successful save crossfades the gallery image and sends a small thumbnail from
  the shutter to the gallery. Failures do not receive a success flight.
- Status/aperture crossfades, badges and zoom readouts animate inside fixed bounds;
  the settings sheet is an overlay. None of these resize the native surface.
- Browser transitions and the thumbnail flight respect `prefers-reduced-motion`.
  Native motion uses Compose animation APIs and the platform duration scale.

## Preview and verification

`index.html` is an interactive design companion using `sample_portrait.jpg`,
not a live camera or an Android emulator. It labels its simulated capabilities
and capture actions, including its video start/stop simulation. No browser MP4 is
recorded. The browser viewer uses only the supplied sample; actual media actions
remain in the Compose implementation.

Run `python3 tools/preview_server.py` for the browser preview. No external font,
image or runtime network service is required. The static design has been checked
at desktop and 320–390 px widths, including Photo/Video, bokeh/resolution exclusivity,
settings, grid, zoom/range motion, capture re-entry guards, reduced motion, and
JavaScript errors. Android layout, touch behavior and HAL
capture must still be verified on the actual phone.


## Viewfinder smoothness follow-up

The source preview targets full-field 1440×1080 at an advertised 60 fps; the
SurfaceView buffer is fixed at upright 1080×1440 with a 60 Hz presentation hint.
Quality/FPS negotiation and explicit fallback never resize the saved JPEG.
Physical zoom handoffs overlay a one-shot outgoing PixelCopy image and dissolve
only after fresh rendered frames; they are not concurrent-camera optical flow.
See [the full contract and phone test matrix](PREVIEW_SMOOTHNESS.md).

## Current implementation validation

See [NOTHING_UI_PORTRAIT.md](NOTHING_UI_PORTRAIT.md). Structural checks and the
browser companion do not establish Compose layout correctness. A full APK build,
font-scale screenshots and device validation of the live SurfaceView remain gates.
