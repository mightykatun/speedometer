package com.mightykatun.speedometer.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.mightykatun.speedometer.app.domain.SessionStatisticsTracker
import com.mightykatun.speedometer.app.domain.model.SpeedEstimate
import com.mightykatun.speedometer.app.domain.model.SpeedometerState
import com.mightykatun.speedometer.app.domain.model.SpeedTrendSample

class SpeedometerViewModel(
    private val sessionTracker: SessionStatisticsTracker
) : ViewModel() {

    private var sessionActive = false
    private var gpsErrorActive = false

    var state by mutableStateOf(SpeedometerState())
        private set

    var errorMessage by mutableStateOf<String?>(null)
        private set

    var warningMessage by mutableStateOf<String?>(null)
        private set

    fun onSpeedEstimateReceived(estimate: SpeedEstimate) {
        val stats = sessionTracker.updateSpeed(estimate)
        val measuredAccuracyKmh = estimate.uncertaintyMetersPerSecond
            .takeIf { it.isFinite() }
            ?.let { (it * 3.6).toFloat() }
        state = state.copy(
            currentSpeedKmh = stats.currentSpeedKmh,
            speedAccuracyKmh = measuredAccuracyKmh,
            estimateQuality = estimate.quality,
            maxSpeedKmh = stats.maxSpeedKmh,
            speedTrend = updatedSpeedTrend(estimate.timestampNanos, stats.currentSpeedKmh)
        )
    }

    fun onSatelliteCountReceived(satelliteCount: Int) {
        val stats = sessionTracker.updateSatelliteCount(satelliteCount)
        state = state.copy(
            satelliteCount = stats.currentSatellites,
            maxSatelliteCount = stats.maxSatellites
        )
    }

    fun onSessionStart() {
        if (sessionActive) return
        sessionActive = true
        sessionTracker.startSession()
    }

    fun onSessionReset() {
        sessionActive = false
        sessionTracker.reset()
        state = SpeedometerState()
        errorMessage = null
        warningMessage = null
        gpsErrorActive = false
    }

    fun onError(message: String) {
        gpsErrorActive = false
        errorMessage = message
    }

    fun onGpsError(message: String) {
        gpsErrorActive = true
        errorMessage = message
    }

    fun onWarning(message: String?) {
        warningMessage = message
    }

    fun onGpsAvailable() {
        if (gpsErrorActive) {
            gpsErrorActive = false
            errorMessage = null
        }
    }

    private fun updatedSpeedTrend(timestampNanos: Long, speedKmh: Float?): List<SpeedTrendSample> {
        if (timestampNanos <= 0L) return state.speedTrend
        val latest = state.speedTrend.lastOrNull()
        if (latest != null && timestampNanos < latest.timestampNanos) return state.speedTrend
        if (latest != null && timestampNanos - latest.timestampNanos < TREND_SAMPLE_PERIOD_NANOS) {
            return state.speedTrend
        }
        val existing = state.speedTrend
        val cutoff = timestampNanos - TREND_WINDOW_NANOS
        return (existing + SpeedTrendSample(timestampNanos, speedKmh))
            .dropWhile { it.timestampNanos < cutoff }
            .takeLast(MAX_TREND_SAMPLES)
    }

    private companion object {
        const val TREND_WINDOW_NANOS = 30_000_000_000L
        const val TREND_SAMPLE_PERIOD_NANOS = 100_000_000L
        const val MAX_TREND_SAMPLES = 360
    }
}
