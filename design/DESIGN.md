# NotiCam camera UI — Photo / Video

## Direction

An iOS-inspired, scene-first camera for the CMF Phone 2 Pro: black surround,
camera-yellow selections, borderless matte capsules and oversized tactile
capture controls. The requested iOS 27 direction is interpreted here, not claimed
as a pixel-verified reproduction of an Apple screen. No Apple assets, private
fonts or platform-only blur APIs are required.

The Android app remains Kotlin/Jetpack Compose over the native Vulkan viewfinder.
Changing the camera stack just for chrome would add risk without improving
capture quality. A small `cameraMatte` modifier retains the control surfaces with
a uniform dark neutral fill: no decorative stroke, reflective gradient, sheen or
backdrop blur. Functional shutter/focus rings and keyboard focus outlines remain.

## Layout

1. Safe-area toolbar: flash, camera-controls button, 12 MP / 50 MP selector.
2. Largest fitting native 3:4 portrait viewfinder with rounded corners.
   - Person-in-frame portrait-effect button in the top-right corner.
   - Active-effect or 50 MP / 1× badge near the top.
   - Lens selector centered near the lower edge, on a contrast gradient.
   - Tap-to-focus, pinch/scrub zoom and AE/AF lock retain their camera actions.
3. Reserved 48 dp strip: status/capture information, or the bokeh aperture slider.
   Toggling bokeh never resizes the native surface.
4. Matte mode capsule with **VIDEO / PHOTO only**.
5. Large ring/disc shutter and gallery thumbnail. The rear-lens cycling button
   is removed; an inert, equal-footprint spacer keeps the shutter centered. Lens
   selection remains available through zoom stops, pinch and horizontal scrubbing.
6. Quiet NotiCam wordmark, inside the safe navigation area.

Camera settings open in a scrollable modal bottom sheet with system back and
outside-tap dismissal. Manual focus/exposure are still available as controls;
there is no separate Pro capture mode.

## Interaction contract

- **Photo:** sharp by default. No synthetic blur unless the portrait button is on.
- **Portrait icon:** toggles bokeh inside Photo. Yellow means enabled; a simulated
  aperture slider appears. Starting from 50 MP explicitly returns to 12 MP.
- **50 MP:** use an advertised main-camera JPEG output at 1×. Disable bokeh,
  rebuild the still session and guard the shutter until ready. Unsupported sizes
  or stream combinations must produce a visible message/12 MP recovery, not an
  upscale. Zoom/lens changes explicitly return to 12 MP.
- **Video:** preview shell only in this phase. Hide the bokeh button, display
  “Video recording is coming later”, and disable the distinct red shutter. No
  fake recording clock or successful-recording animation.
- **Shutter:** disabled while capturing/saving or the session is unavailable;
  admitted captures alone drive its pulse. Photo/Video changes are rejected
  while capture is in flight.

## Motion

- Mode selection has one traveling pill; icon fills and labels ease between states.
- The zoom disc follows a continuous, logarithmic position between lens stops and
  becomes a faint guide away from exact stops. Readouts use critically damped
  easing. No intermediate animated values are sent to Camera2: an animation must
  never trigger repeated optical-lens changes or session rebuilds.
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
and capture actions. Native UI behavior remains in the Compose implementation.

Run `python3 tools/preview_server.py` for the browser preview. No external font,
image or runtime network service is required. The static design has been checked
at desktop and 390 px widths, including Photo/Video, bokeh/resolution exclusivity,
settings, grid, zoom/range motion, capture re-entry guards, reduced motion, and
JavaScript errors. Android layout, touch behavior and HAL
capture must still be verified on the actual phone.
