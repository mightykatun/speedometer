package com.mightykatun.speedometer.app.domain

import com.mightykatun.speedometer.app.domain.model.PositionFix
import com.mightykatun.speedometer.app.domain.model.RegattaMark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RegattaNavigationTest {
    private val boat = mark(latitude = 0.0, longitude = 0.0005)
    private val pin = mark(latitude = 0.0, longitude = -0.0005)

    @Test
    fun `distance is positive pre-start and negative course-side`() {
        val preStart = calculateRegattaMetrics(
            boat,
            pin,
            fix(latitude = -0.0001, longitude = 0.0)
        )
        val courseSide = calculateRegattaMetrics(
            boat,
            pin,
            fix(latitude = 0.0001, longitude = 0.0)
        )

        assertEquals(11.06, preStart.signedDistanceToLineMeters!!, 0.05)
        assertEquals(-11.06, courseSide.signedDistanceToLineMeters!!, 0.05)
        assertNull(courseSide.timeToLineSeconds)
    }

    @Test
    fun `distance uses the infinite line beyond its endpoints`() {
        val metrics = calculateRegattaMetrics(
            boat,
            pin,
            fix(latitude = -0.0001, longitude = -0.01)
        )

        assertEquals(11.06, metrics.signedDistanceToLineMeters!!, 0.05)
    }

    @Test
    fun `time to line uses reliable GPS closing velocity`() {
        val metrics = calculateRegattaMetrics(
            boat,
            pin,
            fix(
                latitude = -0.0001,
                longitude = 0.0,
                speedMetersPerSecond = 5f,
                courseDegrees = 0f,
                speedAccuracy = 0.1f,
                courseAccuracy = 1f
            )
        )

        assertEquals(2.21, metrics.timeToLineSeconds!!, 0.03)
    }

    @Test
    fun `estimator-rejected velocity cannot drive time to line`() {
        val metrics = calculateRegattaMetrics(
            boat,
            pin,
            fix(
                latitude = -0.0001,
                longitude = 0.0,
                velocityAccepted = false
            )
        )

        assertNotNull(metrics.signedDistanceToLineMeters)
        assertNull(metrics.timeToLineSeconds)
    }

    @Test
    fun `time to line is unavailable when not defensibly closing`() {
        val movingAway = calculateRegattaMetrics(
            boat,
            pin,
            fix(latitude = -0.0001, longitude = 0.0, courseDegrees = 180f)
        )
        val parallel = calculateRegattaMetrics(
            boat,
            pin,
            fix(latitude = -0.0001, longitude = 0.0, courseDegrees = 90f)
        )
        val uncertain = calculateRegattaMetrics(
            boat,
            pin,
            fix(
                latitude = -0.0001,
                longitude = 0.0,
                speedMetersPerSecond = 1f,
                courseDegrees = 0f,
                speedAccuracy = 0.6f,
                courseAccuracy = 10f
            )
        )
        val missingAccuracy = calculateRegattaMetrics(
            boat,
            pin,
            fix(
                latitude = -0.0001,
                longitude = 0.0,
                speedAccuracy = null,
                courseAccuracy = null
            )
        )
        val tooSlow = calculateRegattaMetrics(
            boat,
            pin,
            fix(
                latitude = -0.0001,
                longitude = 0.0,
                speedMetersPerSecond = 0.19f,
                speedAccuracy = 0.01f,
                courseAccuracy = 1f
            )
        )
        val uncertainSide = calculateRegattaMetrics(
            mark(latitude = 0.0, longitude = 0.0005, accuracy = 5f),
            mark(latitude = 0.0, longitude = -0.0005, accuracy = 5f),
            fix(
                latitude = -0.0001,
                longitude = 0.0,
                horizontalAccuracy = 5f
            )
        )

        assertNull(movingAway.timeToLineSeconds)
        assertNull(parallel.timeToLineSeconds)
        assertNull(uncertain.timeToLineSeconds)
        assertNull(missingAccuracy.timeToLineSeconds)
        assertNull(tooSlow.timeToLineSeconds)
        assertNotNull(uncertainSide.signedDistanceToLineMeters)
        assertNull(uncertainSide.timeToLineSeconds)
    }

    @Test
    fun `poor current accuracy makes both metrics unavailable`() {
        val metrics = calculateRegattaMetrics(
            boat,
            pin,
            fix(latitude = -0.0001, longitude = 0.0, horizontalAccuracy = 10.01f)
        )

        assertNull(metrics.signedDistanceToLineMeters)
        assertNull(metrics.timeToLineSeconds)
    }

    @Test
    fun `line shorter than endpoint uncertainty is unavailable`() {
        val closeBoat = mark(latitude = 0.0, longitude = 0.0, accuracy = 5f)
        val closePin = mark(latitude = 0.0, longitude = 0.00001, accuracy = 5f)

        val metrics = calculateRegattaMetrics(
            closeBoat,
            closePin,
            fix(latitude = -0.0001, longitude = 0.0)
        )

        assertNull(metrics.signedDistanceToLineMeters)
        assertNull(metrics.timeToLineSeconds)
    }

    @Test
    fun `endpoint-derived line angle uncertainty gates time to line`() {
        val current = fix(
            latitude = -0.0005,
            longitude = 0.0,
            horizontalAccuracy = 1f,
            courseDegrees = 45f
        )
        val accurateLine = calculateRegattaMetrics(
            mark(latitude = 0.0, longitude = 0.00015, accuracy = 1f),
            mark(latitude = 0.0, longitude = -0.00015, accuracy = 1f),
            current
        )
        val uncertainLine = calculateRegattaMetrics(
            mark(latitude = 0.0, longitude = 0.00015, accuracy = 10f),
            mark(latitude = 0.0, longitude = -0.00015, accuracy = 10f),
            current
        )

        assertNotNull(accurateLine.timeToLineSeconds)
        assertNotNull(uncertainLine.signedDistanceToLineMeters)
        assertNull(uncertainLine.timeToLineSeconds)
    }

    @Test
    fun `line must exceed two-sigma endpoint vector uncertainty`() {
        val metrics = calculateRegattaMetrics(
            mark(latitude = 0.0, longitude = 0.00005, accuracy = 10f),
            mark(latitude = 0.0, longitude = -0.00005, accuracy = 1f),
            fix(latitude = -0.0005, longitude = 0.0, horizontalAccuracy = 1f)
        )

        assertNull(metrics.signedDistanceToLineMeters)
        assertNull(metrics.timeToLineSeconds)
    }

    @Test
    fun `line just above minimum length needs finite two-sigma angle bound`() {
        val metrics = calculateRegattaMetrics(
            mark(latitude = 0.0, longitude = 0.000135, accuracy = 10f),
            mark(latitude = 0.0, longitude = -0.000135, accuracy = 10f),
            fix(
                latitude = -0.0005,
                longitude = 0.0,
                horizontalAccuracy = 1f,
                courseDegrees = 20f
            )
        )

        assertNotNull(metrics.signedDistanceToLineMeters)
        assertNull(metrics.timeToLineSeconds)
    }

    @Test
    fun `ordinary sailing approach produces time to line`() {
        val metrics = calculateRegattaMetrics(
            mark(latitude = 0.0, longitude = 0.000135, accuracy = 3f),
            mark(latitude = 0.0, longitude = -0.000135, accuracy = 3f),
            fix(
                latitude = -0.00018,
                longitude = 0.0,
                horizontalAccuracy = 3f,
                speedMetersPerSecond = 2f,
                courseDegrees = 0f,
                speedAccuracy = 0.3f,
                courseAccuracy = 10f
            )
        )

        assertEquals(10.0, metrics.timeToLineSeconds!!, 0.1)
    }

    @Test
    fun `local projection crosses the date line without a world-spanning line`() {
        val metrics = calculateRegattaMetrics(
            mark(0.0, 179.9995),
            mark(0.0, -179.9995),
            fix(latitude = 0.0001, longitude = 180.0)
        )

        assertNotNull(metrics.signedDistanceToLineMeters)
        assertTrue(kotlin.math.abs(metrics.signedDistanceToLineMeters!!) < 20.0)
    }

    private fun mark(
        latitude: Double,
        longitude: Double,
        accuracy: Float = 1f
    ) = RegattaMark(latitude, longitude, accuracy)

    private fun fix(
        latitude: Double,
        longitude: Double,
        horizontalAccuracy: Float = 1f,
        speedMetersPerSecond: Float = 5f,
        courseDegrees: Float = 0f,
        speedAccuracy: Float? = 0.1f,
        courseAccuracy: Float? = 1f,
        velocityAccepted: Boolean = true
    ) = PositionFix(
        latitudeDegrees = latitude,
        longitudeDegrees = longitude,
        courseOverGroundDegrees = courseDegrees,
        horizontalAccuracyMeters = horizontalAccuracy,
        timestampNanos = 1L,
        groundSpeedMetersPerSecond = speedMetersPerSecond,
        groundSpeedAccuracyMetersPerSecond = speedAccuracy,
        courseAccuracyDegrees = courseAccuracy,
        groundVelocityAccepted = velocityAccepted
    )
}
