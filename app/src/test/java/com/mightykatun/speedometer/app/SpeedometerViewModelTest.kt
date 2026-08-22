package com.mightykatun.speedometer.app

import com.mightykatun.speedometer.app.domain.SessionStatisticsTracker
import com.mightykatun.speedometer.app.domain.TimeProvider
import com.mightykatun.speedometer.app.domain.model.EstimateQuality
import com.mightykatun.speedometer.app.domain.model.SessionConfig
import com.mightykatun.speedometer.app.domain.model.SpeedEstimate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class SpeedometerViewModelTest {
    private val timeProvider = mock<TimeProvider>()
    private val viewModel = SpeedometerViewModel(
        SessionStatisticsTracker(SessionConfig(), timeProvider)
    )

    @Test
    fun `low speed estimate remains visible`() {
        whenever(timeProvider.currentTimeMillis()).thenReturn(0L, 1000L)
        viewModel.onSessionStart()

        viewModel.onSpeedEstimateReceived(estimate(0.1, EstimateQuality.TRACKING))

        assertEquals(0.36f, viewModel.state.currentSpeedKmh!!, 0.001f)
    }

    @Test
    fun `unavailable estimate is not represented as zero`() {
        whenever(timeProvider.currentTimeMillis()).thenReturn(0L, 1000L)
        viewModel.onSessionStart()

        viewModel.onSpeedEstimateReceived(estimate(null, EstimateQuality.UNAVAILABLE))

        assertNull(viewModel.state.currentSpeedKmh)
        assertEquals(EstimateQuality.UNAVAILABLE, viewModel.state.estimateQuality)
    }

    @Test
    fun `generic estimator ticks do not clear GPS errors`() {
        whenever(timeProvider.currentTimeMillis()).thenReturn(0L, 1000L)
        viewModel.onSessionStart()
        viewModel.onError("gps provider disabled")

        viewModel.onSpeedEstimateReceived(estimate(null, EstimateQuality.ACQUIRING))

        assertEquals("gps provider disabled", viewModel.errorMessage)
        viewModel.onGpsAvailable()
        assertNull(viewModel.errorMessage)
    }

    private fun estimate(speed: Double?, quality: EstimateQuality) = SpeedEstimate(
        speedMetersPerSecond = speed,
        uncertaintyMetersPerSecond = 0.2,
        quality = quality,
        trustedForMaximum = quality == EstimateQuality.TRACKING,
        timestampNanos = 1L
    )
}
