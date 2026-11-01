package com.zenith.auxcam

/** Pure output contracts: a marketing badge must match the saved pixel data. */
internal object CaptureOutput {
    fun is50MpSize(width: Int, height: Int): Boolean {
        val pixels = width.toLong() * height
        if (width <= 0 || height <= 0 || pixels !in 45_000_000L..55_000_000L) return false
        val longEdge = maxOf(width, height).toDouble()
        val shortEdge = minOf(width, height).toDouble()
        return kotlin.math.abs(longEdge / shortEdge - 4.0 / 3.0) < 0.02
    }

    fun matches(actualWidth: Int, actualHeight: Int, expectedWidth: Int, expectedHeight: Int): Boolean =
        (actualWidth == expectedWidth && actualHeight == expectedHeight) ||
            (actualWidth == expectedHeight && actualHeight == expectedWidth)
}
