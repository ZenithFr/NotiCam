# MereFrame feature-parity delivery plan

Updated: 2026-11-02

## Scope and completion rule

User direction: all compatible MereFrame features, explicitly including Ultra XDR,
photo merging and related computational photography. This is an independent
implementation plan, not permission to redistribute proprietary code/assets or
bypass licensing. The evidence inventory is `MEREFRAME_FEATURE_AUDIT.md`.

No silent omissions: every discovered user-facing option must end in one of:

- **Implemented, host-checked**: code exists, but Android/device behavior is not proven.
- **Device-validated**: tested capture/output behavior on an identified lens/device/configuration.
- **Pending**: implementation or required evidence/testing remains.
- **Unsupported**: a specific hardware/API/session constraint is documented, including a fallback if possible.

A UI toggle is not implementation. Metadata eligibility is not a delivered stream.
The existing inventory is not yet an exhaustive reconstruction of every settings
screen, native function or runtime-dependent option. Newly discovered options
must be added rather than assumed covered by a group heading.

## Delivery ledger

| Workstream | Explicit coverage | Current state |
|---|---|---|
| Exposure | Auto, ISO/shutter together, numeric entry, locks, supported priority modes | Manual pair host-checked; existing locks; numeric/priority pending |
| White balance | Advertised presets, calibrated Kelvin/tint or gains, lock | Presets host-checked; remaining controls pending |
| Focus | Manual/lock, peaking color/threshold, temporary magnifier | Existing manual/lock; preview aids pending |
| Composition | Five grids, level/bubble, aspect options | Grids host-checked; remaining items pending |
| Exposure instruments | Luma/RGB histogram, waveform, RGB parade, zebras/thresholds, false color | Pending; real preview sampling and output isolation required |
| Preview | Efficient/full-detail options, low-light visibility, measured frame cadence | Existing preview separation; additional options/device validation pending |
| Single-frame photos | Minimal processing, native JPEG quality 100, DNG, genuine advertised resolution | Existing paths; continued device/output validation required |
| Photo assets | HEIC, JPEG+DNG pairing, retained RAW and re-export | Pending beyond existing individual JPEG/DNG paths |
| RAW acquisition foundation | Bounded burst, frame/result timestamp matching, calibration, cancellation, failure recovery | Implemented/host-checked: 1–4 separate DNGs within payload budget; exact pairing, cancellation and staged saves. Device/output validation pending |
| Photo merging | Explicit count, automatic count, aligned same-exposure denoising, motion rejection | Pending; must retain single-frame fallback and avoid ghosting/blur |
| Exposure bracketing/HDR merge | Actual requested/delivered exposure sequence, alignment, linear radiance reconstruction, deghosting, SDR rendering | Pending; distinct from same-exposure denoising |
| Night | Bounded multi-frame exposure/merge, motion-aware fallback, thermal/storage limits | Pending; opt-in |
| Astro | Long-exposure sequencing, hot-pixel handling, alignment appropriate to scene, cancellation | Pending; supported exposure duration and sky/foreground behavior must be explicit |
| Ultra XDR | Identify exact MereFrame option, its inputs, merge/render path, file format and display behavior; implement compatible equivalent | Requested explicitly; exact semantics unverified; pending |
| HDR photo export | Genuine HDR/gain-map encoding if applicable, correct color/transfer metadata, SDR fallback and compatible-viewer tests | Pending; not synonymous with HDR merge or a contrast filter |
| RAW development | Black/white normalization, CFA-aware demosaic, calibrated WB/color, highlight handling, optional lens/hot-pixel corrections | Pending; preserve originals and document precision |
| Looks/editing | Optional looks, development controls, re-edit/export and metadata preservation | Pending; never silently replace minimal-processing default |
| Processed video | AVC/HEVC, compatible size/rate/bitrate controls, native-aspect default | Existing AVC/native-aspect path; additional controls and device verification pending |
| Stabilization | Lens-specific OIS, supported EIS modes, crop disclosure | Metadata reporting added; user controls/session validation pending |
| RAW video | Bounded acquisition, clip manifest/index, timestamped metadata, drops/backpressure, storage checks, crash-safe finalization | Pending; no private MereFrame container required |
| Instant RAW video | Live calibrated RAW development to a real encoder, quality/performance selection | Pending; separate from retained RAW and camera-processed video |
| Video color | Documented RAW-derived Log, compatible 10-bit/HLG/HDR paths and correct exported metadata | Pending; hardware support and pipeline precision must be verified |
| Timing | Multiple timer shots, interval/time-lapse capture and assembly, supported slow-motion capture/playback | Existing single timer; remaining items pending |
| Audio | Routing, channels/rate/quality, processing controls, meter, zoom only if genuinely supported | Existing basic audio; extended features pending |
| Storage | Scoped internal/external destinations, folder copies, safe RAW reclamation, interruption/removal handling | Additional features pending |
| Metadata | Capture metadata, opt-in location, paired asset linkage, export preservation | Full coverage pending; never add location silently |
| Convenience | Pin-able shortcuts, settings search, haptics/gestures, volume shutter, mirror/screen flash, lens-area guard | Searchable categorized sheet and manual rail implemented/host-checked; other items and UI/device validation pending |
| Viewer | Original/HDR display, photo/video browsing, RAW development entry, paired-asset management | Existing viewer; HDR/development/paired workflows pending |
| Diagnostics | Per-lens metadata, real stream/session probes, sustained RAW/encoder/storage tests, reproducible reports | Expanded metadata host-checked; actual stream/throughput tests pending |

