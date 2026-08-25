package com.mightykatun.speedometer.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.mightykatun.speedometer.app.domain.SessionStatisticsTracker
import com.mightykatun.speedometer.app.domain.model.EstimateQuality
import com.mightykatun.speedometer.app.domain.model.RefreshRate
import com.mightykatun.speedometer.app.domain.model.SpeedEstimate
import com.mightykatun.speedometer.app.domain.model.SpeedometerState
import com.mightykatun.speedometer.app.domain.model.SpeedTrendSample

class SpeedometerViewModel(
    private val sessionTracker: SessionStatisticsTracker
) : ViewModel() {

    private var sessionActive = false
    private var gpsErrorActive = false
    private var waitingForFreshGnss = false
    private var freshGnssReceived = false
    private var refreshRate = RefreshRate.ONE_SECOND
    private var latestPresentation: PendingPresentation? = null
    private var latestMaxSpeedKmh = 0f
    private var latestSatelliteCount = 0
    private var latestMaxSatelliteCount = 0
    private var lastPresentationTimestampNanos = 0L

    var state by mutableStateOf(SpeedometerState())
        private set

    var errorMessage by mutableStateOf<String?>(null)
        private set

    var warningMessage by mutableStateOf<String?>(null)
        private set

    var signalMessage by mutableStateOf<String?>(null)
        private set

    fun onSpeedEstimateReceived(estimate: SpeedEstimate) {
        val stats = sessionTracker.updateSpeed(estimate)
        latestMaxSpeedKmh = stats.maxSpeedKmh
        latestSatelliteCount = stats.currentSatellites
        latestMaxSatelliteCount = stats.maxSatellites

        val candidate = PendingPresentation(
            timestampNanos = estimate.timestampNanos,
            currentSpeedKmh = stats.currentSpeedKmh,
            speedAccuracyKmh = estimate.uncertaintyMetersPerSecond
                .takeIf { it.isFinite() }
                ?.let { (it * 3.6).toFloat() },
            quality = estimate.quality
        )
        val acceptedForPresentation = latestPresentation?.timestampNanos
            ?.let { candidate.timestampNanos >= it } != false
        if (acceptedForPresentation) {
            latestPresentation = candidate
        }
        if (freshGnssReceived && acceptedForPresentation) {
            freshGnssReceived = false
            waitingForFreshGnss = false
        }
        publishPresentationIfDue()
    }

    fun onSatelliteCountReceived(satelliteCount: Int) {
        val stats = sessionTracker.updateSatelliteCount(satelliteCount)
        latestMaxSpeedKmh = stats.maxSpeedKmh
        latestSatelliteCount = stats.currentSatellites
        latestMaxSatelliteCount = stats.maxSatellites
        if (satelliteCount == 0 && state.satelliteCount != 0) {
            state = state.copy(satelliteCount = 0)
        }
    }

    fun onRefreshRateChanged(refreshRate: RefreshRate) {
        if (this.refreshRate == refreshRate) return
        this.refreshRate = refreshRate
        publishPresentationIfDue(force = true)
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
        signalMessage = null
        gpsErrorActive = false
        waitingForFreshGnss = false
        freshGnssReceived = false
        latestPresentation = null
        latestMaxSpeedKmh = 0f
        latestSatelliteCount = 0
        latestMaxSatelliteCount = 0
        lastPresentationTimestampNanos = 0L
    }

    fun onError(message: String) {
        gpsErrorActive = false
        waitingForFreshGnss = false
        freshGnssReceived = false
        signalMessage = null
        errorMessage = message
    }

    fun onGpsError(message: String) {
        gpsErrorActive = true
        if (message == GPS_PROVIDER_DISABLED_MESSAGE) {
            errorMessage = null
            signalMessage = message
            waitingForFreshGnss = true
            freshGnssReceived = false
            val stats = sessionTracker.updateSatelliteCount(0)
            latestMaxSpeedKmh = stats.maxSpeedKmh
            latestSatelliteCount = stats.currentSatellites
            latestMaxSatelliteCount = stats.maxSatellites
            state = state.copy(
                currentSpeedKmh = null,
                speedAccuracyKmh = null,
                estimateQuality = EstimateQuality.UNAVAILABLE,
                satelliteCount = 0
            )
        } else {
            signalMessage = null
            errorMessage = message
        }
    }

    fun onWarning(message: String?) {
        warningMessage = message
    }

    fun onGpsProviderEnabled() {
        if (signalMessage == GPS_PROVIDER_DISABLED_MESSAGE) {
            gpsErrorActive = false
            signalMessage = null
        }
    }

    fun onGpsAvailable() {
        if (waitingForFreshGnss) freshGnssReceived = true
        if (gpsErrorActive) {
            gpsErrorActive = false
            errorMessage = null
            signalMessage = null
        }
    }

    private fun publishPresentationIfDue(force: Boolean = false) {
        val measured = latestPresentation ?: return
        val latest = if (waitingForFreshGnss) {
            measured.copy(
                currentSpeedKmh = null,
                speedAccuracyKmh = null,
                quality = EstimateQuality.UNAVAILABLE
            )
        } else {
            measured
        }
        val availabilityChanged = (state.currentSpeedKmh == null) != (latest.currentSpeedKmh == null)
        val unavailableBoundary = state.estimateQuality != latest.quality &&
            (state.estimateQuality == EstimateQuality.UNAVAILABLE ||
                latest.quality == EstimateQuality.UNAVAILABLE)
        val refreshDue = lastPresentationTimestampNanos == 0L ||
            latest.timestampNanos - lastPresentationTimestampNanos >= refreshRate.intervalNanos
        if (!force && !availabilityChanged && !unavailableBoundary && !refreshDue) return

        state = state.copy(
            currentSpeedKmh = latest.currentSpeedKmh,
            speedAccuracyKmh = latest.speedAccuracyKmh,
            estimateQuality = latest.quality,
            maxSpeedKmh = latestMaxSpeedKmh,
            satelliteCount = latestSatelliteCount,
            maxSatelliteCount = latestMaxSatelliteCount,
            speedTrend = updatedSpeedTrend(
                state.speedTrend,
                latest.timestampNanos,
                latest.currentSpeedKmh
            )
        )
        lastPresentationTimestampNanos = maxOf(
            lastPresentationTimestampNanos,
            latest.timestampNanos
        )
    }

    private fun updatedSpeedTrend(
        existing: List<SpeedTrendSample>,
        timestampNanos: Long,
        speedKmh: Float?
    ): List<SpeedTrendSample> {
        if (timestampNanos <= 0L) return existing
        val latest = existing.lastOrNull()
        if (latest != null && timestampNanos < latest.timestampNanos) return existing
        val withoutSameTimestamp = if (latest?.timestampNanos == timestampNanos) {
            existing.dropLast(1)
        } else {
            existing
        }
        val cutoff = timestampNanos - TREND_WINDOW_NANOS
        return (withoutSameTimestamp + SpeedTrendSample(timestampNanos, speedKmh))
            .dropWhile { it.timestampNanos < cutoff }
            .takeLast(MAX_TREND_SAMPLES)
    }

    private data class PendingPresentation(
        val timestampNanos: Long,
        val currentSpeedKmh: Float?,
        val speedAccuracyKmh: Float?,
        val quality: EstimateQuality
    )

    private companion object {
        const val GPS_PROVIDER_DISABLED_MESSAGE = "gps provider disabled"
        const val TREND_WINDOW_NANOS = 30_000_000_000L
        const val MAX_TREND_SAMPLES = 360
    }
}
