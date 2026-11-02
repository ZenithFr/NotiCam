# MereFrame 0.8.6: static feature audit and NotiCam implementation map

Date: 2026-11-02. This is a factual compatibility inventory, not a source-code port.

## Exact input and handling

The user supplied `Mereframe_+RAW+Camera_0.8.6_APKPure.jpg`. Its bytes are a ZIP/XAPK, not an image. Initial analysis used a correctly named copy under ignored `.reference/`; those local caches are not guaranteed to persist. The original upload remains the input source for fresh verification.

- XAPK SHA-256: `72bc679d8d175d0b8c79de671cc2abe2add5838e5048c76eeb8e1cc630014fe3`.
- Package: `app.unnamed.rawrecording`, version `0.8.6`, version code `120`.
- XAPK metadata and decoded APK manifest indicate minimum SDK 30, target SDK 36.
- Base APK SHA-256: `2945519d3a4ba32cd807eb230c7343031b42dc20bad5adf46477564e0d0420c4`.
- Split APKs: arm64-v8a and mdpi.
- Native library: `libmereframe_raw.so`, 3,223,032 bytes; SHA-256 `0da2e3a09dfa042cf362cec4a3eed0ebc18d6d1614eafb16e7c5a21aac4c3545`.
- No APK, native library, third-party shader, font, image, resource dump or decompiled source is included in NotiCam's tracked files. The target app was not installed or executed.

## What REA did—and did not do

Used the official `rea-agents` 6.8.0 npm package from the user-requested `morluto/rea` project, with an isolated Node 24.22.2 runtime. No agent registration/setup files were modified.

Successful operations:

1. `inspect-artifact` on the base APK: authenticated archive inventory. Local output: `.cache/mereframe/rea-artifact.json`; evidence ID `ev_8263db17cf0fd501fd3b88d5154a46c4f6ebb265810aed1b032eefa60917a57f`.
2. `inspect-native-exports` on the arm64 library: ELF arm64, 64-bit, little-endian; one defined public function export, `JNI_OnLoad`. Local output: `.cache/mereframe/rea-native-exports.json`; evidence ID `ev_f18b52a9fecbc9b463489c933ba77208b74c3137e100ea1510341aa6ec559c87`.

REA's `inspect-android-package` requires its separate JADX headless MCP 0.7.1 backend. Doctor correctly reported that dependency missing. Downloading the official backend JAR failed at GitHub's release-asset host, which this environment cannot reach. **REA JADX decompilation has not run.** A successful archive inventory is not a successful Android decompilation.

Supplementary static analysis used Androguard to decode the manifest/resources and inspect actual DEX instructions: 2,749 classes, 17,728 DEX strings and 91 methods referencing the selected camera API patterns. Relevant files remain local under `.cache/mereframe/`: `manifest.xml`, `strings.xml`, `classes.txt`, `api-call-inventory.json` and `methods/` disassemblies. Native implementation internals, dynamic JNI registration, licensing checks, runtime behavior and performance have not been reconstructed or tested.

## Evidence levels

- **UI**: an option and explanatory text exist in decoded Android string resources. This does not prove a working implementation on any given device.
- **API**: actual DEX instructions reference relevant Android APIs. This does not prove execution, valid parameters, successful sessions or camera/encoder support.
- **Native inventory**: an ELF export was parsed, not the underlying native algorithm.
- **Device**: requires actual phone testing; not performed here.

The identifiers below are obfuscated DEX descriptors, not descriptive source names. Resource names are also obfuscated, so a settings label cannot automatically be tied to a particular method by its name.

## Feature inventory and independent implementation requirements

