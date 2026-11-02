# Portrait replacement: INT8 MobileNetV4, APU-only

**Blocked / not implemented as an executable neural model.** The old Auto
MediaPipe and MODNet engines, CPU fallback and weights have been removed per
request. Only `mobilenetv4_conv_medium_int8` is exposed; old IDs migrate to it.
`PortraitRuntime.available` is false, and normal sharp-photo capture is retained.

Required pipeline: original YUV/RGBA frame → copied 512×512 model input → trained
INT8 MobileNetV4-Conv-Medium + LRASPP/matting on a verified APU-only runtime → soft
float alpha → guided edge refinement → full-resolution depth-weighted bokeh.

A classification backbone or a model-name/latency table is not a trained matting
export. No trained artifact, INT8 calibration, preprocessing/output metadata,
redistribution license or verified target runtime was supplied. No substitute
weights are downloaded. The asset ledger is intentionally empty. Known retired
MODNet/RVM generated weights are pruned and excluded from APK packaging.

An NNAPI delegate name or successful warmup is insufficient to prove that all
operators execute on the APU. The eventual runtime must reject CPU/GPU or partial
delegation. CPU image copying/quantization and Vulkan refinement/composition are
separate from the model-inference placement requirement.

Existing native filtering/CoC/disc blur and the legacy 256² byte-mask interface
remain inactive infrastructure. They are **not** claimed to implement the full
requested 512² float-alpha path or a selectable hex aperture. No measured 10–15 ms,
parameter-count, hair-detail or arbitrary-object segmentation guarantee is made.

See [the full current implementation/blocker report](APU_PORTRAIT_VIDEO_PROFILES.md)
for completed video controls, testing results and the artifacts needed to finish
this replacement. Earlier Auto/quality reports are historical, not current behavior.
