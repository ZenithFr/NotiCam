package com.zenith.auxcam

import android.os.Build

/**
 * Hardware profile of the one device this build targets: **CMF Phone 2 Pro**
 * (CMF by Nothing), MediaTek Dimensity 7300 Pro + Mali-G615 MC2.
 *
 * Every number the UI needs is here rather than sprinkled through the layout,
 * so retargeting the app is a one-file change:
 *
 * | Property            | Value                | Why it matters                       |
 * |---------------------|----------------------|--------------------------------------|
 * | Display             | 1080 x 2392, 120 Hz  | chrome sizing, preview box fitting   |
 * | Sensor colour frame | 1440 x 1080 (4:3)    | preview + aspect ratio               |
 * | Disparity frame     | 448 x 288 (Y8)       | depth upload stride                  |
 * | Still frame         | 4080 x 3072 (12.5MP) | bokeh composite + thumbnails          |
 * | Logical camera id   | "5"                  | privileged multi-camera session      |
 */
object DeviceProfile {

    const val MARKETING_NAME = "CMF Phone 2 Pro"

    // ── Display ─────────────────────────────────────────────────────────────
    const val DISPLAY_WIDTH_PX = 1080
    const val DISPLAY_HEIGHT_PX = 2392
    const val DISPLAY_REFRESH_HZ = 120
    const val DISPLAY_HEIGHT_DP = 912

    // ── Sensor streams ──────────────────────────────────────────────────────
    const val COLOR_WIDTH = 1440
    const val COLOR_HEIGHT = 1080
    const val DEPTH_WIDTH = 448
    const val DEPTH_HEIGHT = 288
    const val STILL_WIDTH = 4080
    const val STILL_HEIGHT = 3072

    /**
     * Still stream size for a capture resolution. The sensor is 4:3, so both modes
     * keep the 4:3 shape; 50 MP doubles the long edge (8160 x 6120) and is what the
     * CMF Phone 2 Pro exposes as its full-resolution mode.
     */
    fun stillSize(resolution: Resolution): Pair<Int, Int> = when (resolution) {
        Resolution.Standard -> STILL_WIDTH to STILL_HEIGHT
        Resolution.High -> 8160 to 6120
    }

    const val CAMERA_ID = "5"

    /**
     * Sensor 4:3 as presented: width / height of the frame the engine emits.
     *
     * The Vulkan composite maps the full sensor frame onto the swapchain, so the
     * SurfaceView must be laid out with *exactly* this aspect ratio. Historically
     * the view was measured 4:3 but the swapchain was created once at a
     * provisional size, which is what made the preview look stretched.
     */
    const val SENSOR_ASPECT = COLOR_WIDTH.toFloat() / COLOR_HEIGHT.toFloat()

    /**
     * Presentation rotation applied by `composite.comp`.
     *
     * `true` → `composite.comp` rotates the 1440x1080 sensor frame 90° clockwise
     * into the swapchain, so the viewfinder box is 3:4 portrait and the scene is
     * upright when the phone is held normally. Keep in sync with
     * `kPreviewRotate90` in `VulkanPipeline.cpp`.
     */
    const val PREVIEW_ROTATION_90 = true

    /** Aspect the viewfinder composable must use. */
    val previewAspect: Float
        get() = if (PREVIEW_ROTATION_90) 1f / SENSOR_ASPECT else SENSOR_ASPECT

    // ── Lens ladder exposed by the CMF Phone 2 Pro ──────────────────────────
    /** 24 mm equivalent main sensor. */
    const val FOCAL_MAIN_MM = 24f

    /**
     * Lens ladder: 0.6x (ultra-wide), 1x (main) and 2x (sensor crop).
     * The chips, the zoom wheel and the pinch clamp all read this list.
     */
    val ZOOM_STOPS = listOf(0.6f, 1f, 2f)

    /**
     * Chip label for a stop. The ultra-wide reads as "UW" (the way camera apps
     * label it) with the real focal multiplier in small type beneath, instead of
     * a bare "0.6×" that looks like a digital zoom step.
     */
    fun zoomLabel(stop: Float): String = when {
        stop < 0.95f -> "UW"
        else -> if (kotlin.math.abs(stop - stop.toInt()) < 0.05f) "${stop.toInt()}×"
        else String.format("%.1f×", stop)
    }

    /** Small caption under a chip label, or null when the label says it all. */
    fun zoomCaption(stop: Float): String? =
        if (stop < 0.95f) String.format("%.1f×", stop) else null

    /**
     * True when running on the target hardware. The app is tuned for this phone
     * only (privileged camera id 5, Mali-G615 timings), so anything else gets a
     * visible banner instead of silently wrong colours.
     */
    fun isTargetDevice(
        manufacturer: String = Build.MANUFACTURER,
        model: String = Build.MODEL,
        device: String = Build.DEVICE,
    ): Boolean {
        val m = manufacturer.lowercase()
        val md = model.lowercase()
        val dv = device.lowercase()
        val cmfByNothing = m.contains("nothing") || m.contains("cmf")
        val phone2Pro = md.contains("a001") || md.contains("2 pro") || dv.contains("galaga")
        return cmfByNothing && phone2Pro
    }

    fun describe(): String = "${Build.MANUFACTURER} ${Build.MODEL}"
}
