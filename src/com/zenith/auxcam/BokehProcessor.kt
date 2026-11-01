package com.zenith.auxcam

import java.io.Closeable
import java.util.Locale
import java.util.concurrent.CancellationException

internal interface BokehEngine : Closeable {
    val description: String
    fun infer(frame: BokehFrame): ByteArray
    fun reset() {}
    fun policy(thermal: Int, fps: Float) {}
}

/** All methods, including close, belong to the ONE matte executor. */
internal class BokehProcessor(
    private val factory: (BokehModel) -> BokehEngine,
    private val nowNanos: () -> Long = System::nanoTime,
) : Closeable {
    private var engine: BokehEngine? = null
    private var selected: BokehModel? = null
    private var sessionGeneration = -1
    private var lastTimestamp = 0L

    fun process(frame: BokehFrame, model: BokehModel, generation: Int, thermal: Int, fps: Float): Pair<ByteArray, String> {
        try {
            if (selected != model || engine == null) {
                close()
                engine = factory(model)
                selected = model
            }
            val active = checkNotNull(engine)
            if (generation != sessionGeneration || frame.timestampNs <= lastTimestamp || frame.timestampNs - lastTimestamp > FrameData.MATTE_MAX_AGE_NS) active.reset()
            sessionGeneration = generation
            lastTimestamp = frame.timestampNs
            active.policy(thermal, fps)
            return active.infer(frame) to active.description
        } catch (t: Throwable) { close(); throw t }
    }

    /** One captured frame, serial engines, one untimed warmup + five timed runs.
     * Includes model preprocessing/inference/postprocessing, excludes common YUV copy
     * and RGB conversion. Not display FPS, sustained performance or an IQ score.
     */
    fun checks(frame: BokehFrame, models: List<BokehModel>, thermal: Int, fps: Float,
               cancelled: () -> Boolean, progress: (String) -> Unit, result: (BokehCheckResult) -> Unit) {
        close() // Never keep a live ONNX session alongside the benchmark engines.
        for (model in models) {
            if (cancelled()) throw CancellationException("Model checks cancelled")
            progress("Loading ${model.label}…")
            try {
                val loadStart = nowNanos()
                val checked = factory(model).use { active ->
                    val loadMs = (nowNanos() - loadStart) / 1_000_000.0
                    active.policy(thermal, fps)
                    if (cancelled()) throw CancellationException()
                    active.infer(frame) // Warmup also exercises RVM's initial zero state.
                    val times = mutableListOf<Double>()
                    var alpha = ByteArray(0)
                    repeat(5) { index ->
                        if (cancelled()) throw CancellationException()
                        progress("${model.label}: check ${index + 1}/5…")
                        val start = nowNanos()
                        alpha = active.infer(frame)
                        times += (nowNanos() - start) / 1_000_000.0
                        require(alpha.size == 256 * 256) { "Wrong sensor matte size" }
                    }
                    val minimum = alpha.minOf { it.toInt() and 255 }
                    val maximum = alpha.maxOf { it.toInt() and 255 }
                    val foreground = alpha.count { (it.toInt() and 255) >= 128 } * 100.0 / alpha.size
                    val detail = String.format(Locale.US,
                        "PASS runtime · load %.0f ms · median %.1f ms · p95 %.1f ms\n%s\nAlpha %d–%d · foreground %.1f%% · 5 samples%s",
                        loadMs, BokehMask.percentile(times, .5), BokehMask.percentile(times, .95),
                        active.description, minimum, maximum, foreground,
                        if (maximum - minimum < 3) " · flat mask: inspect subject/lighting" else "")
                    BokehCheckResult(model, true, detail)
                }
                result(checked)
            } catch (cancel: CancellationException) { throw cancel } catch (t: Throwable) {
                result(BokehCheckResult(model, false, "FAIL · ${t.javaClass.simpleName}: ${t.message?.take(200) ?: "No details"}"))
            }
        }
    }

    override fun close() {
        val previous = engine
        engine = null; selected = null; sessionGeneration = -1; lastTimestamp = 0L
        previous?.close()
    }
}
