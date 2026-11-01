package com.zenith.auxcam

/** Recorder framing/bitrate policy, independent of the preview and still reader. */
object VideoPolicy {
    const val FPS = 30

    @JvmStatic
    fun matchesSensor(width: Int, height: Int, sensorWidth: Int, sensorHeight: Int): Boolean {
        if (width <= 0 || height <= 0 || sensorWidth <= 0 || sensorHeight <= 0) return false
        // Allow advertised pixel-alignment differences, not a 16:9 crop of 4:3.
        return kotlin.math.abs(width.toDouble() / height - sensorWidth.toDouble() / sensorHeight) < .02
    }

    @JvmStatic
    fun displayAspect(width: Int, height: Int, rotation: Int): Float? {
        if (width <= 0 || height <= 0) return null
        val degrees = ((rotation % 360) + 360) % 360
        return if (degrees == 90 || degrees == 270) height.toFloat() / width else width.toFloat() / height
    }

    @JvmStatic
    fun bitrate(width: Int, height: Int): Int {
        require(width > 0 && height > 0)
        // Scale with actual encoded pixels. 1080p-class ≈22 Mbps; larger native
        // frames must not inherit a fixed 12/24 Mbps budget. Clamp to codec caps later.
        return (width.toDouble() * height * FPS * .35).coerceIn(12_000_000.0, 120_000_000.0).toInt()
    }
}
