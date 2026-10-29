package com.zenith.auxcam

import android.util.Log

/**
 * Single derived authority for viewfinder geometry, scaling, and depth alignment.
 * Derives surface size -> letterbox offsets -> CoC width reference -> depth sample transform.
 */
data class ViewfinderGeometry(
    val surfaceWidth: Int,
    val surfaceHeight: Int,
    val isPortrait: Boolean = true,
) {
    val targetAspect: Float = if (isPortrait) 3f / 4f else 4f / 3f

    // Working half-resolution for bokeh gather & composite (e.g. 540x720 in portrait)
    val workingWidth: Int = if (surfaceWidth > 0) surfaceWidth / 2 else (if (isPortrait) 540 else 720)
    val workingHeight: Int = if (surfaceHeight > 0) surfaceHeight / 2 else (if (isPortrait) 720 else 540)

    val cocReferenceWidth: Int = workingWidth

    // Letterbox offsets:
    // In portrait, the 4:3 sensor frame (1440x1080) rotated 90° becomes 1080x1440 (3:4).
    // The disparity map (448x288, 14:9) has an x-offset letterbox in portrait or y-offset in landscape.
    val letterboxOffsetX: Float = if (isPortrait) 0.0713f else 0.0f
    val letterboxOffsetY: Float = if (isPortrait) 0.0f else 0.0713f
    val depthScale: Float = 0.8571f

    fun depthToColorV(vDepth: Float): Float =
        letterboxOffsetY + depthScale * vDepth

    fun depthToColorU(uDepth: Float): Float =
        letterboxOffsetX + depthScale * uDepth

    fun assertMatchesSurface(actualW: Int, actualH: Int) {
        if (actualW != surfaceWidth || actualH != surfaceHeight) {
            Log.w(
                "ViewfinderGeometry",
                "Surface mismatch: expected ${surfaceWidth}x${surfaceHeight}, actual ${actualW}x${actualH}",
            )
        }
    }

    companion object {
        fun defaultPortrait(): ViewfinderGeometry =
            ViewfinderGeometry(surfaceWidth = 1080, surfaceHeight = 1440, isPortrait = true)
    }
}
