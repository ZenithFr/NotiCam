# 50 MP investigation and quality contract

The stock camera offering 50 MP is evidence that the phone has a full-resolution
capture pipeline. It does **not** establish which Camera2 client, camera ID,
stream combination or OEM session control exposes that pipeline to this app.
Without a connected phone, its current metadata/firmware cannot be inspected here.

## What changed

The old query checked only camera `0`. Discovery now checks enumerated IDs plus
the already-used `0`, `2`, `3` and `5` device routes through the same cached,
shell-context CameraManager used for capture. This prevents capability queries
from using a different context than session opening. Existing auxiliary IDs are
queried even if omitted by enumeration; this is not blind numeric-ID probing.
Candidates are restricted to rear cameras with the main sensor's field of
view (so a 50 MP tele stream cannot be presented as main/1×). Discovery reads:

1. Ordinary JPEG output sizes.
2. High-resolution/slow JPEG output sizes.
3. API 31+ maximum-resolution output maps, with the corresponding sensor pixel
   mode and maximum-resolution active-array crop on the capture request.

Only advertised approximately 50 MP, native 4:3 JPEG sizes enable this regular
capture path. The original encoded bytes and EXIF are preserved; dimensions
must match the configured stream (allowing physical rotation) before publication.
A larger output dimension alone is not proof of unbinned detail; device testing
against the stock app is still required.

## Getting the missing evidence

On the phone, authorize Shizuku and open **Camera controls → Camera report**.
Share the plain-text report. It contains:

- Actual UserService process UID (shell is 2000; root would be 0), context,
  Camera2 attribution and CAMERA/SYSTEM_CAMERA permission status. Attribution is
  reported separately: setting a package name does not change process privilege.
- Build fingerprint, enumerated IDs, queried known routes and physical IDs.
- Standard, slow and maximum-resolution JPEG maps, plus YUV/RAW dimensions.
- Sensor arrays, physical dimensions and focal lengths.
- Physical-camera JPEG sizes, where queryable.
- Camera2 extension JPEG sizes, explicitly labeled as requiring an extension session.
- Available request/session key names and relevant vendor stream/resolution/
  remosaic/pixel characteristics.

A physical-only or extension-only size is reported, **not** automatically sent
to an incompatible ordinary session. Vendor dimensions also do not automatically
enable 50 MP: doing so without knowing the sensor/remosaic controls could produce
an upscaled binned readout. No guessed vendor keys are written.

## Shizuku scope

The app already opens cameras in a Shizuku UserService rather than an ordinary
app CameraManager. All JPEG-size, 50 MP, video-size/FPS, report and open-session
operations now share that one privileged client. The report distinguishes
queryable metadata from an actual successful session: a queryable ID is **not**
a verified openable camera or capture mode. Physical-only IDs stay diagnostic.

ADB-started Shizuku runs with shell privileges, not root or the OEM camera app's
signature/SELinux identity. Private remosaic/session protocols can remain gated.
This change does not modify global app-ops/permissions, disable sensor privacy,
change persistent vendor properties or invent vendor keys. Root-started Shizuku
and firmware-specific restrictions still require device testing. The new report
is the next input for extending a **verified** vendor capture path, not evidence
that 50 MP has already been unlocked.

## Source investigation

The public [Galaga device tree's proprietary file list](https://github.com/nothing-galaga/android_device_nothing_Galaga/blob/HEAD/proprietary-files.txt)
contains the MediaTek camera provider/HAL and Nothing-specific camera processing
nodes (including RAW/YUV/JPEG nodes). This establishes that stock processing uses
vendor components, but does not document the 50 MP mode-switch protocol. No
verified, firmware-specific remosaic control was found in that public source.
The capability report is the next actionable input rather than another hardcoded
8160×6144 request or an inference from sensor-array dimensions.

## Related 2× fix

The 2× chip was gated by an independent tele-assist flag. When off, it cropped
the main sensor. Now it selects physical camera `3` directly and applies only
`globalZoom / lensBaseZoom`; at 2× on the 2× tele lens this is 1, i.e. the whole
active array. JPEG sizes also come from the selected sensor. No second app crop,
preview screenshot or output upscaling is used for sharp photos.
