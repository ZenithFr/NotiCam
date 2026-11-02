# Portrait: Auto + Portrait quality

The camera has exactly two portrait choices, in Settings → Portrait. Portrait is
still the corner button inside **Photo**, not a third capture mode. Defaults,
JPEG quality, output dimensions and non-portrait capture processing are unchanged.

| Choice | Network/input | Runtime |
|---|---|---|
| Auto | MediaPipe General float, RGB [0,1], NHWC 256×256 | TFLite: discovered NNAPI accelerators, system NNAPI selection, then explicit CPU/XNNPACK fallback |
| Portrait quality | MODNet, RGB [-1,1], NCHW 384×512 | ONNX Runtime CPU, two intra-op threads |

The retained IDs are `mediapipe_auto` and `modnet`; old dual/landscape/general/RVM
preferences migrate to Auto. Unused model weights, RVM license and the retired
dual max-union/EMA implementation are removed. This does not automatically make
quality better on every scene: device/ground-truth comparisons are still needed.

## Acceleration is requested, not inferred from a delegate object

The native bridge enumerates NNAPI devices with type `ACCELERATOR`. Auto attempts
up to three distinct discovered names, then lets NNAPI select a backend. It does
not hardcode `mtk-mdla_shim`. NNAPI CPU devices are disabled for these attempts;
TFLite may nevertheless execute unsupported operators on its own CPU backend.
Each attempt must construct an interpreter, validate float tensor types/shapes,
and run a finite, in-range warmup. A rejected session closes its interpreter and
delegate before retrying. Constructor failures close partial delegates. CPU
fallback must pass the same checks, otherwise it fails visibly.

On Dimensity 7300 Pro this allows a vendor NPU driver to be used **if it accepts
the model**. Reports say `NNAPI · <driver> · placement unverified`, or explicit
`CPU/XNNPACK fallback`. Neither proves all operators are on the NPU, effective
partitioning, sustained speed, or a quality gain. MODNet is explicitly CPU; this
change does not pretend its unsupported ONNX operators are NPU-accelerated.
Legacy `probe/ModelAuditActivity.java` is a separate opt-in, external-candidate
benchmark; its old synthetic driver tests are not evidence for this runtime.

## Mask and depth corrections

- Live masks obey the **500 ms sensor timestamp bound** and session/model
  generation. There is no indefinite last-good-mask fallback.
- One bounded upright YUV sample is copied before the Image closes, converted to
  RGB on the serial worker, then packed with the model's required normalization.
  Soft output is validated, bilinearly resized to 256² and inverse-rotated once
  into sensor coordinates. Both networks are stateless: live history is never
  blended into a received still.
- Auto no longer unions two model masks or applies temporal EMA.
- Preview and still CPU staging apply the 14:9 disparity → 4:3 color mapping
  once. Raw-depth lookups into a semantic mask use the inverse mapping. Unmeasured
  color margins become unknown/far, not copies of the disparity edge. The alpha
  shader consumes already aligned metric depth and does not crop it again.
- Disparity 0/255 are unknown; they cannot produce a near foreground object.
  Still focus can derive from the actual captured mask/depth pair rather than
  borrowing a moving preview subject's plane.
- Conservative 3×3 joint bilateral refinement uses RGB differences at the
  **256² semantic texel footprint**, not just +/-1 full-resolution pixel. It
  preserves partial alpha; default production paths do not hard-contrast hair
  or lift alpha just because a pixel is bright. Explicit diagnostic edge modes
  remain available. This is guided upsampling, **not new neural hair detail**.
- Valid depth near the focus surface can protect an object/hand even where the
  person mask is low. Background depth can veto a false-positive semantic mask.
  With no person mask, focus uses the measured near-depth percentile immediately
  rather than retaining a default/previous person's plane. No person foreground
  plus no usable depth means a sharp image, not whole-frame blur. Empty/nearly-empty masks alone do not authorize an effect.

**Both models identify people, not arbitrary object classes.** Depth assistance
is geometric separation on the synchronized main-lens depth path, not universal
object recognition. Calibration, thin structures, transparent objects and actual
occlusion behavior need device testing. No fake central-subject matte is added.

## Scheduling, stills and checks

The serial worker keeps one engine at a time. Live cadence adapts within
83–300 ms. Cold loading is not counted as a slow warm cycle. After two consecutive
warm inference cycles above 220 ms, live masking stops and its published mask is
cleared; preview remains valid-depth-only or sharp. Still inference remains
available on the **received still's bounded sample**, independently of this live
pause. Reselecting the model/session resets the pause. No final-photo resolution
is reduced to make preview faster.

“Check selected” / “Compare both” share one copied frame, release the live engine,
then execute each model serially with one untimed warmup and five timed runs.
Results distinguish backend, init time, latency, alpha range and flat masks.
A runtime PASS is not an accuracy benchmark, preview FPS, or sustained thermal
measurement. Cancellation waits for the current native inference before closing
resources. The selected saved-photo model is not silently replaced by Auto.

## Assets and validation

`assets/models/bokeh-models.json` contains exactly two pinned model entries,
SHA-256, size, provenance and Apache-2.0 licenses. The MediaPipe float asset is
tracked; MODNet is fetched at build time to ignored `assets/models/optional/`.
ONNX Runtime's MIT notice is retained. `python3 tools/fetch_bokeh_models.py --verify`
checks both locally without network. Android repeats hash/size checks when opening
assets; no external file can silently override a selected network.

See [NOTHING_UI_PORTRAIT.md](NOTHING_UI_PORTRAIT.md) for this increment's validation
results and remaining device/build gates. Unit and structural tests do not prove
matting accuracy or actual NPU placement.
