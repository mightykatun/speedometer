package com.mightykatun.speedometer.app

import com.mightykatun.speedometer.app.domain.SessionStatisticsTracker
import com.mightykatun.speedometer.app.domain.MonotonicClock
import com.mightykatun.speedometer.app.domain.model.EstimateQuality
import com.mightykatun.speedometer.app.domain.model.MaximumCandidate
import com.mightykatun.speedometer.app.domain.model.MaximumCandidateChange
import com.mightykatun.speedometer.app.domain.model.RefreshRate
import com.mightykatun.speedometer.app.domain.model.SessionConfig
import com.mightykatun.speedometer.app.domain.model.SpeedEstimate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class SpeedometerViewModelTest {
    private val clock = mock<MonotonicClock>()
    private val viewModel = SpeedometerViewModel(
        SessionStatisticsTracker(SessionConfig(), clock)
    )

    @Test
    fun `low speed estimate remains visible`() {
        whenever(clock.elapsedRealtimeMillis()).thenReturn(0L, 1000L)
        viewModel.onSessionStart()

        viewModel.onSpeedEstimateReceived(estimate(0.1, EstimateQuality.TRACKING))

        assertEquals(0.36f, viewModel.state.currentSpeedKmh!!, 0.001f)
    }

    @Test
    fun `unavailable estimate is not represented as zero`() {
        whenever(clock.elapsedRealtimeMillis()).thenReturn(0L, 1000L)
        viewModel.onSessionStart()

        viewModel.onSpeedEstimateReceived(estimate(null, EstimateQuality.UNAVAILABLE))

        assertNull(viewModel.state.currentSpeedKmh)
        assertEquals(EstimateQuality.UNAVAILABLE, viewModel.state.estimateQuality)
    }

    @Test
    fun `generic estimator ticks do not clear GPS errors`() {
        whenever(clock.elapsedRealtimeMillis()).thenReturn(0L, 1000L)
        viewModel.onSessionStart()
        viewModel.onGpsError("gps provider disabled")

        viewModel.onSpeedEstimateReceived(estimate(null, EstimateQuality.ACQUIRING))

        assertEquals("gps provider disabled", viewModel.errorMessage)
        viewModel.onGpsAvailable()
        assertNull(viewModel.errorMessage)
    }

    @Test
    fun `uncertainty remains paired with the current speed estimate`() {
        whenever(clock.elapsedRealtimeMillis()).thenReturn(0L, 1000L, 1100L, 2000L)
        viewModel.onSessionStart()

        viewModel.onSpeedEstimateReceived(estimate(5.0, EstimateQuality.TRACKING, 0.2, 1_000_000_000L))
        viewModel.onSpeedEstimateReceived(estimate(5.0, EstimateQuality.TRACKING, 0.8, 2_000_000_000L))
        assertEquals(2.88f, viewModel.state.speedAccuracyKmh!!, 0.001f)
    }

    @Test
    fun `first finite uncertainty is shown immediately after acquiring`() {
        whenever(clock.elapsedRealtimeMillis()).thenReturn(0L, 1000L, 1100L)
        viewModel.onSessionStart()
        viewModel.onSpeedEstimateReceived(
            estimate(null, EstimateQuality.ACQUIRING, Double.POSITIVE_INFINITY, 1_000_000_000L)
        )

        viewModel.onSpeedEstimateReceived(estimate(5.0, EstimateQuality.TRACKING, 0.2, 1_100_000_000L))

        assertEquals(0.72f, viewModel.state.speedAccuracyKmh!!, 0.001f)
    }

    @Test
    fun `repeated session start does not reset retained state`() {
        whenever(clock.elapsedRealtimeMillis()).thenReturn(0L)
        viewModel.onSessionStart()
        viewModel.onSpeedEstimateReceived(
            estimate(5.0, EstimateQuality.TRACKING, timestampNanos = 1_000_000_000L)
        )

        viewModel.onSessionStart()

        assertEquals(18f, viewModel.state.currentSpeedKmh!!, 0.001f)
    }

    @Test
    fun `speed trend retains graph targets within a bounded window and clears with the session`() {
        whenever(clock.elapsedRealtimeMillis()).thenReturn(0L)
        viewModel.onSessionStart()
        viewModel.onSpeedEstimateReceived(
            estimate(10.0, EstimateQuality.TRACKING, timestampNanos = 1_000_000_000L)
        )
        viewModel.onSpeedEstimateReceived(
            estimate(20.0, EstimateQuality.TRACKING, timestampNanos = 2_000_000_000L)
        )

        val trend = viewModel.state.speedTrend
        assertEquals(2, trend.size)
        assertEquals(36f, trend.first().speedKmh!!, 0.001f)
        assertEquals(72f, trend.last().speedKmh!!, 0.001f)

        for (index in 101..1_750) {
            viewModel.onSpeedEstimateReceived(
                estimate(
                    20.0,
                    EstimateQuality.TRACKING,
                    timestampNanos = index * 20_000_000L
                )
            )
        }
        assertTrue(viewModel.state.speedTrend.size <= 360)
        assertTrue(
            viewModel.state.speedTrend.first().timestampNanos >=
                viewModel.state.speedTrend.last().timestampNanos - 30_000_000_000L
        )
        assertTrue(
            viewModel.state.speedTrend.last().timestampNanos -
                viewModel.state.speedTrend.first().timestampNanos >= 29_000_000_000L
        )

        viewModel.onSessionReset()

        assertEquals(emptyList<Any>(), viewModel.state.speedTrend)
    }

    @Test
    fun `speed trend records availability transitions without sampling delay`() {
        whenever(clock.elapsedRealtimeMillis()).thenReturn(0L)
        viewModel.onSessionStart()
        viewModel.onSpeedEstimateReceived(
            estimate(10.0, EstimateQuality.TRACKING, timestampNanos = 1_000_000_000L)
        )
        viewModel.onSpeedEstimateReceived(
            estimate(null, EstimateQuality.UNAVAILABLE, timestampNanos = 1_050_000_000L)
        )
        viewModel.onSpeedEstimateReceived(
            estimate(11.0, EstimateQuality.TRACKING, timestampNanos = 1_075_000_000L)
        )

        assertEquals(listOf(36f, null, 39.6f), viewModel.state.speedTrend.map { it.speedKmh })
    }

    @Test
    fun `selected refresh publishes measurement and satellite state together`() {
        whenever(clock.elapsedRealtimeMillis()).thenReturn(0L)
        viewModel.onSessionStart()
        viewModel.onRefreshRateChanged(RefreshRate.HALF_SECOND)

        viewModel.onSpeedEstimateReceived(
            estimate(10.0, EstimateQuality.TRACKING, 0.2, 1_000_000_000L)
        )
        viewModel.onSatelliteCountReceived(5)
        viewModel.onSpeedEstimateReceived(
            estimate(20.0, EstimateQuality.TRACKING, 0.8, 1_400_000_000L)
        )

        assertEquals(36f, viewModel.state.currentSpeedKmh!!, 0.001f)
        assertEquals(0.72f, viewModel.state.speedAccuracyKmh!!, 0.001f)
        assertEquals(0, viewModel.state.satelliteCount)
        assertEquals(listOf(36f), viewModel.state.speedTrend.map { it.speedKmh })

        viewModel.onSpeedEstimateReceived(
            estimate(30.0, EstimateQuality.TRACKING, 0.4, 1_500_000_000L)
        )

        assertEquals(108f, viewModel.state.currentSpeedKmh!!, 0.001f)
        assertEquals(1.44f, viewModel.state.speedAccuracyKmh!!, 0.001f)
        assertEquals(5, viewModel.state.satelliteCount)
        assertEquals(5, viewModel.state.maxSatelliteCount)
        assertEquals(listOf(36f, 108f), viewModel.state.speedTrend.map { it.speedKmh })
    }

    @Test
    fun `throttled presentation does not drop maximum candidates`() {
        whenever(clock.elapsedRealtimeMillis()).thenReturn(0L)
        viewModel.onSessionStart()
        viewModel.onRefreshRateChanged(RefreshRate.HALF_SECOND)
        viewModel.onSpeedEstimateReceived(
            estimate(
                speed = 10.0,
                quality = EstimateQuality.TRACKING,
                timestampNanos = 10_000_000_000L,
                warmupStartTimestampNanos = 1_000_000_000L
            )
        )
        viewModel.onSpeedEstimateReceived(
            estimate(
                speed = 20.0,
                quality = EstimateQuality.TRACKING,
                timestampNanos = 10_400_000_000L,
                warmupStartTimestampNanos = 1_000_000_000L,
                candidateChanges = listOf(
                    MaximumCandidateChange.Upsert(
                        MaximumCandidate(1L, 50.0, 4_000_000_000L, 3)
                    )
                )
            )
        )

        assertEquals(0f, viewModel.state.maxSpeedKmh, 0.001f)

        viewModel.onSpeedEstimateReceived(
            estimate(
                speed = 30.0,
                quality = EstimateQuality.TRACKING,
                timestampNanos = 10_500_000_000L,
                warmupStartTimestampNanos = 1_000_000_000L
            )
        )

        assertEquals(180f, viewModel.state.maxSpeedKmh, 0.001f)
    }

    private fun estimate(
        speed: Double?,
        quality: EstimateQuality,
        uncertainty: Double = 0.2,
        timestampNanos: Long = 1L,
        warmupStartTimestampNanos: Long = 0L,
        candidateChanges: List<MaximumCandidateChange> = emptyList()
    ) = SpeedEstimate(
        speedMetersPerSecond = speed,
        uncertaintyMetersPerSecond = uncertainty,
        quality = quality,
        timestampNanos = timestampNanos,
        maximumWarmupStartTimestampNanos = warmupStartTimestampNanos,
        maximumCandidateChanges = candidateChanges
    )
}
