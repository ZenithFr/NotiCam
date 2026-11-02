package com.zenith.auxcam

import org.junit.Assert.*
import org.junit.Test

class FramingGuideTest {
    @Test fun stableIdsAndCycleDoNotDependOnPreferenceOrdinals() {
        assertEquals(FramingGuide.Thirds, FramingGuide.fromId("removed"))
        assertEquals(FramingGuide.Thirds, FramingGuide.fromId(null))
        for (guide in FramingGuide.entries) assertEquals(guide, FramingGuide.fromId(guide.id))
        var guide = FramingGuide.Thirds
        repeat(FramingGuide.entries.size) { guide = guide.next() }
        assertEquals(FramingGuide.Thirds, guide)
    }
    @Test fun allLinesStayInsideBothPortraitAndLandscapeViewfinders() {
        for ((w,h) in listOf(1080f to 1440f, 1440f to 1080f, 1f to 1f)) for (guide in FramingGuide.entries) {
            val lines = guide.lines(w,h)
            assertEquals(if (guide == FramingGuide.Cross || guide == FramingGuide.Diagonal) 2 else 4, lines.size)
            for (line in lines) {
                assertTrue(line.x1 in 0f..w && line.x2 in 0f..w)
                assertTrue(line.y1 in 0f..h && line.y2 in 0f..h)
            }
        }
    }
    @Test fun thirdsGoldenAndCrossHaveExpectedPlacement() {
        assertEquals(300f, FramingGuide.Thirds.lines(900f,1200f)[0].x1, .001f)
        assertEquals(381.966f, FramingGuide.Golden.lines(1000f,1000f)[0].x1, .001f)
        assertEquals(500f, FramingGuide.Cross.lines(1000f,1000f)[0].x1, 0f)
    }
    @Test fun squareIsPhysicallySquareAndCenteredNotStretchedByAspect() {
        for ((w,h) in listOf(1080f to 1440f, 1440f to 1080f)) {
            val lines = FramingGuide.Square.lines(w,h)
            assertEquals(lines[0].x2-lines[0].x1, lines[1].y2-lines[1].y1, .001f)
            assertEquals(w/2, (lines[0].x1+lines[0].x2)/2, .001f)
            assertEquals(h/2, (lines[1].y1+lines[1].y2)/2, .001f)
        }
    }
    @Test fun invalidGeometryDrawsNothing() {
        for (guide in FramingGuide.entries) {
            assertTrue(guide.lines(0f,100f).isEmpty())
            assertTrue(guide.lines(Float.NaN,100f).isEmpty())
            assertTrue(guide.lines(100f,Float.POSITIVE_INFINITY).isEmpty())
        }
    }
}
