package com.zenith.auxcam

import com.zenith.auxcam.ui.components.ControlSection
import org.junit.Assert.*
import org.junit.Test

class ControlSectionTest {
    @Test fun blankQueryKeepsEverySectionAvailable() {
        for (section in ControlSection.entries) {
            assertTrue(section.matches(""))
            assertTrue(section.matches("  \t "))
        }
    }
    @Test fun multiWordQueriesMatchEveryTermRegardlessOfCaseOrWhitespace() {
        assertTrue(ControlSection.Capture.matches("  RAW   sequence  "))
        assertTrue(ControlSection.Manual.matches("WHITE\tbalance"))
        assertTrue(ControlSection.Portrait.matches("bokeh MODEL"))
        assertTrue(ControlSection.Capture.matches("flash"))
        assertFalse(ControlSection.System.matches("RAW sequence"))
        assertFalse(ControlSection.Manual.matches("iso waveform"))
    }
    @Test fun searchDoesNotAdvertiseUnimplementedProcessing() {
        for (feature in listOf("HDR", "XDR", "Night", "Astro", "waveform"))
            assertFalse(ControlSection.entries.any { it.matches(feature) })
    }
}
