# Selectable bokeh models and on-device checks

## Use it on the phone

1. Open **Photo → Camera controls → Bokeh model**.
2. Choose a model; the choice persists across app restarts. This does not change
   the camera stream, still size, JPEG quality or turn portrait on automatically.
3. Close Settings and use the corner person button to view its portrait effect.
4. To test runtimes, point the camera at a person, open the same Settings section,
   and tap **CHECK SELECTED** or **CHECK ALL**. Portrait need not be enabled.
5. Read the per-model results and optionally **COPY REPORT**. No photo is saved
   and no image is uploaded. **CANCEL CHECKS** cancels between native calls; it
   cannot forcibly interrupt a native inference already executing.

The selected model is never secretly replaced after an initialization/inference
failure. An explicit error is shown; select again or run checks to retry. Only
**MediaPipe Auto**, clearly named as the original adaptive baseline, can change
from dual to Landscape due to thermal/FPS policy. Its effective mode is shown.

## Included choices

| Setting | Runtime and input | Behavior |
| --- | --- | --- |
| MediaPipe Auto (default) | Original TFLite path; NNAPI `mtk-mdla_shim` requested | General + Landscape max fusion; adaptive Landscape fallback; EMA 0.25 |
| MediaPipe Dual | Same original TFLite/delegate path | Fixed General + Landscape pair; no thermal/FPS model substitution |
| MediaPipe Landscape | TFLite CPU/XNNPACK, 2 threads; NHWC 256×144 RGB [0,1] | Landscape only |
| MediaPipe General | TFLite CPU/XNNPACK, 2 threads; NHWC 256² RGB [0,1] | General only |
| MODNet | ONNX CPU, 2 threads; NCHW 384×512 RGB [-1,1] | Single-image portrait alpha |
| RVM MobileNetV3 | ONNX CPU, 2 threads; NCHW 384×512 RGB [0,1], downsample 0.5 | Recurrent portrait alpha; state reset for camera/session/model changes or long gaps |

An NNAPI request is **not proof of APU placement**; actual operator placement and
performance require device logs/profiling. The original dual loader has its own
internal warmup. Fixed CPU and NNAPI-requested paths are different backends—read
the runtime description instead of treating timing as a backend-neutral ranking.

Only one live engine is retained. Model creation, inference, recurrence and close
are serialized on the matte worker. Camera callbacks copy bounded upright YUV
samples; no Image/plane buffer/AHB is passed to that worker. A model switch clears
published masks and generation-checks reject late old-model/old-session outputs.
Native calls are allowed to finish before their resources close. Pause, turning
portrait off and switching models release engines on that same worker.

All models publish a **common 256² sensor-coordinate matte** into the existing
Vulkan depth/CoC/refinement/gather/composite pipeline. Live inference uses an
83–300 ms adaptive dispatch interval, independently of preview rendering. Input
stays **384 × 512**, and row-bulk plane reads preserve the original sampling,
rotation, buffer origins and chroma strides. RGB conversion/inference run on a
background-priority worker, with no new live inference dispatched during capture.

The first (cold/load) cycle is exempt from the slow-cycle policy. Two consecutive
warm cycles over 220 ms pause **only the live effect**, visibly; the chosen model
remains selected and available for stills. Reselecting the model, running checks
or opening a new portrait session resets this policy. This does not promise that
all six models can sustain live bokeh on the phone. Prefer MediaPipe for live use;
the reported ~708 ms MODNet median cannot produce a continuously fresh live mask.
Mask freshness is still **500 ms**; timestamps are never rewritten to hide delay.

Portrait capture no longer depends on a live matte being ready. It copies a
bounded sample of the **received still** and infers with the frozen selected
model on the same serial worker, allowing up to three seconds including queued
work. No still Image is handed to that worker. Timeout/failure saves an explicitly
reported **sharp photo**, not an unrelated or expired preview matte. Portrait
requests disable ZSL; full native output dimensions, JPEG quality 100 and still
GPU refinement remain unchanged. The 256² matte is not full-resolution neural
inference. Ordinary HAL JPEG and 50 MP paths remain unchanged. Video bokeh is not
implemented by adding RVM.

## What checks measure

- One fresh, bounded RGB sample is shared by every checked model.
- Size/SHA-256 and expected model inputs/outputs are validated.
- There is one additional untimed warmup and **five timed cycles** per model.
- Timed cycles include model preprocessing, inference and alpha postprocessing;
  common camera-plane copying and YUV→RGB conversion are excluded. Load time is
  reported separately and can include a loader's internal warmup.
- Float alpha is checked for shape, finiteness and range before byte conversion;
  mask min/max and foreground percentage are reported. Flat masks warn rather
  than automatically fail, since a frame may legitimately contain no person.
- RVM recurrent outputs are validated/copied before its result closes and fed to
  subsequent iterations. Repeating one frame does **not** test moving-edge quality.