| Group | Observed options | Evidence | NotiCam status / requirements |
|---|---|---|---|
| Manual exposure | Shutter time, ISO, auto/manual behavior, exposure lock, numeric entry | UI; `Lyo;->a(...)` references AE mode, exposure time and sensitivity; `LRF;->c(...)` also references all three | Phase-one ISO + shutter controls added below, with advertised ranges and Auto/reset. AE/AF lock and EV already existed. No shutter/ISO priority mode or automatic numeric entry was added. |
| White balance | Auto/manual WB, Kelvin UI | UI; `Lyo;->a(...)` references AWB mode and color-correction gains | Phase-one standard, advertised AWB presets added. Calibrated Kelvin/gains control remains pending; no approximate Kelvin-to-RGB claim. |
| Focus | Manual focus, lock, peaking, peaking color/sensitivity, temporary 4× magnifier | UI; existing focus-related capture-result strings | Manual focus and lock already exist in NotiCam. Peaking/magnifier must be preview-only, not extra processing of saved photographs. |
| Framing | Thirds, golden, cross, diagonal and square grids; level/bubble; aspect controls | UI | Five guide styles now implemented as preview-only geometry with persisted choice. Level/bubble remains pending; native preview geometry is preserved. |
| Exposure instruments | Luma/RGB histogram, waveform, RGB parade, zebras with configurable thresholds, false color | UI | Not implemented. Use actual preview pixels; overlays must never enter photo/video output. Keep off by default and measure preview overhead. Per-channel clipping is different from a luma-only threshold. |
| Preview policy | Efficient/full-detail preview, low-light framing visibility, GPU overlays | UI | Preview/still separation already exists. Do not reduce final still quality to speed up preview. Low-light preview can add latency and should not be silently enabled. |
| Photo formats | JPEG, HEIC, RAW DNG, retained RAW for re-edit/export, JPEG plus DNG | UI | JPEG 100 and DNG still paths exist; HEIC and linked RAW-plus-rendered assets need separate work. Do not fake HEIC by changing extensions. |
| Photo processing | One frame, automatic merge, chosen merge count, Night/Astro, HDR-style rendering, exposure bracketing, hot-pixel repair, lens corrections, looks | UI; native library and shader inventory exist, but algorithms not reconstructed | Bounded 1–4 individual DNG acquisition is now implemented/host-checked (see RAW_CAPTURE_INCREMENT.md). Alignment, merge, calibration/development and the listed processing features remain pending. They are not part of the existing portrait fix and may conflict with the user's minimal-processing preference unless opt-in. Keep Photo/Video as the only top-level modes. |
| Processed video | HEVC, size/rate/aspect selection, quality settings, encoder compatibility checks | UI; stream timing and high-speed API references | Existing NotiCam records native-aspect AVC. Add codec/size/rate selection only for combinations actually accepted by camera and encoder. Preserve the requested uncropped default. |
| RAW video | RAW frames retained for development after recording; automatic precision/resolution selection; post-stop export and storage reclamation | UI and DEX diagnostic strings; ImageReader/API references | Not implemented. Needs a real RAW ImageReader/session, metadata/frame timestamp pairing, bounded ownership/queues, storage throughput tests, a defined clip container, export/development and crash recovery. A video-format label or a DNG still button is not RAW footage. |
| Instant RAW video | Develop RAW during recording into a shareable video | UI and DEX diagnostic strings | Not implemented. Requires sustained Bayer development/color management and encoding in addition to RAW acquisition. Different from direct sensor RAW storage and different from ordinary camera-processed video. |
| Log / HDR | Natural vs Log; RAW-based Log path; 10-bit selection; HLG on supported processed-video devices | UI; `Laj;->U(...)`/`q0(...)` reference DynamicRangeProfiles | A flat tone filter is not camera Log. RAW-derived Log needs calibrated development and a documented transfer function/color space. HLG needs compatible camera dynamic-range profiles, encoder profile and output metadata. The supplied A001 report did not establish these capabilities. |
| Stabilization | Optical stabilization for video; processed-video EIS Off/On/Preview | UI; `LjA;->d(...)` references both video and optical stabilization request keys | Query supported modes. Default EIS off to preserve the user's full-frame requirement; explain any crop. No invented RAW stabilization. OIS availability is lens-specific. |
| Timing | Delayed shutter, multiple timer shots, time-lapse interval and later video creation, slow-motion rate/playback selection | UI; high-speed video table API references | Single delayed shutter exists. Other functions need capture scheduling, cancellation/background lifecycle, timing, storage and real high-speed session support. They can live under Photo/Video options without adding top-level modes. |
| Audio | Phone/wired/USB/Bluetooth input, mono/stereo, quality/sample rate, sound processing, suppression, level meter, audio zoom | UI | Existing basic recording audio is not this full feature set. Validate actual device routing/channel count; expose supported controls only. Audio zoom needs genuine platform support, not a cosmetic toggle. |
| Files | Internal/external recording storage, folder copies, retain/free RAW after export, location/capture metadata | UI | Use permission-scoped storage, removal/failure fallback and atomic publication. Location must be opt-in. Do not delete RAW until export has actually succeeded. |
| Controls | Pin-able corner shortcuts, search settings, haptics, one-finger zoom, swipe gestures, volume shutter, front mirror/screen flash and lens-area guard | UI | Searchable categorized settings and a manual-control rail are now independently implemented/host-checked. Other shortcuts/gestures remain pending. Preserve NotiCam's corner portrait button, glass style and Photo/Video-only layout. Avoid copying the other app's artwork, font or text. |
| Diagnostics | Per-lens camera tests, RAW stream tests, sustained video-speed tests, reports | UI; `Luy;->a(...)` invokes getOutputMinFrameDuration; multiple methods query physical camera IDs and high-speed tables | Metadata reports and model checks exist. Add explicit stream-session/throughput tests before claiming supported RAW recording or sustained frame rates. Never silently change system permissions/app-ops. |

