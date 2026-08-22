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

    var state by mutableStateOf(SpeedometerState())
        private set

    var errorMessage by mutableStateOf<String?>(null)
        private set

    fun onSpeedEstimateReceived(estimate: SpeedEstimate) {
        val stats = sessionTracker.updateSpeed(estimate)
        state = state.copy(
            currentSpeedKmh = stats.currentSpeedKmh,
            speedAccuracyKmh = estimate.uncertaintyMetersPerSecond
                .takeIf { it.isFinite() }
                ?.let { (it * 3.6).toFloat() },
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
        sessionTracker.startSession()
    }

    fun onSessionReset() {
        sessionTracker.reset()
        state = SpeedometerState()
        errorMessage = null
    }

    fun onError(message: String) {
        errorMessage = message
    }

    fun onGpsAvailable() {
        errorMessage = null
    }
}
