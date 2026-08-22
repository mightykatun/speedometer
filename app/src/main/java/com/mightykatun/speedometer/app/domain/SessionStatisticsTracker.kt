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
    private var sessionStartTime: Long = 0L
    private var maxSpeedKmh: Float = 0f
    private var maxSatellites: Int = 0
    private var currentSatellites: Int = 0
    
    fun startSession() {
        sessionStartTime = timeProvider.currentTimeMillis()
        maxSpeedKmh = 0f
        maxSatellites = 0
        currentSatellites = 0
    }

    fun updateSpeed(estimate: SpeedEstimate): SessionStatistics {
        val currentSpeedKmh = estimate.speedMetersPerSecond
            ?.let { SpeedConverter.metersPerSecondToKmh(it.toFloat()) }
        val elapsed = timeProvider.currentTimeMillis() - sessionStartTime
        if (currentSpeedKmh != null && estimate.trustedForMaximum &&
            elapsed >= config.warmupPeriodMillis &&
            currentSatellites >= config.minSatellitesForTracking
        ) {
            maxSpeedKmh = max(maxSpeedKmh, currentSpeedKmh)
        }

        return snapshot(currentSpeedKmh)
    }

    fun updateSatelliteCount(satelliteCount: Int): SessionStatistics {
        currentSatellites = satelliteCount
        maxSatellites = max(maxSatellites, satelliteCount)
        return snapshot(null)
    }
    
    fun reset() {
        sessionStartTime = 0L
        maxSpeedKmh = 0f
        maxSatellites = 0
        currentSatellites = 0
    }

    private fun snapshot(currentSpeedKmh: Float?) = SessionStatistics(
        currentSpeedKmh = currentSpeedKmh,
        maxSpeedKmh = maxSpeedKmh,
        currentSatellites = currentSatellites,
        maxSatellites = maxSatellites
    )
}
