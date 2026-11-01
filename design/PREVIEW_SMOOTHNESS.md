# Smooth viewfinder, independent still quality

## Target and framing

Photo preview aims for **1440×1080 sensor YUV at 60 fps**. This is a full-field
4:3, 1080-line feed, presented upright in a **1080×1440** SurfaceView buffer.
It is deliberately not a 1920×1080 / 16:9 crop of a 4:3 photo. The native surface
buffer size is fixed independently of its UI footprint. API 30+ receives a 60 Hz
Surface frame-rate hint; that hint is not proof of 60 delivered camera frames.

The privileged service checks each actual camera's YUV output map, frame-duration
limits and advertised AE FPS ranges. It prefers the largest eligible 4:3 feed up
to 1440×1080, then the highest eligible rate up to 60. It does not trade 1080-line
detail for a tiny 60 fps stream. Missing 1080-line support is reported with the
actual smaller dimensions. Unknown frame duration does not establish 60 support.

A rejected 60 fps session retries once with a <=30 fps profile, retaining the
same JPEG size and quality. Open/readiness and fresh-frame watchdogs prevent a
frozen handoff from waiting forever. Some HALs cap a full-quality-JPEG or depth
session at 30 fps even if a preview-only 60 fps output exists. No constrained
high-speed session, guessed vendor FPS tag or upscaled still is used to evade
that limit. Existing MP4 recording remains <=1080p/30; its encoder sessions use
the 30 fps policy rather than changing recording quality/framerate silently.

**Camera controls → Viewfinder** shows source dimensions, target FPS, separately
measured rendered/queued-present FPS and fallback reason. Sensor capture-result
FPS is labeled separately in telemetry. These are not physical display-latch
measurements or a claim that every lens/lighting condition sustains 60 fps.

## Zoom and handoff

- Within a physical lens, crops use a retargetable, logarithmic ~55 ms time-constant
  easing, ticking at most once per 16 ms. No animated intermediate value is used
  to choose a physical camera or repeatedly rebuild a session.
- Ultrawide covers 0.6× through just below 1×; main starts at 1× and tele at 2×.
  The old 0.9× boundary could jump to the main camera's minimum 1× field.
- Before switching, PixelCopy samples the outgoing native surface **once**, with
  a 200 ms bounded wait. That private UI snapshot is placed above the live surface
  before asynchronous HAL closure. It is scaled toward the new field of view.
- A fresh ImageReader queue excludes old-session buffers. Only successful native
  render calls from the new reader/token advance readiness. After four incoming
  frames, the overlay allows two UI vsyncs plus a short settle, then dissolves
  over 320 ms. Zooming out shrinks the old image only once the wider live view is
  available underneath. New zoom targets during a handoff are coalesced.
- Capture is gated during handoff. Pause, surface loss, service death, failure
  and disposal cancel it; late PixelCopy callbacks cannot revive a canceled switch.
  PixelCopy failure degrades to the retained native frame rather than blocking.
- This masks a hard cut, not a true simultaneous-camera optical-flow morph.
  A moving subject, parallax between lenses, new autofocus/exposure and HAL
  downtime can remain perceptible. Device validation is essential.

## GPU and still isolation

Sharp viewfinder frames take a dedicated single-composite/present path before
CPU depth/alpha staging. They skip CoC, matte refinement, gather and diagnostic
readback work that their bypass shader would never sample. They retain the GPU
completion wait before releasing imported camera images; removing that wait
without per-flight ownership would be unsafe.

The still reader is separately sized from the sensor's JPEG map (or the selected
advertised 50 MP stream), not the preview profile. JPEG quality remains 100 and
HAL JPEG bytes/EXIF remain unchanged. A shutter request snaps to the **final
requested zoom**, never the in-between preview crop. Bokeh still processing uses
its existing full-resolution resources, not the freeze layer or preview shortcut.
No transition image is eligible for saving or sharing as a captured photograph.

## Validation and phone checks

Source-contract tests, optical math, Java/Kotlin/C++ syntax parsing and browser
regressions are available here. JVM tests are authored for crop convergence,
retargeting, invalid inputs, elapsed-time behavior, 1× routing and shutter gating.
Android/NDK compilation, JVM execution and physical 1080-line/60 fps measurements
are **not** available in this sandbox. The browser demonstrates only transition
motion over its bundled sample; it does not measure camera FPS.

On the phone, test 0.6↔1↔2 chips, slow pinches across 1×/2×, rapid reversals,
near/far subjects, indoor/daylight exposure, bokeh, 50 MP selection and app
backgrounding during each transition. Record target vs rendered FPS for every
lens for at least 30 seconds and after thermal load. Compare saved JPEG dimensions,
EXIF and full-resolution detail before/after this change; verify an immediate
shutter after digital zoom uses the final requested crop. Check 60→30 fallback
without a JPEG size change, and actual MP4 recorder start/stop after preview changes.
