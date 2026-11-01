# Model/runtime provenance and redistribution

- **MediaPipe Selfie Segmentation**, General and Landscape: Google MediaPipe,
  Apache-2.0. Float assets were already part of NotiCam; their existing pinned
  SHA-256 identities are recorded in `models/bokeh-models.json`.
  Source: https://github.com/google-ai-edge/mediapipe
- **MODNet**: original authors, https://github.com/ZHKKKe/MODNet, Apache-2.0.
  FP32 ONNX export hash `07c308cf0fc7e6e8b2065a12ed7fc07e1de8febb7dc7839d7b7f15dd66584df9`.
  These bytes match the ONNX-community export pinned by the vztool-models
  distribution at revision `6af52070d14deafc5e55ce6cc4d752a322cdff76`. An immutable
  Git blob in qianbin1989228/MODNet_onnx is a same-hash download mirror, not a
  change of model license. No mirror wrapper/application code is included.
- **RVM MobileNetV3**: Peter Lin / RobustVideoMatting, v1.0.0, **GPL-3.0-only**.
  FP32 ONNX hash `88d4531297118f595bf2fd60f6f566aec2e559393802d1f436c380f0cbbd2828`.
  Same-hash immutable Git blob mirror: royshil/obs-backgroundremoval. The source
  and export/training instructions are at:
  https://github.com/PeterL1n/RobustVideoMatting/tree/v1.0.0
  https://github.com/PeterL1n/RobustVideoMatting/blob/v1.0.0/documentation/inference.md
  No upstream application/inference implementation is copied into NotiCam.
- **ONNX Runtime Android 1.20.0**, Microsoft, MIT. Maven dependency
  `com.microsoft.onnxruntime:onnxruntime-android:1.20.0`.
  Source: https://github.com/microsoft/onnxruntime/tree/v1.20.0

Model weights are unmodified. Tensor preprocessing and Android adapters are
NotiCam code. All file URLs, sizes and SHA-256 values are in the bundled manifest.
Original license texts accompany this notice in APK assets.

**Private device evaluation is not a blanket public-distribution clearance.**
In particular, review RVM's GPL-3.0-only obligations and compatibility before
publishing a binary containing it. Including license text and upstream links
alone is not a claim that all corresponding-source/distribution obligations have
been satisfied. Do not relabel RVM or the entire application as Apache/MIT.
