package com.mightykatun.speedometer.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.mightykatun.speedometer.app.data.repository.RepositoryError
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
        publishPresentationIfDue()
    }

    fun onSatelliteCountReceived(satelliteCount: Int) {
        latestSatelliteCount = satelliteCount
        latestMaxSatelliteCount = sessionTracker.updateSatelliteCount(satelliteCount)
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
        latestPresentation = null
        latestMaxSpeedKmh = 0f
        latestSatelliteCount = 0
        latestMaxSatelliteCount = 0
        lastPresentationTimestampNanos = 0L
    }

    fun onRepositoryError(error: RepositoryError) {
        when (error) {
            RepositoryError.GPS_PROVIDER_DISABLED -> {
                gpsErrorActive = true
                errorMessage = null
                signalMessage = GPS_PROVIDER_DISABLED_MESSAGE
                waitingForFreshGnss = true
                latestSatelliteCount = 0
                latestMaxSatelliteCount = sessionTracker.updateSatelliteCount(0)
                state = state.copy(
                    currentSpeedKmh = null,
                    speedAccuracyKmh = null,
                    estimateQuality = EstimateQuality.UNAVAILABLE,
                    satelliteCount = 0
                )
            }
            RepositoryError.RETRYABLE_STARTUP_FAILURE -> {
                gpsErrorActive = false
                waitingForFreshGnss = false
                signalMessage = null
                errorMessage = GPS_STARTUP_ERROR_MESSAGE
            }
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

    fun onGpsRecoveryAccepted() {
        if (!waitingForFreshGnss) return
        waitingForFreshGnss = false
        gpsErrorActive = false
        errorMessage = null
        signalMessage = null
        publishPresentationIfDue(force = true)
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
        const val GPS_STARTUP_ERROR_MESSAGE = "Unable to start GPS"
        const val TREND_WINDOW_NANOS = 30_000_000_000L
        const val MAX_TREND_SAMPLES = 360
    }
}
