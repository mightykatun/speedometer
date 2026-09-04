package com.mightykatun.speedometer.app.domain

import com.mightykatun.speedometer.app.domain.model.EstimateQuality
import com.mightykatun.speedometer.app.domain.model.GnssMeasurement
import com.mightykatun.speedometer.app.domain.model.MaximumCandidate
import com.mightykatun.speedometer.app.domain.model.MaximumCandidateChange
import com.mightykatun.speedometer.app.domain.model.MotionMeasurement
import com.mightykatun.speedometer.app.domain.model.SpeedEstimate
import com.mightykatun.speedometer.app.domain.model.SpeedEstimatorConfig
import com.mightykatun.speedometer.app.domain.model.TrackingMode
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
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

        data class Orientation(
            val yawRadians: Double,
            val pitchRadians: Double,
            val rollRadians: Double,
            val reliable: Boolean,
            override val timestampNanos: Long
        ) : Input
    }

    private data class AccelerationProjection(
        val longitudinal: Double,
        val lateral: Double,
        val vertical: Double
    )

    private data class AccelerationInput(val value: Double, val uncertainty: Double)

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
        var firstAcceptedGnssNanos: Long = 0L,
        var lastReliableYawRadians: Double? = null,
        var lastReliablePitchRadians: Double? = null,
        var lastReliableRollRadians: Double? = null,
        var lastReliableOrientationNanos: Long = 0L,
        var lastLongitudinalAcceleration: Double? = null,
        var filteredLongitudinalAcceleration: Double? = null,
        var filteredAccelerationNanos: Long = 0L,
        var accelerationSample0: Double = 0.0,
        var accelerationSample1: Double = 0.0,
        var accelerationSample2: Double = 0.0,
        var accelerationSampleCount: Int = 0,
        var accelerationResidualVariance: Double = 0.0,
        var inertialSystematicVariance: Double = 0.0,
        var inertialSystematicUncertaintyExposure: Double = 0.0,
        var lastObservedHorizontalAcceleration: Double? = null,
        var lastObservedHorizontalAccelerationNanos: Long = 0L,
        var courseRadians: Double? = null,
        var courseAnchorYawRadians: Double? = null,
        var courseAnchorNanos: Long = 0L,
        var legacyCourseOverGroundDegrees: Double? = null,
        var stableLegacyCourseCount: Int = 0,
        var outlierCount: Int = 0,
        var outlierMeanSpeed: Double = 0.0,
        var outlierLastNanos: Long = 0L,
        var lastGnssSpeed: Double? = null,
        var lastGnssSigma: Double? = null,
        var lastGnssSatelliteCount: Int = 0,
        var lastGnssCorrectionNanos: Long = 0L,
        var maximumTrustProbation: Boolean = false,
        var imuQuarantinedUntilNanos: Long = 0L,
        var recentAbruptOrientationNanos: Long = 0L,
        var stationaryCandidateNanos: Long = 0L,
        var stationaryFixCount: Int = 0,
        var stationary: Boolean = false,
        var stationaryExitCandidateNanos: Long = 0L
    )

    private data class HistoryEntry(
        val input: Input,
        var stateAfter: State,
        var maximumCandidate: MaximumCandidate? = null
    )

    private var mode = TrackingMode.HANDHELD
    private var state = State(p11 = config.initialBiasVariance)
    private var historyBase = state.copy()
    private var historyBaseInputNanos = 0L
    private val history = ArrayList<HistoryEntry>()
    private var historyStart = 0
    private val gnssTimestamps = HashSet<Long>()
    private val motionTimestamps = HashSet<Long>()
    private val orientationTimestamps = HashSet<Long>()
    private val pendingCandidateChanges = LinkedHashMap<Long, MaximumCandidateChange>()

    fun reset(trackingMode: TrackingMode = mode) {
        mode = trackingMode
        state = State(p11 = config.initialBiasVariance)
        historyBase = state.copy()
        historyBaseInputNanos = 0L
        history.clear()
        historyStart = 0
        gnssTimestamps.clear()
        motionTimestamps.clear()
        orientationTimestamps.clear()
        pendingCandidateChanges.clear()
    }

    fun setTrackingMode(trackingMode: TrackingMode) {
        if (mode == trackingMode) return
        val finalizedChanges = LinkedHashMap<Long, MaximumCandidateChange.Finalize>()
        pendingCandidateChanges.values
            .filterIsInstance<MaximumCandidateChange.Finalize>()
            .forEach { finalizedChanges[it.id] = it }
        for (index in historyStart until history.size) {
            val entry = history[index]
            if (entry.input is Input.Gnss) {
                finalizedChanges[entry.input.timestampNanos] = MaximumCandidateChange.Finalize(
                    id = entry.input.timestampNanos,
                    candidate = entry.maximumCandidate
                )
            }
        }
        mode = trackingMode
        clearCourse()
        history.clear()
        historyStart = 0
        gnssTimestamps.clear()
        motionTimestamps.clear()
        orientationTimestamps.clear()
        pendingCandidateChanges.clear()
        historyBase = state.copy()
        historyBaseInputNanos = state.lastTimestampNanos
        finalizedChanges.values.forEach(::queueCandidateChange)
    }

    fun ingestGnssMeasurement(measurement: GnssMeasurement): Long? {
        if (measurement.timestampNanos <= historyBaseInputNanos || measurement.timestampNanos <= 0L) return null
        if (!gnssTimestamps.add(measurement.timestampNanos)) return null

        val newestTimestamp = newestHistoryTimestamp()
        if (newestTimestamp - measurement.timestampNanos > config.maximumDelayedGnssNanos) {
            gnssTimestamps.remove(measurement.timestampNanos)
            return null
        }

        return insertAndProcess(Input.Gnss(measurement))
    }

    internal fun isGnssMeasurementAccepted(timestampNanos: Long): Boolean {
        for (index in history.lastIndex downTo historyStart) {
            val entry = history[index]
            val gnss = entry.input as? Input.Gnss ?: continue
            if (gnss.timestampNanos == timestampNanos) {
                return entry.stateAfter.lastAcceptedGnssNanos == timestampNanos
            }
            if (gnss.timestampNanos < timestampNanos) return false
        }
        return false
    }

    fun ingestMotionMeasurement(measurement: MotionMeasurement) {
        if (!hasFiniteMotionValues(measurement)) return
        if (measurement.timestampNanos <= historyBaseInputNanos) return
        if (!motionTimestamps.add(measurement.timestampNanos)) return
        if (measurement.orientationTimestampNanos > historyBaseInputNanos &&
            orientationTimestamps.add(measurement.orientationTimestampNanos)
        ) {
            insertAndProcess(
                Input.Orientation(
                    yawRadians = measurement.deviceYawRadians,
                    pitchRadians = measurement.devicePitchRadians,
                    rollRadians = measurement.deviceRollRadians,
                    reliable = measurement.orientationReliable,
                    timestampNanos = measurement.orientationTimestampNanos
                )
            )
        }
        insertAndProcess(Input.Motion(measurement))
    }

    fun snapshotAt(timestampNanos: Long): SpeedEstimate {
        val snapshotTimestamp = latestTimestamp(timestampNanos)
        val candidateChanges = drainCandidateChanges()
        if (!state.initialized) {
            return SpeedEstimate(
                speedMetersPerSecond = null,
                uncertaintyMetersPerSecond = Double.POSITIVE_INFINITY,
                quality = EstimateQuality.ACQUIRING,
                timestampNanos = snapshotTimestamp,
                maximumWarmupStartTimestampNanos = state.firstAcceptedGnssNanos,
                maximumCandidateChanges = candidateChanges
            )
        }

        val elapsedSincePrediction = if (state.lastTimestampNanos == 0L) 0.0 else {
            max(0L, snapshotTimestamp - state.lastTimestampNanos) / NANOS_PER_SECOND
        }
        val projectedVariance = state.p00 + config.fallbackVelocityProcessNoise * elapsedSincePrediction
        val uncertainty = sqrt(max(0.0, projectedVariance))
        val age = max(0L, snapshotTimestamp - state.lastAcceptedGnssNanos)
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
            timestampNanos = snapshotTimestamp,
            maximumWarmupStartTimestampNanos = state.firstAcceptedGnssNanos,
            maximumCandidateChanges = candidateChanges
        )
    }

    fun onGnssMeasurement(measurement: GnssMeasurement): SpeedEstimate {
        ingestGnssMeasurement(measurement)
        return snapshotAt(measurement.timestampNanos)
    }

    fun onMotionMeasurement(measurement: MotionMeasurement) {
        ingestMotionMeasurement(measurement)
    }

    fun estimateAt(timestampNanos: Long): SpeedEstimate = snapshotAt(timestampNanos)

    private fun insertAndProcess(input: Input): Long? {
        val insertionIndex = insertionIndex(input)
        var newlyAcceptedGnssTimestamp: Long? = null

        if (insertionIndex == history.size) {
            val candidate = process(input)
            history += HistoryEntry(input, state.copy(), candidate)
            candidate?.let { queueCandidateChange(MaximumCandidateChange.Upsert(it)) }
            newlyAcceptedGnssTimestamp = acceptedGnssTimestamp(history.last())
        } else {
            state = if (insertionIndex == historyStart) {
                historyBase.copy()
            } else {
                history[insertionIndex - 1].stateAfter.copy()
            }
            history.add(insertionIndex, HistoryEntry(input, state.copy()))
            for (index in insertionIndex until history.size) {
                val previouslyAccepted = acceptedGnssTimestamp(history[index])
                val oldCandidate = history[index].maximumCandidate
                val newCandidate = process(history[index].input)
                history[index].stateAfter = state.copy()
                history[index].maximumCandidate = newCandidate
                queueCandidateDifference(history[index].input, oldCandidate, newCandidate)
                val accepted = acceptedGnssTimestamp(history[index])
                if (accepted != null && accepted != previouslyAccepted) {
                    newlyAcceptedGnssTimestamp = maxOf(newlyAcceptedGnssTimestamp ?: 0L, accepted)
                }
            }
        }
        pruneHistory(newestHistoryTimestamp())
        return newlyAcceptedGnssTimestamp
    }

    private fun acceptedGnssTimestamp(entry: HistoryEntry): Long? =
        (entry.input as? Input.Gnss)?.timestampNanos?.takeIf {
            entry.stateAfter.lastAcceptedGnssNanos == it
        }

    private fun process(input: Input): MaximumCandidate? =
        when (input) {
            is Input.Gnss -> processGnss(input.measurement)
            is Input.Motion -> processMotion(input.measurement).let { null }
            is Input.Orientation -> processOrientation(input).let { null }
        }

    private fun processGnss(measurement: GnssMeasurement): MaximumCandidate? {
        invalidateCourseIfUnusable(measurement, measurement.speedMetersPerSecond)
        if (!isUsableMeasurement(measurement)) {
            state.outlierCount = 0
            return null
        }

        val speed = measurement.speedMetersPerSecond ?: return null
        val sigma = measurement.speedAccuracyMetersPerSecond
            ?: config.missingSpeedAccuracyMetersPerSecond
        if (sigma > config.maximumSpeedAccuracyMetersPerSecond) {
            state.outlierCount = 0
            return null
        }
        predict(measurement.timestampNanos, null)
        val variance = measurementVariance(sigma)

        if (!state.initialized) {
            initialize(speed, variance, measurement.timestampNanos)
            state.maximumTrustProbation = false
            recordGnssCorrection(speed, sigma, measurement.satelliteCount, measurement.timestampNanos)
            updateCourse(measurement, speed)
            updateStationaryState(measurement, sigma, speed)
            return maximumCandidate(measurement.timestampNanos)
        }

        val innovation = speed - state.speed
        val innovationVariance = state.p00 + variance
        val normalizedInnovation = innovation * innovation / innovationVariance
        if (normalizedInnovation > config.innovationGate) {
            val requiredFixes = if (state.stationary) {
                config.stationaryReacquisitionRequiredFixes
            } else {
                config.movingReacquisitionRequiredFixes
            }
            if (!considerReacquisition(speed, sigma, measurement.timestampNanos, requiredFixes)) return null
        } else {
            state.outlierCount = 0
            if (state.stationary && speed > config.stationarySpeedMetersPerSecond) {
                initialize(speed, variance, measurement.timestampNanos)
            } else {
                kalmanSpeedUpdate(speed, variance)
            }
            state.maximumTrustProbation = false
        }

        recordGnssCorrection(speed, sigma, measurement.satelliteCount, measurement.timestampNanos)
        updateCourse(measurement, speed)
        updateStationaryState(measurement, sigma, speed)
        return maximumCandidate(measurement.timestampNanos)
    }

    private fun processOrientation(orientation: Input.Orientation) {
        if (!orientation.reliable) return
        val previousYaw = state.lastReliableYawRadians
        val previousPitch = state.lastReliablePitchRadians
        val previousRoll = state.lastReliableRollRadians
        if (state.lastReliableOrientationNanos > 0L) {
            val dt = orientation.timestampNanos - state.lastReliableOrientationNanos
            val threshold = Math.toRadians(20.0)
            val abruptRotation = dt in 1..250_000_000L && (
                previousYaw?.let { abs(angleDelta(orientation.yawRadians, it)) > threshold } == true ||
                    previousPitch?.let { abs(angleDelta(orientation.pitchRadians, it)) > threshold } == true ||
                    previousRoll?.let { abs(angleDelta(orientation.rollRadians, it)) > threshold } == true
                )
            if (abruptRotation) state.recentAbruptOrientationNanos = orientation.timestampNanos
        }
        state.lastReliableYawRadians = orientation.yawRadians
        state.lastReliablePitchRadians = orientation.pitchRadians
        state.lastReliableRollRadians = orientation.rollRadians
        state.lastReliableOrientationNanos = orientation.timestampNanos
    }

    private fun processMotion(measurement: MotionMeasurement) {
        val accelerationMagnitude = measurement.accelerationMagnitude()
        val abruptOrientationAge = measurement.timestampNanos - state.recentAbruptOrientationNanos
        val disturbedMount = abruptOrientationAge in 0..config.maximumOrientationAgeNanos &&
            accelerationMagnitude > 2.0

        if (measurement.orientationReliable) {
            state.lastObservedHorizontalAcceleration = sqrt(
                measurement.accelerationEastMetersPerSecondSquared *
                    measurement.accelerationEastMetersPerSecondSquared +
                    measurement.accelerationMagneticNorthMetersPerSecondSquared *
                    measurement.accelerationMagneticNorthMetersPerSecondSquared
            )
            state.lastObservedHorizontalAccelerationNanos = measurement.timestampNanos
        }

        if (accelerationMagnitude > config.maximumAccelerationMetersPerSecondSquared ||
            disturbedMount
        ) {
            quarantineImu(measurement.timestampNanos)
            predict(measurement.timestampNanos, null)
            updateStationaryFromMotion(measurement.timestampNanos, null, null)
            return
        }

        val projection = accelerationProjection(measurement)
        val accelerationInput = projection?.let { robustAcceleration(it, measurement.timestampNanos) }
        predict(measurement.timestampNanos, accelerationInput)
        val unsignedLaunchAcceleration = if (projection == null && measurement.orientationReliable) {
            state.lastObservedHorizontalAcceleration
        } else null
        updateStationaryFromMotion(
            measurement.timestampNanos,
            projection?.longitudinal,
            unsignedLaunchAcceleration
        )
    }

    private fun predict(timestampNanos: Long, acceleration: AccelerationInput?) {
        if (state.lastTimestampNanos == 0L) {
            state.lastTimestampNanos = timestampNanos
            state.lastLongitudinalAcceleration = acceleration?.value
            return
        }
        if (timestampNanos <= state.lastTimestampNanos) return

        val elapsedNanos = timestampNanos - state.lastTimestampNanos
        val dt = elapsedNanos / NANOS_PER_SECOND
        val canUseAcceleration = mode != TrackingMode.HANDHELD && state.initialized && acceleration != null &&
            elapsedNanos <= config.maximumInertialStepNanos &&
            timestampNanos >= state.imuQuarantinedUntilNanos
        var integratedAcceleration: Double? = null

        if (canUseAcceleration) {
            val currentAcceleration = requireNotNull(acceleration)
            val previousAcceleration = state.lastLongitudinalAcceleration ?: currentAcceleration.value
            val meanAcceleration = (previousAcceleration + currentAcceleration.value) / 2.0
            val candidateSpeed = state.speed + (meanAcceleration - state.bias) * dt
            if (isInsideInertialEnvelope(candidateSpeed, timestampNanos)) {
                state.speed = candidateSpeed
                integratedAcceleration = currentAcceleration.value

                val oldP00 = state.p00
                val oldP01 = state.p01
                val oldP10 = state.p10
                val oldP11 = state.p11
                val biasNoise = config.biasRandomWalkNoise
                state.p00 = oldP00 - dt * oldP10 - dt * oldP01 + dt * dt * oldP11 +
                    config.velocityProcessNoise * dt +
                    biasNoise * dt * dt * dt / 3.0
                state.p01 = oldP01 - dt * oldP11 - biasNoise * dt * dt / 2.0
                state.p10 = oldP10 - dt * oldP11 - biasNoise * dt * dt / 2.0
                state.p11 = oldP11 + biasNoise * dt
                addSystematicAccelerationVariance(currentAcceleration.uncertainty, dt)
            } else {
                growFallbackCovariance(dt)
            }
        } else if (state.initialized) {
            growFallbackCovariance(dt)
        }

        state.lastTimestampNanos = timestampNanos
        state.lastLongitudinalAcceleration = integratedAcceleration
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

    private fun considerReacquisition(
        speed: Double,
        sigma: Double,
        timestampNanos: Long,
        requiredFixes: Int
    ): Boolean {
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
        if (state.outlierCount < requiredFixes) return false

        initialize(state.outlierMeanSpeed, measurementVariance(sigma), timestampNanos)
        state.maximumTrustProbation = true
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
        state.stationaryCandidateNanos = 0L
        state.stationaryFixCount = 0
        state.stationaryExitCandidateNanos = 0L
    }

    private fun updateCourse(measurement: GnssMeasurement, speed: Double) {
        if (mode != TrackingMode.FIXED) return
        val reliableYaw = state.lastReliableYawRadians ?: return clearCourse()
        val orientationAge = measurement.timestampNanos - state.lastReliableOrientationNanos
        if (orientationAge !in 0..config.maximumOrientationAgeNanos) return clearCourse()
        val courseOverGround = measurement.courseOverGroundDegrees ?: return clearCourse()
        val horizontalAccuracy = measurement.horizontalAccuracyMeters ?: return clearCourse()
        val declination = measurement.magneticDeclinationDegrees ?: return clearCourse()
        val courseAccuracy = measurement.courseOverGroundAccuracyDegrees

        val acceptable = if (courseAccuracy != null) {
            speed >= config.minimumCourseSpeedMetersPerSecond &&
                horizontalAccuracy <= config.maximumCourseHorizontalAccuracyMeters &&
                courseAccuracy <= config.maximumCourseOverGroundAccuracyDegrees
        } else {
            val previous = state.legacyCourseOverGroundDegrees
            state.legacyCourseOverGroundDegrees = courseOverGround
            state.stableLegacyCourseCount = if (previous != null &&
                abs(angleDeltaDegrees(courseOverGround, previous)) <=
                config.maximumLegacyCourseDeltaDegrees
            ) state.stableLegacyCourseCount + 1 else 1
            speed >= config.legacyMinimumCourseSpeedMetersPerSecond &&
                horizontalAccuracy <= config.legacyMaximumCourseHorizontalAccuracyMeters &&
                state.stableLegacyCourseCount >= 3
        }
        if (!acceptable) {
            val preserveLegacyEvidence = courseAccuracy == null &&
                speed >= config.legacyMinimumCourseSpeedMetersPerSecond &&
                horizontalAccuracy <= config.legacyMaximumCourseHorizontalAccuracyMeters
            return clearCourse(resetLegacyCourse = !preserveLegacyEvidence)
        }

        val newCourse = normalizeRadians(Math.toRadians(courseOverGround - declination))
        val priorCourse = state.courseRadians
        val priorYaw = state.courseAnchorYawRadians
        if (priorCourse != null && priorYaw != null) {
            val expectedCourse = priorCourse + angleDelta(reliableYaw, priorYaw)
            val maximumDifference = Math.toRadians(
                max(
                    config.maximumCourseInconsistencyDegrees,
                    3.0 * (courseAccuracy ?: config.maximumLegacyCourseDeltaDegrees)
                )
            )
            if (abs(angleDelta(newCourse, expectedCourse)) > maximumDifference) {
                clearCourse()
                return
            }
        }
        state.courseRadians = newCourse
        state.courseAnchorYawRadians = reliableYaw
        state.courseAnchorNanos = measurement.timestampNanos
    }

    private fun accelerationProjection(measurement: MotionMeasurement): AccelerationProjection? {
        if (mode != TrackingMode.FIXED) return null
        if (!measurement.orientationReliable) return null
        if (measurement.timestampNanos - state.courseAnchorNanos > config.maximumCourseAgeNanos) return null
        val anchorCourse = state.courseRadians ?: return null
        val anchorYaw = state.courseAnchorYawRadians ?: return null
        val course = anchorCourse + angleDelta(measurement.deviceYawRadians, anchorYaw)
        val longitudinal = measurement.accelerationEastMetersPerSecondSquared * sin(course) +
            measurement.accelerationMagneticNorthMetersPerSecondSquared * cos(course)
        val lateral = measurement.accelerationEastMetersPerSecondSquared * cos(course) -
            measurement.accelerationMagneticNorthMetersPerSecondSquared * sin(course)
        return AccelerationProjection(longitudinal, lateral, measurement.accelerationUpMetersPerSecondSquared)
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

    private fun updateStationaryFromMotion(
        timestampNanos: Long,
        signedLongitudinalAcceleration: Double?,
        unsignedExitAcceleration: Double?
    ) {
        if (!state.stationary) {
            state.stationaryExitCandidateNanos = 0L
            return
        }
        val exitAcceleration = unsignedExitAcceleration ?: signedLongitudinalAcceleration?.let {
            abs(it - state.bias)
        }
        if (exitAcceleration == null) {
            state.stationaryExitCandidateNanos = 0L
            return
        }
        if (exitAcceleration > config.stationaryExitAccelerationMetersPerSecondSquared) {
            if (state.stationaryExitCandidateNanos == 0L) state.stationaryExitCandidateNanos = timestampNanos
            if (timestampNanos - state.stationaryExitCandidateNanos >= config.stationaryExitDwellNanos) {
                state.stationary = false
                state.stationaryCandidateNanos = 0L
                state.stationaryFixCount = 0
            }
        } else {
            state.stationaryExitCandidateNanos = 0L
            signedLongitudinalAcceleration?.let { acceleration ->
                state.bias += 0.02 * (acceleration - state.bias)
            }
        }
    }

    private fun clearCourse(resetLegacyCourse: Boolean = true) {
        state.courseRadians = null
        state.courseAnchorYawRadians = null
        state.courseAnchorNanos = 0L
        resetAccelerationFilter()
        if (resetLegacyCourse) {
            state.legacyCourseOverGroundDegrees = null
            state.stableLegacyCourseCount = 0
        }
    }

    private fun invalidateCourseIfUnusable(measurement: GnssMeasurement, speed: Double?) {
        if (mode != TrackingMode.FIXED) return
        val hasModernCourseAccuracy = measurement.courseOverGroundAccuracyDegrees != null
        val minimumSpeed = if (hasModernCourseAccuracy) {
            config.minimumCourseSpeedMetersPerSecond
        } else {
            config.legacyMinimumCourseSpeedMetersPerSecond
        }
        val maximumHorizontalAccuracy = if (hasModernCourseAccuracy) {
            config.maximumCourseHorizontalAccuracyMeters
        } else {
            config.legacyMaximumCourseHorizontalAccuracyMeters
        }
        val unusable = speed == null || !speed.isFinite() || speed < minimumSpeed ||
            measurement.courseOverGroundDegrees == null ||
            measurement.horizontalAccuracyMeters == null || measurement.magneticDeclinationDegrees == null ||
            measurement.horizontalAccuracyMeters > maximumHorizontalAccuracy ||
            measurement.courseOverGroundAccuracyDegrees?.let {
                it > config.maximumCourseOverGroundAccuracyDegrees
            } == true
        if (unusable) clearCourse()
    }

    private fun robustAcceleration(
        projection: AccelerationProjection,
        timestampNanos: Long
    ): AccelerationInput? {
        if (abs(projection.longitudinal) > config.maximumLongitudinalAccelerationMetersPerSecondSquared ||
            abs(projection.vertical) > config.maximumVerticalAccelerationMetersPerSecondSquared
        ) {
            resetAccelerationFilter()
            return null
        }

        state.accelerationSample0 = state.accelerationSample1
        state.accelerationSample1 = state.accelerationSample2
        state.accelerationSample2 = projection.longitudinal
        state.accelerationSampleCount = min(3, state.accelerationSampleCount + 1)
        if (state.accelerationSampleCount < 3) return null

        val median = medianOfThree(
            state.accelerationSample0,
            state.accelerationSample1,
            state.accelerationSample2
        )
        val elapsedNanos = timestampNanos - state.filteredAccelerationNanos
        val dt = max(0L, elapsedNanos) / NANOS_PER_SECOND
        val residual = projection.longitudinal - median
        val residualAlpha = 1.0 - exp(-dt / config.accelerationResidualTimeConstantSeconds)
        state.accelerationResidualVariance += residualAlpha.coerceIn(0.0, 1.0) *
            (residual * residual - state.accelerationResidualVariance)

        val filtered = state.filteredLongitudinalAcceleration?.let { previous ->
            val alpha = 1.0 - exp(-dt / config.inertialSmoothingTimeConstantSeconds)
            previous + alpha.coerceIn(0.0, 1.0) * (median - previous)
        } ?: median
        state.filteredLongitudinalAcceleration = filtered
        state.filteredAccelerationNanos = timestampNanos

        val orientationError = config.assumedOrientationErrorRadians
        val lateralLeakage = projection.lateral * sin(orientationError)
        val verticalLeakage = projection.vertical * sin(orientationError)
        val baseUncertainty = config.baseAccelerationUncertaintyMetersPerSecondSquared
        val uncertainty = sqrt(
            baseUncertainty * baseUncertainty +
                lateralLeakage * lateralLeakage +
                verticalLeakage * verticalLeakage +
                max(0.0, state.accelerationResidualVariance)
        )
        return AccelerationInput(filtered, uncertainty).takeIf {
            uncertainty <= config.maximumAccelerationUncertaintyMetersPerSecondSquared
        }
    }

    private fun quarantineImu(timestampNanos: Long) {
        state.imuQuarantinedUntilNanos = max(
            state.imuQuarantinedUntilNanos,
            timestampNanos + config.imuQuarantineNanos
        )
        if (mode == TrackingMode.FIXED) clearCourse() else resetAccelerationFilter()
    }

    private fun resetAccelerationFilter() {
        state.lastLongitudinalAcceleration = null
        state.filteredLongitudinalAcceleration = null
        state.filteredAccelerationNanos = 0L
        state.accelerationSample0 = 0.0
        state.accelerationSample1 = 0.0
        state.accelerationSample2 = 0.0
        state.accelerationSampleCount = 0
        state.accelerationResidualVariance = 0.0
    }

    private fun isInsideInertialEnvelope(candidateSpeed: Double, timestampNanos: Long): Boolean {
        val anchorSpeed = state.lastGnssSpeed ?: return false
        val anchorSigma = state.lastGnssSigma ?: return false
        val elapsedNanos = timestampNanos - state.lastGnssCorrectionNanos
        if (elapsedNanos < 0L) return false
        val ageSeconds = elapsedNanos / NANOS_PER_SECOND
        val sigmaMargin = config.inertialEnvelopeSigmaMultiplier * config.speedAccuracyInflation *
            max(anchorSigma, config.minimumSpeedAccuracyMetersPerSecond)
        val lowerBound = max(
            0.0,
            anchorSpeed - config.maximumBrakingAccelerationMetersPerSecondSquared * ageSeconds - sigmaMargin
        )
        val upperBound = anchorSpeed +
            config.maximumDriveAccelerationMetersPerSecondSquared * ageSeconds + sigmaMargin
        return candidateSpeed in lowerBound..upperBound
    }

    private fun growFallbackCovariance(dt: Double) {
        state.p00 += config.fallbackVelocityProcessNoise * dt
        state.p11 += config.biasRandomWalkNoise * dt
    }

    private fun recordGnssCorrection(speed: Double, sigma: Double, satelliteCount: Int, timestampNanos: Long) {
        if (state.firstAcceptedGnssNanos == 0L) state.firstAcceptedGnssNanos = timestampNanos
        state.lastAcceptedGnssNanos = timestampNanos
        state.lastGnssCorrectionNanos = timestampNanos
        state.lastGnssSpeed = speed
        state.lastGnssSigma = sigma
        state.lastGnssSatelliteCount = satelliteCount
        state.inertialSystematicVariance = 0.0
        state.inertialSystematicUncertaintyExposure = 0.0
    }

    private fun addSystematicAccelerationVariance(uncertainty: Double, dt: Double) {
        state.inertialSystematicUncertaintyExposure += uncertainty * dt
        val targetVariance = state.inertialSystematicUncertaintyExposure *
            state.inertialSystematicUncertaintyExposure
        val additionalVariance = max(0.0, targetVariance - state.inertialSystematicVariance)
        state.p00 += additionalVariance
        state.inertialSystematicVariance += additionalVariance
    }

    private fun medianOfThree(first: Double, second: Double, third: Double): Double {
        return first + second + third - min(first, min(second, third)) - max(first, max(second, third))
    }

    private fun inputPriority(input: Input): Int = when (input) {
        is Input.Orientation -> 0
        is Input.Motion -> 1
        is Input.Gnss -> 2
    }

    private fun maximumCandidate(timestampNanos: Long): MaximumCandidate? {
        val uncertainty = sqrt(max(0.0, state.p00))
        val rawGnssUncertainty = state.lastGnssSigma?.let {
            config.speedAccuracyInflation * max(it, config.minimumSpeedAccuracyMetersPerSecond)
        } ?: return null
        val trusted = !state.stationary && !state.maximumTrustProbation &&
            2.0 * uncertainty <= config.maximumTrackingTwoSigmaMetersPerSecond &&
            2.0 * rawGnssUncertainty <= config.maximumTrustedTwoSigmaMetersPerSecond
        if (!trusted || state.lastGnssCorrectionNanos != timestampNanos) return null
        return MaximumCandidate(
            id = timestampNanos,
            speedMetersPerSecond = state.lastGnssSpeed ?: return null,
            timestampNanos = timestampNanos,
            satelliteCount = state.lastGnssSatelliteCount
        )
    }

    private fun insertionIndex(input: Input): Int {
        var low = historyStart
        var high = history.size
        while (low < high) {
            val middle = (low + high).ushr(1)
            val existing = history[middle].input
            val existingComesFirst = existing.timestampNanos < input.timestampNanos ||
                existing.timestampNanos == input.timestampNanos &&
                inputPriority(existing) <= inputPriority(input)
            if (existingComesFirst) low = middle + 1 else high = middle
        }
        return low
    }

    private fun queueCandidateDifference(
        input: Input,
        oldCandidate: MaximumCandidate?,
        newCandidate: MaximumCandidate?
    ) {
        if (input !is Input.Gnss || oldCandidate == newCandidate) return
        queueCandidateChange(
            newCandidate?.let(MaximumCandidateChange::Upsert)
                ?: MaximumCandidateChange.Retract(input.timestampNanos)
        )
    }

    private fun queueCandidateChange(change: MaximumCandidateChange) {
        pendingCandidateChanges[change.id] = change
    }

    private fun drainCandidateChanges(): List<MaximumCandidateChange> {
        if (pendingCandidateChanges.isEmpty()) return emptyList()
        val changes = pendingCandidateChanges.values.toList()
        pendingCandidateChanges.clear()
        return changes
    }

    private fun pruneHistory(newestTimestampNanos: Long) {
        val cutoff = newestTimestampNanos - config.replayHistoryNanos
        while (historyStart < history.size && history[historyStart].input.timestampNanos < cutoff) {
            val removed = history[historyStart++]
            when (removed.input) {
                is Input.Gnss -> {
                    gnssTimestamps.remove(removed.input.timestampNanos)
                    queueCandidateChange(
                        MaximumCandidateChange.Finalize(
                            id = removed.input.timestampNanos,
                            candidate = removed.maximumCandidate
                        )
                    )
                }
                is Input.Motion -> motionTimestamps.remove(removed.input.timestampNanos)
                is Input.Orientation -> orientationTimestamps.remove(removed.input.timestampNanos)
            }
            historyBase = removed.stateAfter.copy()
            historyBaseInputNanos = removed.input.timestampNanos
        }
        compactHistoryIfNeeded()
    }

    private fun compactHistoryIfNeeded() {
        if (historyStart < HISTORY_COMPACTION_MINIMUM_PREFIX || historyStart * 2 < history.size) return
        history.subList(0, historyStart).clear()
        historyStart = 0
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

    private fun hasFiniteMotionValues(measurement: MotionMeasurement): Boolean =
        measurement.timestampNanos > 0L &&
            measurement.accelerationEastMetersPerSecondSquared.isFinite() &&
            measurement.accelerationMagneticNorthMetersPerSecondSquared.isFinite() &&
            measurement.accelerationUpMetersPerSecondSquared.isFinite() &&
            measurement.deviceYawRadians.isFinite() && measurement.devicePitchRadians.isFinite() &&
            measurement.deviceRollRadians.isFinite() && measurement.orientationTimestampNanos > 0L &&
            measurement.orientationTimestampNanos <= measurement.timestampNanos

    private fun MotionMeasurement.accelerationMagnitude(): Double = sqrt(
        accelerationEastMetersPerSecondSquared * accelerationEastMetersPerSecondSquared +
            accelerationMagneticNorthMetersPerSecondSquared * accelerationMagneticNorthMetersPerSecondSquared +
            accelerationUpMetersPerSecondSquared * accelerationUpMetersPerSecondSquared
    )

    private fun newestHistoryTimestamp(): Long =
        if (historyStart < history.size) history.last().input.timestampNanos else 0L

    private fun latestTimestamp(candidate: Long): Long = max(candidate, newestHistoryTimestamp())

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
        const val HISTORY_COMPACTION_MINIMUM_PREFIX = 512
    }
}
