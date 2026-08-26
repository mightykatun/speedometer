package com.mightykatun.speedometer.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RepositoryMappingTest {
    @Test
    fun `motion mapping transforms device acceleration into magnetic ENU`() {
        val measurement = createMotionMeasurement(
            rotationMatrix = floatArrayOf(
                0f, -1f, 0f,
                1f, 0f, 0f,
                0f, 0f, 1f
            ),
            orientation = floatArrayOf(0.1f, -0.2f, 0.3f),
            acceleration = floatArrayOf(2f, 3f, 4f),
            orientationReliable = false,
            timestampNanos = 20L,
            orientationTimestampNanos = 10L
        )

        assertEquals(-3.0, measurement.accelerationEastMetersPerSecondSquared, 0.0)
        assertEquals(2.0, measurement.accelerationMagneticNorthMetersPerSecondSquared, 0.0)
        assertEquals(4.0, measurement.accelerationUpMetersPerSecondSquared, 0.0)
        assertEquals(0.1, measurement.deviceYawRadians, 0.000001)
        assertEquals(-0.2, measurement.devicePitchRadians, 0.000001)
        assertEquals(0.3, measurement.deviceRollRadians, 0.000001)
        assertFalse(measurement.orientationReliable)
        assertEquals(20L, measurement.timestampNanos)
        assertEquals(10L, measurement.orientationTimestampNanos)
    }
}
