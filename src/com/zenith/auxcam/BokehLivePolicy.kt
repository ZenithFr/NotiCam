package com.zenith.auxcam

/** Live timing is independent of whether the selected model can process a photo. */
internal object BokehLivePolicy {
    const val MIN_INTERVAL_MS = 83L
    // Two slow warm cycles cannot keep a 500ms-old matte continuously useful.
    const val CYCLE_BUDGET_MS = 220.0
    fun nextSlowCount(previous: Int, warm: Boolean, cycleMs: Double): Int =
        if (warm && cycleMs.isFinite() && cycleMs > CYCLE_BUDGET_MS) minOf(previous + 1, 2) else 0
    fun intervalMs(cycleMs: Double): Long =
        if (!cycleMs.isFinite() || cycleMs < 0) MIN_INTERVAL_MS
        else (cycleMs + 60.0).toLong().coerceIn(MIN_INTERVAL_MS, 300L)

    data class Matte(val bytes: ByteArray, val timestamp: Long, val session: Int, val model: Int) {
        init { require(bytes.size == 256 * 256) }
        fun forFrame(timestamp: Long, session: Int, model: Int): ByteArray? =
            bytes.takeIf { this.session == session && this.model == model &&
                FrameData.isFresh(this.timestamp, timestamp, FrameData.MATTE_MAX_AGE_NS) }
    }
}
