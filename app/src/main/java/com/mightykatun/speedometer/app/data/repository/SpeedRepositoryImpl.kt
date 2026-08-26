package com.mightykatun.speedometer.app.data.repository

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import com.mightykatun.speedometer.app.domain.SpeedEstimator
import com.mightykatun.speedometer.app.domain.model.GnssMeasurement
import com.mightykatun.speedometer.app.domain.model.MotionMeasurement
import com.mightykatun.speedometer.app.domain.model.SpeedEstimate
import com.mightykatun.speedometer.app.domain.model.TrackingMode
import java.util.concurrent.atomic.AtomicLong

class SpeedRepositoryImpl private constructor(
    private val estimator: SpeedEstimator,
    dependencies: RepositoryDependencies
) : SpeedRepository {
    constructor(
        context: Context,
        estimator: SpeedEstimator = SpeedEstimator()
    ) : this(estimator, productionRepositoryDependencies(context))

    internal constructor(
        estimator: SpeedEstimator,
        worker: RepositoryWorker,
        mainDispatcher: RepositoryMainDispatcher,
        locationGateway: RepositoryLocationGateway,
        motionGateway: RepositoryMotionGateway
    ) : this(
        estimator,
        RepositoryDependencies(worker, mainDispatcher, locationGateway, motionGateway)
    )

    private val worker = dependencies.worker
    private val mainDispatcher = dependencies.mainDispatcher
    private val locationGateway = dependencies.locationGateway
    private val motionGateway = dependencies.motionGateway
    private val stopEpoch = AtomicLong()
    private val modeCommandSequence = AtomicLong()

    override val supportsFixedMode: Boolean = motionGateway.supportsFixedMode

    // All fields below are mutated only by the repository worker.
    private var lifecycle = Lifecycle.STOPPED
    private var session: Session? = null
    private var failedStartDelivery: Delivery? = null

    override fun startUpdates(
        trackingMode: TrackingMode,
        onEstimate: (SpeedEstimate) -> Unit,
        onSatelliteCount: (Int) -> Unit,
        onGpsProviderEnabled: () -> Unit,
        onGpsRecoveryAccepted: () -> Unit,
        onPermissionRequired: () -> Unit,
        onError: (RepositoryError) -> Unit,
        onTrackingModeResult: (TrackingModeResult) -> Unit
    ): Long {
        val commandId = modeCommandSequence.incrementAndGet()
        val callbacks = Callbacks(
            onEstimate,
            onSatelliteCount,
            onGpsProviderEnabled,
            onGpsRecoveryAccepted,
            onPermissionRequired,
            onError,
            onTrackingModeResult
        )
        val expectedEpoch = stopEpoch.get()
        worker.post {
            if (expectedEpoch == stopEpoch.get()) {
                handleStart(commandId, trackingMode, callbacks, expectedEpoch)
            }
        }
        return commandId
    }

    override fun setTrackingMode(trackingMode: TrackingMode): Long {
        val commandId = modeCommandSequence.incrementAndGet()
        val expectedEpoch = stopEpoch.get()
        worker.post {
            if (expectedEpoch == stopEpoch.get() && lifecycle == Lifecycle.STARTED) {
                session?.setTrackingMode(commandId, trackingMode)
            }
        }
        return commandId
    }

    override fun stopUpdates() {
        stopEpoch.incrementAndGet()
        worker.postIfRunning { handleStop() }
    }

    override fun close() {
        stopEpoch.incrementAndGet()
        val closePosted = worker.postIfRunning {
            if (lifecycle == Lifecycle.CLOSED) return@postIfRunning
            invalidateDeliveries()
            stopSession()
            lifecycle = Lifecycle.CLOSED
            worker.close()
        }
        if (!closePosted) worker.close()
    }

    private fun handleStart(
        commandId: Long,
        requestedMode: TrackingMode,
        callbacks: Callbacks,
        expectedEpoch: Long
    ) {
        if (lifecycle == Lifecycle.CLOSED) return
        failedStartDelivery?.invalidate()
        failedStartDelivery = null

        if (lifecycle == Lifecycle.STARTED) {
            val currentSession = session ?: return
            currentSession.replaceCallbacks(callbacks)
            currentSession.setTrackingMode(commandId, requestedMode)
            return
        }

        lifecycle = Lifecycle.STARTING
        val delivery = newDelivery(expectedEpoch, callbacks)
        val newSession = Session(delivery)
        session = newSession
        if (newSession.start(commandId, requestedMode)) {
            lifecycle = Lifecycle.STARTED
        } else {
            newSession.stop()
            session = null
            lifecycle = Lifecycle.STOPPED
            // Keep the failed attempt valid long enough for its queued error to be delivered.
            failedStartDelivery = delivery
        }
    }

    private fun handleStop() {
        if (lifecycle == Lifecycle.CLOSED) return
        invalidateDeliveries()
        stopSession()
        lifecycle = Lifecycle.STOPPED
    }

    private fun stopSession() {
        session?.stop()
        session = null
    }

    private fun invalidateDeliveries() {
        session?.invalidateDelivery()
        failedStartDelivery?.invalidate()
        failedStartDelivery = null
    }

    private fun newDelivery(expectedEpoch: Long, callbacks: Callbacks): Delivery =
        Delivery(expectedEpoch, callbacks)

    private inner class Session(initialDelivery: Delivery) {
        private val rotationMatrix = FloatArray(9)
        private val orientation = FloatArray(3)
        private val delivery = initialDelivery
        private var effectiveMode = TrackingMode.HANDHELD
        private var lastRotationTimestampNanos = 0L
        private var rotationReliable = false
        private var sensorsRegistered = false
        private var locationRegistered = false
        private var gnssRegistered = false
        private var gpsProviderDisabled = false
        private var providerRecoveryPending = false
        private var providerRecoveryBoundaryNanos = 0L
        private var satelliteEvidence: SatelliteEvidence? = null

        private val estimateTick = object : Runnable {
            override fun run() {
                if (!isCurrentStarted()) return
                val timestampNanos = worker.elapsedRealtimeNanos()
                expireSatelliteEvidence(timestampNanos)
                emitEstimate(estimator.snapshotAt(timestampNanos))
                worker.postDelayed(this, OUTPUT_PERIOD_MILLISECONDS)
            }
        }

        private val locationListener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (!isCurrent() || gpsProviderDisabled) return
                val measurement = createMeasurement(location)
                val acceptedCorrectionTimestamp = estimator.ingestGnssMeasurement(measurement)
                emitEstimate(estimator.snapshotAt(location.elapsedRealtimeNanos))
                if (providerRecoveryPending &&
                    acceptedCorrectionTimestamp != null &&
                    acceptedCorrectionTimestamp > providerRecoveryBoundaryNanos
                ) {
                    providerRecoveryPending = false
                    emitGpsRecoveryAccepted()
                }
            }

            override fun onProviderEnabled(provider: String) {
                if (provider == LocationManager.GPS_PROVIDER && isCurrent()) {
                    markGpsProviderEnabled()
                }
            }

            override fun onProviderDisabled(provider: String) {
                if (provider == LocationManager.GPS_PROVIDER && isCurrent()) {
                    markGpsProviderDisabled()
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        }

        private val gnssCallback = object : GnssStatus.Callback() {
            override fun onStarted() {
                if (isCurrent()) {
                    markGpsProviderEnabled()
                }
            }

            override fun onStopped() {
                if (isCurrent()) clearSatelliteEvidence()
            }

            override fun onSatelliteStatusChanged(status: GnssStatus) {
                if (!isCurrent() || gpsProviderDisabled) return
                val count = (0 until status.satelliteCount).count(status::usedInFix)
                satelliteEvidence = SatelliteEvidence(count, worker.elapsedRealtimeNanos())
                emitSatelliteCount(count)
            }
        }

        private val sensorListener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (!isCurrentStarted()) return
                when (event.sensor.type) {
                    Sensor.TYPE_ROTATION_VECTOR -> updateRotation(event)
                    Sensor.TYPE_LINEAR_ACCELERATION -> updateAcceleration(event)
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
                if (!isCurrentStarted()) return
                if (sensor?.type == Sensor.TYPE_ROTATION_VECTOR &&
                    accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE
                ) {
                    rotationReliable = false
                }
            }
        }

        fun start(commandId: Long, requestedMode: TrackingMode): Boolean {
            effectiveMode = when (requestedMode) {
                TrackingMode.FIXED -> {
                    if (!registerLocationCallbacks()) return false
                    if (registerSensors()) TrackingMode.FIXED else TrackingMode.HANDHELD
                }
                TrackingMode.HANDHELD -> {
                    if (!registerLocationCallbacks()) return false
                    TrackingMode.HANDHELD
                }
            }
            estimator.reset(effectiveMode)
            clearSatelliteEvidence()
            emitTrackingModeResult(commandId, requestedMode)
            worker.postDelayed(estimateTick, OUTPUT_PERIOD_MILLISECONDS)
            return true
        }

        fun stop() {
            worker.removeCallbacks(estimateTick)
            unregisterSensors()
            if (gnssRegistered) {
                runCatching { locationGateway.unregisterGnssStatusCallback(gnssCallback) }
            }
            if (locationRegistered) {
                runCatching { locationGateway.removeLocationUpdates(locationListener) }
            }
            gnssRegistered = false
            locationRegistered = false
            gpsProviderDisabled = false
            providerRecoveryPending = false
            providerRecoveryBoundaryNanos = 0L
            satelliteEvidence = null
        }

        fun replaceCallbacks(callbacks: Callbacks) {
            delivery.replaceCallbacks(callbacks)
        }

        fun invalidateDelivery() {
            delivery.invalidate()
        }

        fun setTrackingMode(
            commandId: Long,
            requestedMode: TrackingMode
        ) {
            if (!isCurrentStarted()) return
            if (requestedMode == effectiveMode) {
                emitTrackingModeResult(commandId, requestedMode)
                return
            }

            val nextMode = when (requestedMode) {
                TrackingMode.FIXED -> {
                    if (!locationRegistered && !registerLocationCallbacks()) return
                    if (!registerSensors()) {
                        effectiveMode = TrackingMode.HANDHELD
                        estimator.setTrackingMode(effectiveMode)
                        emitTrackingModeResult(commandId, requestedMode)
                        emitEstimate(estimator.snapshotAt(worker.elapsedRealtimeNanos()))
                        return
                    }
                    TrackingMode.FIXED
                }
                TrackingMode.HANDHELD -> {
                    if (!locationRegistered && !registerLocationCallbacks()) return
                    unregisterSensors()
                    TrackingMode.HANDHELD
                }
            }

            effectiveMode = nextMode
            estimator.setTrackingMode(effectiveMode)
            emitTrackingModeResult(commandId, requestedMode)
            emitEstimate(estimator.snapshotAt(worker.elapsedRealtimeNanos()))
        }

        private fun registerLocationCallbacks(): Boolean {
            if (!runCatching { locationGateway.hasFineLocationPermission() }.getOrDefault(false)) {
                emitPermissionRequired()
                return false
            }

            return try {
                locationGateway.requestLocationUpdates(locationListener)
                locationRegistered = true
                if (!locationGateway.registerGnssStatusCallback(gnssCallback)) {
                    cleanupLocationCallbacks()
                    emitError(RepositoryError.RETRYABLE_STARTUP_FAILURE)
                    false
                } else {
                    gnssRegistered = true
                    true
                }
            } catch (_: SecurityException) {
                cleanupLocationCallbacks()
                emitPermissionRequired()
                false
            } catch (exception: RuntimeException) {
                cleanupLocationCallbacks()
                emitError(RepositoryError.RETRYABLE_STARTUP_FAILURE)
                false
            }
        }

        private fun cleanupLocationCallbacks() {
            runCatching { locationGateway.unregisterGnssStatusCallback(gnssCallback) }
            runCatching { locationGateway.removeLocationUpdates(locationListener) }
            gnssRegistered = false
            locationRegistered = false
        }

        private fun registerSensors(): Boolean {
            unregisterSensors()
            resetSensorState()
            if (!supportsFixedMode || !isCurrent()) return false
            sensorsRegistered = runCatching {
                motionGateway.register(sensorListener)
            }.getOrDefault(false)
            return sensorsRegistered
        }

        private fun unregisterSensors() {
            if (sensorsRegistered) runCatching { motionGateway.unregister(sensorListener) }
            sensorsRegistered = false
            resetSensorState()
        }

        private fun resetSensorState() {
            lastRotationTimestampNanos = 0L
            rotationReliable = false
        }

        private fun clearSatelliteEvidence() {
            satelliteEvidence = null
            emitSatelliteCount(0)
        }

        private fun markGpsProviderDisabled() {
            if (!gpsProviderDisabled) {
                providerRecoveryPending = true
                providerRecoveryBoundaryNanos = worker.elapsedRealtimeNanos()
            }
            gpsProviderDisabled = true
            clearSatelliteEvidence()
            emitError(RepositoryError.GPS_PROVIDER_DISABLED)
        }

        private fun markGpsProviderEnabled() {
            val wasDisabled = gpsProviderDisabled
            gpsProviderDisabled = false
            if (providerRecoveryPending && wasDisabled) {
                providerRecoveryBoundaryNanos = maxOf(
                    providerRecoveryBoundaryNanos,
                    worker.elapsedRealtimeNanos()
                )
            }
            emitGpsProviderEnabled()
        }

        private fun expireSatelliteEvidence(timestampNanos: Long) {
            val evidence = satelliteEvidence ?: return
            if (timestampNanos >= evidence.observedAtElapsedRealtimeNanos &&
                timestampNanos - evidence.observedAtElapsedRealtimeNanos >
                MAX_SATELLITE_EVIDENCE_AGE_NANOS
            ) {
                clearSatelliteEvidence()
            }
        }

        private fun createMeasurement(location: Location): GnssMeasurement {
            val includeCourseFields = effectiveMode == TrackingMode.FIXED
            val speedAccuracy = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                location.hasSpeedAccuracy()
            ) {
                location.speedAccuracyMetersPerSecond
            } else null
            val bearingAccuracy = if (includeCourseFields &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                location.hasBearingAccuracy()
            ) {
                location.bearingAccuracyDegrees
            } else null
            val declination = if (includeCourseFields) {
                val altitude = if (location.hasAltitude()) location.altitude.toFloat() else 0f
                runCatching {
                    GeomagneticField(
                        location.latitude.toFloat(),
                        location.longitude.toFloat(),
                        altitude,
                        location.time
                    ).declination.toDouble()
                }.getOrNull()
            } else null
            val coherentSatelliteCount = satelliteEvidence?.takeIf { evidence ->
                evidence.observedAtElapsedRealtimeNanos <= location.elapsedRealtimeNanos &&
                    location.elapsedRealtimeNanos - evidence.observedAtElapsedRealtimeNanos <=
                    MAX_SATELLITE_EVIDENCE_AGE_NANOS
            }?.usedInFixCount ?: 0

            return createGnssMeasurement(
                location = location,
                satelliteCount = coherentSatelliteCount,
                magneticDeclinationDegrees = declination,
                speedAccuracyMetersPerSecond = speedAccuracy,
                bearingAccuracyDegrees = bearingAccuracy,
                includeCourseFields = includeCourseFields
            )
        }

        private fun updateRotation(event: SensorEvent) {
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
            SensorManager.getOrientation(rotationMatrix, orientation)
            lastRotationTimestampNanos = event.timestamp
            val headingAccuracy = event.values.getOrNull(4)?.toDouble()
                ?.takeIf { it.isFinite() && it >= 0.0 }
            rotationReliable = event.accuracy != SensorManager.SENSOR_STATUS_UNRELIABLE &&
                (headingAccuracy?.let { it <= MAX_HEADING_ACCURACY_RADIANS }
                    ?: (event.accuracy >= SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM))
        }

        private fun updateAcceleration(event: SensorEvent) {
            val orientationAgeNanos = event.timestamp - lastRotationTimestampNanos
            if (!sensorsRegistered) return
            if (lastRotationTimestampNanos == 0L ||
                orientationAgeNanos !in 0..MAX_ORIENTATION_AGE_NANOS
            ) return
            estimator.ingestMotionMeasurement(
                createMotionMeasurement(
                    rotationMatrix = rotationMatrix,
                    orientation = orientation,
                    acceleration = event.values,
                    orientationReliable = rotationReliable,
                    timestampNanos = event.timestamp,
                    orientationTimestampNanos = lastRotationTimestampNanos
                )
            )
        }

        private fun emitEstimate(estimate: SpeedEstimate) {
            val target = delivery
            mainDispatcher.post {
                if (target.valid) target.callbacks.onEstimate(estimate)
            }
        }

        private fun emitSatelliteCount(count: Int) {
            val target = delivery
            mainDispatcher.post {
                if (target.valid) target.callbacks.onSatelliteCount(count)
            }
        }

        private fun emitGpsProviderEnabled() {
            val target = delivery
            mainDispatcher.post {
                if (target.valid) target.callbacks.onGpsProviderEnabled()
            }
        }

        private fun emitGpsRecoveryAccepted() {
            val target = delivery
            mainDispatcher.post {
                if (target.valid) target.callbacks.onGpsRecoveryAccepted()
            }
        }

        private fun emitPermissionRequired() {
            val target = delivery
            mainDispatcher.post {
                if (target.valid) target.callbacks.onPermissionRequired()
            }
        }

        private fun emitError(error: RepositoryError) {
            val target = delivery
            mainDispatcher.post {
                if (target.valid) target.callbacks.onError(error)
            }
        }

        private fun emitTrackingModeResult(commandId: Long, requestedMode: TrackingMode) {
            val result = TrackingModeResult(commandId, requestedMode, effectiveMode)
            val target = delivery
            mainDispatcher.post {
                if (target.valid) target.callbacks.onTrackingModeResult(result)
            }
        }

        private fun isCurrent(): Boolean =
            (lifecycle == Lifecycle.STARTING || lifecycle == Lifecycle.STARTED) && session === this

        private fun isCurrentStarted(): Boolean = lifecycle == Lifecycle.STARTED && session === this
    }

    private data class Callbacks(
        val onEstimate: (SpeedEstimate) -> Unit,
        val onSatelliteCount: (Int) -> Unit,
        val onGpsProviderEnabled: () -> Unit,
        val onGpsRecoveryAccepted: () -> Unit,
        val onPermissionRequired: () -> Unit,
        val onError: (RepositoryError) -> Unit,
        val onTrackingModeResult: (TrackingModeResult) -> Unit
    )

    private inner class Delivery(
        private val expectedEpoch: Long,
        callbacks: Callbacks
    ) {
        @Volatile
        var callbacks: Callbacks = callbacks
            private set

        @Volatile
        private var enabled: Boolean = true

        val valid: Boolean
            get() = enabled && expectedEpoch == stopEpoch.get()

        fun replaceCallbacks(callbacks: Callbacks) {
            this.callbacks = callbacks
        }

        fun invalidate() {
            enabled = false
        }
    }

    private data class SatelliteEvidence(
        val usedInFixCount: Int,
        val observedAtElapsedRealtimeNanos: Long
    )

    private enum class Lifecycle {
        STOPPED,
        STARTING,
        STARTED,
        CLOSED
    }

    private companion object {
        const val OUTPUT_PERIOD_MILLISECONDS = 100L
        const val MAX_ORIENTATION_AGE_NANOS = 100_000_000L
        const val MAX_SATELLITE_EVIDENCE_AGE_NANOS = 2_000_000_000L
        val MAX_HEADING_ACCURACY_RADIANS: Double = Math.toRadians(25.0)
    }
}

