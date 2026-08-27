package com.mightykatun.speedometer.app

import com.mightykatun.speedometer.app.domain.model.SpeedTrendSample
import com.mightykatun.speedometer.app.domain.model.SpeedUnit
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI

class AccuracyLevelTest {
    @Test
    fun `low speeds receive more lenient relative uncertainty bands`() {
        assertEquals(AccuracyLevel.GOOD, accuracyLevel(1f, 0.15f))
        assertEquals(AccuracyLevel.POOR, accuracyLevel(10f, 1.5f))
    }

    @Test
    fun `accuracy color bands include their dynamic boundaries`() {
        val speed = 10f
        val green = greenUncertaintyThreshold(speed.toDouble()).toFloat()
        val orange = orangeUncertaintyThreshold(speed.toDouble()).toFloat()

        assertEquals(AccuracyLevel.GOOD, accuracyLevel(speed, speed * (green - 0.001f) / 100f))
        assertEquals(AccuracyLevel.FAIR, accuracyLevel(speed, speed * (green + 0.001f) / 100f))
        assertEquals(AccuracyLevel.FAIR, accuracyLevel(speed, speed * (orange - 0.001f) / 100f))
        assertEquals(AccuracyLevel.POOR, accuracyLevel(speed, speed * (orange + 0.001f) / 100f))
    }

    @Test
    fun `uncertainty curves have the confirmed endpoints and asymptotes`() {
        assertEquals(30.0, greenUncertaintyThreshold(0.0), 0.0)
        assertEquals(40.0, orangeUncertaintyThreshold(0.0), 0.0)
        assertEquals(
            10.0 - 5.0 * PI / 2.0,
            greenUncertaintyThreshold(Double.POSITIVE_INFINITY),
            1e-12
        )
        assertEquals(
            20.0 - 5.0 * PI,
            orangeUncertaintyThreshold(Double.POSITIVE_INFINITY),
            1e-12
        )
    }

    @Test
    fun `accuracy without positive current speed is poor`() {
        assertEquals(AccuracyLevel.POOR, accuracyLevel(0f, 0.1f))
        assertEquals(AccuracyLevel.POOR, accuracyLevel(null, 1f))
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
