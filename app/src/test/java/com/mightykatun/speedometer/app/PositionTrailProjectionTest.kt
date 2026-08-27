package com.mightykatun.speedometer.app

import androidx.compose.ui.geometry.Offset
import com.mightykatun.speedometer.app.domain.model.PositionFix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PositionTrailProjectionTest {
    @Test
    fun `projection remains north-up and east-right`() {
        val origin = fix(51.0, 4.0, timestampNanos = 1L)
        val north = fix(51.001, 4.0, timestampNanos = 2L)
        val east = fix(51.0, 4.001, timestampNanos = 3L)

        val projection = requireNotNull(
            projectPositionTrail(
                trail = listOf(origin, north, east),
                current = east,
                width = 300f,
                height = 200f,
                padding = 20f
            )
        )

        assertTrue(projection.trace[1].y < projection.trace[0].y)
        assertTrue(projection.trace[2].x > projection.trace[0].x)
    }

    @Test
    fun `projection keeps the complete trace inside its padding`() {
        val trail = listOf(
            fix(40.0, -74.0, timestampNanos = 1L),
            fix(40.02, -73.98, timestampNanos = 2L),
            fix(39.99, -74.03, timestampNanos = 3L)
        )

        val projection = requireNotNull(
            projectPositionTrail(
                trail = trail,
                current = trail.last(),
                width = 320f,
                height = 180f,
                padding = 16f
            )
        )

        assertTrue(projection.trace.all { it.x in 16f..304f })
        assertTrue(projection.trace.all { it.y in 16f..164f })
        assertEquals(projection.trace.last(), projection.current)
    }

    @Test
    fun `projection takes the short path across the date line`() {
        val west = fix(0.0, 179.999, timestampNanos = 1L)
        val east = fix(0.0, -179.999, timestampNanos = 2L)

        val projection = requireNotNull(
            projectPositionTrail(
                trail = listOf(west, east),
                current = east,
                width = 300f,
                height = 180f,
                padding = 20f
            )
        )

        assertTrue(projection.trace.last().x > projection.trace.first().x)
        assertTrue(projection.trace.all { it.x in 19.99f..280.01f })
    }

    @Test
    fun `projection does not connect acquisition segments`() {
        val trail = listOf(
            fix(51.0, 4.0, timestampNanos = 1L),
            fix(51.001, 4.0, timestampNanos = 2L),
            fix(52.0, 5.0, timestampNanos = 3L),
            fix(52.001, 5.0, timestampNanos = 4L)
        )

        val projection = requireNotNull(
            projectPositionTrail(
                trail = trail,
                current = trail.last(),
                segmentStarts = listOf(3L),
                width = 300f,
                height = 200f,
                padding = 20f
            )
        )

        assertEquals(2, projection.traceSegments.size)
        assertEquals(2, projection.traceSegments[0].size)
        assertEquals(2, projection.traceSegments[1].size)
        assertEquals(projection.trace, projection.traceSegments.flatten())
    }

    @Test
    fun `invalid geometry is rejected`() {
        val invalid = fix(Double.NaN, 4.0, timestampNanos = 1L)

        assertNull(
            projectPositionTrail(
                trail = listOf(invalid),
                current = invalid,
                width = 300f,
                height = 180f,
                padding = 20f
            )
        )
    }

    @Test
    fun `heading label normalizes degrees and selects a cardinal`() {
        assertEquals("315\u00b0 NW", headingLabel(-45f))
        assertEquals("090\u00b0 E", headingLabel(90f))
        assertEquals("000\u00b0 N", headingLabel(359.6f))
        assertEquals("123 m", altitudeLabel(123.4))
        assertEquals("-8 m", altitudeLabel(-7.6))
    }

    @Test
    fun `trace corner rounding preserves straights and trims bends`() {
        assertNull(
            roundedTraceCorner(
                previous = Offset(0f, 0f),
                corner = Offset(10f, 0f),
                next = Offset(20f, 0f),
                maximumCut = 4f
            )
        )

        val bend = requireNotNull(
            roundedTraceCorner(
                previous = Offset(0f, 0f),
                corner = Offset(10f, 0f),
                next = Offset(10f, 10f),
                maximumCut = 4f
            )
        )
        assertEquals(7.5f, bend.entry.x, 0f)
        assertEquals(0f, bend.entry.y, 0f)
        assertEquals(10f, bend.exit.x, 0f)
        assertEquals(2.5f, bend.exit.y, 0f)
    }

    private fun fix(
        latitude: Double,
        longitude: Double,
        timestampNanos: Long
    ) = PositionFix(
        latitudeDegrees = latitude,
        longitudeDegrees = longitude,
        headingDegrees = null,
        horizontalAccuracyMeters = 5f,
        timestampNanos = timestampNanos
    )
}
