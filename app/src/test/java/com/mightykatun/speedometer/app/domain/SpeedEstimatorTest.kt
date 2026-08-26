package com.mightykatun.speedometer.app.domain

import com.mightykatun.speedometer.app.domain.model.EstimateQuality
import com.mightykatun.speedometer.app.domain.model.GnssMeasurement
import com.mightykatun.speedometer.app.domain.model.MaximumCandidateChange
import com.mightykatun.speedometer.app.domain.model.MotionMeasurement
import com.mightykatun.speedometer.app.domain.model.SpeedEstimatorConfig
import com.mightykatun.speedometer.app.domain.model.TrackingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedEstimatorTest {
    @Test
    fun `GNSS ingestion reports only accepted correction timestamps`() {
        val estimator = SpeedEstimator()

        assertEquals(seconds(1), estimator.ingestGnssMeasurement(gnss(10.0, 0.1, seconds(1))))
        assertNull(estimator.ingestGnssMeasurement(gnss(10.0, 0.1, seconds(1))))
        assertNull(estimator.ingestGnssMeasurement(gnss(10.0, 3.0, seconds(2))))
        assertNull(estimator.ingestGnssMeasurement(gnss(null, 0.1, seconds(3))))
    }

    @Test
    fun `accepted delayed GNSS reports its measurement timestamp`() {
        val estimator = SpeedEstimator()
        estimator.ingestGnssMeasurement(gnss(10.0, 0.1, seconds(1)))
        estimator.ingestGnssMeasurement(gnss(10.0, 0.1, seconds(3)))

        assertEquals(seconds(2), estimator.ingestGnssMeasurement(gnss(10.0, 0.1, seconds(2))))
    }

    @Test
    fun `delayed replay reports a later correction that becomes accepted`() {
        val estimator = SpeedEstimator()
        estimator.ingestGnssMeasurement(gnss(10.0, 0.1, seconds(10)))
        assertNull(estimator.ingestGnssMeasurement(gnss(20.0, 0.1, seconds(12))))

        val acceptedTimestamp = estimator.ingestGnssMeasurement(
            gnss(20.0, 0.1, seconds(11))
        )

        assertEquals(seconds(12), acceptedTimestamp)
    }

    @Test
    fun `valid low speeds remain visible without a floor`() {
        listOf(0.01, 0.1, 0.2, 0.5, 1.0).forEach { speed ->
            val estimate = SpeedEstimator().onGnssMeasurement(gnss(speed, 0.1, seconds(1)))
            val displayedSpeed = estimate.speedMetersPerSecond!!

            assertEquals(speed, displayedSpeed, 0.0001)
            assertTrue(displayedSpeed > 0.0)
        }
    }

    @Test
    fun `horizontal position accuracy does not reject speed`() {
        val estimate = SpeedEstimator().onGnssMeasurement(
            gnss(speed = 12.0, sigma = 0.2, time = seconds(1), horizontalAccuracy = 500.0)
        )

        assertEquals(12.0, estimate.speedMetersPerSecond!!, 0.0001)
    }

    @Test
    fun `missing invalid and negative speeds do not initialize`() {
        val estimator = SpeedEstimator()

        assertNull(estimator.onGnssMeasurement(gnss(null, 0.2, seconds(1))).speedMetersPerSecond)
        assertNull(estimator.onGnssMeasurement(gnss(Double.NaN, 0.2, seconds(2))).speedMetersPerSecond)
        assertNull(estimator.onGnssMeasurement(gnss(-1.0, 0.2, seconds(3))).speedMetersPerSecond)
    }

    @Test
    fun `poor speed uncertainty preserves the prior estimate`() {
        val estimator = SpeedEstimator()
        estimator.onGnssMeasurement(gnss(8.0, 0.2, seconds(1)))

        val estimate = estimator.onGnssMeasurement(gnss(20.0, 4.0, seconds(2)))

        assertEquals(8.0, estimate.speedMetersPerSecond!!, 0.0001)
    }

    @Test
    fun `isolated implausible speed is rejected`() {
        val estimator = SpeedEstimator()
        estimator.onGnssMeasurement(gnss(5.0, 0.1, seconds(1)))

        val estimate = estimator.onGnssMeasurement(gnss(30.0, 0.1, seconds(2)))

        assertTrue(estimate.speedMetersPerSecond!! < 6.0)
    }

    @Test
    fun `two consistent high quality fixes reacquire after a jump`() {
        val estimator = SpeedEstimator()
        estimator.onGnssMeasurement(gnss(5.0, 0.1, seconds(1)))
        estimator.onGnssMeasurement(gnss(30.0, 0.1, seconds(2)))

        val estimate = estimator.onGnssMeasurement(gnss(30.1, 0.1, seconds(3)))

        assertEquals(30.0, estimate.speedMetersPerSecond!!, 0.2)
        assertTrue(estimate.maximumCandidateChanges.isEmpty())

        val confirmed = estimator.onGnssMeasurement(gnss(30.0, 0.1, seconds(4)))

        val candidate = confirmed.maximumCandidateChanges.single() as
            com.mightykatun.speedometer.app.domain.model.MaximumCandidateChange.Upsert
        assertEquals(30.0, candidate.candidate.speedMetersPerSecond, 0.0001)
    }

    @Test
    fun `invalid fix breaks a reacquisition sequence`() {
        val estimator = SpeedEstimator()
        estimator.onGnssMeasurement(gnss(5.0, 0.1, seconds(1)))
        estimator.onGnssMeasurement(gnss(30.0, 0.1, seconds(1) + 500_000_000L))
        estimator.onGnssMeasurement(gnss(null, 0.1, seconds(2)))

        val estimate = estimator.onGnssMeasurement(gnss(30.0, 0.1, seconds(2) + 500_000_000L))

        assertTrue(estimate.speedMetersPerSecond!! < 10.0)
    }

    @Test
    fun `normal GNSS interval stays tracking before uncertainty degrades`() {
        val estimator = SpeedEstimator()
        val initial = estimator.onGnssMeasurement(gnss(10.0, 0.1, seconds(1)))

        val normalInterval = estimator.estimateAt(seconds(2))
        val delayedInterval = estimator.estimateAt(seconds(3))

        assertTrue(normalInterval.uncertaintyMetersPerSecond > initial.uncertaintyMetersPerSecond)
        assertEquals(EstimateQuality.TRACKING, normalInterval.quality)
        assertEquals(EstimateQuality.DEGRADED, delayedInterval.quality)
    }

    @Test
    fun `display quality does not relax maximum speed trust`() {
        val estimator = SpeedEstimator()
        val gnssEstimate = estimator.onGnssMeasurement(gnss(10.0, 0.1, seconds(1)))

        val prediction = estimator.estimateAt(seconds(2))

        assertTrue(gnssEstimate.maximumCandidateChanges.isNotEmpty())
        assertEquals(EstimateQuality.TRACKING, prediction.quality)
        assertTrue(prediction.maximumCandidateChanges.isEmpty())
    }

    @Test
    fun `inertial prediction does not expose a maximum candidate`() {
        val estimator = fixedEstimatorWithSeed()
        estimator.onMotionMeasurement(motion(1.0, seconds(1) + 20_000_000L))
        estimator.onMotionMeasurement(motion(1.0, seconds(1) + 40_000_000L))

        estimator.onMotionMeasurement(motion(1.0, seconds(1) + 60_000_000L))
        val estimate = estimator.snapshotAt(seconds(1) + 60_000_000L)

        assertTrue(estimate.speedMetersPerSecond!! > 10.0)
        assertTrue(estimate.maximumCandidateChanges.isEmpty())
    }

    @Test
    fun `handheld mode ignores acceleration`() {
        val estimator = SpeedEstimator()
        estimator.onGnssMeasurement(gnss(10.0, 0.1, seconds(1)))

        estimator.onMotionMeasurement(motion(5.0, seconds(2)))
        val estimate = estimator.estimateAt(seconds(2))

        assertEquals(10.0, estimate.speedMetersPerSecond!!, 0.0001)
    }

    @Test
    fun `fixed mode integrates acceleration only with a valid course`() {
        val estimator = SpeedEstimator()
        estimator.setTrackingMode(TrackingMode.FIXED)
        estimator.onMotionMeasurement(motion(0.0, seconds(1)))
        estimator.onGnssMeasurement(gnss(10.0, 0.1, seconds(1) + 10_000_000L, bearing = 0.0))

        estimator.onMotionMeasurement(motion(1.0, seconds(1) + 110_000_000L))
        estimator.onMotionMeasurement(motion(1.0, seconds(1) + 210_000_000L))
        estimator.onMotionMeasurement(motion(1.0, seconds(1) + 310_000_000L))
        val estimate = estimator.snapshotAt(seconds(1) + 310_000_000L)

        assertTrue(estimate.speedMetersPerSecond!! > 10.05)
    }

    @Test
    fun `GNSS corrections learn fixed-mode acceleration bias`() {
        val estimator = fixedEstimatorWithSeed()
        for (step in 1..100) {
            val timestamp = seconds(1) + step * 100_000_000L
            estimator.onMotionMeasurement(motion(0.2, timestamp))
            if (step % 10 == 0) {
                estimator.onGnssMeasurement(gnss(10.0, 0.1, timestamp, bearing = 0.0))
            }
        }

        for (step in 101..120) {
            estimator.onMotionMeasurement(motion(0.2, seconds(1) + step * 100_000_000L))
        }
        val estimate = estimator.estimateAt(seconds(13))

        assertEquals(10.0, estimate.speedMetersPerSecond!!, 0.35)
    }

    @Test
    fun `fixed mode falls back when course expires`() {
        val estimator = SpeedEstimator()
        estimator.setTrackingMode(TrackingMode.FIXED)
        estimator.onMotionMeasurement(motion(0.0, seconds(1)))
        estimator.onGnssMeasurement(gnss(10.0, 0.1, seconds(1), bearing = 0.0))

        estimator.onMotionMeasurement(motion(5.0, seconds(5)))
        val estimate = estimator.estimateAt(seconds(5))

        assertNull(estimate.speedMetersPerSecond)
        assertEquals(EstimateQuality.UNAVAILABLE, estimate.quality)
    }

    @Test
    fun `fixed mode invalidates stale course from an unusable fix`() {
        val estimator = fixedEstimatorWithSeed()
        estimator.onGnssMeasurement(gnss(1.0, 0.1, seconds(2), bearing = null))

        estimator.onMotionMeasurement(motion(-10.0, seconds(2) + 100_000_000L))
        val estimate = estimator.snapshotAt(seconds(2) + 100_000_000L)

        assertEquals(10.0, estimate.speedMetersPerSecond!!, 0.0001)
    }

    @Test
    fun `fixed mode inertial drift cannot force a moving estimate to zero`() {
        val estimator = fixedEstimatorWithSeed()
        for (step in 1..10) {
            estimator.onMotionMeasurement(motion(-15.0, seconds(1) + step * 100_000_000L))
        }

        val estimate = estimator.estimateAt(seconds(2))

        assertTrue(estimate.speedMetersPerSecond!! >= 5.0)
    }

    @Test
    fun `large GNSS correction requires corroboration after suspect motion`() {
        val estimator = fixedEstimatorWithSeed()
        for (step in 1..10) {
            estimator.onMotionMeasurement(motion(-15.0, seconds(1) + step * 100_000_000L))
        }

        val first = estimator.onGnssMeasurement(gnss(0.0, 0.1, seconds(2) + 10_000_000L))
        val estimate = estimator.onGnssMeasurement(gnss(0.0, 0.1, seconds(2) + 510_000_000L))

        assertTrue(first.speedMetersPerSecond!! > 5.0)
        assertTrue(estimate.speedMetersPerSecond!! < 0.1)
    }

    @Test
    fun `delayed GNSS replay matches chronological processing`() {
        val chronological = fixedEstimatorWithSeed()
        chronological.onMotionMeasurement(motion(1.0, seconds(1) + 100_000_000L))
        chronological.onGnssMeasurement(gnss(10.1, 0.2, seconds(1) + 150_000_000L, bearing = 0.0))
        chronological.onMotionMeasurement(motion(1.0, seconds(1) + 200_000_000L))

        val delayed = fixedEstimatorWithSeed()
        delayed.onMotionMeasurement(motion(1.0, seconds(1) + 100_000_000L))
        delayed.onMotionMeasurement(motion(1.0, seconds(1) + 200_000_000L))
        delayed.onGnssMeasurement(gnss(10.1, 0.2, seconds(1) + 150_000_000L, bearing = 0.0))

        assertEquals(
            chronological.estimateAt(seconds(1) + 200_000_000L).speedMetersPerSecond!!,
            delayed.estimateAt(seconds(1) + 200_000_000L).speedMetersPerSecond!!,
            1e-9
        )
    }

    @Test
    fun `delayed GNSS retracts a later candidate invalidated by replay`() {
        val estimator = SpeedEstimator()
        estimator.onGnssMeasurement(gnss(10.0, 0.1, seconds(1)))
        estimator.onGnssMeasurement(gnss(20.0, 0.1, seconds(3)))
        val acceptedBeforeReplay = estimator.onGnssMeasurement(gnss(10.0, 0.1, seconds(4)))

        val replayed = estimator.onGnssMeasurement(gnss(20.0, 0.1, seconds(2)))

        assertTrue(
            acceptedBeforeReplay.maximumCandidateChanges.any {
                it is MaximumCandidateChange.Upsert && it.id == seconds(4)
            }
        )
        assertTrue(
            replayed.maximumCandidateChanges.any {
                it is MaximumCandidateChange.Retract && it.id == seconds(4)
            }
        )
    }

    @Test
    fun `candidate finalizes at replay boundary and cannot be changed afterward`() {
        val estimator = SpeedEstimator(
            SpeedEstimatorConfig(
                replayHistoryNanos = seconds(1),
                maximumDelayedGnssNanos = seconds(1)
            )
        )
        estimator.onGnssMeasurement(gnss(10.0, 0.1, seconds(1)))

        estimator.ingestMotionMeasurement(motion(0.0, seconds(2) + 100_000_000L))
        val finalized = estimator.snapshotAt(seconds(2) + 100_000_000L)
        estimator.ingestGnssMeasurement(gnss(30.0, 0.1, seconds(1)))
        val afterLateDuplicate = estimator.snapshotAt(seconds(2) + 100_000_000L)

        val change = finalized.maximumCandidateChanges.single {
            it is MaximumCandidateChange.Finalize && it.id == seconds(1)
        } as MaximumCandidateChange.Finalize
        assertEquals(10.0, change.candidate!!.speedMetersPerSecond, 0.0)
        assertTrue(afterLateDuplicate.maximumCandidateChanges.isEmpty())
    }

    @Test
    fun `duplicate motion is idempotent for filters covariance and bias`() {
        fun result(duplicate: Boolean): Pair<Double, Double> {
            val estimator = fixedEstimatorWithSeed()
            val sample = motion(0.3, seconds(1) + 20_000_000L)
            estimator.ingestMotionMeasurement(sample)
            if (duplicate) estimator.ingestMotionMeasurement(sample)
            estimator.ingestMotionMeasurement(motion(0.3, seconds(1) + 40_000_000L))
            estimator.ingestMotionMeasurement(motion(0.3, seconds(1) + 60_000_000L))
            val estimate = estimator.snapshotAt(seconds(1) + 60_000_000L)
            return estimate.speedMetersPerSecond!! to estimate.uncertaintyMetersPerSecond
        }

        val once = result(duplicate = false)
        val twice = result(duplicate = true)

        assertEquals(once.first, twice.first, 0.0)
        assertEquals(once.second, twice.second, 0.0)
    }

    @Test
    fun `distinct motion samples may share one orientation identity`() {
        val estimator = fixedEstimatorWithSeed()
        val orientationTime = seconds(1) + 10_000_000L

        estimator.ingestMotionMeasurement(
            motion(1.0, seconds(1) + 20_000_000L, orientationTimestampNanos = orientationTime)
        )
        estimator.ingestMotionMeasurement(
            motion(1.0, seconds(1) + 40_000_000L, orientationTimestampNanos = orientationTime)
        )
        estimator.ingestMotionMeasurement(
            motion(1.0, seconds(1) + 60_000_000L, orientationTimestampNanos = orientationTime)
        )

        assertTrue(estimator.snapshotAt(seconds(1) + 60_000_000L).speedMetersPerSecond!! > 10.0)
    }

    @Test
    fun `delayed first accepted fix deterministically moves warmup anchor`() {
        val estimator = SpeedEstimator()
        val initiallyFirst = estimator.onGnssMeasurement(gnss(10.0, 0.1, seconds(3)))

        val replayed = estimator.onGnssMeasurement(gnss(10.0, 0.1, seconds(1)))

        assertEquals(seconds(3), initiallyFirst.maximumWarmupStartTimestampNanos)
        assertEquals(seconds(1), replayed.maximumWarmupStartTimestampNanos)
    }

    @Test
    fun `duplicate fix timestamp has no effect`() {
        val estimator = SpeedEstimator()
        estimator.onGnssMeasurement(gnss(4.0, 0.1, seconds(1)))

        val estimate = estimator.onGnssMeasurement(gnss(20.0, 0.1, seconds(1)))

        assertEquals(4.0, estimate.speedMetersPerSecond!!, 0.0001)
    }

    @Test
    fun `GNSS mode switch preserves filtered state`() {
        val estimator = SpeedEstimator()
        estimator.onGnssMeasurement(gnss(4.0, 0.1, seconds(1)))

        estimator.setTrackingMode(TrackingMode.FIXED)

        assertEquals(4.0, estimator.estimateAt(seconds(1)).speedMetersPerSecond!!, 0.0001)
        assertEquals(EstimateQuality.TRACKING, estimator.estimateAt(seconds(1)).quality)
    }

    @Test
    fun `GNSS mode switch does not trust first outlier as maximum`() {
        val estimator = SpeedEstimator()
        estimator.onGnssMeasurement(gnss(10.0, 0.1, seconds(1)))
        estimator.setTrackingMode(TrackingMode.FIXED)

        val estimate = estimator.onGnssMeasurement(gnss(50.0, 0.1, seconds(2)))

        assertEquals(10.0, estimate.speedMetersPerSecond!!, 0.1)
        assertTrue(
            estimate.maximumCandidateChanges.none {
                it is MaximumCandidateChange.Upsert && it.candidate.speedMetersPerSecond == 50.0
            }
        )
    }

    @Test
    fun `stationary state requires near-zero evidence and does not hide a crawl`() {
        val crawl = SpeedEstimator()
        crawl.onGnssMeasurement(gnss(0.1, 0.1, seconds(1)))
        crawl.onGnssMeasurement(gnss(0.1, 0.1, seconds(2)))
        val crawlEstimate = crawl.onGnssMeasurement(gnss(0.1, 0.1, seconds(3)))

        val stopped = SpeedEstimator()
        stopped.onGnssMeasurement(gnss(0.0, 0.1, seconds(1)))
        stopped.onGnssMeasurement(gnss(0.0, 0.1, seconds(2)))
        val stoppedEstimate = stopped.onGnssMeasurement(gnss(0.0, 0.1, seconds(3)))

        assertTrue(crawlEstimate.speedMetersPerSecond!! > 0.05)
        assertEquals(0.0, stoppedEstimate.speedMetersPerSecond!!, 0.0)
    }

    @Test
    fun `accepted crawl exits a previously confirmed stop`() {
        val estimator = SpeedEstimator()
        estimator.onGnssMeasurement(gnss(0.0, 0.1, seconds(1)))
        estimator.onGnssMeasurement(gnss(0.0, 0.1, seconds(2)))
        estimator.onGnssMeasurement(gnss(0.0, 0.1, seconds(3)))

        val estimate = estimator.onGnssMeasurement(gnss(0.1, 0.1, seconds(4)))

        assertTrue(estimate.speedMetersPerSecond!! > 0.0)
    }

    @Test
    fun `large launch from a confirmed stop requires corroboration`() {
        val estimator = SpeedEstimator()
        estimator.onGnssMeasurement(gnss(0.0, 0.1, seconds(1)))
        estimator.onGnssMeasurement(gnss(0.0, 0.1, seconds(2)))
        estimator.onGnssMeasurement(gnss(0.0, 0.1, seconds(3)))

        val first = estimator.onGnssMeasurement(gnss(10.0, 0.2, seconds(4)))
        val estimate = estimator.onGnssMeasurement(gnss(10.0, 0.2, seconds(5)))

        assertEquals(0.0, first.speedMetersPerSecond!!, 0.0)
        assertEquals(10.0, estimate.speedMetersPerSecond!!, 0.0001)
    }

    @Test
    fun `isolated speed spike does not exit a confirmed stop`() {
        val estimator = SpeedEstimator()
        estimator.onGnssMeasurement(gnss(0.0, 0.1, seconds(1)))
        estimator.onGnssMeasurement(gnss(0.0, 0.1, seconds(2)))
        estimator.onGnssMeasurement(gnss(0.0, 0.1, seconds(3)))

        val estimate = estimator.onGnssMeasurement(gnss(20.0, 0.1, seconds(4)))

        assertEquals(0.0, estimate.speedMetersPerSecond!!, 0.0)
        assertTrue(estimate.maximumCandidateChanges.isEmpty())
    }

    @Test
    fun `median filter removes a single acceleration spike`() {
        val estimator = fixedEstimatorWithSeed()
        estimator.onMotionMeasurement(motion(0.0, seconds(1) + 20_000_000L))
        estimator.onMotionMeasurement(motion(8.0, seconds(1) + 40_000_000L))
        estimator.onMotionMeasurement(motion(0.0, seconds(1) + 60_000_000L))
        val estimate = estimator.snapshotAt(seconds(1) + 60_000_000L)

        assertEquals(10.0, estimate.speedMetersPerSecond!!, 0.01)
    }

    @Test
    fun `violent handling quarantines inertial prediction after course is reacquired`() {
        val estimator = fixedEstimatorWithSeed()
        estimator.onMotionMeasurement(motion(20.0, seconds(1) + 20_000_000L))
        estimator.onMotionMeasurement(motion(0.0, seconds(1) + 100_000_000L))
        estimator.onGnssMeasurement(gnss(10.0, 0.1, seconds(1) + 110_000_000L, bearing = 0.0))
        estimator.onMotionMeasurement(motion(2.0, seconds(1) + 120_000_000L))
        estimator.onMotionMeasurement(motion(2.0, seconds(1) + 140_000_000L))
        estimator.onMotionMeasurement(motion(2.0, seconds(1) + 160_000_000L))
        val quarantined = estimator.snapshotAt(seconds(1) + 160_000_000L)

        estimator.onMotionMeasurement(motion(2.0, seconds(1) + 540_000_000L))
        estimator.onMotionMeasurement(motion(2.0, seconds(1) + 560_000_000L))
        estimator.onMotionMeasurement(motion(2.0, seconds(1) + 580_000_000L))
        val recovered = estimator.snapshotAt(seconds(1) + 580_000_000L)

        assertEquals(10.0, quarantined.speedMetersPerSecond!!, 0.0001)
        assertTrue(recovered.speedMetersPerSecond!! > 10.0)
    }

    @Test
    fun `stale orientation cannot establish a course`() {
        val estimator = SpeedEstimator()
        estimator.setTrackingMode(TrackingMode.FIXED)
        estimator.onMotionMeasurement(motion(0.0, seconds(1)))
        estimator.onGnssMeasurement(gnss(10.0, 0.1, seconds(1) + 200_000_000L, bearing = 0.0))
        estimator.onMotionMeasurement(motion(2.0, seconds(1) + 220_000_000L))
        estimator.onMotionMeasurement(motion(2.0, seconds(1) + 240_000_000L))
        estimator.onMotionMeasurement(motion(2.0, seconds(1) + 260_000_000L))
        val estimate = estimator.snapshotAt(seconds(1) + 260_000_000L)

        assertEquals(10.0, estimate.speedMetersPerSecond!!, 0.0001)
    }

    @Test
    fun `delayed GNSS replays against the exact earlier orientation epoch`() {
        val estimator = SpeedEstimator()
        estimator.setTrackingMode(TrackingMode.FIXED)
        estimator.onMotionMeasurement(
            motion(
                northAcceleration = 0.0,
                time = seconds(1) + 90_000_000L,
                orientationTimestampNanos = seconds(1)
            )
        )
        estimator.onGnssMeasurement(gnss(10.0, 0.1, seconds(1) + 50_000_000L, bearing = 0.0))
        estimator.onMotionMeasurement(motion(1.0, seconds(1) + 110_000_000L))
        estimator.onMotionMeasurement(motion(1.0, seconds(1) + 130_000_000L))
        val estimate = estimator.snapshotAt(seconds(1) + 130_000_000L)

        assertTrue(estimate.speedMetersPerSecond!! > 10.0)
    }

    @Test
    fun `unreliable orientation cannot establish a course`() {
        val estimator = SpeedEstimator()
        estimator.setTrackingMode(TrackingMode.FIXED)
        estimator.onMotionMeasurement(motion(0.0, seconds(1), orientationReliable = false))
        estimator.onGnssMeasurement(gnss(10.0, 0.1, seconds(1) + 10_000_000L, bearing = 0.0))
        estimator.onMotionMeasurement(motion(2.0, seconds(1) + 30_000_000L))
        estimator.onMotionMeasurement(motion(2.0, seconds(1) + 50_000_000L))
        estimator.onMotionMeasurement(motion(2.0, seconds(1) + 70_000_000L))
        val estimate = estimator.snapshotAt(seconds(1) + 70_000_000L)

        assertEquals(10.0, estimate.speedMetersPerSecond!!, 0.0001)
    }

    @Test
    fun `vertical shock is not integrated as vehicle acceleration`() {
        val estimator = fixedEstimatorWithSeed()
        estimator.onMotionMeasurement(motion(0.0, seconds(1) + 20_000_000L, upAcceleration = 5.0))
        estimator.onMotionMeasurement(motion(0.0, seconds(1) + 40_000_000L))
        estimator.onMotionMeasurement(motion(0.0, seconds(1) + 60_000_000L))
        estimator.onMotionMeasurement(motion(0.0, seconds(1) + 80_000_000L))
        val estimate = estimator.snapshotAt(seconds(1) + 80_000_000L)

        assertEquals(10.0, estimate.speedMetersPerSecond!!, 0.01)
    }

    @Test
    fun `time based acceleration filter is stable across sensor rates`() {
        fun prediction(periodNanos: Long): Pair<Double, Double> {
            val estimator = fixedEstimatorWithSeed()
            val steps = (1_000_000_000L / periodNanos).toInt()
            for (step in 1..steps) {
                val acceleration = if (step * periodNanos <= 300_000_000L) 0.0 else 1.0
                estimator.onMotionMeasurement(motion(acceleration, seconds(1) + step * periodNanos))
            }
            val estimate = estimator.estimateAt(seconds(2))
            return estimate.speedMetersPerSecond!! to estimate.uncertaintyMetersPerSecond
        }

        val fiftyHertz = prediction(20_000_000L)
        val tenHertz = prediction(100_000_000L)

        assertEquals(fiftyHertz.first, tenHertz.first, 0.2)
        assertEquals(fiftyHertz.second, tenHertz.second, 0.15)
    }

    @Test
    fun `earlier projection uncertainty remains in dropout covariance`() {
        fun prediction(withEarlyLateralMotion: Boolean): Double {
            val estimator = fixedEstimatorWithSeed()
            for (step in 1..50) {
                estimator.onMotionMeasurement(
                    motion(
                        northAcceleration = 1.0,
                        time = seconds(1) + step * 20_000_000L,
                        eastAcceleration = if (withEarlyLateralMotion && step <= 25) 5.0 else 0.0
                    )
                )
            }
            return estimator.estimateAt(seconds(2)).uncertaintyMetersPerSecond
        }

        val stable = prediction(withEarlyLateralMotion = false)
        val uncertainThenStable = prediction(withEarlyLateralMotion = true)

        assertTrue(
            "expected retained uncertainty: stable=$stable, uncertainThenStable=$uncertainThenStable",
            uncertainThenStable > stable + 0.05
        )
    }

    @Test
    fun `fixed mode can confirm a stop after its course expires and resume a crawl`() {
        val estimator = fixedEstimatorWithSeed()
        for (second in 2L..6L) {
            estimator.onMotionMeasurement(motion(0.0, seconds(second) - 10_000_000L))
            estimator.onGnssMeasurement(gnss(0.0, 0.1, seconds(second), bearing = null))
        }

        val stopped = estimator.estimateAt(seconds(6))
        val crawling = estimator.onGnssMeasurement(gnss(0.01, 0.1, seconds(7)))

        assertEquals(0.0, stopped.speedMetersPerSecond!!, 0.0)
        assertEquals(0.01, crawling.speedMetersPerSecond!!, 0.0001)
    }

    @Test
    fun `motion older than retained replay history is ignored`() {
        val estimator = fixedEstimatorWithSeed()
        for (second in 2L..8L) {
            estimator.onGnssMeasurement(gnss(10.0, 0.1, seconds(second), bearing = 0.0))
        }
        val before = estimator.estimateAt(seconds(8))

        estimator.onMotionMeasurement(motion(10.0, seconds(1)))
        val after = estimator.estimateAt(seconds(8))

        assertEquals(before.speedMetersPerSecond!!, after.speedMetersPerSecond!!, 0.0)
        assertEquals(before.uncertaintyMetersPerSecond, after.uncertaintyMetersPerSecond, 0.0)
    }

    @Test
    fun `history compaction preserves fixed mode results over a long sensor stream`() {
        val estimator = fixedEstimatorWithSeed()
        for (step in 1..1_000) {
            val timestamp = seconds(1) + step * 20_000_000L
            estimator.ingestMotionMeasurement(motion(0.0, timestamp))
            if (step % 50 == 0) {
                estimator.ingestGnssMeasurement(gnss(10.0, 0.1, timestamp, bearing = 0.0))
            }
        }
        val beforeOldDuplicate = estimator.snapshotAt(seconds(21))

        estimator.ingestMotionMeasurement(motion(8.0, seconds(2)))
        val afterOldDuplicate = estimator.snapshotAt(seconds(21))

        assertEquals(10.0, beforeOldDuplicate.speedMetersPerSecond!!, 0.05)
        assertEquals(beforeOldDuplicate.speedMetersPerSecond, afterOldDuplicate.speedMetersPerSecond)
        assertEquals(
            beforeOldDuplicate.uncertaintyMetersPerSecond,
            afterOldDuplicate.uncertaintyMetersPerSecond,
            0.0
        )
    }

    @Test
    fun `estimate becomes unavailable instead of snapping to zero`() {
        val estimator = SpeedEstimator()
        estimator.onGnssMeasurement(gnss(7.0, 0.1, seconds(1)))

        val estimate = estimator.estimateAt(seconds(5))

        assertNull(estimate.speedMetersPerSecond)
        assertEquals(EstimateQuality.UNAVAILABLE, estimate.quality)
    }

    @Test
    fun `moderate GNSS uncertainty can update maximum`() {
        val estimator = SpeedEstimator()

        val estimate = estimator.onGnssMeasurement(gnss(10.0, 0.5, seconds(1)))

        assertTrue(estimate.maximumCandidateChanges.any { it is MaximumCandidateChange.Upsert })
    }

    private fun fixedEstimatorWithSeed(): SpeedEstimator = SpeedEstimator().also { estimator ->
        estimator.setTrackingMode(TrackingMode.FIXED)
        estimator.onMotionMeasurement(motion(0.0, seconds(1) - 10_000_000L))
        estimator.onGnssMeasurement(gnss(10.0, 0.1, seconds(1), bearing = 0.0))
    }

    private fun gnss(
        speed: Double?,
        sigma: Double?,
        time: Long,
        horizontalAccuracy: Double = 5.0,
        bearing: Double? = null
    ) = GnssMeasurement(
        speedMetersPerSecond = speed,
        speedAccuracyMetersPerSecond = sigma,
        bearingDegrees = bearing,
        bearingAccuracyDegrees = bearing?.let { 5.0 },
        horizontalAccuracyMeters = horizontalAccuracy,
        magneticDeclinationDegrees = bearing?.let { 0.0 },
        satelliteCount = 6,
        timestampNanos = time
    )

    private fun motion(
        northAcceleration: Double,
        time: Long,
        eastAcceleration: Double = 0.0,
        upAcceleration: Double = 0.0,
        yawRadians: Double = 0.0,
        orientationReliable: Boolean = true,
        orientationTimestampNanos: Long = time
    ) = MotionMeasurement(
        accelerationEastMetersPerSecondSquared = eastAcceleration,
        accelerationMagneticNorthMetersPerSecondSquared = northAcceleration,
        accelerationUpMetersPerSecondSquared = upAcceleration,
        deviceYawRadians = yawRadians,
        devicePitchRadians = 0.0,
        deviceRollRadians = 0.0,
        orientationReliable = orientationReliable,
        timestampNanos = time,
        orientationTimestampNanos = orientationTimestampNanos
    )

    private fun seconds(value: Long): Long = value * 1_000_000_000L
}
