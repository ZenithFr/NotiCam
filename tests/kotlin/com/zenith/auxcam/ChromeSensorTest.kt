package com.zenith.auxcam

import org.junit.Assert.*
import org.junit.Test

class ChromeSensorTest {
    @Test fun isoDetentsKeepAdvertisedEndpointsAndSnapToCanonicalStops() {
        val stops = SensorControlPolicy.isoStops(60, 6100)
        assertEquals(60L, stops.first()); assertEquals(6100L, stops.last())
        assertEquals(stops.sorted().distinct(), stops)
        assertTrue(stops.contains(100L)); assertTrue(stops.contains(3200L))
        for (stop in stops) assertEquals(stop, SensorControlPolicy.snapped(SensorControlPolicy.fraction(stop, 60, 6100), stops))
        assertTrue(SensorControlPolicy.isoStops(0, 6400).isEmpty())
    }
    @Test fun shutterDetentsRespectPhotoAndVideoLimitsWithoutRoundingPastThem() {
        val stops = SensorControlPolicy.shutterStops(100_000, SensorControlPolicy.framePeriod(60))
        assertEquals(100_000L, stops.first()); assertEquals(16_666_666L, stops.last())
        assertTrue(stops.contains(8_000_000L))
        for (stop in stops) assertEquals(stop, SensorControlPolicy.snapped(SensorControlPolicy.fraction(stop, stops.first(), stops.last()), stops))
        assertEquals(stops.first(), SensorControlPolicy.snapped(Float.NaN, stops))
        assertEquals(stops.last(), SensorControlPolicy.snapped(2f, stops))
        assertEquals(0L, SensorControlPolicy.snapped(.5f, emptyList()))
    }
    @Test fun detentMappingIsMonotonicForRapidDrags() {
        val stops = SensorControlPolicy.isoStops(50, 6400)
        var previous = 0L
        for (index in 0..1000) {
            val value = SensorControlPolicy.snapped(index / 1000f, stops)
            assertTrue(value >= previous); assertTrue(value in stops); previous = value
        }
    }
    @Test fun manualColorHasItsOwnCapabilityBitNotAnAwbPreset() {
        val caps = SensorControlCapabilities(awbModes = 3)
        assertTrue(caps.supportsTemperature)
        assertTrue(caps.supportsWhiteBalance(1))
        assertFalse(caps.supportsWhiteBalance(0))
        assertTrue(SensorControlCapabilities.fromWire(caps.toWire()).supportsTemperature)
        assertFalse(SensorControlCapabilities(awbModes = 2).supportsTemperature)
    }
    @Test fun temperatureCorrectionIsBoundedAndChangesWarmthNotImagePixels() {
        val cool = SensorControlPolicy.temperatureGains(2000)
        val warm = SensorControlPolicy.temperatureGains(10000)
        assertTrue(cool[3] > warm[3]); assertTrue(warm[0] > cool[0])
        for (kelvin in 2000..10000 step 50) {
            val gains = SensorControlPolicy.temperatureGains(kelvin)
            assertEquals(4, gains.size)
            assertEquals(1f, gains[1]); assertEquals(1f, gains[2])
            assertTrue(gains.all { it.isFinite() && it in 1f..4f })
        }
    }
    @Test fun raw50IsOneRealLargeFrameNotAFourFrameOrUpscaledClaim() {
        val high = CameraUiState(raw = true, resolution = Resolution.High, sessionReady = true,
            rawCapabilityChecked = true, rawSensorWidth = 8160, rawSensorHeight = 6144)
        assertEquals(1, high.maxRawFrames); assertTrue(high.canTakePhoto)
        assertFalse(high.copy(rawSensorWidth = 0, rawSensorHeight = 0).canTakePhoto)
        assertEquals(4, high.copy(rawSensorWidth = 4080, rawSensorHeight = 3072).maxRawFrames)
    }
}
