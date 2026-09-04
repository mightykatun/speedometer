package com.mightykatun.speedometer.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HeadingTrackerTest {
    @Test
    fun `portrait screen facing aft maps device back to cardinal bow headings`() {
        assertHeading(0.0, NORTH)
        assertHeading(90.0, EAST)
        assertHeading(180.0, SOUTH)
        assertHeading(270.0, WEST)
    }

    @Test
    fun `flat mount with vertical bow axis is unavailable`() {
        assertNull(sample(IDENTITY, timestampNanos = 1L))
        assertNull(sample(NEAR_VERTICAL_BOW, timestampNanos = 1L))
    }

    @Test
    fun `unreliable and inaccurate samples are unavailable`() {
        assertNull(
            createHeadingSensorSample(
                NORTH,
                reportedAccuracyRadians = Math.toRadians(5.0),
                sensorAccuracy = HeadingSensorAccuracy.UNRELIABLE,
                source = HeadingSensorSource.ROTATION_VECTOR,
                timestampNanos = 1L
            )
        )
        assertNull(
            createHeadingSensorSample(
                NORTH,
                reportedAccuracyRadians = Math.toRadians(25.01),
                sensorAccuracy = HeadingSensorAccuracy.HIGH,
                source = HeadingSensorSource.ROTATION_VECTOR,
                timestampNanos = 1L
            )
        )
        assertNull(
            createHeadingSensorSample(
                NORTH,
                reportedAccuracyRadians = null,
                sensorAccuracy = HeadingSensorAccuracy.LOW,
                source = HeadingSensorSource.ROTATION_VECTOR,
                timestampNanos = 1L
            )
        )
    }

    @Test
    fun `true heading adds declination and wraps north`() {
        val tracker = HeadingTracker()
        tracker.updateDeclination(5.0, timestampNanos = 1L)
        tracker.update(sampleForDegrees(358.0, timestampNanos = 2L))

        val heading = requireNotNull(tracker.snapshot(2L))

        assertEquals(3f, heading.trueDegrees, 0.001f)
        assertEquals(5f, heading.accuracyDegrees!!, 0f)
    }

    @Test
    fun `filter follows the shortest arc across north`() {
        val tracker = HeadingTracker()
        tracker.updateDeclination(0.0, timestampNanos = 1L)
        tracker.update(sampleForDegrees(359.0, timestampNanos = 100_000_000L))
        tracker.update(sampleForDegrees(1.0, timestampNanos = 350_000_000L))

        val heading = requireNotNull(tracker.snapshot(350_000_000L))

        assertEquals(0.264f, heading.trueDegrees, 0.02f)
    }

    @Test
    fun `stale out-of-order and future-declination evidence cannot produce heading`() {
        val tracker = HeadingTracker()
        tracker.updateDeclination(2.0, timestampNanos = 10L)
        tracker.update(sampleForDegrees(90.0, timestampNanos = 9L))
        assertNull(tracker.snapshot(9L))

        tracker.update(sampleForDegrees(90.0, timestampNanos = 11L))
        assertEquals(92f, requireNotNull(tracker.snapshot(11L)).trueDegrees, 0f)
        tracker.update(sampleForDegrees(180.0, timestampNanos = 10L))
        assertEquals(92f, requireNotNull(tracker.snapshot(11L)).trueDegrees, 0f)
        assertNull(tracker.snapshot(11L + HEADING_STALE_NANOS + 1L))
    }

    @Test
    fun `source change and stale gap reset filtering`() {
        val tracker = HeadingTracker()
        tracker.updateDeclination(0.0, timestampNanos = 1L)
        tracker.update(sampleForDegrees(0.0, timestampNanos = 10L))
        tracker.update(
            sampleForDegrees(
                degrees = 180.0,
                timestampNanos = 20L,
                source = HeadingSensorSource.GEOMAGNETIC_ROTATION_VECTOR
            )
        )
        assertEquals(180f, requireNotNull(tracker.snapshot(20L)).trueDegrees, 0f)

        tracker.update(
            sampleForDegrees(
                degrees = 90.0,
                timestampNanos = 20L + HEADING_STALE_NANOS + 1L,
                source = HeadingSensorSource.GEOMAGNETIC_ROTATION_VECTOR
            )
        )
        assertEquals(
            90f,
            requireNotNull(tracker.snapshot(20L + HEADING_STALE_NANOS + 1L)).trueDegrees,
            0f
        )
    }

    private fun assertHeading(expected: Double, matrix: FloatArray) {
        assertEquals(expected, requireNotNull(sample(matrix, 1L)).magneticDegrees, 0.0001)
    }

    private fun sample(matrix: FloatArray, timestampNanos: Long) = createHeadingSensorSample(
        rotationMatrix = matrix,
        reportedAccuracyRadians = Math.toRadians(5.0),
        sensorAccuracy = HeadingSensorAccuracy.HIGH,
        source = HeadingSensorSource.ROTATION_VECTOR,
        timestampNanos = timestampNanos
    )

    private fun sampleForDegrees(
        degrees: Double,
        timestampNanos: Long,
        source: HeadingSensorSource = HeadingSensorSource.ROTATION_VECTOR
    ) = HeadingSensorSample(
        magneticDegrees = degrees,
        accuracyDegrees = 5.0,
        source = source,
        timestampNanos = timestampNanos
    )

    private companion object {
        val NORTH = floatArrayOf(
            1f, 0f, 0f,
            0f, 0f, -1f,
            0f, 1f, 0f
        )
        val EAST = floatArrayOf(
            0f, 0f, -1f,
            -1f, 0f, 0f,
            0f, 1f, 0f
        )
        val SOUTH = floatArrayOf(
            -1f, 0f, 0f,
            0f, 0f, 1f,
            0f, 1f, 0f
        )
        val WEST = floatArrayOf(
            0f, 0f, 1f,
            1f, 0f, 0f,
            0f, 1f, 0f
        )
        val IDENTITY = floatArrayOf(
            1f, 0f, 0f,
            0f, 1f, 0f,
            0f, 0f, 1f
        )
        val NEAR_VERTICAL_BOW = floatArrayOf(
            1f, 0f, 0f,
            0f, 0.9949874f, -0.1f,
            0f, 0.1f, 0.9949874f
        )
    }
}
