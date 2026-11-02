# APU-only portrait request and explicit video profiles

> Subsequent chrome, 50 MP manual/RAW gating, sidecar v11 and HDR status:
> [latest controls increment](CONTROLS_RAW_HDR_STATUS.md). Older counts/contracts
> below describe the earlier increment.

2026-11-02 · `arena/74b91173-noticam`

## Video changes implemented

- Separate **1080 / 4K** and **FPS** controls in the header, plus explicit choices
  in Settings → Capture. 1080 permits 24/30/60 targets; 4K permits 24/30 only.
  Switching from 1080/60 to 4K selects 30 in the UI; it does not keep a fake 4K/60
  request or silently change the recording rate after Record is pressed.
- New appended AIDL transaction **22**, `getVideoConfiguration(lens, quality, fps)`.
  IDs 0–21 and reserved destroy 16777114 remain unchanged. Sidecar version **10**
  retires stale version-9 services.
- Probes intersect advertised recorder dimensions, native sensor aspect, minimum
  frame duration, exact advertised fixed AE range, YUV preview duration and
  hardware surface-input AVC encoder size/rate support. A variable [30,60] range
  is not proof of constant 60 capture. Unknown duration does not prove support.
- Actual recording passes the frozen selected FPS to MediaRecorder and the camera
  repeating request. The old 30-fps encoder-validation fallback, guessed [30,60]
  range, unadvertised MediaTek vendor key and "4K+ / overclocked" UI are removed.
- Unsupported rates are disabled in settings. The shutter is disabled until the
  selected profile is checked and supported. Stop remains available regardless
  of profile-probe state. Async results are guarded by selection, lens, session,
  request token, lifecycle and recording state. Failed configurations do not
  silently publish a substitute stream.
- Idle preview remains independently negotiated, so an unavailable selected
  recording profile does not strand the camera on an invalid idle AE range.

### Full-field constraint retained

The earlier native/full-field video requirement is preserved, not silently
replaced by a 16:9 crop. On a 4:3 sensor, 1080-line capture can be 1440×1080.
4K requires an advertised **3840-wide native-aspect** output (e.g. 3840×2880 for
4:3) and encoder support. 2880×2160 is not mislabeled 4K. Standard 3840×2160 is
not selected on a 4:3 sensor behind the user's back. Consequently any target combination, including 4K, 24 or 60 fps,
may be unavailable on a particular lens/device. Supporting cropped 16:9 recording
would be a separate, explicit framing decision. Metadata eligibility still does
not prove a successful combined HAL session or sustained encoded frame rate.

## Portrait: removed old engines; replacement is BLOCKED

The user requested MobileNetV4-Conv-Medium + LRASPP/matting, INT8, **APU-only model
inference**, using this pipeline:

```
YUV/RGBA frame ── downsample/copy 512×512 ── INT8 APU matting ── raw soft alpha
       │                                                   │
       │                                          guided edge refinement
       └──────── original full-resolution frame ────────────┴── depth-weighted disc/hex bokeh
```

**The neural replacement is not implemented or claimed to run.** No trained
matting/segmentation export was supplied. A MobileNetV4 classification backbone,
architecture name or latency table is not a trained portrait model. No measured
10–15 ms, parameter-count or crisp-hair guarantee is claimed.

Implemented safe changes:
- Removed MediaPipe/MODNet inference, selectable alternatives, CPU/XNNPACK/ORT
  fallback, weights and obsolete model licensing entries. Known old generated
  weights are pruned/excluded from APK assets. The asset ledger is empty rather
  than containing invented URLs/checksums or substitute classification weights.
- Removed the unused native synthetic `NpuWorker` and its misleading APU-duty
  logs. It never proved neural inference on the APU.
- Single stable model ID `mobilenetv4_conv_medium_int8`; old preferences migrate
  to it. Copied upright input geometry is prepared as 512×512 without resizing
  the original saved frame. No quantization scale/zero point is invented.
- `PortraitRuntime.available` is false. The portrait button explains the missing
  export/runtime and cannot enable native depth-only blur as a fake replacement.
  Engine construction fails explicitly; ordinary sharp JPEG/RAW capture remains
  available. The app does not use NNAPI delegate existence as APU-only proof.

Still required to complete the requested pipeline:
1. Trained MobileNetV4-Conv-Medium **with the LRASPP/matting head**, redistribution
   license, INT8 calibration/quantization metadata, normalization/color-range and
   resize/rotation contract, tensor names/shapes and soft-alpha output contract.
2. Target-device APU runtime/export integration with **whole-graph accelerator
   placement verified**, rejecting CPU/GPU or partially delegated inference.
   Vendor NNAPI/Neuron details or a supported compiled model/runtime are needed;
   an accelerator name alone is insufficient.
3. Wire the real dequantized float-alpha output into the requested refinement
   and compositor. The retained native infrastructure still has its legacy 256²
   byte-mask interface; it is **not** presented as an implemented 512² float-mask
   pipeline. Existing guided filtering, CoC and disc gather remain infrastructure;
   a selectable hex aperture has not been added in this blocked increment.
4. Validate real moving subjects, hair/thin structures, objects, low-light,
   still-frame identity, preview latency, thermal behavior and saved-image quality.
   Preprocessing and Vulkan postprocessing are distinct from APU-only inference.

## Validation for this increment

- `./build.sh check`: **107 Python tests PASS**, native CoC executable check
  PASS, including new video/blocked-runtime source contracts. Shader compilation
  is skipped because `glslc` is unavailable.
- **118 JVM tests PASS**, compiled with Kotlin 2.0.21/JRE17/ECJ/JUnit. Production
  policy/state/worker logic uses minimal Compose state/image stand-ins and the
  actual extracted aperture constant; no HAL/device behavior is claimed.
- AIDL generation and actual Java CameraCapabilities/CameraUserService backend
  compile against Android35: PASS.
- Targeted **actual** CameraController, VideoCapture, BokehInput/disabled factory
  and ViewfinderGeometry Kotlin compile against Android35, generated Binder
  classes and the real Shizuku jars: PASS. Compose state/image types are stand-ins.
- All **37** production Kotlin files pass PSI syntax parsing, not Compose typing
  or rendered-layout checks.
- No complete APK/Compose/native-library build or real-phone video/APU validation
  is claimed. No APU model execution or matting accuracy benchmark was possible.
- Toolchains and generated binaries are confined to ignored `.cache/validation`.

The unrelated manually launched `probe/ModelAuditActivity` diagnostic and its
TensorFlow Lite dependencies remain; it is not the camera portrait backend, and
its delegate benchmarks cannot establish the requested APU-only implementation.

Prior UI/portrait reports are historical. The earlier Auto/quality feature and
its tests/benchmark results do not establish this requested neural replacement.
Broader merge/Ultra XDR/Night/Astro/RAW-video parity remains in the phased backlog.