## Useful DEX anchors

`Lyo;->a(CaptureRequest.Builder, CameraCharacteristics, Ljo;, boolean, boolean)` references:

- `CONTROL_AE_MODE`, `SENSOR_EXPOSURE_TIME`, `SENSOR_SENSITIVITY`;
- `CONTROL_AWB_MODE`, `COLOR_CORRECTION_GAINS`;
- `CONTROL_VIDEO_STABILIZATION_MODE`, `LENS_OPTICAL_STABILIZATION_MODE`.

`LjA;->d(CaptureRequest.Builder, CameraCharacteristics, LZw;, boolean, boolean, int)` references both stabilization keys. `Luy;->a(CameraCharacteristics, boolean)` invokes `StreamConfigurationMap.getOutputMinFrameDuration(format, size)`. These observations support standard Camera2-based control/capability handling, **not an OEM-only capability unlock**.

The UI distinguishes three video pipelines: camera-processed, RAW-derived development during capture, and retained RAW developed afterwards. It also describes stabilization cropping and explicitly limits Log to the RAW-derived pipelines. These distinctions should be kept in an independent implementation rather than combining them into a misleading format toggle.

## Port boundaries and suggested sequence

The user selected **all compatible features, in phases**. This audit does not transplant third-party source. The first independently implemented slice is described below; all other rows are pending unless explicitly marked already present. No billing, entitlement, anti-tamper, report encryption or installation protection is bypassed or copied.

1. Add validated manual controls and non-destructive viewfinder aids, preserving Auto and existing capture safety gates.
2. Add real session/throughput diagnostics, codec/rate selection and supported OIS/EIS options. Keep native-aspect and minimal-processing defaults.
3. Build a bounded, explicitly experimental RAW clip path with frame/result pairing, storage validation, drops/timing telemetry and reliable finalization.
4. Add independent RAW development, export and color-managed Log only after the RAW path is validated on the target phone.
5. Add optional merging, editing, extended audio/storage and convenience settings separately, subject to quality/processing scope decisions.

This sequencing is a proposal, not a claim that the referenced app's implementation has been recovered or all its features are feasible on Nothing A001. Static inspection cannot prove 50 MP, 60 fps, encoder Main10, RAW throughput, dynamic range gains or Shizuku-only unlocks.

## Implemented first slice: manual sensor controls and framing

- **Manual ISO and shutter together**, bounded by the current open camera's advertised MANUAL_SENSOR capability, AE-OFF mode, sensitivity/exposure/frame-duration metadata and request-key availability. No ranges are invented when metadata is missing.
- **Auto by default**, with explicit return to Auto. ISO/exposure/frame-duration keys are removed from the reused request builder when Auto resumes. Exposure compensation and flash are blocked in manual exposure; the previous EV value resumes in Auto. Manual exposure is disabled for the unverified high-resolution mode.
- Standard **white-balance presets** shown only when advertised. This is not calibrated manual Kelvin control.
- Preview, still and recorder requests use the same selection. Photo shutter is initially capped at **one second** to stay within the existing capture watchdog; video is bounded by its frame period. Photo preview exposure is separately capped to preserve its target frame rate, so a long-shutter preview can be darker than the saved photo; the UI explains this trade-off. Acceptance of a request is not proof of measured sensor timing.
- Updates run on the camera handler, coalesce a queued slider backlog, and report acceptance/rejection asynchronously. Failed requests restore the last accepted selection instead of disabling a healthy camera. Session/callback identity and client request tokens reject stale replies. The Shizuku sidecar version is bumped to **8** for the appended IPC methods; original transaction IDs and the reserved destroy ID are retained.
- **Thirds, golden-ratio, cross, diagonal and centered square guides**, drawn solely in the existing Compose viewfinder overlay. The enabled state and stable style ID are persisted. No image sampling, extra camera stream, frame crop or saved-file processing is introduced by these guides.
- Photo/Video remain the only top-level modes. JPEG quality 100, native output size and the existing portrait implementation are not replaced. Manual selections are not persisted across a fresh app launch; Auto remains the safe startup default.

