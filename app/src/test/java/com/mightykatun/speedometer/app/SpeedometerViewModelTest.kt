package com.mightykatun.speedometer.app

import com.mightykatun.speedometer.app.domain.SessionStatisticsTracker
import com.mightykatun.speedometer.app.domain.MonotonicClock
import com.mightykatun.speedometer.app.data.repository.RepositoryError
import com.mightykatun.speedometer.app.domain.model.EstimateQuality
import com.mightykatun.speedometer.app.domain.model.MaximumCandidate
import com.mightykatun.speedometer.app.domain.model.MaximumCandidateChange
import com.mightykatun.speedometer.app.domain.model.PositionFix
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
    fun `provider recovery unmasks only the correlated accepted estimate`() {
        whenever(clock.elapsedRealtimeMillis()).thenReturn(0L, 1000L)
        viewModel.onSessionStart()
        viewModel.onSatelliteCountReceived(6)
        viewModel.onSpeedEstimateReceived(
            estimate(12.0, EstimateQuality.TRACKING, timestampNanos = 10_000_000_000L)
        )

        viewModel.onRepositoryError(RepositoryError.GPS_PROVIDER_DISABLED)

        viewModel.onSpeedEstimateReceived(
            estimate(12.0, EstimateQuality.TRACKING, timestampNanos = 12_000_000_000L)
        )

        assertNull(viewModel.errorMessage)
        assertEquals("gps provider disabled", viewModel.signalMessage)
        assertNull(viewModel.state.currentSpeedKmh)
        assertEquals(EstimateQuality.UNAVAILABLE, viewModel.state.estimateQuality)
        assertEquals(0, viewModel.state.satelliteCount)

        viewModel.onRefreshRateChanged(RefreshRate.HALF_SECOND)
        assertNull(viewModel.state.currentSpeedKmh)

        viewModel.onGpsProviderEnabled()
        viewModel.onRefreshRateChanged(RefreshRate.TWO_SECONDS)
        assertNull(viewModel.state.currentSpeedKmh)
        assertNull(viewModel.signalMessage)
        assertNull(viewModel.state.currentSpeedKmh)

        viewModel.onSpeedEstimateReceived(
            estimate(12.0, EstimateQuality.TRACKING, timestampNanos = 13_000_000_000L)
        )
        assertNull(viewModel.state.currentSpeedKmh)
        viewModel.onGpsRecoveryAccepted()

        assertEquals(43.2f, viewModel.state.currentSpeedKmh!!, 0.001f)
    }

    @Test
    fun `satellite loss publishes immediately without clearing session maximum`() {
        whenever(clock.elapsedRealtimeMillis()).thenReturn(0L)
        viewModel.onSessionStart()
        viewModel.onRefreshRateChanged(RefreshRate.TWO_SECONDS)
        viewModel.onSatelliteCountReceived(6)
        viewModel.onSpeedEstimateReceived(
            estimate(12.0, EstimateQuality.TRACKING, timestampNanos = 1_000_000_000L)
        )
        assertEquals(6, viewModel.state.satelliteCount)

        viewModel.onSatelliteCountReceived(0)

        assertEquals(0, viewModel.state.satelliteCount)
        assertEquals(6, viewModel.state.maxSatelliteCount)
    }

    @Test
    fun `other GPS errors remain blocking errors`() {
        viewModel.onRepositoryError(RepositoryError.RETRYABLE_STARTUP_FAILURE)

        assertEquals("Unable to start GPS", viewModel.errorMessage)
        assertNull(viewModel.signalMessage)
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
    fun `stopping acquisition preserves session aggregates and starts a new trail segment`() {
        whenever(clock.elapsedRealtimeMillis()).thenReturn(0L)
        viewModel.onSessionStart()
        viewModel.onSatelliteCountReceived(7)
        viewModel.onSpeedEstimateReceived(
            estimate(
                speed = 20.0,
                quality = EstimateQuality.TRACKING,
                timestampNanos = 4_000_000_000L,
                warmupStartTimestampNanos = 1_000_000_000L,
                candidateChanges = listOf(
                    MaximumCandidateChange.Upsert(
                        MaximumCandidate(1L, 20.0, 4_000_000_000L, 7)
                    )
                )
            )
        )
        val first = position(10_000, latitude = 51.0)
        val second = position(11_000, latitude = 51.0001)
        viewModel.onPositionFixReceived(first)
        viewModel.onPositionFixReceived(second)

        viewModel.onAcquisitionStopped()

        assertNull(viewModel.state.currentSpeedKmh)
        assertNull(viewModel.state.speedAccuracyKmh)
        assertEquals(EstimateQuality.ACQUIRING, viewModel.state.estimateQuality)
        assertEquals(72f, viewModel.state.maxSpeedKmh, 0.001f)
        assertEquals(0, viewModel.state.satelliteCount)
        assertEquals(7, viewModel.state.maxSatelliteCount)
        assertTrue(viewModel.state.speedTrend.isEmpty())
        assertNull(viewModel.state.currentPosition)
        assertEquals(listOf(first, second), viewModel.state.positionTrail)

        viewModel.onSessionStart()
        val resumedFirst = position(20_000, latitude = 52.0)
        val resumedSecond = position(21_000, latitude = 52.0001)
        viewModel.onPositionFixReceived(resumedFirst)
        viewModel.onPositionFixReceived(resumedSecond)

        assertEquals(
            listOf(first, second, resumedFirst, resumedSecond),
            viewModel.state.positionTrail
        )
        assertEquals(
            listOf(resumedFirst.timestampNanos),
            viewModel.state.positionTrailSegmentStarts
        )
    }

    @Test
    fun `stopping acquisition retains the latest accepted trail endpoint`() {
        viewModel.onSessionStart()
        val first = position(1_000, latitude = 51.0)
        val nearby = position(2_000, latitude = 51.000005)
        viewModel.onPositionFixReceived(first)
        viewModel.onPositionFixReceived(nearby)
        assertEquals(listOf(first), viewModel.state.positionTrail)

        viewModel.onAcquisitionStopped()

        assertEquals(listOf(first, nearby), viewModel.state.positionTrail)
    }

    @Test
    fun `resumed trail rejects cached fixes from the previous acquisition`() {
        viewModel.onSessionStart()
        val first = position(10_000, latitude = 51.0)
        val previousEndpoint = position(11_000, latitude = 51.0001)
        viewModel.onPositionFixReceived(first)
        viewModel.onPositionFixReceived(previousEndpoint)
        viewModel.onAcquisitionStopped()

        viewModel.onPositionFixReceived(position(9_000, latitude = 40.0))
        viewModel.onPositionFixReceived(position(11_000, latitude = 40.0))
        val resumedFirst = position(20_000, latitude = 52.0)
        val resumedSecond = position(21_000, latitude = 52.0001)
        viewModel.onPositionFixReceived(resumedFirst)
        viewModel.onPositionFixReceived(resumedSecond)

        assertEquals(
            listOf(first, previousEndpoint, resumedFirst, resumedSecond),
            viewModel.state.positionTrail
        )
        assertEquals(
            listOf(resumedFirst.timestampNanos),
            viewModel.state.positionTrailSegmentStarts
        )
    }

    @Test
    fun `position trail follows refresh rate and clears with the session`() {
        viewModel.onSessionStart()
        val first = position(1_000, latitude = 51.0)
        val second = position(1_500, latitude = 51.0001)
        val third = position(2_000, latitude = 51.0002)
        val fourth = position(2_500, latitude = 51.0003)

        viewModel.onPositionFixReceived(first)
        assertNull(viewModel.state.currentPosition)

        viewModel.onPositionFixReceived(second)
        assertEquals(second, viewModel.state.currentPosition)

        viewModel.onPositionFixReceived(third)
        assertEquals(second, viewModel.state.currentPosition)

        viewModel.onPositionFixReceived(fourth)

        assertEquals(fourth, viewModel.state.currentPosition)
        assertEquals(listOf(first, second, third, fourth), viewModel.state.positionTrail)

        viewModel.onSessionReset()

        assertNull(viewModel.state.currentPosition)
        assertTrue(viewModel.state.positionTrail.isEmpty())
        assertTrue(viewModel.state.positionTrailSegmentStarts.isEmpty())
    }

    @Test
    fun `position trail rejects poor and out-of-order fixes without recording jitter`() {
        viewModel.onSessionStart()
        val first = position(1_000, latitude = 51.0)
        val nearby = position(2_000, latitude = 51.000005)

        viewModel.onPositionFixReceived(first)
        viewModel.onPositionFixReceived(nearby)
        viewModel.onPositionFixReceived(
            position(3_000, latitude = 52.0, horizontalAccuracyMeters = 101f)
        )
        viewModel.onPositionFixReceived(position(500, latitude = 50.0))

        assertEquals(nearby, viewModel.state.currentPosition)
        assertEquals(listOf(first), viewModel.state.positionTrail)
    }

    @Test
    fun `position trail export snapshot includes the freshest accepted fix`() {
        viewModel.onSessionStart()
        val first = position(1_000, latitude = 51.0)
        val nearby = position(2_000, latitude = 51.000005)
        viewModel.onPositionFixReceived(first)
        viewModel.onPositionFixReceived(nearby)

        val snapshot = viewModel.positionTrailSnapshot()

        assertEquals(listOf(first, nearby), snapshot.points)
    }

    @Test
    fun `position trail replaces an uncorroborated startup outlier`() {
        viewModel.onSessionStart()
        viewModel.onPositionFixReceived(position(1_000, latitude = 10.0))
        val firstReliable = position(2_000, latitude = 51.0)
        val secondReliable = position(3_000, latitude = 51.0001)

        viewModel.onPositionFixReceived(firstReliable)
        viewModel.onPositionFixReceived(secondReliable)

        assertEquals(secondReliable, viewModel.state.currentPosition)
        assertEquals(listOf(firstReliable, secondReliable), viewModel.state.positionTrail)
    }

    @Test
    fun `equal-timestamp position outlier cannot replace the current fix`() {
        viewModel.onSessionStart()
        val first = position(1_000, latitude = 51.0)
        val current = position(2_000, latitude = 51.0001)
        viewModel.onPositionFixReceived(first)
        viewModel.onPositionFixReceived(current)

        viewModel.onPositionFixReceived(position(2_000, latitude = 10.0))

        assertEquals(current, viewModel.state.currentPosition)
        assertEquals(listOf(first, current), viewModel.state.positionTrail)
    }

    @Test
    fun `long position trail stays bounded while retaining its endpoints`() {
        viewModel.onSessionStart()

        for (index in 1..2_100) {
            viewModel.onPositionFixReceived(
                position(
                    milliseconds = index * 1_000L,
                    latitude = 40.0 + index * 0.0001
                )
            )
        }

        assertTrue(viewModel.state.positionTrail.size <= 2_048)
        assertEquals(1_000_000_000L, viewModel.state.positionTrail.first().timestampNanos)
        assertEquals(2_100_000_000_000L, viewModel.state.positionTrail.last().timestampNanos)
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

    private fun position(
        milliseconds: Long,
        latitude: Double,
        longitude: Double = 4.0,
        horizontalAccuracyMeters: Float = 5f
    ) = PositionFix(
        latitudeDegrees = latitude,
        longitudeDegrees = longitude,
        headingDegrees = 0f,
        horizontalAccuracyMeters = horizontalAccuracyMeters,
        timestampNanos = milliseconds * 1_000_000L
    )
}