## Next implementation sequence

1. **Complete feature evidence and build/device baseline in parallel.**
   Inventory remaining settings/submenus, especially Ultra XDR and merge options;
   preserve exact labels, input/output behavior and evidence anchors. Build and
   validate the already-written controls/diagnostics before treating them as shipped.
   Running/installing apps or device probes remains subject to user approval.
2. **Implement actual RAW session/burst diagnostics and bounded acquisition.**
   Match each image timestamp to its capture result; retain calibration/exposure;
   bound image ownership, memory and disk use; handle disconnect/cancel/partial
   failure; preserve native frames. This foundation supports both the originally
   planned RAW video phase and the explicitly requested photo processing.
3. **Deliver the photo-processing branch on that foundation.**
   First repeatable same-exposure alignment/merge, then exposure bracketing and
   HDR merge/deghosting. Add Night/Astro and the verified Ultra XDR equivalent
   incrementally. Export genuine HDR assets only with a defined color pipeline
   and verified encoding; keep a correct SDR fallback and untouched RAW source.
   This promotes the newly emphasized photo-processing work; it does not drop
   the remaining video/development work.
4. **Complete retained RAW clips, development/export and Instant RAW video.**
   Reuse timestamp pairing, calibration, storage limits and recovery, but measure
   sustained throughput independently of short photo bursts. Add calibrated Log
   and compatible HDR/10-bit paths only after end-to-end precision is established.
5. **Finish the ledger, not just the headline features.**
   Remaining scopes, controls, processed-video options, stabilization, timing,
   audio, storage, editing and convenience features each need explicit acceptance
   evidence or an honest unsupported reason. Preview aids can be developed in
   parallel but must never enter recorded render targets.

## Acceptance gates

- **Burst/merge:** real captured frames, exact timestamp/result pairing, bounded
  memory, successful cancellation, moving-scene tests, no duplicate-frame averaging
  masquerading as a burst, and measured noise/detail/ghosting comparisons.
- **HDR/XDR:** distinguish capture dynamic range, merge precision, tone mapping
  and HDR display encoding. Confirm the actual file payload/metadata, SDR behavior
  and output on a compatible HDR display; a label or edited 8-bit JPEG is not proof.
