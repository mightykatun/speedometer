package com.mightykatun.speedometer.app.domain

import com.mightykatun.speedometer.app.domain.model.EstimateQuality
import com.mightykatun.speedometer.app.domain.model.SessionConfig
import com.mightykatun.speedometer.app.domain.model.SpeedEstimate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class SessionStatisticsTrackerTest {
    private val timeProvider = mock<TimeProvider>()
    private val tracker = SessionStatisticsTracker(SessionConfig(), timeProvider)

    @Test
    fun `current speed is shown during warmup without updating maximum`() {
        whenever(timeProvider.currentTimeMillis()).thenReturn(0L, 1000L)
        tracker.startSession()
        tracker.updateSatelliteCount(5)

        val stats = tracker.updateSpeed(estimate(10.0, trusted = true, candidateTimestamp = 1_000_000_000L))

        assertEquals(36f, stats.currentSpeedKmh!!, 0.01f)
        assertEquals(0f, stats.maxSpeedKmh, 0.01f)
    }

    @Test
    fun `trusted speed updates maximum after warmup with enough satellites`() {
        whenever(timeProvider.currentTimeMillis()).thenReturn(0L, 6000L)
        tracker.startSession()
        tracker.updateSatelliteCount(3)

        val stats = tracker.updateSpeed(estimate(20.0, trusted = true))

        assertEquals(72f, stats.maxSpeedKmh, 0.01f)
    }

    @Test
    fun `maximum uses the raw GNSS candidate instead of fused display speed`() {
        whenever(timeProvider.currentTimeMillis()).thenReturn(0L, 6000L)
        tracker.startSession()
        tracker.updateSatelliteCount(5)

        val stats = tracker.updateSpeed(estimate(20.0, trusted = true, maximumCandidate = 18.0))

        assertEquals(72f, stats.currentSpeedKmh!!, 0.01f)
        assertEquals(64.8f, stats.maxSpeedKmh, 0.01f)
    }

    @Test
    fun `degraded estimate cannot update maximum`() {
        whenever(timeProvider.currentTimeMillis()).thenReturn(0L, 6000L)
        tracker.startSession()
        tracker.updateSatelliteCount(5)

        val stats = tracker.updateSpeed(estimate(20.0, trusted = false))

        assertEquals(72f, stats.currentSpeedKmh!!, 0.01f)
        assertEquals(0f, stats.maxSpeedKmh, 0.01f)
    }

    @Test
    fun `maximum candidate rejected before warmup is not admitted by a later tick`() {
        whenever(timeProvider.currentTimeMillis()).thenReturn(0L, 4900L, 5100L)
        tracker.startSession()
        tracker.updateSatelliteCount(5)
        val candidate = estimate(20.0, trusted = true, candidateTimestamp = 4_900_000_000L)

        tracker.updateSpeed(candidate)
        val stats = tracker.updateSpeed(candidate)

        assertEquals(0f, stats.maxSpeedKmh, 0.01f)
    }

    @Test
    fun `maximum speed requires minimum satellites on the originating fix`() {
        whenever(timeProvider.currentTimeMillis()).thenReturn(0L, 6000L)
        tracker.startSession()
        tracker.updateSatelliteCount(5)

        val stats = tracker.updateSpeed(estimate(20.0, trusted = true, candidateSatellites = 2))

        assertEquals(0f, stats.maxSpeedKmh, 0.01f)
    }

    @Test
    fun `satellite updates are independent from speed`() {
        whenever(timeProvider.currentTimeMillis()).thenReturn(0L)
        tracker.startSession()

        tracker.updateSatelliteCount(7)
        val stats = tracker.updateSatelliteCount(4)

        assertNull(stats.currentSpeedKmh)
        assertEquals(4, stats.currentSatellites)
        assertEquals(7, stats.maxSatellites)
    }

    @Test
    fun `unavailable estimate remains unavailable`() {
        whenever(timeProvider.currentTimeMillis()).thenReturn(0L, 6000L)
        tracker.startSession()

        val stats = tracker.updateSpeed(estimate(null, trusted = false))

        assertNull(stats.currentSpeedKmh)
        assertEquals(0f, stats.maxSpeedKmh, 0.01f)
    }

    private fun estimate(
        speed: Double?,
        trusted: Boolean,
        maximumCandidate: Double? = speed.takeIf { trusted },
        candidateTimestamp: Long = 6_000_000_000L,
        candidateSatellites: Int = 3
    ) = SpeedEstimate(
        speedMetersPerSecond = speed,
        uncertaintyMetersPerSecond = 0.2,
        quality = if (trusted) EstimateQuality.TRACKING else EstimateQuality.DEGRADED,
        trustedForMaximum = trusted,
        timestampNanos = candidateTimestamp,
        maximumCandidateMetersPerSecond = maximumCandidate,
        maximumCandidateTimestampNanos = candidateTimestamp.takeIf { maximumCandidate != null } ?: 0L,
        maximumCandidateSatelliteCount = candidateSatellites
    )
}
