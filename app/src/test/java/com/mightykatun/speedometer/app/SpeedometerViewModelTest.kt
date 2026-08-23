package com.mightykatun.speedometer.app

import com.mightykatun.speedometer.app.domain.SessionStatisticsTracker
import com.mightykatun.speedometer.app.domain.MonotonicClock
import com.mightykatun.speedometer.app.domain.model.EstimateQuality
import com.mightykatun.speedometer.app.domain.model.SessionConfig
import com.mightykatun.speedometer.app.domain.model.SpeedEstimate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun `uncertainty display updates at most once per second`() {
        whenever(clock.elapsedRealtimeMillis()).thenReturn(0L, 1000L, 1100L, 2000L)
        viewModel.onSessionStart()

        viewModel.onSpeedEstimateReceived(estimate(5.0, EstimateQuality.TRACKING, 0.2, 1_000_000_000L))
        viewModel.onSpeedEstimateReceived(estimate(5.0, EstimateQuality.TRACKING, 0.8, 1_500_000_000L))
        assertEquals(0.72f, viewModel.state.speedAccuracyKmh!!, 0.001f)

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

    private fun estimate(
        speed: Double?,
        quality: EstimateQuality,
        uncertainty: Double = 0.2,
        timestampNanos: Long = 1L
    ) = SpeedEstimate(
        speedMetersPerSecond = speed,
        uncertaintyMetersPerSecond = uncertainty,
        quality = quality,
        timestampNanos = timestampNanos
    )
}
