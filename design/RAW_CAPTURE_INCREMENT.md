# RAW acquisition + controls increment

> Subsequent chrome, 50 MP manual/RAW gating, sidecar v11 and HDR status:
> [latest controls increment](CONTROLS_RAW_HDR_STATUS.md). Older counts/contracts
> below describe the earlier increment.

Date: 2026-11-02. Implementation status: host-checked; not APK/device validated.

## Implemented

- Retain JPEG 100, minimal processing and one frame as defaults. Photo and Video
  remain the only modes; portrait remains an optional corner toggle.
- Independent MereFrame-inspired control organization: searchable Capture,
  Manual, Portrait, Aids and System sections; persistent search/navigation header;
  a fixed-height instrument rail for Auto/EV, ISO, shutter, advertised WB and focus.
  Existing model selection/checks, aperture, guides, timer and diagnostics remain
  reachable. Borderless glass surfaces are retained, without decorative rims.
- Select 1–4 **individual** DNG frames, restricted by the current lens's advertised
  RAW capability/stream and a shared 128 MiB acquired RAW payload policy. The
  largest eligible advertised RAW_SENSOR size is selected, not an invented
  4080/4096 size. Large frames can allow fewer than four acquisitions.
- FIFO acquisition, exact Image/SENSOR_TIMESTAMP pairing and tagged per-frame
  capture results. Either callback order is supported; no latest-result pairing.
- EXIF orientation codes (1/6/3/8), not degrees, passed to DngCreator. Each DNG
  writer receives that frame's actual TotalCaptureResult and lens characteristics.
- One DNG writer executor, explicit Image/FD ownership, duplicate/budget rejection,
  request failure/abort handling and a 20-second service watchdog. Reader closure
  is ordered after in-flight writers. Cancellation is exposed during RAW capture
  and the RAW countdown; pause, service disconnection, timeout and disposal also
  abandon the pending sequence.
- Scoped MediaStore destinations are prepared as pending rows on the IO executor.
  Submission and cancellation are ordered on Main, preventing late prepared
  requests from entering a replacement session. An atomic descriptor handoff
  also allows teardown to reclaim a dispatch removed from Main's queue.
- Dedicated write-backed completion, nonempty output checks, staged publication
  and all-sequence deletion attempts on failure/cancel. Latest URI/thumbnail and
  success status are assigned only in the current sequence's guarded Main callback.
  Failed RAW session combinations return to JPEG. Flash and portrait are off in RAW.

## Important limits

**This is not Merge, HDR, Night, Astro, Ultra XDR, RAW video or RAW development.**
The frame-count control saves separate sensor DNGs. It does not promise identical
exposure across frames when Auto is selected. Manual ISO/shutter remain bounded
by the existing policy (currently at most one second for stills).

RAW_SENSOR is a 16-bit storage format; it does not establish 16 useful sensor bits,
50 MP RAW, HDR precision or any OEM-only mode. The misleading “16-bit DNG” UI
claim has been removed. Shizuku cannot create an unadvertised sensor stream.

The payload budget is not a whole-process or HAL memory bound. Actual plane
padding is checked during acquisition and can reject an otherwise eligible
estimate. Preview buffers, reader/HAL queues, codec/decoder internals and DNG
writer overhead are outside that estimate. Reader maxImages and accepted frame
count are bounded independently. Sustained throughput and peak memory must be
measured on the target device.

MediaStore rows cannot be published as one atomic multi-image transaction.
Publication starts only after all writes and size checks, but another app could
briefly observe some rows if a later publication fails. Cleanup is best effort:
provider deletion failure is logged. This increment does not promise crash-safe
recovery across app/OS death or add a persistent burst manifest.

Original package resource strings supported the manual-rail/settings/search
interaction vocabulary. The APK had no XML layouts, and no runtime screenshots
were verified. This is an independent adaptation to NotiCam's existing styling,
not a claim of pixel-perfect cloning. The old browser concept in design/index.html
is not an Android screenshot and does not validate this new native layout.

## Validation performed

| Check | Actual result / boundary |
|---|---|
| `./build.sh check` | 94 Python checks, source/UI contracts and physical CoC checks pass; shader compilation skipped because glslc is absent |
| Production matcher JUnit | 19 tests pass: callback order, 500 deterministic shuffled trials, exact identity/metadata, duplicate handling, ownership, close failures, frame/byte bounds, overflow and EXIF mapping |
| Full available JVM policy suite | 108 tests pass, including 5 new RAW state tests and 3 settings-search tests; model orchestration tests use fake engines, not actual inference |
| AIDL | Actual updated service/callback interfaces regenerated with the Android AIDL tool; existing IDs 0–18 retained, new service IDs 19–21 appended |
| Android Java compile | Actual service, capability/zoom helpers, matcher and generated AIDL compile against Android 35, Java 17 |
| Targeted Kotlin compile | Actual controller, UI state, VideoCapture and related policies compile against Android 35/generated AIDL/local Shizuku jars. Temporary Compose State/ImageBitmap shapes and model-factory stand-in used; **not a full Compose/model build** |
| Kotlin PSI parser | All 49 production/test Kotlin files parse without syntax errors; **not UI type checking** |
| `git diff --check` | Pass |
| APK, Compose compilation, NDK/shaders, device capture or screenshots | **Not performed**; complete SDK/Gradle/NDK and required artifacts are not available in this sandbox |

On a configured build machine, run `./build.sh testDebugUnitTest` and
`./build.sh debug` (Gradle 8.9, JDK 17, Android SDK 35 and NDK 27.1.12297006;
see repository build instructions/model asset tasks). The source check command
alone does not run the JUnit suite or assemble an APK.

## Device acceptance gates before claiming delivery

1. Build/install the actual APK; check Compose layout at small/large font scales,
   keyboard-open search, scroll/pinned navigation, rail gestures and accessibility.
2. For each advertised RAW lens, verify real session creation, saved dimensions,
   CFA/black/white/color metadata, exposure/ISO, timestamp uniqueness, orientation
   and opening every DNG in an independent RAW reader. Record unsupported stream
   combinations and ensure JPEG fallback does not loop.
3. Exercise image-first/result-first delivery, counts 1–4 where eligible, padded
   planes, repeated capture, storage-full/permission/write errors and HAL aborts.
   Confirm no success is reported for empty or incomplete output.
4. Cancel during countdown, preparation, acquisition, DNG writes and publication;
   also pause/close, disconnect/rebind the sidecar and capture after each recovery.
   Check no deleted URI is shown as the latest saved photo and no camera remains held.
5. Measure peak application/HAL memory, responsiveness, write latency, temperature
   and preview cadence. RAW capability metadata is not throughput/session proof.
6. Only then build aligned same-exposure merging with motion rejection and real
   noise/detail/ghosting comparisons. HDR/bracketing, Night/Astro, verified XDR,
   development/Log and retained RAW clips remain tracked separately in
   MEREFRAME_PARITY_PLAN.md.