The phase-one controls now live in **Settings → Manual → Sensor controls**, with quick controls in the instrument rail. Framing styles are in **Settings → Aids**, next to GRID. Histogram, waveform, parade, zebras, peaking, level, RAW video, Instant RAW development, calibrated Log/HLG, merging and the remaining inventory are **not implemented by this slice**. Existing native portrait JPEG persistence does not gain full sensor EXIF metadata from this work.

### Validation of this slice

- `./build.sh check`: **77 Python tests passed**, including new IPC, metadata, request-order, rollback, UI-wiring and output-isolation source contracts, plus the existing compiled host submission helper and optical checks.
- **81 JVM tests passed**: new range/clamping/slider/wire/WB/geometry/safety tests and existing suites. The standalone harness substitutes only Compose marker/image types, Android Build and controller constants; it does not validate the Android controller or Compose rendering.
- Actual AIDL generation and Java 17 compilation of the changed privileged camera service plus CameraCapabilities/CameraZoom against Android 35 passed (warnings only), linking the compiled Kotlin policy.
- Java/Kotlin syntax parsing and `git diff --check` passed.
- No full APK/Compose/Android native/shader build or on-device testing. Do not interpret these results as proof of manual control behavior, frame-rate stability, camera session compatibility or RAW recording support on Nothing A001.

### Phone acceptance for the first slice

Open Settings with each lens. Confirm only supported WB presets/ranges appear, and unsupported controls remain unavailable. Compare a low/high manual ISO and a short/long shutter in normal JPEG and DNG; return to Auto and confirm AE resumes. Test portrait with both a short and long shutter, remembering the preview cap. Start video after configuring a legal shutter, verify its timing, and confirm editing is locked while recording. Exercise pause/resume, rapid lens changes, slider drags and rejected requests; no stale reply should alter a new session. Cycle all five guides in both Photo and Video, restart the app, and verify that saved files contain no guide lines and retain their native dimensions.

## Local-agent follow-up (user supplied, 2026-11-02)

The user supplied a local REA/JADX and `dumpsys media.camera` summary. This is
additional reported evidence, not an independently reproduced device test here.
The local agent reports the correct target hashes, REA 6.8.0 and a verified
jadx-headless-mcp 0.7.1 backend, Java 21, but no native decompiler. Its Node 26.7.0
is below REA's stated 26.12.1 minimum on that release line; repeat authoritative
runs on a supported runtime. CLI startup alone does not establish supported operation.

### Important corrections to the supplied interpretation

If the vendor stream table uses standard Android HAL format identifiers:

| Value | Meaning |
|---|---|
| 32 / 0x20 | RAW16 / RAW_SENSOR |
| 33 / 0x21 | BLOB, commonly carrying JPEG; **not RAW_SENSOR** |
| 34 / 0x22 | IMPLEMENTATION_DEFINED / Java PRIVATE |
| 35 / 0x23 | YUV_420_888 |
| 37 / 0x25 | RAW10 |
| 38 / 0x26 | RAW12 |

Java `ImageFormat.JPEG` is 256 / 0x100, distinct from HAL BLOB 33. The reported
`[33,8160,6144,...]` and `[35,8160,6144,...]` entries therefore **do not prove
50 MP RAW**. They are possible high-resolution BLOB/YUV candidates, pending
confirmation of this custom vendor tag's schema and an actual successful stream.
Do not infer the meaning of its final 0/1 field without checking the layout.
NotiCam currently handles format 33 as a vendor JPEG candidate rather than RAW;
no RAW reinterpretation should be added based on the supplied summary.

RAW capability plus a 4080×3072 sensor/active array supports investigating RAW
at advertised sizes, not inventing a larger Bayer output. Standard maximum-
resolution stream maps, pixel modes and delivered images still need inspection.
Likewise, vendor HDR10+ flags are not proof of Android dynamic-range negotiation,
a usable Main10 encoder or app-accessible HDR recording. A 240 fps high-speed
entry does not imply a normal full-field/RAW stream at that rate. HEIC support
metadata needs actual size/encoder/session verification. A reported white level
of 1023 is not proof of 12-bit or 16-bit sensor precision; storage bit depth and
sensor signal precision must remain distinct.

