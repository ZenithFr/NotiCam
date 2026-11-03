package com.zenith.auxcam

/** Recorder framing/bitrate policy, independent of the preview and still reader. */
object VideoPolicy {
    const val FPS = 30
    const val HD = 1080
    const val UHD = 2160
    @JvmStatic fun allowedRate(quality: Int, fps: Int): Boolean = when (quality) {
        HD -> fps == 24 || fps == 30 || fps == 60
        UHD -> fps == 24 || fps == 30
        else -> false
    }
    @JvmStatic fun profileKey(quality: Int, fps: Int): Int = quality * 100 + fps
    @JvmStatic fun matchesQuality(width: Int, height: Int, quality: Int): Boolean =
        width > 0 && height > 0 && when (quality) {
            HD -> minOf(width, height) == 1080 && maxOf(width, height) <= 1920
            UHD -> maxOf(width, height) == 3840
            else -> false
        }
    @JvmStatic fun fixedRate(lower: Int, upper: Int, fps: Int): Boolean =
        upper == fps && (lower == fps || lower <= fps)


    @JvmStatic
    fun matchesSensor(width: Int, height: Int, sensorWidth: Int, sensorHeight: Int): Boolean {
        if (width <= 0 || height <= 0 || sensorWidth <= 0 || sensorHeight <= 0) return false
        val aspect = maxOf(width, height).toDouble() / minOf(width, height)
        val sensorAspect = maxOf(sensorWidth, sensorHeight).toDouble() / minOf(sensorWidth, sensorHeight)
        return kotlin.math.abs(aspect - sensorAspect) < .05 || kotlin.math.abs(aspect - 16.0 / 9.0) < .05
    }

    @JvmStatic
    fun displayAspect(width: Int, height: Int, rotation: Int): Float? {
        if (width <= 0 || height <= 0) return null
        val degrees = ((rotation % 360) + 360) % 360
        return if (degrees == 90 || degrees == 270) height.toFloat() / width else width.toFloat() / height
    }

    @JvmStatic
    fun bitrate(width: Int, height: Int): Int = bitrate(width, height, FPS)

    @JvmStatic
    fun bitrate(width: Int, height: Int, fps: Int): Int {
        require(width > 0 && height > 0 && fps > 0)
        // Scale with actual encoded pixels. 1080p-class ≈22 Mbps; larger native
        // frames must not inherit a fixed 12/24 Mbps budget. Clamp to codec caps later.
        return (width.toDouble() * height * fps * .35).coerceIn(12_000_000.0, 120_000_000.0).toInt()
    }
}
