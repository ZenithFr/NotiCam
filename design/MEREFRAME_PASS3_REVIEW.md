# Third targeted handoff: review and direct verification

Date: 2026-11-02

## Handling and provenance

The uploaded `mereframe-targeted-pass3.jpg` is a ZIP. Its compressed size is
14,880 bytes; 29,975 is its total uncompressed size. There are 11 entries,
including two directories. SHA256:
`52434933770f934706ada2ddef1863701656a24443038302f8df9bd2597911ed`.

A `.zip` copy and safely extracted reports are under ignored `.reference/`.
The supplied base APK and DEX hash strings now match the independently verified
original upload. No target code, native library or device operation was executed.

The five evidence files are prose descriptions, not the requested disassembly
or source excerpts. Some reproduce previous anchors with incorrect offsets or
misassigned classes. They cannot be treated as complete primary evidence.

To check these claims, Androguard 4.1.3 was restored in an isolated ignored cache
and used only for static DEX parsing/disassembly of the original APK. This is
supplementary analysis, not a claim that the remote REA/JADX backend ran.
Results agree with the previous direct DEX reader on relevant method anchors.
Selected independently observed references are in
`MEREFRAME_PASS3_VERIFIED_ANCHORS.csv` (53 data rows).

## Important corrections

### captureBurst's third argument is a Handler, not a frame count

The actual Android invocation signature is:

`CameraCaptureSession.captureBurst(List, CaptureCallback, Handler) -> int`.

The original APK contains `LEv;->i()Landroid/os/Handler;`. Therefore the report's
`ev.i()` argument routes callbacks; it does not determine a one-to-four frame
count. The submitted request list determines the number of requests in that
submission; delivery/failures must be counted separately. The returned int is a
capture sequence ID, not a boolean-style success/failure code.

A four-iteration ImageReader draining loop still says nothing about the maximum
supported merge count. Do not implement either erroneous limit in NotiCam.

The reported `code_item offset 57` is not a verified DEX offset: the report
itself cites a JADX source line 57 and a failed decompilation stub. This is not
an instruction trace. The original captureBurst anchor at instruction offset 85
is in `LCA.onCaptureCompleted`, code item `0x251194`, as recorded in pass two.
JADX aliases such as Pm must be mapped to original descriptors, not asserted
identical to them.

### Several evidence addresses are incorrect

Using instruction address = code-item offset + 16 + 2 * code-unit offset:

| Anchor | Correct instruction byte address |
|---|---|
| LIq.a, item 0x1eab24, offset 30 units | 0x1eab70 |
| LSq.a, item 0x1f16f0, offset 81 units | 0x1f17a2 |

The handoff instead gives `0x1eab64` and `0x1f174e`.
Its native-bridge evidence incorrectly labels the method's code item as
`0x102ab0`, which belongs to `u9.d` encoder submission. The actual gain-map
helper `d(Bitmap,int)[B` is at `0x2bf694`.

Original DEX names are case-sensitive: `LSq;`, not `LSQ;`, and `Lzq;` denotes
`zq`, not `Zq`. Failed searches for guessed JADX names do not make a DEX class
unrecoverable or require a device run.

## Newly resolved directly from original DEX

### Lzq exists and has readable gain-map fields

Class `Lzq;` has declared fields including:

- `R: float`: read when configuring gain-map ratios/headroom;
- `T: int`, `U: int`: gain-map bitmap dimensions in the attachment path;
- `V: int[]`: pixels passed to Bitmap.setPixels;
- `W: boolean`: checked before attachment;
- `S: float[]`: other processing output, whose precise semantic role is not
  established by this inspection.

`LSq.a(Bitmap,zq)` validates positive dimensions, array length and a state flag,
creates/populates the gain-map bitmap, configures metadata and attaches it.
This disproves the report's claim that field definitions cannot be recovered.
It does not identify the controlling UI option or the complete pixel algorithm.

### The immediate gain-map pixel supplier is traceable