The local report also identifies DNG-writing methods. These establish leads to
DNG output, **not the retained-RAW video container**. Trace clip start/append/
index/read/export/finalize paths before treating still-image DNG writers as video
storage. A 640×480 ImageReader session does not establish whether that stream
was a probe, thumbnail, preview, still or video output.

### Additional implementation anchors reported by the local agent

- `defpackage.C1553yo.a`: request controls; `C1553yo.b`: claimed API-36 AE-priority
  handling; `C1553yo.c`: AF handling. Exact API calls/guards are still needed.
- `defpackage.C0777jA.d`: camera processing modes, video stabilization and OIS.
- `defpackage.C1363uy`: size/capability selection utilities.
- `Aa`, `Ev`, `Hh`, `Pm`, `RF`: camera/probe/session/control-flow leads.
- `C1469x3`, `AbstractC1558yt`, `C1459wu`: RAW buffers and calibration metadata.
- `C1478xC`, `Lm`, `Um.Z`, `C0744ic`, `C1072p5`, `C1589za`: DNG/export leads.
- `C0218Rg`, `C1325u9`, `AbstractC0403bj`, `L7`, `C0481dB`: codec/muxer leads;
  identify audio versus video setup rather than assuming every encoder is video.
- `C0659gr`, `PA`, `EnumC0389bI`, `Ps`: scope/zebra/peaking settings and rendering
  leads. Settings getters alone do not establish algorithms, processing cadence
  or exclusion from recording; trace actual render targets/callers.

JADX aliases such as `C1553yo` may differ from the original DEX descriptors or a
subsequent decompiler run. Retain exact signatures, descriptors, tool aliases,
relevant call sites and evidence in follow-up reports.

### Still needed from the local investigation

1. Corrected raw metadata blocks with exact camera IDs, tag names/types, standard
   RAW/default/maximum-resolution maps, frame durations and pixel-mode capabilities.
2. Exact request-control/AE-priority and stabilization logic, supported-mode checks
   and fallbacks. NotiCam currently compiles against SDK 35; API-36 handling needs
   an explicit compatible approach, not unguarded newer API references.
3. The video (not audio) MediaFormat/encoder setup: MIME, profile, pixel/surface
   input, bitrate, dimensions, rate, color standard/transfer/range and HDR profile.
4. Actual RAW video writer/reader/index/metadata/finalization flow, distinct from
   DNG still export, including backpressure and frame/result timestamp pairing.
5. Actual scope/overlay rendering flow and isolation from recording output.
6. With separate user approval, short bounded session/throughput probes that
   report delivered formats, sizes, frames, timestamps, drops and rejection reasons.

The report is useful progress toward controls and pipeline reconstruction. It
still leaves the Log transfer function, working bit depth, sustained RAW recording,
HDR negotiation and native development algorithms unresolved. Do not upgrade those
items to "implemented", "verified" or "hardware unlocked" based on this summary.

## Second local-agent follow-up (user supplied, 2026-11-02)

The newer summary reports **Node 26.12.1, Java 25.0.4.1, REA 6.8.0 and
JADX 0.7.1 healthy** on the local machine. This supersedes the earlier local
Node/JADX setup limitation; do not keep requesting a backend download fix there.
No native decompilation, raw device dump or delivered-frame/session test was
supplied with this summary. Tool health and the following traces are locally
reported, not independently reproduced in this checkout.

### Useful pipeline leads, and what they do not establish

- `ey.c(Handler, ey, x3, ImageReader)` / DEX
  `(Landroid/os/Handler;Ley;Lx3;Landroid/media/ImageReader;)V`: reported acquisition
  loop (up to four images), flag gates, `x3.d`, image-to-`qy`, `x3.b(image,jv,true)`,
  image closure, queue `m` and `Um.Q0`/`Yx.I`; `q`/`r` are reported failure counters.
  This identifies an acquisition/processing lead, not a recovered clip format.
  Frame ownership, copying before image closure, result/timestamp pairing,
  backpressure, native frame representation and reader/finalization remain open.
