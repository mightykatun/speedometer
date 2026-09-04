package com.mightykatun.speedometer.app.domain

import com.mightykatun.speedometer.app.domain.model.EstimateQuality
import com.mightykatun.speedometer.app.domain.model.GnssMeasurement
import com.mightykatun.speedometer.app.domain.model.MaximumCandidate
import com.mightykatun.speedometer.app.domain.model.MaximumCandidateChange
import com.mightykatun.speedometer.app.domain.model.SessionConfig
import com.mightykatun.speedometer.app.domain.model.SpeedEstimate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionStatisticsTrackerTest {
    private val clock = TestClock()
    private val tracker = SessionStatisticsTracker(SessionConfig(), clock)

    @Test
    fun `current speed is shown during first fix warmup without updating maximum`() {
        tracker.startSession()

        val stats = tracker.updateSpeed(update(10.0, seconds(10), upsert(1, 10.0, seconds(10))))

        assertEquals(36f, stats.currentSpeedKmh!!, 0.01f)
        assertEquals(0f, stats.maxSpeedKmh, 0.01f)
    }

    @Test
    fun `warmup starts at first accepted fix rather than activity start`() {
        tracker.startSession()
        tracker.updateSpeed(update(10.0, seconds(10)))

        val before = tracker.updateSpeed(update(20.0, seconds(10), upsert(1, 20.0, seconds(11) + 900_000_000L)))
        val atBoundary = tracker.updateSpeed(update(20.0, seconds(10), upsert(2, 20.0, seconds(12))))

        assertEquals(0f, before.maxSpeedKmh, 0.01f)
        assertEquals(72f, atBoundary.maxSpeedKmh, 0.01f)
    }

    @Test
    fun `retraction removes a provisional maximum`() {
        tracker.startSession()
        tracker.updateSpeed(update(20.0, seconds(1), upsert(1, 20.0, seconds(6))))

        val stats = tracker.updateSpeed(update(20.0, seconds(1), MaximumCandidateChange.Retract(1)))

        assertEquals(0f, stats.maxSpeedKmh, 0.01f)
    }

    @Test
    fun `replacement can lower a candidate without lowering an unrelated higher maximum`() {
        tracker.startSession()
        tracker.updateSpeed(
            update(
                30.0,
                seconds(1),
                upsert(1, 20.0, seconds(6)),
                upsert(2, 30.0, seconds(7))
            )
        )

        val stats = tracker.updateSpeed(update(20.0, seconds(1), upsert(1, 10.0, seconds(6))))

        assertEquals(108f, stats.maxSpeedKmh, 0.01f)
    }

    @Test
    fun `duplicate upsert is idempotent`() {
        tracker.startSession()
        val candidate = upsert(1, 20.0, seconds(6))
        tracker.updateSpeed(update(20.0, seconds(1), candidate))

        val stats = tracker.updateSpeed(update(20.0, seconds(1), candidate))

        assertEquals(72f, stats.maxSpeedKmh, 0.01f)
    }

    @Test
    fun `finalization preserves maximum after active candidate removal`() {
        tracker.startSession()
        val candidate = candidate(1, 20.0, seconds(6))
        tracker.updateSpeed(update(20.0, seconds(1), MaximumCandidateChange.Upsert(candidate)))

        val finalized = tracker.updateSpeed(
            update(20.0, seconds(1), MaximumCandidateChange.Finalize(candidate.id, candidate))
        )
        val afterRetraction = tracker.updateSpeed(
            update(10.0, seconds(1), MaximumCandidateChange.Retract(candidate.id))
        )

        assertEquals(72f, finalized.maxSpeedKmh, 0.01f)
        assertEquals(72f, afterRetraction.maxSpeedKmh, 0.01f)
    }

    @Test
    fun `finalizing an invalid replay result removes its former value`() {
        tracker.startSession()
        tracker.updateSpeed(update(20.0, seconds(1), upsert(1, 20.0, seconds(6))))

        val stats = tracker.updateSpeed(
            update(20.0, seconds(1), MaximumCandidateChange.Finalize(1, null))
        )

        assertEquals(0f, stats.maxSpeedKmh, 0.01f)
    }

    @Test
    fun `candidate requires satellites from its originating fix`() {
        tracker.startSession()

        val stats = tracker.updateSpeed(update(20.0, seconds(1), upsert(1, 20.0, seconds(6), satellites = 2)))

        assertEquals(0f, stats.maxSpeedKmh, 0.01f)
    }

    @Test
    fun `delayed and chronological mutations produce the same final maximum`() {
        fun result(changes: List<List<MaximumCandidateChange>>): Float {
            val local = SessionStatisticsTracker(SessionConfig(), TestClock())
            local.startSession()
            var maximum = 0f
            changes.forEach { batch ->
                maximum = local.updateSpeed(update(10.0, seconds(1), *batch.toTypedArray())).maxSpeedKmh
            }
            return maximum
        }
        val high = candidate(2, 30.0, seconds(7))
        val low = candidate(1, 20.0, seconds(6))

        val chronological = result(listOf(listOf(MaximumCandidateChange.Upsert(low)), listOf(MaximumCandidateChange.Upsert(high))))
        val delayed = result(
            listOf(
                listOf(MaximumCandidateChange.Upsert(high)),
                listOf(MaximumCandidateChange.Upsert(low)),
                listOf(MaximumCandidateChange.Retract(low.id))
            )
        )

        assertEquals(chronological, delayed, 0f)
    }

    @Test
    fun `estimator replay and statistics tracking produce chronological maximum`() {
        fun result(measurements: List<GnssMeasurement>): Float {
            val estimator = SpeedEstimator()
            val localTracker = SessionStatisticsTracker(
                SessionConfig(warmupPeriodMillis = 0L),
                TestClock()
            )
            localTracker.startSession()
            var maximum = 0f
            measurements.forEach { measurement ->
                maximum = localTracker.updateSpeed(
                    estimator.onGnssMeasurement(measurement)
                ).maxSpeedKmh
            }
            return maximum
        }
        val first = gnss(10.0, seconds(1))
        val delayed = gnss(20.0, seconds(2))
        val later = gnss(20.0, seconds(3))
        val last = gnss(10.0, seconds(4))

        val chronologicalMaximum = result(listOf(first, delayed, later, last))
        val replayedMaximum = result(listOf(first, later, last, delayed))

        assertEquals(chronologicalMaximum, replayedMaximum, 0f)
    }

    @Test
    fun `GNSS mode switch preserves an eligible maximum and rejects the first outlier`() {
        val estimator = SpeedEstimator()
        val localTracker = SessionStatisticsTracker(SessionConfig(), TestClock())
        localTracker.startSession()
        localTracker.updateSpeed(estimator.onGnssMeasurement(gnss(10.0, seconds(1))))
        val beforeSwitch = localTracker.updateSpeed(
            estimator.onGnssMeasurement(gnss(12.0, seconds(3)))
        )

        estimator.setTrackingMode(com.mightykatun.speedometer.app.domain.model.TrackingMode.FIXED)
        localTracker.updateSpeed(estimator.snapshotAt(seconds(3)))
        val afterOutlier = localTracker.updateSpeed(
            estimator.onGnssMeasurement(gnss(50.0, seconds(4)))
        )

        assertEquals(43.2f, beforeSwitch.maxSpeedKmh, 0.01f)
        assertEquals(beforeSwitch.maxSpeedKmh, afterOutlier.maxSpeedKmh, 0f)
    }

    @Test
    fun `GNSS mode switch preserves the original maximum warmup boundary`() {
        val estimator = SpeedEstimator()
        val localTracker = SessionStatisticsTracker(SessionConfig(), TestClock())
        localTracker.startSession()
        localTracker.updateSpeed(estimator.onGnssMeasurement(gnss(10.0, seconds(1))))
        localTracker.updateSpeed(estimator.onGnssMeasurement(gnss(11.0, seconds(2))))

        estimator.setTrackingMode(com.mightykatun.speedometer.app.domain.model.TrackingMode.FIXED)
        val duringWarmup = localTracker.updateSpeed(estimator.snapshotAt(seconds(2)))
        val atBoundary = localTracker.updateSpeed(
            estimator.onGnssMeasurement(gnss(12.0, seconds(3)))
        )

        assertEquals(0f, duringWarmup.maxSpeedKmh, 0f)
        assertEquals(43.2f, atBoundary.maxSpeedKmh, 0.01f)
    }

    @Test
    fun `satellite updates are independent from speed`() {
        tracker.startSession()

        tracker.updateSatelliteCount(7)
        val maxSatellites = tracker.updateSatelliteCount(4)

        assertEquals(7, maxSatellites)
    }

    @Test
    fun `stopping acquisition preserves eligible maxima across estimator epochs`() {
        tracker.startSession()
        tracker.updateSatelliteCount(7)
        tracker.updateSpeed(update(20.0, seconds(1), upsert(1, 20.0, seconds(4))))

        val stopped = tracker.stopAcquisition()
        val resumed = tracker.updateSpeed(
            update(10.0, seconds(20), upsert(2, 10.0, seconds(23)))
        )

        assertNull(stopped.currentSpeedKmh)
        assertEquals(72f, stopped.maxSpeedKmh, 0.01f)
        assertEquals(0, stopped.currentSatellites)
        assertEquals(7, stopped.maxSatellites)
        assertEquals(72f, resumed.maxSpeedKmh, 0.01f)
    }

    private fun update(
        speed: Double?,
        warmupStart: Long,
        vararg changes: MaximumCandidateChange
    ) = SpeedEstimate(
        speedMetersPerSecond = speed,
        uncertaintyMetersPerSecond = 0.2,
        quality = if (speed == null) EstimateQuality.UNAVAILABLE else EstimateQuality.TRACKING,
        timestampNanos = warmupStart,
        maximumWarmupStartTimestampNanos = warmupStart,
        maximumCandidateChanges = changes.toList()
    )

    private fun upsert(
        id: Long,
        speed: Double,
        timestamp: Long,
        satellites: Int = 3
    ) = MaximumCandidateChange.Upsert(candidate(id, speed, timestamp, satellites))

    private fun candidate(
        id: Long,
        speed: Double,
        timestamp: Long,
        satellites: Int = 3
    ) = MaximumCandidate(id, speed, timestamp, satellites)

    private fun gnss(speed: Double, timestamp: Long) = GnssMeasurement(
        speedMetersPerSecond = speed,
        speedAccuracyMetersPerSecond = 0.1,
        courseOverGroundDegrees = null,
        courseOverGroundAccuracyDegrees = null,
        horizontalAccuracyMeters = 1.0,
        magneticDeclinationDegrees = null,
        satelliteCount = 5,
        timestampNanos = timestamp
    )

    private fun seconds(value: Long): Long = value * 1_000_000_000L

    private class TestClock : MonotonicClock {
        override fun elapsedRealtimeMillis(): Long = 0L
    }
}
