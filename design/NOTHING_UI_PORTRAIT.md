# Nothing-inspired UI / two-choice portrait increment

> Portrait/model and video-profile details below are historical where they conflict
> with the current [APU-only replacement report](APU_PORTRAIT_VIDEO_PROFILES.md).
> Auto/MODNet inference is now removed; the replacement is blocked pending artifacts.

Date: 2026-11-02. Implementation on `arena/ae08650c-noticam`.

## Delivered source changes

- Neutral black, Window Grey, N-Grey and white; restrained N-Red for recording.
  Borderless matte-glass fills and low-alpha neutral diffusion replace warm
  thumbnail-derived color. The gallery uses the same neutral surrounding surface.
- Original NotiCam identity, system sans/mono typography, no proprietary Nothing
  fonts/logos or implication of official affiliation. The community reference is
  design inspiration, not an official OS 5 UI specification.
- Rounded 24 dp camera preview retained. Photo/Video only, corner portrait button,
  zoom/physical-lens behavior, JPEG100, genuine metadata-gated 50 MP and saved-photo
  quality defaults remain unchanged.
- 48 dp minimum interaction surfaces, 40 dp icon bodies, padded variable-height
  text pills, bounded/ellipsized labels, separate video resolution/rate lines,
  and a font-scale-aware reserved manual-control rail. No decorative glass rims.
- Exactly **Auto** / **Portrait quality**. Old selections migrate to Auto. Removed
  dual/landscape/general/RVM exposure, old dual implementation and unused tracked
  weights. The fetcher retires the known generated RVM cache; Gradle also excludes
  that weight on incremental builds so removing its license does not leave it
  silently packaged from an earlier download.
- Auto uses discovered NNAPI accelerator names, tensor checks and a real warmup
  before accepting a session; failures release resources and retry CPU/XNNPACK.
  Runtime status distinguishes NNAPI requests from proven hardware placement.
- No expired-mask fallback. No dual-mask maximum union/temporal EMA. Conservative
  RGB-guided soft-alpha refinement, single raw-depth/color alignment, inverse
  mask/depth lookups, invalid-disparity rejection, relative focus-plane depth
  assistance, and captured-pair subject focus for stills. Empty masks without
  usable depth cannot authorize whole-frame blur.

Both models are **person** models. A depth surface can protect a foreground
object without the network recognizing its class; arbitrary object recognition
has not been added. Calibration and thin/hair/transparent boundaries remain
real-world evaluation work. See [BOKEH_MODELS.md](BOKEH_MODELS.md) for the actual
scheduling, limits, provenance and failure behavior.

## Validation actually run

| Check | Result / boundary |
|---|---|
| `./build.sh check` | PASS: source reference/resource checks, CoC host test, **101 Python tests** (includes compiling/executing the new C++ geometry test with `-Wall -Wextra -Werror`) |
| JVM production policies/state/orchestration | **117 tests PASS**, Kotlin 2.0.21 + JRE17 + ECJ/JUnit. Minimal Compose `Immutable`/`ImageBitmap` stand-ins and the extracted, unchanged aperture constant; no controller/HAL behavior is simulated as proven |
| Targeted Auto Android/TFLite compile | PASS against Android35 and the actual local TFLite jars, including NNAPI options/tensors. Quality branch excluded from this targeted compile; this is not the full Android app |
| Kotlin PSI syntax parse | **36 production Kotlin files PASS**; syntax only, not Compose typing/layout |
| glslang WASM | **All 7 compute shaders compile to SPIR-V 1.3**, including modified `alpha.comp`; outputs stay in ignored validation cache |
| Both retained model hashes | PASS, `python3 tools/fetch_bokeh_models.py --verify` |
| Actual host inference | MediaPipe with Linux TFLite 2.14 CPU and MODNet with Linux ONNX Runtime 1.20.0 CPU run the bundled sample. Outputs `[1,256,256,1]` / `[1,1,512,384]`, finite range `[0,1]`. This is not Android delegate validation, an accuracy score or a sustained benchmark |
| Browser companion | PASS at **320/360/390 px**: Photo/Video interaction, no page/format overflow, no JavaScript errors. A clearly labeled sample/simulation, not the Compose camera or Android emulator |
| `git diff --check` | PASS |

Validation toolchains, npm packages, model scratch work and compiled outputs live
in ignored `.cache/validation`; none are new production dependencies. MODNet
weights remain in ignored `assets/models/optional` and are bundled by the existing
verified build-time fetch task. Full Android SDK/NDK/Compose dependency resolution
is unavailable in this environment. **No complete APK/native-library build or
on-device validation is claimed.**

## Device/build release gates

1. Build the full APK with Android35, NDK27.1 and actual Compose/ORT Android
   dependencies. Confirm the new `-lneuralnetworks` link and JNI discovery method.
2. On CMF Phone 2 Pro, compare Auto/quality on stationary and moving people,
   hair, hands, a held object and no-person scenes. Cover bright, low-light and
   low-contrast backgrounds. Test main depth, ultra-wide/tele semantic-only paths,
   rapid zoom/session changes, background/resume and received stills after motion.
3. Verify unknown/all-zero/all-255 depth plus empty segmentation saves sharp;
   inspect actual still pixels rather than only a status label. Check top/bottom
   depth margins and subject focus after capture motion.
4. Inspect real NNAPI devices, accepted partitions/profiling and warm/thermal
   latency. A named delegate or runtime PASS does not prove D7300 Pro NPU execution.
   Confirm fallback, failed warmup cleanup, quality live pause and still inference.
5. Screenshot native Photo/Video/settings/gallery on narrow layouts and
   font scales 1.0/1.3/2.0 with navigation/cutouts. Check pill wrapping, header
   readouts, scrollable model descriptions, rail height and rounded SurfaceView.
6. Confirm normal JPEG/RAW/full-field video/50 MP behavior remains unchanged.
   This increment does not implement photo merging, Ultra XDR, Night/Astro or
   RAW/Log video; the phased parity backlog remains in MEREFRAME_PARITY_PLAN.md.

The live browser companion is served by `python3 tools/preview_server.py`;
it intentionally uses a bundled sample and cannot capture or record media.
