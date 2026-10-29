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
     * `false` → the sensor frame is presented as-is (1440x1080 landscape), so the
     * viewfinder box is 4:3. Keep in sync with `kPreviewRotate90` in
     * `VulkanPipeline.cpp`; flipping both to `true` presents the frame upright
     * with a 3:4 box, which is the iPhone-like full-height preview.
     */
    const val PREVIEW_ROTATION_90 = false

    /** Aspect the viewfinder composable must use. */
    val previewAspect: Float
        get() = if (PREVIEW_ROTATION_90) 1f / SENSOR_ASPECT else SENSOR_ASPECT

    // ── Lens ladder exposed by the CMF Phone 2 Pro ──────────────────────────
    /** 24 mm equivalent main sensor. */
    const val FOCAL_MAIN_MM = 24f
    val ZOOM_STOPS = listOf(1f, 2f, 5f)

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
