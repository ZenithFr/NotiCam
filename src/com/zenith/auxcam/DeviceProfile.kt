package com.zenith.auxcam

import android.os.Build

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

    // Full-resolution JPEG dimensions are queried from the privileged HAL.
    // Do not infer them by doubling the bokeh pipeline's fixed dimensions.

    const val CAMERA_ID = "5"

    // ── Device lock ─────────────────────────────────────────────────────────
    // Exact values captured from the CMF Phone 2 Pro (Nothing A001 / Galaga):
    //   ro.product.manufacturer=Nothing
    //   ro.product.brand=Nothing
    //   ro.product.model=A001
    //   ro.product.device=Galaga
    //   ro.soc.manufacturer=Mediatek
    //   ro.soc.model=MT6878
    //   ro.hardware=mt6878
    // Fingerprint and product name are deliberately NOT pinned — they change
    // with OTA updates.  The combination of manufacturer + model + device + SoC
    // is sufficient to identify the target hardware model.
    const val TARGET_MANUFACTURER = "Nothing"
    const val TARGET_BRAND        = "Nothing"
    const val TARGET_MODEL        = "A001"
    const val TARGET_DEVICE       = "Galaga"
    const val TARGET_SOC_MODEL    = "MT6878"
    const val TARGET_HARDWARE     = "mt6878"

    data class DeviceCheck(
        val supported: Boolean,
        val reason: String? = null
    )

    @JvmStatic
    fun check(): DeviceCheck {
        // ── manufacturer / brand ────────────────────────────────────────────
        val m = Build.MANUFACTURER
        val b = Build.BRAND
        if (m !in listOf(TARGET_MANUFACTURER, TARGET_BRAND)) {
            return DeviceCheck(false, "Manufacturer/brand not recognized: $m / $b")
        }

        // ── model ───────────────────────────────────────────────────────────
        val md = Build.MODEL
        if (md != TARGET_MODEL) {
            return DeviceCheck(false, "Model not $TARGET_MODEL, got $md")
        }

        // ── device ─────────────────────────────────────────────────────────
        val dv = Build.DEVICE
        if (dv != TARGET_DEVICE) {
            return DeviceCheck(false, "Device not $TARGET_DEVICE, got $dv")
        }

        // ── hardware ───────────────────────────────────────────────────────
        val hw = Build.HARDWARE
        if (hw != TARGET_HARDWARE) {
            return DeviceCheck(false, "Hardware not $TARGET_HARDWARE, got $hw")
        }

        // ── SoC model (API 31+) ────────────────────────────────────────────
        val socModel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else null
        if (socModel != TARGET_SOC_MODEL) {
            return DeviceCheck(false, "SoC model not $TARGET_SOC_MODEL, got $socModel")
        }

        return DeviceCheck(true, null)
    }

    @JvmStatic
    fun isTargetDevice(): Boolean = check().supported

    /** Returns a human‑readable string such as "Nothing A001 / Galaga". */
    fun describe(): String = "${Build.MANUFACTURER} ${Build.MODEL}"

    // ── Lens ladder exposed by the CMF Phone 2 Pro ──────────────────────────
    /** 24 mm equivalent main sensor. */
    const val FOCAL_MAIN_MM = 24f

    /**
     * Lens ladder: 0.6x (ultra-wide), 1x (main) and 2x (physical telephoto).
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
     * Aspect the viewfinder composable must use.
     *
     * `true` → `composite.comp` rotates the 1440x1080 sensor frame 90° clockwise
     * into the swapchain, so the viewfinder box is 3:4 portrait and the scene is
     * upright when the phone is held normally. Keep in sync with
     * `kPreviewRotate90` in `VulkanPipeline.cpp`.
     */
    const val PREVIEW_ROTATION_90 = true

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
    const val PREVIEW_ROTATION_90_ALIAS = PREVIEW_ROTATION_90

    /**
     * Aspect the viewfinder composable must use.
     */
    val previewAspect: Float
        get() = if (PREVIEW_ROTATION_90) 1f / SENSOR_ASPECT else SENSOR_ASPECT
}