- `u9` / `C1325u9.i(File,String,int,boolean)`: reported video MediaFormat creation,
  encoder configuration/start, muxer/PFD and orientation handling. Encrypted key
  names prevent treating guessed bitrate/profile/color fields as confirmed.
  **`MediaCodec.configure(format,null,null,CONFIGURE_FLAG_ENCODE)` does not prove
  byte-buffer input.** A surface encoder normally also passes null for the
  configure output surface. Trace `COLOR_FormatSurface`, `createInputSurface` /
  `setInputSurface`, `start`, input-buffer/dequeue calls and all relevant callers.
- `Rg` / `C0218Rg` is reported to be audio-focused, not proof of the video path.
  `x3`, `AbstractC1558yt`, `C1459wu` and DNG exporter aliases remain useful leads;
  DNG export alone still does not establish retained-RAW video storage.
- Control/AE-priority, Kelvin calibration, Log transfer/working precision and
  native development are still unresolved. Scope preference getters still do
  not prove actual algorithms or render-target isolation. Do not copy presumed
  native behavior or activate speculative API-36 request controls in SDK-35 code.

The newer wording still conflates format 35 with IMPLEMENTATION_DEFINED:
**35 is YUV_420_888; IMPLEMENTATION_DEFINED/PRIVATE is 34.** Vendor 33/35
50 MP entries remain BLOB/YUV candidates, not 50 MP RAW evidence. A null-surface
encoder configuration is not evidence of CPU buffer input. These corrections
are material to designing independent acquisition and encoding paths.

### Rechecked input integrity in this checkout

The original uploaded archive is still available at the supplied upload path.
It was freshly hashed, and its base APK was hashed from the ZIP contents:

- XAPK: `72bc679d8d175d0b8c79de671cc2abe2add5838e5048c76eeb8e1cc630014fe3`
- Base APK: `2945519d3a4ba32cd807eb230c7343031b42dc20bad5adf46477564e0d0420c4`

These match the original recorded values. The newer pasted XAPK string differs;
its base string is truncated/malformed and differs as well. Treat those as
unresolved transcription/integrity discrepancies, not proof of substitution or
verified local inputs. The local agent must rehash its own files; checking the
remote original does not attest to files on another machine. No archive or
third-party binary is added to Git.

## Implemented next increment: read-only recording/RAW metadata diagnostics

`CameraCapabilities.report`, reached through the existing Camera report share
action, now adds:

- An explicit HAL/Java format legend and warnings against interpreting vendor
  BLOB/YUV entries as RAW or assuming the vendor table's final-field schema.
- Separate **DEFAULT** and API-guarded **MAXIMUM_RESOLUTION** pixel-mode maps for
  RAW_SENSOR, RAW10 and RAW12, each with ordinary and high-resolution/slow sizes.
- Per-size minimum frame and stall durations. FPS is explicitly theoretical;
  zero minimum duration reports unknown rather than unlimited FPS. RAW_SENSOR
  gets a width × height × 2 RAW16 storage estimate excluding row padding, not
  a claim of sensor ADC precision, measured bandwidth or recording capacity.
- Sensor sensitivity/exposure/frame-duration ranges, maximum analog ISO, CFA,
  black/white levels, both calibration/color/forward matrices and illuminants;
  advertised manual/AE/AWB/AF/OIS/EIS metadata.
- Constrained high-speed sizes/ranges and HEIC size metadata, explicitly not
  proofs of normal/full-field/RAW high-speed recording or working HEIC encoding.
- Encoder input color formats, advertised surface input, dimension alignment
  and frame-rate range alongside existing profile/bitrate/dimension evidence.
  These ranges are not a promise that every combination or camera pair works.

Optional metadata, format/size timing queries, high-speed range queries and
codec MIME queries have independent exception handling. A missing calibration
key, unsupported RAW10 query, recorder candidate failure or maximum-resolution
query no longer suppresses unrelated later metadata for that camera. The report
retains its existing per-camera outer guard and size limit. No new camera
session, stream, encoder instance, benchmark, guessed vendor request or system
setting write is introduced. Capture defaults, photo quality, native video
framing and top-level modes are unchanged by this increment.

### Validation of this increment

- `./build.sh check`: **84 Python tests passed**, including seven new diagnostic
  source-contract tests. Existing source/Compose checks and host optical/CoC
  checks passed; shader compilation was skipped because `glslc` is unavailable.
- The actual changed **CameraCapabilities.java** compiled successfully against
  **Android 35**, linked to the actual compiled **VideoPolicy.kt**, targeting
  **Java 17**. Compiler tools were restored only in ignored `.cache/validation`.
