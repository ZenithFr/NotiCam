package com.zenith.auxcam

import java.nio.FloatBuffer
import org.junit.Assert.*
import org.junit.Test

class BokehMathTest {
    @Test fun modelPreferencesUseStableIdsAndSafeUnknownFallback() {
        BokehModel.entries.forEach { assertEquals(it, BokehModel.fromId(it.id)) }
        assertEquals(BokehModel.MobileNetV4Int8, BokehModel.fromId(null))
        assertEquals(BokehModel.MobileNetV4Int8, BokehModel.fromId("removed_model"))
        assertEquals(1, BokehModel.entries.map { it.id }.distinct().size)
    }
    @Test fun mediaPipeReceivesUnsignedNhwcRgb() {
        val frame = BokehFrame(2, 1, byteArrayOf(-1, 0, -128, 0, -1, 64), 10L)
        val out = FloatBuffer.allocate(6)
        frame.writeTensor(out, 2, 1, nchw = false)
        val actual = FloatArray(6).also { out.get(it) }
        assertArrayEquals(floatArrayOf(1f, 0f, 128f / 255f, 0f, 1f, 64f / 255f), actual, .00001f)
    }
    @Test fun tensorPackingKeepsUnsignedAndSignedNchwDistinct() {
        val frame = BokehFrame(2, 1, byteArrayOf(-1, 0, -128, 0, -1, 64), 10L)
        val out = FloatBuffer.allocate(6)
        frame.writeTensor(out, 2, 1, nchw = true)
        val unsigned = FloatArray(6).also { out.get(it) }
        assertArrayEquals(floatArrayOf(1f, 0f, 0f, 1f, 128f / 255f, 64f / 255f), unsigned, .00001f)
        frame.writeTensor(out, 2, 1, nchw = true, signed = true)
        val modnet = FloatArray(6).also { out.get(it) }
        assertArrayEquals(unsigned.map { it * 2f - 1f }.toFloatArray(), modnet, .00001f)
    }
    @Test fun modelResizeKeepsChannelsTogether() {
        val frame = BokehFrame(1, 1, byteArrayOf(-1, 0, 0), 10L)
        val out = FloatBuffer.allocate(12)
        frame.writeTensor(out, 2, 2, nchw = true)
        assertArrayEquals(floatArrayOf(1f, 1f, 1f, 1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f), out.array(), 0f)
    }
    @Test fun alphaResizeAndSensorRotationHappenExactlyOnce() {
        val alpha = floatArrayOf(0f, 1f, .25f, .75f)
        val unrotated = BokehMask.sensorBytes(alpha, 2, 2, false)
        assertEquals(0, unrotated[0].toInt() and 255)
        assertEquals(255, unrotated[255].toInt() and 255)
        assertEquals(64, unrotated[255 * 256].toInt() and 255)
        assertEquals(191, unrotated.last().toInt() and 255)
        val rotated = BokehMask.sensorBytes(alpha, 2, 2, true)
        assertEquals(255, rotated[0].toInt() and 255)
        assertEquals(191, rotated[255].toInt() and 255)
        assertEquals(0, rotated[255 * 256].toInt() and 255)
        assertEquals(64, rotated.last().toInt() and 255)
    }
    @Test fun flatMaskIsValidButNotProofOfSubjectQuality() {
        assertTrue(BokehMask.sensorBytes(floatArrayOf(0f), 1, 1, true).all { it.toInt() == 0 })
        assertTrue(BokehMask.sensorBytes(floatArrayOf(1f), 1, 1, true).all { (it.toInt() and 255) == 255 })
    }
    @Test fun badAlphaFailsBeforeByteQuantizationCanHideIt() {
        for (bad in listOf(Float.NaN, Float.POSITIVE_INFINITY, -1f, 1.2f)) {
            assertThrows(IllegalArgumentException::class.java) { BokehMask.sensorBytes(floatArrayOf(bad), 1, 1, true) }
        }
        assertThrows(IllegalArgumentException::class.java) { BokehMask.sensorBytes(FloatArray(3), 2, 2, true) }
    }
    @Test fun shortBenchmarkReportsMedianAndNearestRankP95NotFps() {
        val times = listOf(50.0, 10.0, 30.0, 20.0, 40.0)
        assertEquals(30.0, BokehMask.percentile(times, .5), 0.0)
        assertEquals(50.0, BokehMask.percentile(times, .95), 0.0)
        assertThrows(IllegalArgumentException::class.java) { BokehMask.percentile(emptyList(), .5) }
    }
    @Test fun inputFrameAndTensorSizesAreStrict() {
        assertThrows(IllegalArgumentException::class.java) { BokehFrame(2, 1, ByteArray(3), 10L) }
        val frame = BokehFrame(1, 1, ByteArray(3), 10L)
        assertThrows(IllegalArgumentException::class.java) { frame.writeTensor(FloatBuffer.allocate(2), 1, 1, false) }
    }
}
