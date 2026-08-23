package com.mightykatun.speedometer.app

import com.mightykatun.speedometer.app.domain.model.SpeedTrendSample
import com.mightykatun.speedometer.app.domain.model.SpeedUnit
import org.junit.Assert.assertEquals
import org.junit.Test

class AccuracyLevelTest {
    @Test
    fun `accuracy color bands use percentage of current speed`() {
        assertEquals(AccuracyLevel.GOOD, accuracyLevel(currentSpeed = 100f, accuracy = 10f))
        assertEquals(AccuracyLevel.FAIR, accuracyLevel(currentSpeed = 100f, accuracy = 20f))
        assertEquals(AccuracyLevel.POOR, accuracyLevel(currentSpeed = 100f, accuracy = 20.1f))
    }

    @Test
    fun `accuracy without positive current speed is poor`() {
        assertEquals(AccuracyLevel.POOR, accuracyLevel(currentSpeed = 0f, accuracy = 0.1f))
        assertEquals(AccuracyLevel.POOR, accuracyLevel(currentSpeed = null, accuracy = 1f))
    }

    @Test
    fun `trend description exposes direction and current endpoint`() {
        val samples = listOf(
            SpeedTrendSample(1_000_000_000L, 10f),
            SpeedTrendSample(2_000_000_000L, 15f)
        )

        assertEquals(
            "30 second speed trend, rising, latest 12.43 mph",
            speedTrendDescription(samples, currentSpeedKmh = 20f, SpeedUnit.MILES_PER_HOUR)
        )
    }

    @Test
    fun `trend description retains history when current speed is unavailable`() {
        val samples = listOf(
            SpeedTrendSample(1_000_000_000L, 15f),
            SpeedTrendSample(2_000_000_000L, 10f)
        )

        assertEquals(
            "30 second speed trend, falling, latest 10.00 km/h",
            speedTrendDescription(samples, currentSpeedKmh = null, SpeedUnit.KILOMETERS_PER_HOUR)
        )
    }
}
