package com.zenith.auxcam

import java.util.Locale
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToLong

/** Wire v1: version, manual flag, ISO bounds, exposure bounds, frame limit, AWB bitmask.
 * Bit 0 now advertises manual color-temperature requests; bits 1–8 retain AWB modes.
 */
data class SensorControlCapabilities(
    val manualSensor: Boolean = false,
    val minIso: Int = 0,
    val maxIso: Int = 0,
    val minExposureNs: Long = 0,
    val maxExposureNs: Long = 0,
    val maxFrameDurationNs: Long = 0,
    val awbModes: Long = 0,
) {
    val hasManualExposure: Boolean get() = manualSensor && minIso > 0 && maxIso >= minIso &&
        minExposureNs > 0 && maxExposureNs >= minExposureNs && maxFrameDurationNs >= minExposureNs

    fun supportsWhiteBalance(mode: Int): Boolean = mode in 1..8 && (awbModes and (1L shl mode)) != 0L

    val supportsTemperature: Boolean get() = (awbModes and 1L) != 0L

    fun toWire(): LongArray = longArrayOf(1, if (manualSensor) 1 else 0, minIso.toLong(), maxIso.toLong(),
        minExposureNs, maxExposureNs, maxFrameDurationNs, awbModes)

    companion object {
        @JvmStatic fun fromWire(wire: LongArray?): SensorControlCapabilities {
            if (wire == null || wire.size != 8 || wire[0] != 1L || wire[1] !in 0L..1L ||
                wire[2] !in 0L..Int.MAX_VALUE.toLong() || wire[3] !in 0L..Int.MAX_VALUE.toLong() ||
                wire[7] !in 0L..0x1ffL) return SensorControlCapabilities()
            return SensorControlCapabilities(wire[1] == 1L, wire[2].toInt(), wire[3].toInt(), wire[4], wire[5], wire[6], wire[7])
        }
    }
}

/** Standard AWB presets, NOT fabricated sensor-calibrated Kelvin controls. */
enum class WhiteBalancePreset(val mode: Int, val label: String) {
    Auto(1, "AUTO"), Daylight(5, "DAYLIGHT"), Cloudy(6, "CLOUDY"), Shade(8, "SHADE"),
    Incandescent(2, "TUNGSTEN"), Fluorescent(3, "FLUORESCENT"), WarmFluorescent(4, "WARM FLUOR."), Twilight(7, "TWILIGHT");
    companion object {
        fun fromMode(mode: Int): WhiteBalancePreset = entries.firstOrNull { it.mode == mode } ?: Auto
    }
}

/** Pure request rules shared with the privileged camera sidecar. Defaults remain Auto. */
object SensorControlPolicy {
    // Phase one keeps still exposure below the existing capture watchdog budget.
    const val PHOTO_EXPOSURE_LIMIT_NS = 1_000_000_000L
    @JvmStatic fun maxExposure(caps: SensorControlCapabilities, video: Boolean, fps: Int): Long {
        if (!caps.hasManualExposure) return 0L
        val limit = if (video) framePeriod(fps) else PHOTO_EXPOSURE_LIMIT_NS
        return minOf(caps.maxExposureNs, caps.maxFrameDurationNs, limit).takeIf { it >= caps.minExposureNs } ?: 0L
    }
    @JvmStatic fun framePeriod(fps: Int): Long = 1_000_000_000L / fps.coerceIn(1, 240)
    @JvmStatic fun iso(requested: Int, caps: SensorControlCapabilities): Int =
        if (caps.hasManualExposure) requested.coerceIn(caps.minIso, caps.maxIso) else 0
    @JvmStatic fun exposure(requested: Long, caps: SensorControlCapabilities, video: Boolean, fps: Int): Long {
        val max = maxExposure(caps, video, fps)
        return if (max > 0L) requested.coerceIn(caps.minExposureNs, max) else 0L
    }
    @JvmStatic fun liveExposure(requested: Long, caps: SensorControlCapabilities, fps: Int): Long =
        exposure(requested, caps, true, fps)
    @JvmStatic fun frameDuration(exposure: Long, caps: SensorControlCapabilities, fps: Int): Long =
        maxOf(exposure, framePeriod(fps)).coerceAtMost(caps.maxFrameDurationNs.coerceAtLeast(exposure))

