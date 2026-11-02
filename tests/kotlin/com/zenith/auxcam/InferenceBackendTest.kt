package com.zenith.auxcam

import org.junit.Assert.*
import org.junit.Test

class InferenceBackendTest {
    private class Resource(val name: String) : AutoCloseable {
        var closes = 0
        override fun close() { closes++ }
    }
    @Test fun validatedDeviceWinsWithoutCpuAndIsNotClosedEarly() {
        val requested = mutableListOf<String?>()
        val result = InferenceBackend.open(listOf("actual-mdla"), { requested += it; Resource(it!!) },
            { fail("CPU must not be constructed"); Resource("cpu") }, { assertEquals("actual-mdla", it.name) })
        assertEquals(listOf("actual-mdla"), requested)
        assertEquals(0, result.closes)
        result.close(); assertEquals(1, result.closes)
    }
    @Test fun creationAndWarmupFailuresRetryAndCloseBeforeCpu() {
        val warmupFailure = Resource("bad-warmup")
        val requested = mutableListOf<String?>()
        val cpu = Resource("cpu")
        val result = InferenceBackend.open(listOf("throws", "bad-warmup"), {
            requested += it
            if (it == "bad-warmup") warmupFailure else throw UnsatisfiedLinkError("driver unavailable")
        }, { assertEquals(1, warmupFailure.closes); cpu }, {
            if (it === warmupFailure) throw IllegalArgumentException("tensor shape/warmup rejected")
        })
        assertSame(cpu, result)
        assertEquals(listOf("throws", "bad-warmup", null), requested)
        assertEquals(0, cpu.closes)
    }
    @Test fun systemNnapiCanWorkWhenNoDevicesAreListed() {
        var calls = 0
        val result = InferenceBackend.open(emptyList(), { assertNull(it); calls++; Resource("system") },
            { fail("No CPU expected"); Resource("cpu") }, {})
        assertEquals("system", result.name); assertEquals(1, calls)
    }
    @Test fun duplicateDevicesAndInitializationAttemptsAreBounded() {
        val requested = mutableListOf<String?>()
        InferenceBackend.open(listOf("a", "a", "b", "c", "d"), {
            requested += it; throw IllegalStateException("unsupported")
        }, { Resource("cpu") }, {})
        assertEquals(listOf("a", "b", "c", null), requested)
    }
    @Test fun rejectedCpuIsClosedAndItsFailureEscapes() {
        val cpu = Resource("cpu")
        assertThrows(IllegalStateException::class.java) {
            InferenceBackend.open(emptyList(), { throw IllegalStateException("no delegate") }, { cpu }, {
                throw IllegalStateException("invalid CPU model")
            })
        }
        assertEquals(1, cpu.closes)
    }
    @Test fun fatalRuntimeFailureIsNotHiddenAsCpuFallback() {
        val oom = OutOfMemoryError("test")
        val thrown = assertThrows(OutOfMemoryError::class.java) {
            InferenceBackend.open(emptyList(), { throw oom }, { fail("No fallback on fatal error"); Resource("cpu") }, {})
        }
        assertSame(oom, thrown)
    }
    @Test fun removedPreferencesMigrateToAutoWithoutChangingModnetId() {
        listOf("mediapipe_dual", "mediapipe_landscape", "mediapipe_general", "rvm", "unknown", null).forEach {
            assertEquals(BokehModel.MediaPipeAuto, BokehModel.fromId(it))
        }
        assertEquals(BokehModel.MODNet, BokehModel.fromId("modnet"))
        assertEquals(listOf("mediapipe_auto", "modnet"), BokehModel.entries.map { it.id })
    }
    @Test fun unmeasuredDepthCannotAuthorizeWholeFrameBlur() {
        assertFalse(FrameData.hasUsableDepth(null))
        assertFalse(FrameData.hasUsableDepth(byteArrayOf()))
        assertFalse(FrameData.hasUsableDepth(ByteArray(64)))
        assertFalse(FrameData.hasUsableDepth(ByteArray(64) { 255.toByte() }))
        assertFalse(FrameData.hasUsableDepth(ByteArray(64) { 5 }))
        assertTrue(FrameData.hasUsableDepth(ByteArray(64) { 6 }))
        assertTrue(FrameData.hasUsableDepth(ByteArray(64) { 254.toByte() }))
    }
    @Test fun emptyAndNearEmptyMasksDoNotAuthorizeWholeFrameBlur() {
        assertFalse(BokehMask.hasForeground(byteArrayOf()))
        assertFalse(BokehMask.hasForeground(ByteArray(65536)))
        assertFalse(BokehMask.hasForeground(ByteArray(65536) { if (it == 0) 255.toByte() else 0 }))
        assertFalse(BokehMask.hasForeground(ByteArray(65536) { 127 }))
        assertTrue(BokehMask.hasForeground(ByteArray(65536) { if (it < 100) 128.toByte() else 0 }))
        assertTrue(BokehMask.hasForeground(byteArrayOf(255.toByte())))
    }
}
