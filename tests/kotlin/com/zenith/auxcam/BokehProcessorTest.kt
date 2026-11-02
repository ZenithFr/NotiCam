package com.zenith.auxcam

import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

/** Fake engines test orchestration, not the neural networks or Android delegates. */
class BokehProcessorTest {
    private class FakeEngine : BokehEngine {
        var calls = 0
        var resets = 0
        var closed = false
        var fail = false
        val frames = mutableListOf<BokehFrame>()
        override val description = "fake test engine"
        override fun infer(frame: BokehFrame): ByteArray {
            check(!closed)
            calls++
            frames += frame
            if (fail) error("test failure")
            return ByteArray(256 * 256)
        }
        override fun reset() { resets++ }
        override fun close() { closed = true }
    }
    private fun frame(timestamp: Long = 1L) = BokehFrame(1, 1, ByteArray(3), timestamp)

    @Test fun reusesEngineAndResetsOnSessionChangeLongGapAndTimestampReversal() {
        val engine = FakeEngine()
        val processor = BokehProcessor(factory = { engine })
        processor.process(frame(1), BokehModel.MobileNetV4Int8, 1, 0, 30f)
        processor.process(frame(83_000_001), BokehModel.MobileNetV4Int8, 1, 0, 30f)
        assertEquals(1, engine.resets)
        processor.process(frame(900_000_001), BokehModel.MobileNetV4Int8, 1, 0, 30f)
        assertEquals(2, engine.resets)
        processor.process(frame(983_000_001), BokehModel.MobileNetV4Int8, 2, 0, 30f)
        processor.process(frame(100), BokehModel.MobileNetV4Int8, 2, 0, 30f)
        assertEquals(4, engine.resets)
        processor.close()
        assertTrue(engine.closed)
    }
    @Test fun explicitResetClosesOldEngineBeforeCreatingAnother() {
        val engines = mutableListOf<FakeEngine>()
        val processor = BokehProcessor(factory = {
            if (engines.isNotEmpty()) assertTrue(engines.last().closed)
            FakeEngine().also { engines += it }
        })
        processor.process(frame(), BokehModel.MobileNetV4Int8, 1, 0, 30f)
        processor.close()
        processor.process(frame(2), BokehModel.MobileNetV4Int8, 1, 0, 30f)
        assertEquals(2, engines.size)
        assertFalse(engines.last().closed)
        processor.close()
    }
    @Test fun failedInferenceClosesAndRetriesOnlyTheExplicitModel() {
        val requested = mutableListOf<BokehModel>()
        val engines = mutableListOf<FakeEngine>()
        val processor = BokehProcessor(factory = { model ->
            requested += model
            FakeEngine().also { it.fail = engines.isEmpty(); engines += it }
        })
        assertThrows(IllegalStateException::class.java) { processor.process(frame(), BokehModel.MobileNetV4Int8, 1, 0, 30f) }
        assertTrue(engines.first().closed)
        processor.process(frame(2), BokehModel.MobileNetV4Int8, 1, 0, 30f)
        assertEquals(listOf(BokehModel.MobileNetV4Int8, BokehModel.MobileNetV4Int8), requested)
        processor.close()
    }
    @Test fun checksShareTheSameFrameWarmOnceAndTimeFiveRuns() {
        val input = frame()
        val engines = mutableListOf<FakeEngine>()
        val results = mutableListOf<BokehCheckResult>()
        var time = 0L
        val processor = BokehProcessor(factory = { FakeEngine().also { engines += it } }, nowNanos = { time += 1_000_000; time })
        processor.checks(input, listOf(BokehModel.MobileNetV4Int8, BokehModel.MobileNetV4Int8), 0, 30f, { false }, {}, { result ->
            assertTrue(engines.last().closed) // Publish only after resource cleanup.
            results += result
        })
        assertEquals(listOf(6, 6), engines.map { it.calls })
        assertTrue(engines.flatMap { it.frames }.all { it === input })
        assertEquals(2, results.size)
        assertTrue(results.all { it.passed && it.summary.contains("5 samples") && it.summary.contains("flat mask") })
    }
    @Test fun checksReleaseLiveEngineBeforeAllocatingBenchmarkEngine() {
        val engines = mutableListOf<FakeEngine>()
        val processor = BokehProcessor(factory = {
            if (engines.isNotEmpty()) assertTrue(engines.last().closed)
            FakeEngine().also { engines += it }
        })
        processor.process(frame(), BokehModel.MobileNetV4Int8, 1, 0, 30f)
        processor.checks(frame(2), listOf(BokehModel.MobileNetV4Int8), 0, 30f, { false }, {}, {})
        assertEquals(2, engines.size)
        assertTrue(engines.all { it.closed })
    }
    @Test fun failedCheckDoesNotPreventCheckingTheNextModel() {
        val engines = mutableListOf<FakeEngine>()
        val results = mutableListOf<BokehCheckResult>()
        val processor = BokehProcessor(factory = { model -> FakeEngine().also { it.fail = engines.isEmpty(); engines += it } })
        processor.checks(frame(), listOf(BokehModel.MobileNetV4Int8, BokehModel.MobileNetV4Int8), 0, 30f, { false }, {}, { results += it })
        assertEquals(listOf(false, true), results.map { it.passed })
        assertTrue(engines.all { it.closed })
    }
    @Test fun cancellationWaitsForNativeCallThenClosesWithoutPublishingPass() {
        val engine = FakeEngine()
        val results = mutableListOf<BokehCheckResult>()
        val processor = BokehProcessor(factory = { engine })
        assertThrows(CancellationException::class.java) {
            processor.checks(frame(), listOf(BokehModel.MobileNetV4Int8), 0, 30f, { engine.calls >= 2 }, {}, { results += it })
        }
        assertEquals(2, engine.calls)
        assertTrue(engine.closed)
        assertTrue(results.isEmpty())
    }
}
