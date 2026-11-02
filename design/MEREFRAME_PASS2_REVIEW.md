# Second evidence-bundle review and independent DEX checks

Date: 2026-11-02

## Archive

`mereframe-evidence-pass2.jpg` is a valid ZIP: 29,533 bytes, 20 entries,
58,348 uncompressed bytes. SHA256:
`9d3a88754b1c700ba9e1544373df47b98d1d52d325e804889804d37b3db0ed6f`.

The original is preserved. A `.zip` copy and safely extracted reports are under
ignored `.reference/`. No archive contents or target application code were executed.

## Status of the supplied evidence

The bundle adds named gain-map processing leads, but its opening claims exceed
what the attached evidence demonstrates:

- `evidence/` still contains only the old `opening-summary.md`; no primary method
  excerpts, disassembly files, native registrations or output samples are attached.
- Eleven files are byte-identical to the first handoff, including the hash file,
  old opening summary and older encoder report. Some contradict the new prose.
- The base hash is still the same invalid 48-character string. Freshly hashing
  the original uploaded base APK again gives:
  `2945519d3a4ba32cd807eb230c7343031b42dc20bad5adf46477564e0d0420c4`.
- The new gain-map CSV has malformed column counts in 16 of 17 data rows; the
  new native-boundary CSV has malformed counts in 8 of 10 data rows. The two old
  CSV errors remain. These are not ready for automated ingestion.
- The encoder document describes dequeue/fill/queue as the *standard* workflow
  and explicitly says submission is not shown in the `u9.i` body. This alone
  would not support its stronger opening claim that submission was traced.
- The encrypted setter key is still shown encrypted. Recognizing 54/2135033992
  as format constants is strong evidence of a color-format selection, not proof
  that the actual key was decoded.
- Static code presence is not proof of a functional pipeline on the phone.

The imported files remain unmodified so their original claims can be audited.

## Independent checks performed here

Rather than repeat another broad prompt, the original uploaded APK was inspected
with a supplementary standard-library DEX reader. It reads identifier/class/method
tables and walks encoded instruction boundaries to locate invoke instructions.
It does not execute classes, decode encrypted keys or reconstruct native math.
This check is separate from REA/JADX, not a claim that the remote JADX backend ran.

Input base APK SHA256 is recorded above. Its `classes.dex` SHA256 is:
`ee76e9b32f266db9a7b188e2aa567b18e5bd8ff8ed80743d78f138d6615899d8`.
The reader walked 13,954 method bodies. Selected independently found call anchors
are saved in `MEREFRAME_PASS2_VERIFIED_ANCHORS.csv` (43 rows).

Offsets in that CSV are the DEX code-item file offset and the invoke offset in
16-bit code units from the instruction-array start, not native addresses or
JADX source line numbers. Instruction byte address = code-item offset + 16 +
2 * invoke offset. Each row verifies a static invocation, not runtime reachability,
session success, output correctness or the semantic role of all arguments.

### 1. Real Camera2 scheduling calls are present

Examples include:

- `LCA;->onCaptureCompleted(CameraCaptureSession,CaptureRequest,TotalCaptureResult)V`
  calls `captureBurst` at code-unit offset 85.
- `LFv;->run()V`, `LGv;->run()V`, `LIv;->run()V`,
  `LK3;->onConfigured(CameraCaptureSession)V` and
  `LQu;->i(Surface,Z)V` also contain `captureBurst` calls.
- `LGh;->onConfigured(CameraCaptureSession)V` and
  `LHh;->b(Ljo;Lyf;)V` contain `setRepeatingBurst` calls.
- Multiple methods contain `setRepeatingRequest` calls.

Therefore, not finding these in the local agent's selected trace does not mean
runtime testing is required merely to locate scheduling code. These are concrete
static starting points. Their association with a specific photo/RAW/video
feature and their exposure/frame-count logic still need caller/data-flow tracing.
Presence of `onCaptureCompleted` does not by itself establish frame/result pairing.

### 2. Non-surface encoder input submission is directly visible in u9.d

`Lu9;->d(JLyf;)V`, DEX code item `0x102ab0`, invokes:

