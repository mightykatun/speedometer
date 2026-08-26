package com.mightykatun.speedometer.app.domain

import com.mightykatun.speedometer.app.domain.model.SessionConfig
import com.mightykatun.speedometer.app.domain.model.SessionStatistics
import com.mightykatun.speedometer.app.domain.model.MaximumCandidate
import com.mightykatun.speedometer.app.domain.model.MaximumCandidateChange
import com.mightykatun.speedometer.app.domain.model.SpeedEstimate
import com.mightykatun.speedometer.app.domain.util.SpeedConverter
import java.util.TreeMap
import kotlin.math.max

class SessionStatisticsTracker(
    private val config: SessionConfig,
    private val clock: MonotonicClock
) {
    private var sessionStartTimestampNanos: Long = 0L
    private var maximumWarmupStartTimestampNanos: Long = 0L
    private var committedMaxSpeedMetersPerSecond: Double = 0.0
    private val activeCandidates = HashMap<Long, MaximumCandidate>()
    private val eligibleSpeedCounts = TreeMap<Double, Int>()
    private var maxSatellites: Int = 0
    private var currentSatellites: Int = 0
    
    fun startSession() {
        sessionStartTimestampNanos = clock.elapsedRealtimeMillis() * NANOS_PER_MILLISECOND
        maximumWarmupStartTimestampNanos = 0L
        committedMaxSpeedMetersPerSecond = 0.0
        activeCandidates.clear()
        eligibleSpeedCounts.clear()
        maxSatellites = 0
        currentSatellites = 0
    }

    fun updateSpeed(estimate: SpeedEstimate): SessionStatistics {
        val currentSpeedKmh = estimate.speedMetersPerSecond
            ?.let { SpeedConverter.metersPerSecondToKmh(it.toFloat()) }
        val newWarmupStart = estimate.maximumWarmupStartTimestampNanos
            .takeIf { it > 0L }
            ?.coerceAtLeast(sessionStartTimestampNanos)
            ?: 0L
        if (newWarmupStart != maximumWarmupStartTimestampNanos) {
            maximumWarmupStartTimestampNanos = newWarmupStart
            rebuildEligibleSpeeds()
        }
        estimate.maximumCandidateChanges.forEach(::applyCandidateChange)

        return snapshot(currentSpeedKmh)
    }

    fun updateSatelliteCount(satelliteCount: Int): Int {
        currentSatellites = satelliteCount
        maxSatellites = max(maxSatellites, satelliteCount)
        return maxSatellites
    }
    
    fun reset() {
        sessionStartTimestampNanos = 0L
        maximumWarmupStartTimestampNanos = 0L
        committedMaxSpeedMetersPerSecond = 0.0
        activeCandidates.clear()
        eligibleSpeedCounts.clear()
        maxSatellites = 0
        currentSatellites = 0
    }

    private fun applyCandidateChange(change: MaximumCandidateChange) {
        removeActiveCandidate(change.id)
        when (change) {
            is MaximumCandidateChange.Upsert -> addActiveCandidate(change.candidate)
            is MaximumCandidateChange.Retract -> Unit
            is MaximumCandidateChange.Finalize -> change.candidate?.let { candidate ->
                if (isEligible(candidate)) {
                    committedMaxSpeedMetersPerSecond = max(
                        committedMaxSpeedMetersPerSecond,
                        candidate.speedMetersPerSecond
                    )
                }
            }
        }
    }

    private fun addActiveCandidate(candidate: MaximumCandidate) {
        activeCandidates[candidate.id] = candidate
        if (isEligible(candidate)) incrementSpeed(candidate.speedMetersPerSecond)
    }

    private fun removeActiveCandidate(id: Long) {
        val candidate = activeCandidates.remove(id) ?: return
        if (!isEligible(candidate)) return
        val count = eligibleSpeedCounts[candidate.speedMetersPerSecond] ?: return
        if (count == 1) eligibleSpeedCounts.remove(candidate.speedMetersPerSecond)
        else eligibleSpeedCounts[candidate.speedMetersPerSecond] = count - 1
    }

    private fun rebuildEligibleSpeeds() {
        eligibleSpeedCounts.clear()
        activeCandidates.values.filter(::isEligible).forEach { candidate ->
            incrementSpeed(candidate.speedMetersPerSecond)
        }
    }

    private fun incrementSpeed(speedMetersPerSecond: Double) {
        eligibleSpeedCounts[speedMetersPerSecond] =
            (eligibleSpeedCounts[speedMetersPerSecond] ?: 0) + 1
    }

    private fun isEligible(candidate: MaximumCandidate): Boolean =
        maximumWarmupStartTimestampNanos > 0L &&
            candidate.timestampNanos - maximumWarmupStartTimestampNanos >=
            config.warmupPeriodMillis * NANOS_PER_MILLISECOND &&
            candidate.satelliteCount >= config.minSatellitesForTracking

    private fun maximumSpeedKmh(): Float {
        val activeMaximum = if (eligibleSpeedCounts.isEmpty()) 0.0 else eligibleSpeedCounts.lastKey()
        return SpeedConverter.metersPerSecondToKmh(
            max(committedMaxSpeedMetersPerSecond, activeMaximum).toFloat()
        )
    }

    private fun snapshot(currentSpeedKmh: Float?) = SessionStatistics(
        currentSpeedKmh = currentSpeedKmh,
        maxSpeedKmh = maximumSpeedKmh(),
        currentSatellites = currentSatellites,
        maxSatellites = maxSatellites
    )

    private companion object {
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}
