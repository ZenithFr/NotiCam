package com.zenith.auxcam

import org.junit.Assert.*
import org.junit.Test

class InferenceBackendTest {
    @Test fun missingVerifiedApuRuntimeIsExplicitAndCannotEnablePortrait() {
        assertFalse(PortraitRuntime.available)
        assertEquals(512, PortraitRuntime.INPUT_SIZE)
        val failure = assertThrows(PortraitUnavailableException::class.java) { PortraitRuntime.requireAvailable() }
        assertTrue(failure.message!!.contains("No CPU/GPU fallback"))
        assertFalse(CameraUiState(bokehEnabled = true).bokehActive)
        assertTrue(CameraUiState(sessionReady = true).canTakePhoto)
    }
    @Test fun allRemovedPreferencesMigrateToTheSingleRequestedModel() {
        listOf("mediapipe_auto", "modnet", "rvm", null, "unknown", PortraitRuntime.MODEL_ID).forEach {
            assertEquals(BokehModel.MobileNetV4Int8, BokehModel.fromId(it))
        }
        assertEquals(listOf(PortraitRuntime.MODEL_ID), BokehModel.entries.map { it.id })
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