- `git diff --check` passed. Original upload/base hashes were freshly verified.
- The earlier 81-test JVM suite and generated-AIDL/service compile are historical
  first-slice results, not newly rerun by this increment. No full APK/Compose/
  native build or device/session/throughput validation was performed.

This is a better evidence collector, **not RAW video, Log, HLG, a 50 MP unlock or
measured 60 fps support**. Independent recording/session diagnostics and bounded
RAW capture remain the next substantive pipeline work; no private MereFrame
container or proprietary development algorithm is required to start that work.


## Uploaded handoff review (2026-11-02)

The user supplied `mereframe-handoff.jpg`, verified as a small reports-only ZIP.
A `.zip` copy and its reports are under ignored `.reference/`. Full assessment:
[`MEREFRAME_HANDOFF_REVIEW.md`](MEREFRAME_HANDOFF_REVIEW.md).

The handoff adds reported copy-before-close and frame-layout details but contains
no underlying code excerpts/native addresses/output samples. Ultra XDR and the
merge/development algorithms remain unresolved. Do not promote those features
or the claimed encoder/overlay behavior to independently verified status.

A concrete new check identifies the report's supposed bitrate values as Android
codec color-format constants: 2135033992 is COLOR_FormatYUV420Flexible and 54 is
COLOR_FormatYUVP010, verified against Android 35. Resolve actual setter keys and
input submission before drawing pipeline or supported-bit-depth conclusions.
The base hash is still malformed; two CSV rows have incorrect column counts.
The review records exact corrections and the focused primary-evidence request.


## Second uploaded evidence bundle and independent checks (2026-11-02)

The user supplied `mereframe-evidence-pass2.jpg`, opened as a ZIP. See
[`MEREFRAME_PASS2_REVIEW.md`](MEREFRAME_PASS2_REVIEW.md) and the 43-row
[`MEREFRAME_PASS2_VERIFIED_ANCHORS.csv`](MEREFRAME_PASS2_VERIFIED_ANCHORS.csv).

The bundle still lacks primary evidence excerpts, retains the malformed base
hash and includes contradictory older reports. However, a supplementary direct
DEX inspection of the original verified APK independently locates actual
captureBurst/repeating calls, non-surface input submission in `Lu9;->d(JLyf;)V`,
gain-map creation/attachment in `LSq;->a(Bitmap,Lzq;)V`, and a declared native
byte-array encoding boundary in the named gain-map helper. These are static
anchors, not runtime tests or reconstructed native algorithms.

Consequently, Camera2 scheduling is not absent from the APK; the relevant caller
chains need further tracing. Buffer input in the u9 wrapper is now supported by
actual submission calls, not its null configure output surface. Successful
10-bit recording, exported gain-map correctness, the user-facing Ultra XDR label,
merge scheduling/algorithm and actual result pairing remain unverified.


## Third targeted bundle and direct static verification (2026-11-02)

The third bundle has corrected input hashes but its evidence files remain prose
notes, with several incorrect addresses and signatures. See
[`MEREFRAME_PASS3_REVIEW.md`](MEREFRAME_PASS3_REVIEW.md) and the 53-row
[`MEREFRAME_PASS3_VERIFIED_ANCHORS.csv`](MEREFRAME_PASS3_VERIFIED_ANCHORS.csv).

Supplementary Androguard static disassembly of the original APK resolves several
claimed blockers: `Lzq` has readable fields; `Lyq.c` transfers gain-map pixels and
dimensions from a Future's `Luq` result; `Baa.h(Bitmap,int)` is JPEG compression;
and the synthetic Baa.f/g patterns participate in an encoder-compatibility check,
not scene merging. `Ev.i()` returns a Handler: captureBurst's third argument is
not a frame count. These findings must replace the handoff's contrary guesses.

No complete setting-to-capture-to-HDR-file chain or merge algorithm was recovered.
No device operation was authorized/performed, and no capture code changed.


## Current implementation boundary

The newer RAW acquisition/UI work and exact validation results are recorded in
`RAW_CAPTURE_INCREMENT.md` and the parity plan. This includes a bounded short
DNG sequence, not photo merging or RAW footage. Earlier phase-specific test
counts above are historical; current checks are 94 Python and 108 JVM tests.
There is still no full Compose/APK build or on-device validation.
