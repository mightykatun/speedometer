package com.mightykatun.speedometer.app.domain

import com.mightykatun.speedometer.app.domain.model.EstimateQuality
import com.mightykatun.speedometer.app.domain.model.GnssMeasurement
import com.mightykatun.speedometer.app.domain.model.MotionMeasurement
import com.mightykatun.speedometer.app.domain.model.TrackingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedEstimatorTest {
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
    fun `three consistent high quality fixes reacquire after a jump`() {
        val estimator = SpeedEstimator()
        estimator.onGnssMeasurement(gnss(5.0, 0.1, seconds(1)))
        estimator.onGnssMeasurement(gnss(30.0, 0.1, seconds(2)))
        estimator.onGnssMeasurement(gnss(30.1, 0.1, seconds(3)))

        val estimate = estimator.onGnssMeasurement(gnss(29.9, 0.1, seconds(4)))

        assertEquals(30.0, estimate.speedMetersPerSecond!!, 0.2)
    }

    @Test
    fun `invalid fix breaks a reacquisition sequence`() {
        val estimator = SpeedEstimator()
        estimator.onGnssMeasurement(gnss(5.0, 0.1, seconds(1)))
        estimator.onGnssMeasurement(gnss(30.0, 0.1, seconds(1) + 500_000_000L))
        estimator.onGnssMeasurement(gnss(null, 0.1, seconds(2)))
        estimator.onGnssMeasurement(gnss(30.0, 0.1, seconds(2) + 500_000_000L))

        val estimate = estimator.onGnssMeasurement(gnss(30.0, 0.1, seconds(3)))

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

        assertTrue(gnssEstimate.trustedForMaximum)
        assertEquals(EstimateQuality.TRACKING, prediction.quality)
        assertTrue(!prediction.trustedForMaximum)
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

        val estimate = estimator.onMotionMeasurement(motion(1.0, seconds(1) + 110_000_000L))

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

        val estimate = estimator.onMotionMeasurement(motion(-10.0, seconds(2) + 100_000_000L))

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
    fun `GNSS corrects immediately after fixed mode reaches its inertial bound`() {
        val estimator = fixedEstimatorWithSeed()
        for (step in 1..10) {
            estimator.onMotionMeasurement(motion(-15.0, seconds(1) + step * 100_000_000L))
        }

        val estimate = estimator.onGnssMeasurement(gnss(0.0, 0.1, seconds(2) + 10_000_000L))

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
    fun `duplicate fix timestamp has no effect`() {
        val estimator = SpeedEstimator()
        estimator.onGnssMeasurement(gnss(4.0, 0.1, seconds(1)))

        val estimate = estimator.onGnssMeasurement(gnss(20.0, 0.1, seconds(1)))

        assertEquals(4.0, estimate.speedMetersPerSecond!!, 0.0001)
    }

    @Test
    fun `mode switch discards prior estimator state`() {
        val estimator = SpeedEstimator()
        estimator.onGnssMeasurement(gnss(4.0, 0.1, seconds(1)))

        estimator.setTrackingMode(TrackingMode.FIXED)

        assertNull(estimator.estimateAt(seconds(1)).speedMetersPerSecond)
        assertEquals(EstimateQuality.ACQUIRING, estimator.estimateAt(seconds(1)).quality)
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
    fun `normal acceleration exits a confirmed stop on the first trusted fix`() {
        val estimator = SpeedEstimator()
        estimator.onGnssMeasurement(gnss(0.0, 0.1, seconds(1)))
        estimator.onGnssMeasurement(gnss(0.0, 0.1, seconds(2)))
        estimator.onGnssMeasurement(gnss(0.0, 0.1, seconds(3)))

        val estimate = estimator.onGnssMeasurement(gnss(10.0, 0.2, seconds(4)))

        assertEquals(10.0, estimate.speedMetersPerSecond!!, 0.0001)
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
    fun `estimate becomes unavailable instead of snapping to zero`() {
        val estimator = SpeedEstimator()
        estimator.onGnssMeasurement(gnss(7.0, 0.1, seconds(1)))

        val estimate = estimator.estimateAt(seconds(5))

        assertNull(estimate.speedMetersPerSecond)
        assertEquals(EstimateQuality.UNAVAILABLE, estimate.quality)
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

    private fun motion(northAcceleration: Double, time: Long) = MotionMeasurement(
        accelerationEastMetersPerSecondSquared = 0.0,
        accelerationMagneticNorthMetersPerSecondSquared = northAcceleration,
        accelerationUpMetersPerSecondSquared = 0.0,
        deviceYawRadians = 0.0,
        devicePitchRadians = 0.0,
        deviceRollRadians = 0.0,
        orientationReliable = true,
        timestampNanos = time
    )

    private fun seconds(value: Long): Long = value * 1_000_000_000L
}