- **RAW/video:** record actual delivered size/format/rate, row stride, drops,
  sustained storage speed, thermal behavior, audio sync and recoverable finalization.
- **Controls/aids:** verify accepted and delivered settings, lens/session changes,
  preview cadence and that saved photos/videos do not contain diagnostic overlays.
- **Defaults:** Photo/Video remain the only top-level modes; portrait stays a corner
  toggle. Keep native-aspect video, genuine metadata-backed resolution, JPEG 100
  and the minimal-processing single-frame path. New merging/looks remain opt-in.

## Present validation boundary

Current RAW/UI increment: **94 Python checks and 108 JVM tests pass**. The JVM
suite includes 19 production RAW matcher tests (500 deterministic callback-order
trials inside one test), 5 RAW state tests and 3 settings-search tests. Python
integration checks are structural, not Camera2 runtime tests.

Regenerated AIDL and changed Java service/capability/matcher sources compile
against Android 35, targeting Java 17. The actual Kotlin controller, state and
related policies also compile against Android 35 and the regenerated interfaces,
using temporary Compose State/ImageBitmap type stand-ins and a bokeh factory
stand-in. That targeted compile does **not** compile the Compose UI or real model
factory. Kotlin PSI syntax checking passes for all 49 source/test Kotlin files;
this is not Compose type checking. Full Gradle/NDK/APK, shader and device tests
remain outstanding. See `RAW_CAPTURE_INCREMENT.md` for exact boundaries and gates.
No claim of exhaustive feature parity, implemented merging/Ultra XDR, RAW video
or genuine 50 MP RAW is made by this plan.


## Handoff evidence update (2026-11-02)

The uploaded reports-only ZIP has been opened and reviewed; see
[`MEREFRAME_HANDOFF_REVIEW.md`](MEREFRAME_HANDOFF_REVIEW.md). It does not resolve
Ultra XDR or photo merging and does not close any device-validation gate.
Reported RAW copy/layout details are useful leads pending primary traces.
Two supposed bitrate values were independently recognized as codec color-format
constants. A narrower evidence request is documented instead of repeating the
full inventory exercise. No capture-path implementation changed in this review.


## Second-bundle evidence update (2026-11-02)

The second ZIP was reviewed and original-APK DEX invocation anchors were checked
independently; see `MEREFRAME_PASS2_REVIEW.md` and
`MEREFRAME_PASS2_VERIFIED_ANCHORS.csv`. There are now concrete static targets for
Camera2 burst/repeating scheduling, u9 input submission, gain-map attachment and
a native encoding boundary. They narrow the investigation but do not complete
merge/HDR implementation or device/output validation. No capture code changed.


## Third-bundle evidence update (2026-11-02)

See `MEREFRAME_PASS3_REVIEW.md` and `MEREFRAME_PASS3_VERIFIED_ANCHORS.csv`.
Direct static verification resolves gain-map field/supplier and JPEG-helper
questions, and corrects a Handler argument misidentified as burst frame count.
The synthetic compatibility probe must not be mistaken for the scene HDR merge
pipeline. Remaining investigation needs actual excerpts and correct alias
mapping, not another broad handoff. Merge/HDR feature and device-validation
statuses remain pending; no production capture behavior changed in this review.


## App implementation increment (2026-11-02)

The acquisition foundation is now implemented rather than only planned. UI work
also uses the original package's control vocabulary/search/manual-rail evidence,
independently adapted to NotiCam's existing borderless glass style. There were no
XML layouts or verified runtime screenshots to establish a pixel-exact clone.
No proprietary packaged assets or app implementation were copied.

Immediate next gates: full APK/Compose build, real per-lens DNG capture/cancel/
partial-failure tests, then measured same-exposure alignment/merge. Bracketing,
HDR, Night/Astro, verified XDR, RAW development/Log and RAW video remain explicit
workstreams; a saved DNG sequence does not mark any of them complete.
