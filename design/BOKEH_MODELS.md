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
Vulkan depth/CoC/refinement/gather/composite pipeline. Inference remains capped
at about 12 updates/sec, independently of preview rendering. This is not a new
full-resolution still-matting path. A bokeh still uses the timestamp-valid
preview matte from the selected engine; it does not infer on the exact captured
still. Ordinary HAL JPEG and 50 MP paths remain unchanged. Video bokeh is not
implemented by adding RVM; RVM here is evaluated on the live Photo portrait feed.

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

Still capture/model changes are gated during checks. Portrait capture also waits
for a fresh successful matte from the selected engine. A too-slow stale mask is
reported, not made valid by assigning it a newer timestamp. Checks reject severe
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