internal fun createGnssMeasurement(
    location: Location,
    satelliteCount: Int,
    magneticDeclinationDegrees: Double?,
    speedAccuracyMetersPerSecond: Float?,
    bearingAccuracyDegrees: Float?,
    includeCourseFields: Boolean
): GnssMeasurement {
    val speed = location.speed.toDouble().takeIf {
        location.hasSpeed() && it.isFinite() && it >= 0.0
    }
    val speedAccuracy = speedAccuracyMetersPerSecond?.toDouble()?.takeIf { it.isFinite() && it >= 0.0 }
    val bearing = location.bearing.toDouble().takeIf {
        includeCourseFields && location.hasBearing() && it.isFinite()
    }
    val bearingAccuracy = bearingAccuracyDegrees?.toDouble()?.takeIf {
        includeCourseFields && it.isFinite() && it >= 0.0
    }
    val horizontalAccuracy = location.accuracy.toDouble().takeIf {
        includeCourseFields && location.hasAccuracy() && it.isFinite() && it >= 0.0
    }
    return GnssMeasurement(
        speedMetersPerSecond = speed,
        speedAccuracyMetersPerSecond = speedAccuracy,
        bearingDegrees = bearing,
        bearingAccuracyDegrees = bearingAccuracy,
        horizontalAccuracyMeters = horizontalAccuracy,
        magneticDeclinationDegrees = magneticDeclinationDegrees.takeIf { includeCourseFields },
        satelliteCount = satelliteCount,
        timestampNanos = location.elapsedRealtimeNanos
    )
}