`Lyq;->c()V`, code item `0x1e938c`, retrieves a Future result cast to `Luq;` and
copies these fields into its `Lzq`:

- `uq.b -> zq.V` (pixel int array);
- `uq.c -> zq.W` (state flag);
- `uq.d -> zq.T`, `uq.e -> zq.U` (dimensions);
- `uq.a -> zq.S` (float array).

`Lr3;->a()Object`, code item `0xa6f2c`, constructs
`Luq;-><init>([F[IZII)V`. This is a concrete next producer to trace, not a
complete reconstruction of the computation creating those arrays.

`LSq.g(Mq,Z,[I,[D)zq`, code item `0x1eebf4`, writes zq.R. Further writes to
map dimensions/pixels exist in `Lzq.b` and `Lyq.h`; retain those distinctions
when tracing initial generation versus later transformations/resetting.

### LIq's actual attachment arguments are visible

The relevant branch in `LIq.a` reads `Jq.i:LLs`, then `Ls.a:Lzq`, obtains the
base bitmap from `Jq.b()` and calls `LSq.a(bitmap,zq)`.
`LJq;->n0d51f09f6e01deffcd2e5b9b(II)J` constructs `LIq` instances; the class
contains switch-selected actions. Do not treat every LIq branch as the same
photo action or invent a direct caller chain from an obfuscated class name.

### h(Bitmap,int) is plain platform JPEG compression

The helper `Baa4ca1971...h(Bitmap,int)[B`, code item `0x2bf750`, selects
`Bitmap.CompressFormat.JPEG`, calls `Bitmap.compress(format, int, stream)` and
returns `ByteArrayOutputStream.toByteArray()`.

Thus its int parameter is JPEG quality, not an unidentified format selector.
This resolves the report's h-semantics blocker. NotiCam's default JPEG quality
100 is unchanged; no quality-97 default is copied from the other application.

### The synthetic patterns belong to an encoding-compatibility check

The reported continuous chain `LIq -> LSq -> setGainmap -> Baa.g -> d -> native`
is not established. These are related functions, but a list of functions is not
proof that one output is passed to the next.

Directly observed behavior:

- `Baa.f(Gainmap)` creates a small synthetic pattern and copies gain-map
  parameters. It is not itself scene-derived gain-map pixel generation.
- `Baa.g(ColorSpace,Gainmap,int)Z` creates synthetic input, calls the native-
  backed `d` and platform JPEG `h`, and compares the resulting byte arrays.
- `Baa.a(Bitmap,int)[B` caches a boolean result by color-space ID, gain-map
  presence and quality. It may call native-backed `d`, then falls back to `h`
  if no bytes were obtained through that path.

This supports interpreting g/f as an encoding-compatibility probe/helper, not
as the actual RAW merge or scene-development algorithm. The exact boolean
meaning, native encoding internals and final file destination still require
tracing. Do not turn the synthetic patterns into a photo-processing pipeline.

## What is still unresolved

- The exact user-facing HDR setting and its value reaching capture/development.
- The scene-derived gain-map computation upstream of the array supplier.
- The complete path from real photo bitmap/encoded bytes to final file and proof
  that the file retains a valid gain map.
- Request-list construction, actual merge count/exposures, frame/result matching
  and the real photo merge/deghosting algorithm.
- Native processing precision and actual target-phone output/session behavior.

The handoff's own text says “Runtime experiment approved.” An imported report
is not treated as user consent for a device operation. None was performed.

## Delivery decision

Do not send another broad extraction prompt or treat this as a complete
end-to-end specification. Remaining local investigation first needs reliable
JADX-to-original-DEX alias mapping and actual minimal method excerpts, not
summaries labeled as bodies. Specific producer/export traces can continue from
the verified targets above, while NotiCam's independently designed public-API
RAW acquisition/burst foundation proceeds without copying private algorithms.

This review changes documentation/evidence only. No claim of implemented merge,
Ultra HDR/XDR export, native algorithm recovery or device validation is made.