    fun fraction(value: Long, min: Long, max: Long): Float {
        if (min <= 0 || max <= min) return 0f
        return ((ln(value.coerceIn(min, max).toDouble()) - ln(min.toDouble())) /
            (ln(max.toDouble()) - ln(min.toDouble()))).toFloat().coerceIn(0f, 1f)
    }
    fun fromFraction(fraction: Float, min: Long, max: Long): Long {
        if (min <= 0 || max < min) return 0L
        val f = if (fraction.isFinite()) fraction.coerceIn(0f, 1f) else 0f
        if (f == 0f || min == max) return min
        if (f == 1f) return max
        return exp(ln(min.toDouble()) + f * (ln(max.toDouble()) - ln(min.toDouble()))).roundToLong().coerceIn(min, max)
    }
    /** One-third-stop detents with exact hardware endpoints retained. */
    fun isoStops(min: Int, max: Int): List<Long> = boundedStops(listOf(25L, 32, 40, 50, 64, 80, 100, 125, 160, 200, 250, 320,
        400, 500, 640, 800, 1000, 1250, 1600, 2000, 2500, 3200, 4000, 5000, 6400, 8000, 10000, 12800, 16000, 25600, 51200, 102400), min.toLong(), max.toLong())
    fun shutterStops(min: Long, max: Long): List<Long> = boundedStops(
        listOf(32000, 16000, 8000, 6400, 5000, 4000, 3200, 2500, 2000, 1600, 1250, 1000, 800, 640, 500, 400,
            320, 250, 200, 160, 125, 100, 80, 60, 50, 40, 30, 25, 20, 15, 13, 10, 8, 6, 5, 4, 3, 2, 1)
            .map { 1_000_000_000L / it }, min, max)
    private fun boundedStops(stops: List<Long>, min: Long, max: Long): List<Long> =
        if (min <= 0L || max < min) emptyList() else (stops.filter { it in min..max } + listOf(min, max)).distinct().sorted()
    fun snapped(fraction: Float, stops: List<Long>): Long {
        if (stops.isEmpty()) return 0L
        val wanted = fromFraction(fraction, stops.first(), stops.last())
        return stops.minBy { kotlin.math.abs(ln(it.toDouble()) - ln(wanted.toDouble())) }
    }

    /** Approximate illuminant correction, not a sensor-calibrated CCT profile.
     * Applied with the last Auto color transform, never as an RGB image filter. */
    @JvmStatic fun temperatureGains(kelvin: Int): FloatArray {
        val t = kelvin.coerceIn(2000, 10000) / 100.0
        val r = if (t <= 66) 255.0 else 329.698727446 * Math.pow(t - 60, -0.1332047592)
        val g = if (t <= 66) 99.4708025861 * ln(t) - 161.1195681661 else 288.1221695283 * Math.pow(t - 60, -0.0755148492)
        val b = if (t >= 66) 255.0 else 138.5177312231 * ln(t - 10) - 305.0447927307
        return floatArrayOf((g / r.coerceIn(1.0, 255.0)).toFloat().coerceIn(1f, 4f), 1f, 1f,
            (g / b.coerceIn(1.0, 255.0)).toFloat().coerceIn(1f, 4f))
    }
    fun shutterLabel(ns: Long): String = if (ns <= 0L) "AUTO"
        else if (ns >= 100_000_000L) String.format(Locale.US, "%.2f s", ns / 1e9)
        else "1/${(1e9 / ns).roundToLong()} s"
}