- Median and nearest-rank p95 are shown; with five samples, p95 is the maximum.
  These are short runtime checks, **not sustained FPS, GPU/thermal profiling,
  display-latch measurements, accuracy scores or proof of better hair detail**.

Still capture/model changes are gated during checks. Outside checks, a missing,
failed, stale or suspended live matte does **not** disable the shutter. Camera
readiness, one-capture-in-flight and zoom-transition safety gates remain. Checks reject severe
thermal load and cancel cooperatively if it develops. The live view continues,
but preview performance/portrait masking can degrade while benchmarking; inspect
steady-state preview separately after checks finish.

## Reproducible APK bundling

All picker choices ship in the APK—there are no model-download buttons or fake
availability rows at runtime. New FP32 weights total **40,864,336 bytes** (~39 MiB)
before APK compression, plus the ONNX Runtime dependency. They remain out of Git
under the repository's existing external-model convention.

```bash
python3 tools/fetch_bokeh_models.py          # download missing/corrupt weights; verify SHA-256
python3 tools/fetch_bokeh_models.py --verify # local/offline verification only
gradle assembleDebug                       # preBuild invokes the same fetch automatically
```

Python 3 and network access to GitHub model URLs are needed for the first build.
Once verified weights are cached, model fetching works offline (Gradle's other
artifacts must also be cached). A checksum failure aborts bundling. The manifest
`assets/models/bokeh-models.json` pins release URLs, immutable Git-blob mirrors,
byte sizes, hashes, licenses and source URLs. The public downloader does not need
GitHub credentials. Git-blob mirrors contain the exact same checked model bytes,
not LFS pointers. The app verifies weights again before creating an engine.

[Bundled notices](../assets/models/licenses/NOTICE.md) include Apache-2.0 (MediaPipe,
MODNet), MIT (ONNX Runtime), and **GPL-3.0-only (RVM)**. Review RVM's redistribution
and corresponding-source obligations before publishing binaries; including
notices alone is not a public-distribution legal clearance.

## Validation and phone acceptance

Performed in this workspace:

- Portable fetcher/model/UI/lifecycle contracts via `./build.sh check`.
- Actual TFLite and ONNX weights executed on desktop CPU: expected shapes/dtypes,
  finite alpha, RVM zero-initialized recurrence, repeated recurrence and reset.
- Kotlin 2.0.21 compilation of the seven model/data/profile source files against
  Android 35, the project's TFLite jars and official ONNX Runtime 1.20 Java API
  sources. This is a model-layer compile check, **not an APK/Compose/NDK build**.
- 25 executed JVM tests: BokehMathTest + BokehProcessorTest + FrameDataTest. Other Gradle/JVM suites,
  shader compilation, full Android build and phone delegate/performance tests
  remain outstanding.

On the phone: test every row and persistence after restart; run selected/all with
and without portrait enabled; test empty scenes, hair, glasses, motion, low light
and camera occlusion. Confirm explicit failure on corrupted/missing model assets,
not silent fallback. Cancel during model load/inference; background and return;
change model quickly; test session/lens rebuilds and thermal interruption. Ensure
old models cannot publish after a switch, RVM starts a fresh temporal history,
and normal/50 MP JPEG dimensions/quality and video recording are unchanged.

## Portrait integration regression pass (2026-11-01)

The Nothing A001 report showed why a runtime PASS is not an integrated camera
acceptance test. Fixes additionally normalize logical stereo camera `5` to UI/main
lens `0` (preserving synchronized depth routing), publish coherent generation-tagged
matte snapshots, and report failed/aborted still requests as failures rather than
successful captures. A request failure permits retry on an otherwise healthy
standard-resolution session; device/session failures remain separate.

Current host validation: **71 Python tests**, including compilation/execution of
the platform-free C++ submission-order regression; **69 JVM tests** covering the
actual policy, sampler, readiness, zoom and existing math/orchestration code.
For the standalone JVM run only, Compose marker/image types, Android Build and
controller constants had test stand-ins; this is not controller/Compose validation.
Actual AIDL generation and Java 17 compilation of CameraUserService,
CameraCapabilities and CameraZoom against Android 35 also passed (warnings only).
The C++ helper also ran with address/undefined-behavior sanitizers. Java/Kotlin
syntax parsing and VulkanPipeline.cpp syntax parsing passed. Full APK/Compose,
Android native compilation, shader compilation and on-device validation remain
outstanding. Historical model-runtime results above are not repeated by these
host tests.

Phone acceptance: start with MediaPipe Landscape, portrait at 1× and a person in
view. Confirm the shutter works during warmup; compare f/1.4 and f/16 in preview
and saved photos. Repeat at 0.6×/2× and after pause/resume. Select MODNet and wait
for the live-paused notice, then verify still portrait capture (or an explicit
sharp fallback if it times out). Retry a failed request, run/cancel checks, and
ensure no old matte appears after model/lens switches. Record rendered FPS and
preview gaps **outside capture** separately from full-resolution capture latency.
