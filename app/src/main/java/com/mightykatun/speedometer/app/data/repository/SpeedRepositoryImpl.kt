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

    override val supportsFixedMode: Boolean = motionGateway.supportsFixedMode

    // All fields below are mutated only by the repository worker.
    private var lifecycle = Lifecycle.STOPPED
    private var session: Session? = null
    private var failedStartDelivery: Delivery? = null

    override fun startUpdates(
        trackingMode: TrackingMode,
        onEstimate: (SpeedEstimate) -> Unit,
        onSatelliteCount: (Int) -> Unit,
        onGnssAvailable: () -> Unit,
        onPermissionRequired: () -> Unit,
        onError: (String) -> Unit,
        onTrackingModeChanged: (TrackingMode) -> Unit
    ) {
        val callbacks = Callbacks(
            onEstimate,
            onSatelliteCount,
            onGnssAvailable,
            onPermissionRequired,
            onError,
            onTrackingModeChanged
        )
        val expectedEpoch = stopEpoch.get()
        worker.post {
            if (expectedEpoch == stopEpoch.get()) handleStart(trackingMode, callbacks, expectedEpoch)
        }
    }

    override fun setTrackingMode(trackingMode: TrackingMode) {
        val expectedEpoch = stopEpoch.get()
        worker.post {
            if (expectedEpoch == stopEpoch.get() && lifecycle == Lifecycle.STARTED) {
                session?.setTrackingMode(trackingMode)
            }
        }
    }

    override fun stopUpdates() {
        stopEpoch.incrementAndGet()
        worker.post { handleStop() }
    }

    override fun close() {
        stopEpoch.incrementAndGet()
        worker.post {
            if (lifecycle == Lifecycle.CLOSED) return@post
            invalidateDeliveries()
            stopSession()
            lifecycle = Lifecycle.CLOSED
            worker.close()
        }
    }

    private fun handleStart(requestedMode: TrackingMode, callbacks: Callbacks, expectedEpoch: Long) {
        if (lifecycle == Lifecycle.CLOSED) return
        failedStartDelivery?.invalidate()
        failedStartDelivery = null

        if (lifecycle == Lifecycle.STARTED) {
            val currentSession = session ?: return
            currentSession.replaceCallbacks(callbacks)
            currentSession.setTrackingMode(requestedMode, notifyWhenUnchanged = true)
            return
        }

        lifecycle = Lifecycle.STARTING
        val delivery = newDelivery(expectedEpoch, callbacks)
        val newSession = Session(delivery)
        session = newSession
        if (newSession.start(requestedMode)) {
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
        private var satelliteEvidence: SatelliteEvidence? = null

        private val estimateTick = object : Runnable {
            override fun run() {
                if (!isCurrentStarted()) return
                emitEstimate(estimator.snapshotAt(worker.elapsedRealtimeNanos()))
                worker.postDelayed(this, OUTPUT_PERIOD_MILLISECONDS)
            }
        }

        private val locationListener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (!isCurrent()) return
                val measurement = createMeasurement(location)
                estimator.ingestGnssMeasurement(measurement)
                if (measurement.speedMetersPerSecond != null) emitGnssAvailable()
                emitEstimate(estimator.snapshotAt(location.elapsedRealtimeNanos))
            }

            override fun onProviderEnabled(provider: String) = Unit

            override fun onProviderDisabled(provider: String) {
                if (provider == LocationManager.GPS_PROVIDER && isCurrent()) {
                    clearSatelliteEvidence()
                    emitError("gps provider disabled")
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        }

        private val gnssCallback = object : GnssStatus.Callback() {
            override fun onStopped() {
                if (isCurrent()) clearSatelliteEvidence()
            }

            override fun onSatelliteStatusChanged(status: GnssStatus) {
                if (!isCurrent()) return
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

        fun start(requestedMode: TrackingMode): Boolean {
            if (!registerLocationCallbacks()) return false

            val fixedModeActive = requestedMode == TrackingMode.FIXED && registerSensors()
            effectiveMode = if (fixedModeActive) TrackingMode.FIXED else TrackingMode.HANDHELD
            estimator.reset(effectiveMode)
            clearSatelliteEvidence()
            emitTrackingModeChanged(effectiveMode)
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
            satelliteEvidence = null
        }

        fun replaceCallbacks(callbacks: Callbacks) {
            delivery.replaceCallbacks(callbacks)
        }

        fun invalidateDelivery() {
            delivery.invalidate()
        }

        fun setTrackingMode(requestedMode: TrackingMode, notifyWhenUnchanged: Boolean = false) {
            if (!isCurrentStarted()) return
            if (requestedMode == effectiveMode) {
                if (notifyWhenUnchanged) emitTrackingModeChanged(effectiveMode)
                return
            }

            if (requestedMode == TrackingMode.HANDHELD) {
                unregisterSensors()
                effectiveMode = TrackingMode.HANDHELD
                estimator.setTrackingMode(effectiveMode)
                emitTrackingModeChanged(effectiveMode)
                emitEstimate(estimator.snapshotAt(worker.elapsedRealtimeNanos()))
                return
            }

            if (!registerSensors()) {
                emitTrackingModeChanged(TrackingMode.HANDHELD)
                return
            }

            effectiveMode = TrackingMode.FIXED
            estimator.setTrackingMode(effectiveMode)
            emitTrackingModeChanged(effectiveMode)
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
                    emitError("Unable to monitor GNSS status")
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
                emitError("Error starting GPS: ${exception.message ?: "unknown error"}")
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
            sensorsRegistered = runCatching { motionGateway.register(sensorListener) }.getOrDefault(false)
            if (!sensorsRegistered) runCatching { motionGateway.unregister(sensorListener) }
            return sensorsRegistered
        }

        private fun unregisterSensors() {
            runCatching { motionGateway.unregister(sensorListener) }
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

        private fun createMeasurement(location: Location): GnssMeasurement {
            val speed = location.speed.toDouble().takeIf {
                location.hasSpeed() && it.isFinite() && it >= 0.0
            }
            val speedAccuracy = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                location.hasSpeedAccuracy()
            ) {
                location.speedAccuracyMetersPerSecond.toDouble().takeIf { it.isFinite() && it >= 0.0 }
            } else null
            val bearing = location.bearing.toDouble().takeIf { location.hasBearing() && it.isFinite() }
            val bearingAccuracy = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                location.hasBearingAccuracy()
            ) {
                location.bearingAccuracyDegrees.toDouble().takeIf { it.isFinite() && it >= 0.0 }
            } else null
            val horizontalAccuracy = location.accuracy.toDouble().takeIf {
                location.hasAccuracy() && it.isFinite() && it >= 0.0
            }
            val altitude = if (location.hasAltitude()) location.altitude.toFloat() else 0f
            val declination = runCatching {
                GeomagneticField(
                    location.latitude.toFloat(),
                    location.longitude.toFloat(),
                    altitude,
                    location.time
                ).declination.toDouble()
            }.getOrNull()
            val coherentSatelliteCount = satelliteEvidence?.takeIf { evidence ->
                evidence.observedAtElapsedRealtimeNanos <= location.elapsedRealtimeNanos &&
                    location.elapsedRealtimeNanos - evidence.observedAtElapsedRealtimeNanos <=
                    MAX_SATELLITE_EVIDENCE_AGE_NANOS
            }?.usedInFixCount ?: 0

            return GnssMeasurement(
                speedMetersPerSecond = speed,
                speedAccuracyMetersPerSecond = speedAccuracy,
                bearingDegrees = bearing,
                bearingAccuracyDegrees = bearingAccuracy,
                horizontalAccuracyMeters = horizontalAccuracy,
                magneticDeclinationDegrees = declination,
                satelliteCount = coherentSatelliteCount,
                timestampNanos = location.elapsedRealtimeNanos
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
            if (!sensorsRegistered || lastRotationTimestampNanos == 0L ||
                orientationAgeNanos !in 0..MAX_ORIENTATION_AGE_NANOS
            ) return

            val deviceX = event.values[0]
            val deviceY = event.values[1]
            val deviceZ = event.values[2]
            val east = rotationMatrix[0] * deviceX + rotationMatrix[1] * deviceY + rotationMatrix[2] * deviceZ
            val north = rotationMatrix[3] * deviceX + rotationMatrix[4] * deviceY + rotationMatrix[5] * deviceZ
            val up = rotationMatrix[6] * deviceX + rotationMatrix[7] * deviceY + rotationMatrix[8] * deviceZ
            estimator.ingestMotionMeasurement(
                MotionMeasurement(
                    accelerationEastMetersPerSecondSquared = east.toDouble(),
                    accelerationMagneticNorthMetersPerSecondSquared = north.toDouble(),
                    accelerationUpMetersPerSecondSquared = up.toDouble(),
                    deviceYawRadians = orientation[0].toDouble(),
                    devicePitchRadians = orientation[1].toDouble(),
                    deviceRollRadians = orientation[2].toDouble(),
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

        private fun emitGnssAvailable() {
            val target = delivery
            mainDispatcher.post {
                if (target.valid) target.callbacks.onGnssAvailable()
            }
        }

        private fun emitPermissionRequired() {
            val target = delivery
            mainDispatcher.post {
                if (target.valid) target.callbacks.onPermissionRequired()
            }
        }

        private fun emitError(message: String) {
            val target = delivery
            mainDispatcher.post {
                if (target.valid) target.callbacks.onError(message)
            }
        }

        private fun emitTrackingModeChanged(mode: TrackingMode) {
            val target = delivery
            mainDispatcher.post {
                if (target.valid) target.callbacks.onTrackingModeChanged(mode)
            }
        }

        private fun isCurrent(): Boolean =
            (lifecycle == Lifecycle.STARTING || lifecycle == Lifecycle.STARTED) && session === this

        private fun isCurrentStarted(): Boolean = lifecycle == Lifecycle.STARTED && session === this
    }

    private data class Callbacks(
        val onEstimate: (SpeedEstimate) -> Unit,
        val onSatelliteCount: (Int) -> Unit,
        val onGnssAvailable: () -> Unit,
        val onPermissionRequired: () -> Unit,
        val onError: (String) -> Unit,
        val onTrackingModeChanged: (TrackingMode) -> Unit
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
