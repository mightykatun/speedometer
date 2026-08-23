package com.mightykatun.speedometer.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.mightykatun.speedometer.app.domain.SessionStatisticsTracker
import com.mightykatun.speedometer.app.domain.model.SpeedEstimate
import com.mightykatun.speedometer.app.domain.model.SpeedometerState

class SpeedometerViewModel(
    private val sessionTracker: SessionStatisticsTracker
) : ViewModel() {

    private var lastAccuracyUpdateNanos = Long.MIN_VALUE
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
        val shouldRefreshAccuracy = measuredAccuracyKmh != null &&
            (state.speedAccuracyKmh == null || lastAccuracyUpdateNanos == Long.MIN_VALUE ||
                estimate.timestampNanos - lastAccuracyUpdateNanos >= ACCURACY_UPDATE_PERIOD_NANOS)
        if (shouldRefreshAccuracy) lastAccuracyUpdateNanos = estimate.timestampNanos

        state = state.copy(
            currentSpeedKmh = stats.currentSpeedKmh,
            speedAccuracyKmh = when {
                measuredAccuracyKmh == null -> null
                shouldRefreshAccuracy -> measuredAccuracyKmh
                else -> state.speedAccuracyKmh
            },
            estimateQuality = estimate.quality,
            maxSpeedKmh = stats.maxSpeedKmh
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
        lastAccuracyUpdateNanos = Long.MIN_VALUE
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

    private companion object {
        const val ACCURACY_UPDATE_PERIOD_NANOS = 1_000_000_000L
    }
}
