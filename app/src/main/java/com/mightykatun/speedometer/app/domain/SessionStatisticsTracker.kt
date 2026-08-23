package com.mightykatun.speedometer.app.domain

import com.mightykatun.speedometer.app.domain.model.SessionConfig
import com.mightykatun.speedometer.app.domain.model.SessionStatistics
import com.mightykatun.speedometer.app.domain.model.SpeedEstimate
import com.mightykatun.speedometer.app.domain.util.SpeedConverter
import kotlin.math.max

class SessionStatisticsTracker(
    private val config: SessionConfig,
    private val timeProvider: TimeProvider
) {
    private var sessionStartTimestampNanos: Long = 0L
    private var maxSpeedKmh: Float = 0f
    private var maxSatellites: Int = 0
    private var currentSatellites: Int = 0
    private var lastMaximumCandidateTimestampNanos: Long = 0L
    
    fun startSession() {
        sessionStartTimestampNanos = timeProvider.currentTimeMillis() * NANOS_PER_MILLISECOND
        maxSpeedKmh = 0f
        maxSatellites = 0
        currentSatellites = 0
        lastMaximumCandidateTimestampNanos = 0L
    }

    fun updateSpeed(estimate: SpeedEstimate): SessionStatistics {
        val currentSpeedKmh = estimate.speedMetersPerSecond
            ?.let { SpeedConverter.metersPerSecondToKmh(it.toFloat()) }
        val maximumCandidateKmh = estimate.maximumCandidateMetersPerSecond
            ?.let { SpeedConverter.metersPerSecondToKmh(it.toFloat()) }
        val isNewMaximumCandidate = maximumCandidateKmh != null &&
            estimate.maximumCandidateTimestampNanos != lastMaximumCandidateTimestampNanos
        if (isNewMaximumCandidate) {
            lastMaximumCandidateTimestampNanos = estimate.maximumCandidateTimestampNanos
        }
        if (isNewMaximumCandidate && estimate.trustedForMaximum &&
            estimate.maximumCandidateTimestampNanos - sessionStartTimestampNanos >=
            config.warmupPeriodMillis * NANOS_PER_MILLISECOND &&
            estimate.maximumCandidateSatelliteCount >= config.minSatellitesForTracking
        ) {
            maxSpeedKmh = max(maxSpeedKmh, requireNotNull(maximumCandidateKmh))
        }

        return snapshot(currentSpeedKmh)
    }

    fun updateSatelliteCount(satelliteCount: Int): SessionStatistics {
        currentSatellites = satelliteCount
        maxSatellites = max(maxSatellites, satelliteCount)
        return snapshot(null)
    }
    
    fun reset() {
        sessionStartTimestampNanos = 0L
        maxSpeedKmh = 0f
        maxSatellites = 0
        currentSatellites = 0
        lastMaximumCandidateTimestampNanos = 0L
    }

    private fun snapshot(currentSpeedKmh: Float?) = SessionStatistics(
        currentSpeedKmh = currentSpeedKmh,
        maxSpeedKmh = maxSpeedKmh,
        currentSatellites = currentSatellites,
        maxSatellites = maxSatellites
    )

    private companion object {
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}