| Code-unit offset | Call |
|---:|---|
| 19 | MediaCodec.dequeueInputBuffer(J)I |
| 24 | MediaCodec.getInputImage(I)Image |
| 30 | Lyf.c(Object)Object |
| 33 | MediaCodec.getInputBuffer(I)ByteBuffer |
| 50 | MediaCodec.queueInputBuffer(IIIJI)V |

This is direct static evidence of non-surface input submission in this wrapper,
including an input-Image callback. It supersedes inferring the input path from
`configure(...,null,...)`. Buffer filling, callback behavior, color layout,
configured profile and successful 10-bit output remain separate questions.

Other classes, including `Loh;`, have surface-creation calls as well as buffer
submission paths. Do not generalize the `u9.d` result to every video pipeline.

### 3. Gain-map construction and attachment have concrete anchors

`LSq;->a(Bitmap,Lzq;)V`, code item `0x1f16f0`, invokes bitmap creation/setPixels,
`Lx0;->d(Bitmap)Gainmap`, gain-map parameter helpers, then
`Lx0;->j(Bitmap,Gainmap)V` at code-unit offset 81.

The wrappers directly invoke:

- `Lx0;->d(Bitmap)Gainmap`: Gainmap constructor taking a Bitmap.
- `Lx0;->j(Bitmap,Gainmap)V`: Bitmap.setGainmap(Gainmap).

`LIq;->a()Object`, code item `0x1eab24`, invokes `LSq.a` at offset 30.
This is a materially stronger starting point than merely finding API names.
It establishes attachment code, not how the `Lzq` gain-map pixels are generated,
which user option selects the path or whether the exported file retains it.

### 4. The reported gain-map helper exists, with a native encoding boundary

The original DEX contains:
`Lapp/mereframe/b/Baa4ca1971bf25b9708ac2120;`.

Its `f(Gainmap)Gainmap` reads an existing map's contents/parameters and constructs
another gain map with parameter setters. That is not by itself evidence of
scene-derived HDR/merge computation.

Its `d(Bitmap,I)[B`, code item `0x2bf694`, calls this declared native method at
code-unit offset 53:
`na59086ccd2bac2624534e6ae(Bitmap,I,java.util.function.Supplier)[B`.

This is a real native boundary to investigate for encoding, rather than the
speculative native-boundary rows in the supplied CSV. It does not establish the
native implementation, output format or licensing behavior, and no native code
was executed or reconstructed here.

## Interpretation limits still applying

- The user-facing name **Ultra XDR** has not been anchored. Reports mention
  **Ultra HDR**, but those names must not be assumed synonymous. The requested
  HDR photo feature stays in scope while exact MereFrame behavior is investigated.
- A gain map attached to an in-memory Bitmap is not proof of a correctly encoded
  exported HDR photo or compatible-display behavior.
- `z=true` selecting a P010-like format value is not proof of a Main10 output,
  high-precision source content, HDR color management or actual device support.
- P010 is semiplanar YUV 4:2:0 using 16-bit sample storage for 10-bit components;
  it is not a general planar 16-bits-per-image-pixel format as the report suggests.
- The encoder report's generic instruction to fill an encoder input buffer with
  *compressed* frames is also incorrect: an encoder normally receives uncompressed
  input pixels. Actual input layout/callback behavior must be traced.
- Assigning `jv.j = x3.H` is still not result pairing. The ownership report also
  contradicts itself about `x3.E`'s type and labels a read of `x3.H` as a write.
  Recover actual field definitions/writes rather than propagating those guesses.

## Next evidence targets, without another broad inventory loop

1. Follow `LIq.a -> LSq.a` backward to the selected output/development mode and
   forward to the file writer. Follow the producer and fields of `Lzq`.
2. Trace `LSq.b` and relevant request-building callers starting from the concrete
   burst sites above; identify the exact merge count/exposure schedule.
3. Trace the native byte-array encoding boundary in the gain-map helper and its
   caller's output destination. Do not infer output metadata from setter names.
4. Trace the input callback passed to `u9.d` and the still-encrypted setter key;
   distinguish input pixel precision from encoded bit depth.
5. Find actual SENSOR_TIMESTAMP field accesses and callback/queue matching.

Independent public-API RAW acquisition and bounded burst/session testing in
NotiCam do not depend on recovering a proprietary merge algorithm or container.
No NotiCam capture behavior was changed by this review; no device was accessed.
