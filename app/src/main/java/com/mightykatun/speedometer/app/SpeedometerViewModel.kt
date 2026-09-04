package com.mightykatun.speedometer.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.mightykatun.speedometer.app.data.repository.RepositoryError
import com.mightykatun.speedometer.app.domain.SessionStatisticsTracker
import com.mightykatun.speedometer.app.domain.calculateRegattaMetrics
import com.mightykatun.speedometer.app.domain.model.EstimateQuality
import com.mightykatun.speedometer.app.domain.model.PositionFix
import com.mightykatun.speedometer.app.domain.model.PortraitDisplayMode
import com.mightykatun.speedometer.app.domain.model.RefreshRate
import com.mightykatun.speedometer.app.domain.model.RegattaMark
import com.mightykatun.speedometer.app.domain.model.SpeedEstimate
import com.mightykatun.speedometer.app.domain.model.SpeedometerState
import com.mightykatun.speedometer.app.domain.model.SpeedTrendSample
import com.mightykatun.speedometer.app.domain.model.VesselHeading
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
    private var latestNavigationFix: PositionFix? = null
    private var pendingInitialPositionFix: PositionFix? = null
    private var newPositionSegmentPending = false
    private var lastPositionPresentationTimestampNanos = 0L
    private var latestVesselHeading: VesselHeading? = null
    private var lastHeadingPresentationTimestampNanos = 0L

    var state by mutableStateOf(SpeedometerState())
        private set

    var errorMessage by mutableStateOf<String?>(null)
        private set

    var warningMessage by mutableStateOf<String?>(null)
        private set

    var signalMessage by mutableStateOf<String?>(null)
        private set

    fun onSpeedEstimateReceived(estimate: SpeedEstimate) {
        expireNavigationFix(estimate.timestampNanos)
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
                pending == null -> {
                    pendingInitialPositionFix = fix
                    publishInitialNavigationFix(fix)
                }
                fix.timestampNanos < pending.timestampNanos -> Unit
                fix.timestampNanos == pending.timestampNanos -> {
                    pendingInitialPositionFix = fix
                    publishInitialNavigationFix(fix)
                }
                isPlausiblePositionTransition(pending, fix) -> {
                    pendingInitialPositionFix = null
                    recordPositionFix(pending)
                    recordPositionFix(fix)
                    publishPositionIfDue()
                }
                else -> {
                    pendingInitialPositionFix = fix
                    publishInitialNavigationFix(fix)
                }
            }
            return
        }
        if (fix.timestampNanos < previous.timestampNanos) return
        val replacesLatest = fix.timestampNanos == previous.timestampNanos
        if (!isPlausiblePositionTransition(previous, fix)) return

        recordPositionFix(fix)
        publishPositionIfDue(force = replacesLatest)
    }

    fun onVesselHeadingReceived(heading: VesselHeading?) {
        if (!sessionActive) return
        if (heading == null) {
            latestVesselHeading = null
            lastHeadingPresentationTimestampNanos = 0L
            if (state.vesselHeading != null) state = state.copy(vesselHeading = null)
            return
        }
        if (latestVesselHeading?.timestampNanos?.let { heading.timestampNanos < it } == true) return
        latestVesselHeading = heading
        publishHeadingIfDue()
    }

    fun cyclePortraitDisplayMode() {
        state = state.copy(portraitDisplayMode = state.portraitDisplayMode.next())
    }

    fun capturePinMark(): Boolean = captureRegattaMark(isPin = true)

    fun captureBoatMark(): Boolean = captureRegattaMark(isPin = false)

    fun clearPinMark() {
        state = state.copy(pinMark = null, regattaMetrics = calculateMetrics(pinMark = null))
    }

    fun clearBoatMark() {
        state = state.copy(boatMark = null, regattaMetrics = calculateMetrics(boatMark = null))
    }

    fun onRefreshRateChanged(refreshRate: RefreshRate) {
        if (this.refreshRate == refreshRate) return
        this.refreshRate = refreshRate
        publishPresentationIfDue(force = true)
        publishPositionIfDue(force = true)
        publishHeadingIfDue(force = true)
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
            positionTrailSegmentStarts = positionTrailSegmentStarts.toList(),
            vesselHeading = null,
            regattaMetrics = calculateRegattaMetrics(state.boatMark, state.pinMark, null)
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
        latestNavigationFix = null
        pendingInitialPositionFix = null
        lastPositionPresentationTimestampNanos = 0L
        latestVesselHeading = null
        lastHeadingPresentationTimestampNanos = 0L
    }

    fun onSessionReset() {
        sessionActive = false
        sessionTracker.reset()
        state = SpeedometerState(
            portraitDisplayMode = state.portraitDisplayMode,
            pinMark = state.pinMark,
            boatMark = state.boatMark
        )
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
        latestNavigationFix = null
        pendingInitialPositionFix = null
        newPositionSegmentPending = false
        lastPositionPresentationTimestampNanos = 0L
        latestVesselHeading = null
        lastHeadingPresentationTimestampNanos = 0L
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
                    satelliteCount = 0,
                    regattaMetrics = calculateRegattaMetrics(state.boatMark, state.pinMark, null)
                )
                latestNavigationFix = null
            }
            RepositoryError.RETRYABLE_STARTUP_FAILURE -> {
                gpsErrorActive = false
                waitingForFreshGnss = false
                signalMessage = null
                errorMessage = GPS_STARTUP_ERROR_MESSAGE
                latestVesselHeading = null
                state = state.copy(vesselHeading = null)
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
            positionTrailSegmentStarts = positionTrailSegmentStarts.toList(),
            regattaMetrics = calculateMetrics()
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
        latestNavigationFix = fix
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

    private fun publishHeadingIfDue(force: Boolean = false) {
        val latest = latestVesselHeading ?: return
        val refreshDue = lastHeadingPresentationTimestampNanos == 0L ||
            latest.timestampNanos - lastHeadingPresentationTimestampNanos >= refreshRate.intervalNanos
        if (!force && !refreshDue) return
        state = state.copy(vesselHeading = latest)
        lastHeadingPresentationTimestampNanos = maxOf(
            lastHeadingPresentationTimestampNanos,
            latest.timestampNanos
        )
    }

    private fun publishInitialNavigationFix(fix: PositionFix) {
        latestNavigationFix = fix
        val metrics = calculateMetrics()
        if (metrics != state.regattaMetrics) state = state.copy(regattaMetrics = metrics)
    }

    private fun captureRegattaMark(isPin: Boolean): Boolean {
        val fix = latestNavigationFix?.takeIf { current ->
            current.horizontalAccuracyMeters <= MAX_REGATTA_MARK_ACCURACY_METERS &&
                latestPresentation?.timestampNanos?.let { now ->
                    now < current.timestampNanos ||
                        now - current.timestampNanos <= MAX_REGATTA_FIX_AGE_NANOS
                } != false
        } ?: return false
        val mark = RegattaMark(
            latitudeDegrees = fix.latitudeDegrees,
            longitudeDegrees = fix.longitudeDegrees,
            horizontalAccuracyMeters = fix.horizontalAccuracyMeters
        )
        val pinMark = if (isPin) mark else state.pinMark
        val boatMark = if (isPin) state.boatMark else mark
        state = state.copy(
            pinMark = pinMark,
            boatMark = boatMark,
            regattaMetrics = calculateRegattaMetrics(boatMark, pinMark, latestNavigationFix)
        )
        return true
    }

    private fun calculateMetrics(
        pinMark: RegattaMark? = state.pinMark,
        boatMark: RegattaMark? = state.boatMark
    ) = calculateRegattaMetrics(boatMark, pinMark, latestNavigationFix)

    private fun expireNavigationFix(timestampNanos: Long) {
        val fix = latestNavigationFix ?: return
        if (timestampNanos >= fix.timestampNanos &&
            timestampNanos - fix.timestampNanos > MAX_REGATTA_FIX_AGE_NANOS
        ) {
            latestNavigationFix = null
            if (state.regattaMetrics.signedDistanceToLineMeters != null ||
                state.regattaMetrics.timeToLineSeconds != null
            ) {
                state = state.copy(
                    regattaMetrics = calculateRegattaMetrics(state.boatMark, state.pinMark, null)
                )
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
            courseOverGroundDegrees?.isFinite() != false &&
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
        const val MAX_REGATTA_MARK_ACCURACY_METERS = 10f
        const val MAX_REGATTA_FIX_AGE_NANOS = 3_000_000_000L
        const val EARTH_RADIUS_METERS = 6_371_000.0
    }
}
