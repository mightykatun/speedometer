package com.mightykatun.speedometer.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.mightykatun.speedometer.app.data.repository.RepositoryError
import com.mightykatun.speedometer.app.domain.SessionStatisticsTracker
import com.mightykatun.speedometer.app.domain.model.EstimateQuality
import com.mightykatun.speedometer.app.domain.model.PositionFix
import com.mightykatun.speedometer.app.domain.model.RefreshRate
import com.mightykatun.speedometer.app.domain.model.SpeedEstimate
import com.mightykatun.speedometer.app.domain.model.SpeedometerState
import com.mightykatun.speedometer.app.domain.model.SpeedTrendSample
import kotlin.math.cos
import kotlin.math.hypot

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
    private val positionTrail = ArrayList<PositionFix>()
    private val positionTrailSegmentStarts = ArrayList<Long>()
    private var latestPositionFix: PositionFix? = null
    private var pendingInitialPositionFix: PositionFix? = null
    private var newPositionSegmentPending = false
    private var lastPositionPresentationTimestampNanos = 0L

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

    fun onPositionFixReceived(fix: PositionFix) {
        if (!sessionActive || !fix.isUsableForTrail()) return
        if (newPositionSegmentPending &&
            fix.timestampNanos <= (positionTrail.lastOrNull()?.timestampNanos ?: Long.MIN_VALUE)
        ) return
        val previous = latestPositionFix
        if (previous == null) {
            val pending = pendingInitialPositionFix
            when {
                pending == null -> pendingInitialPositionFix = fix
                fix.timestampNanos < pending.timestampNanos -> Unit
                fix.timestampNanos == pending.timestampNanos -> pendingInitialPositionFix = fix
                isPlausiblePositionTransition(pending, fix) -> {
                    pendingInitialPositionFix = null
                    recordPositionFix(pending)
                    recordPositionFix(fix)
                    publishPositionIfDue()
                }
                else -> pendingInitialPositionFix = fix
            }
            return
        }
        if (fix.timestampNanos < previous.timestampNanos) return
        val replacesLatest = fix.timestampNanos == previous.timestampNanos
        if (!isPlausiblePositionTransition(previous, fix)) return

        recordPositionFix(fix)
        publishPositionIfDue(force = replacesLatest)
    }

    fun onRefreshRateChanged(refreshRate: RefreshRate) {
        if (this.refreshRate == refreshRate) return
        this.refreshRate = refreshRate
        publishPresentationIfDue(force = true)
        publishPositionIfDue(force = true)
    }

    internal fun positionTrailSnapshot(): PositionTrailSnapshot {
        val points = positionTrail.toMutableList()
        latestPositionFix?.let { latest ->
            if (points.lastOrNull()?.timestampNanos == latest.timestampNanos) {
                points[points.lastIndex] = latest
            } else {
                points += latest
            }
        }
        return PositionTrailSnapshot(
            points = points,
            segmentStarts = positionTrailSegmentStarts.toList()
        )
    }

    fun onSessionStart() {
        if (sessionActive) return
        sessionActive = true
        sessionTracker.startSession()
    }

    fun onAcquisitionStopped() {
        if (!sessionActive) return
        retainLatestPositionEndpoint()
        val stats = sessionTracker.stopAcquisition()
        latestMaxSpeedKmh = stats.maxSpeedKmh
        latestSatelliteCount = 0
        latestMaxSatelliteCount = stats.maxSatellites
        state = state.copy(
            currentSpeedKmh = null,
            speedAccuracyKmh = null,
            estimateQuality = EstimateQuality.ACQUIRING,
            maxSpeedKmh = latestMaxSpeedKmh,
            satelliteCount = 0,
            maxSatelliteCount = latestMaxSatelliteCount,
            speedTrend = emptyList(),
            currentPosition = null,
            positionTrail = positionTrail.toList(),
            positionTrailSegmentStarts = positionTrailSegmentStarts.toList()
        )
        errorMessage = null
        warningMessage = null
        signalMessage = null
        gpsErrorActive = false
        waitingForFreshGnss = false
        latestPresentation = null
        lastPresentationTimestampNanos = 0L
        newPositionSegmentPending = positionTrail.isNotEmpty()
        latestPositionFix = null
        pendingInitialPositionFix = null
        lastPositionPresentationTimestampNanos = 0L
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
        positionTrail.clear()
        positionTrailSegmentStarts.clear()
        latestPositionFix = null
        pendingInitialPositionFix = null
        newPositionSegmentPending = false
        lastPositionPresentationTimestampNanos = 0L
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

    private fun publishPositionIfDue(force: Boolean = false) {
        val latest = latestPositionFix ?: return
        val refreshDue = lastPositionPresentationTimestampNanos == 0L ||
            latest.timestampNanos - lastPositionPresentationTimestampNanos >= refreshRate.intervalNanos
        if (!force && !refreshDue) return

        state = state.copy(
            currentPosition = latest,
            positionTrail = positionTrail.toList(),
            positionTrailSegmentStarts = positionTrailSegmentStarts.toList()
        )
        lastPositionPresentationTimestampNanos = maxOf(
            lastPositionPresentationTimestampNanos,
            latest.timestampNanos
        )
    }

    private fun compactPositionTrailIfNeeded() {
        if (positionTrail.size <= MAX_TRAIL_POINTS) return
        val lastIndex = positionTrail.lastIndex
        val compacted = ArrayList<PositionFix>(positionTrail.size / 2 + 1)
        compacted += positionTrail.first()
        for (index in 2 until lastIndex step 2) compacted += positionTrail[index]
        if (compacted.last().timestampNanos != positionTrail[lastIndex].timestampNanos) {
            compacted += positionTrail[lastIndex]
        }
        positionTrail.clear()
        positionTrail.addAll(compacted)
    }

    private fun retainLatestPositionEndpoint() {
        val latest = latestPositionFix ?: return
        when {
            positionTrail.isEmpty() -> positionTrail += latest
            positionTrail.last().timestampNanos == latest.timestampNanos -> {
                positionTrail[positionTrail.lastIndex] = latest
            }
            latest.timestampNanos > positionTrail.last().timestampNanos -> {
                positionTrail += latest
                compactPositionTrailIfNeeded()
            }
        }
    }

    private fun recordPositionFix(fix: PositionFix) {
        latestPositionFix = fix
        when {
            newPositionSegmentPending -> {
                newPositionSegmentPending = false
                positionTrailSegmentStarts += fix.timestampNanos
                positionTrail += fix
                compactPositionTrailIfNeeded()
            }
            positionTrail.isEmpty() -> positionTrail += fix
            positionTrail.last().timestampNanos == fix.timestampNanos -> {
                positionTrail[positionTrail.lastIndex] = fix
            }
            positionDistanceMeters(positionTrail.last(), fix) >= MIN_TRAIL_STEP_METERS -> {
                positionTrail += fix
                compactPositionTrailIfNeeded()
            }
        }
    }

    private fun isPlausiblePositionTransition(first: PositionFix, second: PositionFix): Boolean {
        val elapsedSeconds = (second.timestampNanos - first.timestampNanos) / 1_000_000_000.0
        if (elapsedSeconds < 0.0) return false
        val accuracyAllowance = 2.0 *
            (first.horizontalAccuracyMeters + second.horizontalAccuracyMeters)
        val maximumDistance = accuracyAllowance + MAX_TRAIL_SPEED_METERS_PER_SECOND * elapsedSeconds
        return positionDistanceMeters(first, second) <= maximumDistance
    }

    private fun PositionFix.isUsableForTrail(): Boolean =
        timestampNanos > 0L &&
            latitudeDegrees.isFinite() && latitudeDegrees in -90.0..90.0 &&
            longitudeDegrees.isFinite() && longitudeDegrees in -180.0..180.0 &&
            headingDegrees?.isFinite() != false &&
            altitudeMeters?.isFinite() != false &&
            horizontalAccuracyMeters.isFinite() &&
            horizontalAccuracyMeters in 0f..MAX_TRAIL_ACCURACY_METERS

    private fun positionDistanceMeters(first: PositionFix, second: PositionFix): Double {
        val meanLatitudeRadians = Math.toRadians(
            (first.latitudeDegrees + second.latitudeDegrees) / 2.0
        )
        val eastRadians = Math.toRadians(
            Math.IEEEremainder(second.longitudeDegrees - first.longitudeDegrees, 360.0)
        ) * cos(meanLatitudeRadians)
        val northRadians = Math.toRadians(second.latitudeDegrees - first.latitudeDegrees)
        return hypot(eastRadians, northRadians) * EARTH_RADIUS_METERS
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
        const val MAX_TRAIL_POINTS = 2_048
        const val MIN_TRAIL_STEP_METERS = 3.0
        const val MAX_TRAIL_ACCURACY_METERS = 100f
        const val MAX_TRAIL_SPEED_METERS_PER_SECOND = 400.0
        const val EARTH_RADIUS_METERS = 6_371_000.0
    }
}