internal fun createMotionMeasurement(
    rotationMatrix: FloatArray,
    orientation: FloatArray,
    acceleration: FloatArray,
    orientationReliable: Boolean,
    timestampNanos: Long,
    orientationTimestampNanos: Long
): MotionMeasurement {
    val deviceX = acceleration[0]
    val deviceY = acceleration[1]
    val deviceZ = acceleration[2]
    return MotionMeasurement(
        accelerationEastMetersPerSecondSquared =
            (rotationMatrix[0] * deviceX + rotationMatrix[1] * deviceY + rotationMatrix[2] * deviceZ).toDouble(),
        accelerationMagneticNorthMetersPerSecondSquared =
            (rotationMatrix[3] * deviceX + rotationMatrix[4] * deviceY + rotationMatrix[5] * deviceZ).toDouble(),
        accelerationUpMetersPerSecondSquared =
            (rotationMatrix[6] * deviceX + rotationMatrix[7] * deviceY + rotationMatrix[8] * deviceZ).toDouble(),
        deviceYawRadians = orientation[0].toDouble(),
        devicePitchRadians = orientation[1].toDouble(),
        deviceRollRadians = orientation[2].toDouble(),
        orientationReliable = orientationReliable,
        timestampNanos = timestampNanos,
        orientationTimestampNanos = orientationTimestampNanos
    )
}
