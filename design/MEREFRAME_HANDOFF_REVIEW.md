# Review of uploaded MereFrame handoff

Date: 2026-11-02

## Input and handling

- Uploaded `mereframe-handoff.jpg` is a ZIP, not an image.
- Archive size: 15,122 bytes; 14 entries; 25,752 uncompressed bytes.
- SHA256: `5f594206e933a48db90d892cdf324ec19a6a8015930f1f9dbeeea9c18484912d`.
- A byte-identical `.zip` copy is at `.reference/mereframe-handoff.zip`.
- Reports were safely extracted under `.reference/mereframe-handoff/` after
  checking traversal, absolute paths, symlinks and report sizes. Nothing in the
  archive was executed. The original attachment is preserved.
- Inputs and imported third-party reports remain Git-ignored. This review is an
  independent assessment, not acceptance of every claim in the imported reports.

## What was actually supplied

The expected topic reports, opening summary, feature matrix and call-map CSV
are present. However, `evidence/` contains only `opening-summary.md`: there are
no underlying method excerpts, disassemblies, resource identifiers, native
registration addresses or captured output samples. A separate empty directory
named `{evidence}` is also present.

The report describes additional acquisition details: `x3.b` copies plane 0 into
an owned ByteBuffer before the caller closes the Image, with timestamp, size,
stride and RAW10/RAW12 flags. This is a useful reported lead for an independent
bounded acquisition design, but the underlying body/callers are not supplied
here for independent verification.

Ultra XDR capture/render/export wiring, merge scheduling and actual merging,
Night/Astro, calibrated development, retained-RAW storage format and Log remain
unresolved. The matrix has only 17 rows, mostly unknown defaults/keys/conditions;
it is not an exhaustive feature inventory or an implementation-ready parity spec.

## Integrity checks

Fresh hashes of the original XAPK, both configuration splits and the native
library match the valid corresponding hashes in the handoff. The base APK hash
in the handoff remains malformed/truncated (48 hexadecimal characters):

- Supplied: `2945519d8c7343031b42dc20bad5adf46477564e0d0420c4`
- Actual base from the original uploaded XAPK:
  `2945519d3a4ba32cd807eb230c7343031b42dc20bad5adf46477564e0d0420c4`

Checking the original archive here does not attest to files on the local agent's
machine. Ask that agent to generate its hash report directly from its actual
inputs rather than transcribing prior summaries. Preserve the imported report
unchanged; do not silently correct its claimed evidence.

## Material interpretation corrections

### Encoder values are recognized color-format constants

The video report calls `z ? 54 : 2135033992` a possible bitrate. Compiling and
running an isolated constant-printing program against the actual Android 35
API jar establishes:

| Android CodecCapabilities constant | Decimal |
|---|---:|
| COLOR_FormatYUV420Flexible | 2135033992 |
| COLOR_FormatYUVP010 | 54 |
| COLOR_FormatSurface | 2130708361 |

This is strong evidence that the guessed field is color-format-related, not
bitrate. It does **not** recover the encrypted key, prove the branch's purpose,
prove successfully configured P010 recording, or establish the whole encoder
input path. Resolve that setter key and its callers before implementation.

`configure(format, null, null, ENCODE)` alone still does not prove byte-buffer
input. A surface encoder also normally passes null for the configure output
surface. The absence of a surface-creation call in one method is not enough:
trace the entire initialization and frame submission path. The report repeats
the incorrect byte-buffer conclusion in several files and its CSV.

### A frame field is not capture-result matching

The report itself identifies `x3.H` as a float. Assigning it to `jv.j` does not
prove a link to TotalCaptureResult or sensor calibration metadata. Recover its
writes/meaning and find actual SENSOR_TIMESTAMP -> Image.timestamp matching,
including missing, out-of-order and late-result behavior.

### Queue draining does not establish a Camera2 burst

A loop acquiring up to four images can drain a repeating stream. It does not
prove a bracketed still burst, merge frame count, exposure sequence or merging.
Find capture/captureBurst/setRepeatingRequest call sites and consumers of the
queued frames before assigning the path to photo merging or retained RAW video.

### API presence is not XDR export or render-target isolation

Gainmap descriptors/method references do not show whether the application creates,
reads, displays or exports gain maps. Find the actual call sites and output writer.
Likewise, preference getters do not prove that zebras/peaking/false color are
excluded from encoder targets. The instruments report asserts exclusion while
also explicitly listing render destinations as unresolved; treat exclusion as
unverified.

### No native function map was recovered

The supplied native-call CSV lists Java leads and JNI_OnLoad, but no registered
native names/signatures/addresses or internal processing functions. Lack of a
native decompiler does not explain unresolved Java resource IDs, getters/setters,
Camera2 request builders or MediaFormat key construction; those can be pursued
independently with the already-working JADX backend.

## CSV validation

- `feature-matrix.csv`: 14-column header; bracketing row (line 18) has 15 fields.
- `native-call-map.csv`: 6-column header; instruments row (line 9) has 5 fields.

Do not ingest these as authoritative structured data until their schema is
corrected. Confidence fields also need to distinguish observed API presence from
inferred behavior rather than labeling entire inferred pipelines confirmed.

## Focused next handoff requested from local investigation

Avoid another broad summary. Supply a small primary-evidence packet:

1. **Ultra XDR resource/UI trace:** exact string/resource ID and every code
   reference; actual getter/setter/default; its next capture/development/export
   consumer. If the string cannot be located, explicitly withdraw the unanchored
   label-presence assertion rather than inventing a key.
2. **Gainmap callers:** method signatures and minimal relevant excerpts for
   get/set/construct/hasGainmap and the image writer. Classify read/display versus
   generation/export. Do not assume framework/library references belong to XDR.
3. **Acquisition proof:** small excerpts for `ey.c`, `x3.d`, `x3.b`, `jv` fields,
   `x3.H` writes, `Um.Q0` and their immediate consumers, including original DEX
   descriptors and evidence locations. Find actual result/timestamp matching.
4. **Capture scheduling:** request-builder, capture/captureBurst/repeating call
   sites and how merge count/exposure selections reach them. Include one complete
   path rather than a list of obfuscated aliases.
5. **Encoder proof:** resolve setter keys around 54/2135033992; actual input
   enqueue/surface creation calls and full initialization order, including `bj.r`
   and relevant `C1047oh`/`C1190rb` callers.
6. **Native boundary, if tooling available:** Java native declarations, JNI_OnLoad
   RegisterNatives tables and addresses for one reachable merge/development function.
   Report unavailable tooling, but continue the independent Java/resource work.
7. Generate hashes programmatically, repair CSV column counts and include evidence
   references for confirmed rows. Keep runtime/device work separately approved.

No further generic feature summary is needed. Primary traces are what would
turn the present leads into verifiable compatibility specifications. Meanwhile,
NotiCam's independent public-API RAW acquisition and test work need not wait for
recovery of a private container or proprietary merge algorithm.

## Validation boundary of this review

Archive integrity, original input hashes, CSV structure and Android API constant
values were checked here. No APK/native library was executed, no camera session
was opened, and no device operation was performed. No NotiCam capture behavior
was changed by this review.
