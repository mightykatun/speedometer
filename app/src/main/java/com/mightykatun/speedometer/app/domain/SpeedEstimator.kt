package com.mightykatun.speedometer.app.domain

import com.mightykatun.speedometer.app.domain.model.EstimateQuality
import com.mightykatun.speedometer.app.domain.model.GnssMeasurement
import com.mightykatun.speedometer.app.domain.model.MotionMeasurement
import com.mightykatun.speedometer.app.domain.model.SpeedEstimate
import com.mightykatun.speedometer.app.domain.model.SpeedEstimatorConfig
import com.mightykatun.speedometer.app.domain.model.TrackingMode
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

class SpeedEstimator(
    private val config: SpeedEstimatorConfig = SpeedEstimatorConfig()
) {
    private sealed interface Input {
        val timestampNanos: Long

        data class Gnss(val measurement: GnssMeasurement) : Input {
            override val timestampNanos: Long = measurement.timestampNanos
        }

        data class Motion(val measurement: MotionMeasurement) : Input {
            override val timestampNanos: Long = measurement.timestampNanos
        }
    }

    private data class State(
        var initialized: Boolean = false,
        var speed: Double = 0.0,
        var bias: Double = 0.0,
        var p00: Double = 100.0,
        var p01: Double = 0.0,
        var p10: Double = 0.0,
        var p11: Double = 0.25,
        var lastTimestampNanos: Long = 0L,
        var lastAcceptedGnssNanos: Long = 0L,
        var lastYawRadians: Double? = null,
        var lastPitchRadians: Double? = null,
        var lastRollRadians: Double? = null,
        var lastMotionNanos: Long = 0L,
        var lastLongitudinalAcceleration: Double? = null,
        var filteredLongitudinalAcceleration: Double? = null,
        var lastObservedLongitudinalAcceleration: Double? = null,
        var lastObservedAccelerationNanos: Long = 0L,
        var lastObservedHorizontalAcceleration: Double? = null,
        var lastObservedHorizontalAccelerationNanos: Long = 0L,
        var courseRadians: Double? = null,
        var courseAnchorYawRadians: Double? = null,
        var courseAnchorNanos: Long = 0L,
        var legacyBearingDegrees: Double? = null,
        var stableLegacyBearingCount: Int = 0,
        var outlierCount: Int = 0,
        var outlierMeanSpeed: Double = 0.0,
        var outlierLastNanos: Long = 0L,
        var inertialAnchorSpeed: Double? = null,
        var stationaryCandidateNanos: Long = 0L,
        var stationaryFixCount: Int = 0,
        var stationary: Boolean = false,
        var stationaryExitCandidateNanos: Long = 0L
    )

    private data class HistoryEntry(val input: Input, var stateAfter: State)

    private var mode = TrackingMode.HANDHELD
    private var state = State(p11 = config.initialBiasVariance)
    private var historyBase = state.copy()
    private val history = mutableListOf<HistoryEntry>()

    fun reset(trackingMode: TrackingMode = mode) {
        mode = trackingMode
        state = State(p11 = config.initialBiasVariance)
        historyBase = state.copy()
        history.clear()
    }

    fun setTrackingMode(trackingMode: TrackingMode) {
        if (mode != trackingMode) reset(trackingMode)
    }

    fun onGnssMeasurement(measurement: GnssMeasurement): SpeedEstimate {
        if (measurement.timestampNanos <= 0L) return estimateAt(latestTimestamp(measurement.timestampNanos))
        if (history.any { it.input is Input.Gnss && it.input.timestampNanos == measurement.timestampNanos }) {
            return estimateAt(latestTimestamp(measurement.timestampNanos))
        }

        val newestTimestamp = history.lastOrNull()?.input?.timestampNanos ?: 0L
        if (newestTimestamp - measurement.timestampNanos > config.maximumDelayedGnssNanos) {
            return estimateAt(newestTimestamp)
        }

        insertAndProcess(Input.Gnss(measurement))
        return estimateAt(latestTimestamp(measurement.timestampNanos))
    }

    fun onMotionMeasurement(measurement: MotionMeasurement): SpeedEstimate {
        if (!isFiniteMotion(measurement)) return estimateAt(latestTimestamp(measurement.timestampNanos))
        if (measurement.timestampNanos <= historyBase.lastTimestampNanos) {
            return estimateAt(latestTimestamp(measurement.timestampNanos))
        }
        insertAndProcess(Input.Motion(measurement))
        return estimateAt(latestTimestamp(measurement.timestampNanos))
    }

    fun estimateAt(timestampNanos: Long): SpeedEstimate {
        if (!state.initialized) {
            return SpeedEstimate(null, Double.POSITIVE_INFINITY, EstimateQuality.ACQUIRING, false, timestampNanos)
        }

        val elapsedSincePrediction = max(0L, timestampNanos - state.lastTimestampNanos) / NANOS_PER_SECOND
        val projectedVariance = state.p00 + config.fallbackVelocityProcessNoise * elapsedSincePrediction
        val uncertainty = sqrt(max(0.0, projectedVariance))
        val age = max(0L, timestampNanos - state.lastAcceptedGnssNanos)
        val quality = when {
            age <= config.trackingAgeNanos &&
                2.0 * uncertainty <= config.maximumTrackingTwoSigmaMetersPerSecond -> EstimateQuality.TRACKING
            age <= config.unavailableAgeNanos -> EstimateQuality.DEGRADED
            else -> EstimateQuality.UNAVAILABLE
        }
        val displaySpeed = when (quality) {
            EstimateQuality.UNAVAILABLE, EstimateQuality.ACQUIRING -> null
            else -> if (state.stationary) 0.0 else max(0.0, state.speed)
        }

        return SpeedEstimate(
            speedMetersPerSecond = displaySpeed,
            uncertaintyMetersPerSecond = uncertainty,
            quality = quality,
            trustedForMaximum = quality == EstimateQuality.TRACKING && !state.stationary &&
                age <= config.maximumTrustedGnssAgeNanos &&
                2.0 * uncertainty <= config.maximumTrustedTwoSigmaMetersPerSecond,
            timestampNanos = timestampNanos
        )
    }

    private fun insertAndProcess(input: Input) {
        val insertionIndex = history.indexOfFirst {
            it.input.timestampNanos > input.timestampNanos ||
                (it.input.timestampNanos == input.timestampNanos && input is Input.Motion && it.input is Input.Gnss)
        }.let { if (it < 0) history.size else it }

        if (insertionIndex == history.size) {
            process(input)
            history += HistoryEntry(input, state.copy())
        } else {
            state = if (insertionIndex == 0) historyBase.copy() else history[insertionIndex - 1].stateAfter.copy()
            history.add(insertionIndex, HistoryEntry(input, state.copy()))
            for (index in insertionIndex until history.size) {
                process(history[index].input)
                history[index].stateAfter = state.copy()
            }
        }
        pruneHistory(history.last().input.timestampNanos)
    }

    private fun process(input: Input) {
        when (input) {
            is Input.Gnss -> processGnss(input.measurement)
            is Input.Motion -> processMotion(input.measurement)
        }
    }

    private fun processGnss(measurement: GnssMeasurement) {
        invalidateCourseIfUnusable(measurement, measurement.speedMetersPerSecond)
        if (!isUsableMeasurement(measurement)) {
            state.outlierCount = 0
            return
        }

        val speed = measurement.speedMetersPerSecond ?: return
        val sigma = measurement.speedAccuracyMetersPerSecond
            ?: config.missingSpeedAccuracyMetersPerSecond
        if (sigma > config.maximumSpeedAccuracyMetersPerSecond) {
            state.outlierCount = 0
            return
        }
        predict(measurement.timestampNanos, null)
        val variance = measurementVariance(sigma)

        if (!state.initialized) {
            initialize(speed, variance, measurement.timestampNanos)
            updateCourse(measurement, speed)
            updateStationaryState(measurement, sigma, speed)
            return
        }
        if (state.stationary && speed > config.stationarySpeedMetersPerSecond) {
            reinitializeMovingSpeed(speed, variance, measurement.timestampNanos)
            updateCourse(measurement, speed)
            return
        }

        val innovation = speed - state.speed
        val innovationVariance = state.p00 + variance
        val normalizedInnovation = innovation * innovation / innovationVariance
        if (normalizedInnovation > config.innovationGate) {
            if (!considerReacquisition(speed, sigma, measurement.timestampNanos)) return
        } else {
            state.outlierCount = 0
            kalmanSpeedUpdate(speed, variance)
        }

        state.lastAcceptedGnssNanos = measurement.timestampNanos
        updateCourse(measurement, speed)
        updateStationaryState(measurement, sigma, speed)
        state.inertialAnchorSpeed = if (state.stationary) 0.0 else max(0.0, state.speed)
    }

    private fun processMotion(measurement: MotionMeasurement) {
        val previousMotionNanos = state.lastMotionNanos
        val previousYaw = state.lastYawRadians
        val previousPitch = state.lastPitchRadians
        val previousRoll = state.lastRollRadians

        if (measurement.orientationReliable && previousMotionNanos > 0L) {
            val dt = measurement.timestampNanos - previousMotionNanos
            val abruptRotation = dt in 1..250_000_000L && listOfNotNull(
                previousYaw?.let { abs(angleDelta(measurement.deviceYawRadians, it)) },
                previousPitch?.let { abs(angleDelta(measurement.devicePitchRadians, it)) },
                previousRoll?.let { abs(angleDelta(measurement.deviceRollRadians, it)) }
            ).maxOrNull()?.let { it > Math.toRadians(20.0) } == true
            if (abruptRotation && measurement.accelerationMagnitude() > 2.0) clearCourse()
        }

        state.lastYawRadians = measurement.deviceYawRadians
        state.lastPitchRadians = measurement.devicePitchRadians
        state.lastRollRadians = measurement.deviceRollRadians
        state.lastMotionNanos = measurement.timestampNanos
        if (measurement.orientationReliable) {
            state.lastObservedHorizontalAcceleration = sqrt(
                measurement.accelerationEastMetersPerSecondSquared *
                    measurement.accelerationEastMetersPerSecondSquared +
                    measurement.accelerationMagneticNorthMetersPerSecondSquared *
                    measurement.accelerationMagneticNorthMetersPerSecondSquared
            )
            state.lastObservedHorizontalAccelerationNanos = measurement.timestampNanos
        }

        val observedAcceleration = longitudinalAcceleration(measurement)
        if (observedAcceleration != null) {
            state.lastObservedLongitudinalAcceleration = observedAcceleration
            state.lastObservedAccelerationNanos = measurement.timestampNanos
        }
        val filteredAcceleration = observedAcceleration?.let { acceleration ->
            state.filteredLongitudinalAcceleration?.let { previous ->
                previous + config.inertialAccelerationSmoothingFactor * (acceleration - previous)
            } ?: acceleration
        }
        state.filteredLongitudinalAcceleration = filteredAcceleration
        predict(measurement.timestampNanos, filteredAcceleration)
        updateStationaryFromMotion(measurement.timestampNanos, observedAcceleration)
    }

    private fun predict(timestampNanos: Long, acceleration: Double?) {
        if (state.lastTimestampNanos == 0L) {
            state.lastTimestampNanos = timestampNanos
            state.lastLongitudinalAcceleration = acceleration
            return
        }
        if (timestampNanos <= state.lastTimestampNanos) return

        val elapsedNanos = timestampNanos - state.lastTimestampNanos
        val dt = elapsedNanos / NANOS_PER_SECOND
        val canUseAcceleration = mode == TrackingMode.FIXED && state.initialized && acceleration != null &&
            elapsedNanos <= config.maximumInertialStepNanos

        if (canUseAcceleration) {
            val currentAcceleration = requireNotNull(acceleration)
            val previousAcceleration = state.lastLongitudinalAcceleration ?: currentAcceleration
            val meanAcceleration = (previousAcceleration + currentAcceleration) / 2.0
            state.speed += (meanAcceleration - state.bias) * dt
            boundInertialSpeed()

            val oldP00 = state.p00
            val oldP01 = state.p01
            val oldP10 = state.p10
            val oldP11 = state.p11
            val biasNoise = config.biasRandomWalkNoise
            state.p00 = oldP00 - dt * oldP10 - dt * oldP01 + dt * dt * oldP11 +
                config.velocityProcessNoise * dt + biasNoise * dt * dt * dt / 3.0
            state.p01 = oldP01 - dt * oldP11 - biasNoise * dt * dt / 2.0
            state.p10 = oldP10 - dt * oldP11 - biasNoise * dt * dt / 2.0
            state.p11 = oldP11 + biasNoise * dt
        } else if (state.initialized) {
            state.p00 += config.fallbackVelocityProcessNoise * dt
            state.p11 += config.biasRandomWalkNoise * dt
        }

        state.lastTimestampNanos = timestampNanos
        state.lastLongitudinalAcceleration = acceleration
    }

    private fun kalmanSpeedUpdate(measuredSpeed: Double, variance: Double) {
        val innovation = measuredSpeed - state.speed
        val innovationVariance = state.p00 + variance
        val k0 = state.p00 / innovationVariance
        val k1 = state.p10 / innovationVariance
        val oldP00 = state.p00
        val oldP01 = state.p01
        val oldP10 = state.p10
        val oldP11 = state.p11

        state.speed += k0 * innovation
        state.bias += k1 * innovation

        val a00 = 1.0 - k0
        val a10 = -k1
        val ap00 = a00 * oldP00
        val ap01 = a00 * oldP01
        val ap10 = oldP10 + a10 * oldP00
        val ap11 = oldP11 + a10 * oldP01
        val newP00 = ap00 * a00 + k0 * k0 * variance
        val newP01 = ap00 * a10 + ap01 + k0 * k1 * variance
        val newP10 = ap10 * a00 + k1 * k0 * variance
        val newP11 = ap10 * a10 + ap11 + k1 * k1 * variance
        state.p00 = max(newP00, 1e-9)
        state.p01 = (newP01 + newP10) / 2.0
        state.p10 = state.p01
        state.p11 = max(newP11, 1e-9)
    }

    private fun considerReacquisition(speed: Double, sigma: Double, timestampNanos: Long): Boolean {
        if (sigma > 1.0) {
            state.outlierCount = 0
            return false
        }
        val tolerance = max(1.0, 3.0 * sigma)
        if (state.outlierCount == 0 || timestampNanos - state.outlierLastNanos > config.trackingAgeNanos ||
            abs(speed - state.outlierMeanSpeed) > tolerance
        ) {
            state.outlierCount = 1
            state.outlierMeanSpeed = speed
            state.outlierLastNanos = timestampNanos
            return false
        }
        state.outlierMeanSpeed =
            (state.outlierMeanSpeed * state.outlierCount + speed) / (state.outlierCount + 1)
        state.outlierCount++
        state.outlierLastNanos = timestampNanos
        if (state.outlierCount < 3) return false

        initialize(state.outlierMeanSpeed, measurementVariance(sigma), timestampNanos)
        state.outlierCount = 0
        return true
    }

    private fun initialize(speed: Double, variance: Double, timestampNanos: Long) {
        state.initialized = true
        state.speed = speed
        state.bias = 0.0
        state.p00 = variance
        state.p01 = 0.0
        state.p10 = 0.0
        state.p11 = config.initialBiasVariance
        state.lastTimestampNanos = timestampNanos
        state.lastAcceptedGnssNanos = timestampNanos
        state.stationary = false
        state.inertialAnchorSpeed = max(0.0, speed)
    }

    private fun reinitializeMovingSpeed(speed: Double, variance: Double, timestampNanos: Long) {
        state.speed = speed
        state.p00 = variance
        state.p01 = 0.0
        state.p10 = 0.0
        state.lastTimestampNanos = timestampNanos
        state.lastAcceptedGnssNanos = timestampNanos
        state.stationary = false
        state.inertialAnchorSpeed = max(0.0, speed)
        state.stationaryCandidateNanos = 0L
        state.stationaryFixCount = 0
        state.stationaryExitCandidateNanos = 0L
        state.outlierCount = 0
    }

    private fun updateCourse(measurement: GnssMeasurement, speed: Double) {
        if (mode != TrackingMode.FIXED || state.lastYawRadians == null) return
        val bearing = measurement.bearingDegrees ?: return clearCourse()
        val horizontalAccuracy = measurement.horizontalAccuracyMeters ?: return clearCourse()
        val declination = measurement.magneticDeclinationDegrees ?: return clearCourse()
        val bearingAccuracy = measurement.bearingAccuracyDegrees

        val acceptable = if (bearingAccuracy != null) {
            speed >= config.minimumCourseSpeedMetersPerSecond &&
                horizontalAccuracy <= config.maximumCourseHorizontalAccuracyMeters &&
                bearingAccuracy <= config.maximumBearingAccuracyDegrees
        } else {
            val previous = state.legacyBearingDegrees
            state.legacyBearingDegrees = bearing
            state.stableLegacyBearingCount = if (previous != null &&
                abs(angleDeltaDegrees(bearing, previous)) <= config.maximumLegacyBearingDeltaDegrees
            ) state.stableLegacyBearingCount + 1 else 1
            speed >= config.legacyMinimumCourseSpeedMetersPerSecond &&
                horizontalAccuracy <= config.legacyMaximumCourseHorizontalAccuracyMeters &&
                state.stableLegacyBearingCount >= 3
        }
        if (!acceptable) {
            val preserveLegacyEvidence = bearingAccuracy == null &&
                speed >= config.legacyMinimumCourseSpeedMetersPerSecond &&
                horizontalAccuracy <= config.legacyMaximumCourseHorizontalAccuracyMeters
            return clearCourse(resetLegacyBearing = !preserveLegacyEvidence)
        }

        state.courseRadians = normalizeRadians(Math.toRadians(bearing - declination))
        state.courseAnchorYawRadians = state.lastYawRadians
        state.courseAnchorNanos = measurement.timestampNanos
    }

    private fun longitudinalAcceleration(measurement: MotionMeasurement): Double? {
        if (mode != TrackingMode.FIXED || !measurement.orientationReliable) return null
        if (measurement.timestampNanos - state.courseAnchorNanos > config.maximumCourseAgeNanos) return null
        val anchorCourse = state.courseRadians ?: return null
        val anchorYaw = state.courseAnchorYawRadians ?: return null
        val course = anchorCourse + angleDelta(measurement.deviceYawRadians, anchorYaw)
        val acceleration = measurement.accelerationEastMetersPerSecondSquared * sin(course) +
            measurement.accelerationMagneticNorthMetersPerSecondSquared * cos(course)
        return acceleration.takeIf { abs(it) <= config.maximumAccelerationMetersPerSecondSquared }
    }

    private fun updateStationaryState(measurement: GnssMeasurement, sigma: Double, speed: Double) {
        if (speed > config.stationarySpeedMetersPerSecond) {
            state.stationary = false
            state.stationaryCandidateNanos = 0L
            state.stationaryFixCount = 0
            return
        }
        val quietMotion = mode == TrackingMode.HANDHELD ||
            state.lastObservedHorizontalAcceleration?.let {
                measurement.timestampNanos - state.lastObservedHorizontalAccelerationNanos <=
                    config.maximumOrientationAgeNanos * 2 &&
                    it <= config.stationaryAccelerationMetersPerSecondSquared
            } == true
        val stationaryEvidence = speed <= config.stationarySpeedMetersPerSecond &&
            sigma <= config.stationarySpeedAccuracyMetersPerSecond && quietMotion

        if (stationaryEvidence) {
            if (state.stationaryCandidateNanos == 0L) state.stationaryCandidateNanos = measurement.timestampNanos
            state.stationaryFixCount++
            if (state.stationaryFixCount >= config.stationaryRequiredFixes &&
                measurement.timestampNanos - state.stationaryCandidateNanos >= config.stationaryDwellNanos
            ) {
                state.stationary = true
                kalmanSpeedUpdate(0.0, config.zeroVelocityVariance)
            }
        } else {
            state.stationaryCandidateNanos = 0L
            state.stationaryFixCount = 0
            if (speed - 2.0 * sigma > config.stationarySpeedMetersPerSecond) state.stationary = false
        }
    }

    private fun updateStationaryFromMotion(timestampNanos: Long, acceleration: Double?) {
        if (!state.stationary || acceleration == null) {
            state.stationaryExitCandidateNanos = 0L
            return
        }
        if (abs(acceleration - state.bias) > config.stationaryExitAccelerationMetersPerSecondSquared) {
            if (state.stationaryExitCandidateNanos == 0L) state.stationaryExitCandidateNanos = timestampNanos
            if (timestampNanos - state.stationaryExitCandidateNanos >= config.stationaryExitDwellNanos) {
                state.stationary = false
                state.stationaryCandidateNanos = 0L
                state.stationaryFixCount = 0
            }
        } else {
            state.stationaryExitCandidateNanos = 0L
            state.bias += 0.02 * (acceleration - state.bias)
        }
    }

    private fun clearCourse(resetLegacyBearing: Boolean = true) {
        state.courseRadians = null
        state.courseAnchorYawRadians = null
        state.courseAnchorNanos = 0L
        state.lastLongitudinalAcceleration = null
        state.filteredLongitudinalAcceleration = null
        state.lastObservedLongitudinalAcceleration = null
        state.lastObservedAccelerationNanos = 0L
        if (resetLegacyBearing) {
            state.legacyBearingDegrees = null
            state.stableLegacyBearingCount = 0
        }
    }

    private fun invalidateCourseIfUnusable(measurement: GnssMeasurement, speed: Double?) {
        if (mode != TrackingMode.FIXED) return
        val hasModernBearingAccuracy = measurement.bearingAccuracyDegrees != null
        val minimumSpeed = if (hasModernBearingAccuracy) {
            config.minimumCourseSpeedMetersPerSecond
        } else {
            config.legacyMinimumCourseSpeedMetersPerSecond
        }
        val maximumHorizontalAccuracy = if (hasModernBearingAccuracy) {
            config.maximumCourseHorizontalAccuracyMeters
        } else {
            config.legacyMaximumCourseHorizontalAccuracyMeters
        }
        val unusable = speed == null || !speed.isFinite() || speed < minimumSpeed ||
            measurement.bearingDegrees == null ||
            measurement.horizontalAccuracyMeters == null || measurement.magneticDeclinationDegrees == null ||
            measurement.horizontalAccuracyMeters > maximumHorizontalAccuracy ||
            measurement.bearingAccuracyDegrees?.let { it > config.maximumBearingAccuracyDegrees } == true
        if (unusable) clearCourse()
    }

    private fun boundInertialSpeed() {
        val anchor = state.inertialAnchorSpeed ?: return
        val fraction = config.maximumInertialSpeedChangeFraction
        val lowerBound = anchor * (1.0 - fraction)
        val upperBound = anchor + max(
            config.minimumUpwardInertialSpeedChangeMetersPerSecond,
            anchor * fraction
        )
        val unboundedSpeed = state.speed
        state.speed = unboundedSpeed.coerceIn(lowerBound, upperBound)
        if (state.speed != unboundedSpeed) {
            val constrainedCorrection = max(
                config.minimumUpwardInertialSpeedChangeMetersPerSecond,
                anchor * fraction
            )
            state.p00 = max(state.p00, constrainedCorrection * constrainedCorrection)
        }
    }

    private fun pruneHistory(newestTimestampNanos: Long) {
        val cutoff = newestTimestampNanos - config.replayHistoryNanos
        while (history.isNotEmpty() && history.first().input.timestampNanos < cutoff) {
            historyBase = history.removeAt(0).stateAfter.copy()
        }
    }

    private fun isUsableMeasurement(measurement: GnssMeasurement): Boolean {
        val speed = measurement.speedMetersPerSecond ?: return false
        val accuracy = measurement.speedAccuracyMetersPerSecond
        return measurement.timestampNanos > 0L && speed.isFinite() && speed >= 0.0 &&
            (accuracy == null || accuracy.isFinite() && accuracy >= 0.0)
    }

    private fun measurementVariance(sigma: Double): Double {
        val inflatedSigma = config.speedAccuracyInflation *
            max(sigma, config.minimumSpeedAccuracyMetersPerSecond)
        return inflatedSigma * inflatedSigma
    }

    private fun isFiniteMotion(measurement: MotionMeasurement): Boolean =
        measurement.timestampNanos > 0L &&
            measurement.accelerationEastMetersPerSecondSquared.isFinite() &&
            measurement.accelerationMagneticNorthMetersPerSecondSquared.isFinite() &&
            measurement.accelerationUpMetersPerSecondSquared.isFinite() &&
            measurement.deviceYawRadians.isFinite() && measurement.devicePitchRadians.isFinite() &&
            measurement.deviceRollRadians.isFinite() &&
            measurement.accelerationMagnitude() <= config.maximumAccelerationMetersPerSecondSquared

    private fun MotionMeasurement.accelerationMagnitude(): Double = sqrt(
        accelerationEastMetersPerSecondSquared * accelerationEastMetersPerSecondSquared +
            accelerationMagneticNorthMetersPerSecondSquared * accelerationMagneticNorthMetersPerSecondSquared +
            accelerationUpMetersPerSecondSquared * accelerationUpMetersPerSecondSquared
    )

    private fun latestTimestamp(candidate: Long): Long =
        max(candidate, history.lastOrNull()?.input?.timestampNanos ?: candidate)

    private fun angleDelta(first: Double, second: Double): Double = normalizeRadians(first - second)

    private fun angleDeltaDegrees(first: Double, second: Double): Double =
        Math.toDegrees(angleDelta(Math.toRadians(first), Math.toRadians(second)))

    private fun normalizeRadians(value: Double): Double {
        var normalized = value
        while (normalized > PI) normalized -= 2.0 * PI
        while (normalized < -PI) normalized += 2.0 * PI
        return normalized
    }

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000.0
    }
}
