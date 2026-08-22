package com.mightykatun.speedometer.app.data.repository

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
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
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.mightykatun.speedometer.app.domain.SpeedEstimator
import com.mightykatun.speedometer.app.domain.model.GnssMeasurement
import com.mightykatun.speedometer.app.domain.model.MotionMeasurement
import com.mightykatun.speedometer.app.domain.model.SpeedEstimate
import com.mightykatun.speedometer.app.domain.model.TrackingMode
import kotlin.math.abs

class SpeedRepositoryImpl(
    context: Context,
    private val estimator: SpeedEstimator = SpeedEstimator()
) : SpeedRepository {
    private val applicationContext = context.applicationContext
    private val locationManager =
        applicationContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val sensorManager =
        applicationContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val linearAccelerationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    private val rotationVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val workerThread = HandlerThread("speed-sensors").apply { start() }
    private val workerHandler = Handler(workerThread.looper)

    override val supportsFixedMode: Boolean
        get() = linearAccelerationSensor != null && rotationVectorSensor != null

    @Volatile
    private var started = false

    @Volatile
    private var generation = 0

    private var trackingMode = TrackingMode.HANDHELD
    private var session: Session? = null
    private var onEstimate: ((SpeedEstimate) -> Unit)? = null
    private var onSatelliteCount: ((Int) -> Unit)? = null
    private var onGnssAvailable: (() -> Unit)? = null
    private var onError: ((String) -> Unit)? = null

    override fun startUpdates(
        trackingMode: TrackingMode,
        onEstimate: (SpeedEstimate) -> Unit,
        onSatelliteCount: (Int) -> Unit,
        onGnssAvailable: () -> Unit,
        onError: (String) -> Unit
    ) {
        this.onEstimate = onEstimate
        this.onSatelliteCount = onSatelliteCount
        this.onGnssAvailable = onGnssAvailable
        this.onError = onError
        val newMode = effectiveMode(trackingMode)
        if (started) {
            setTrackingMode(newMode)
            return
        }

        this.trackingMode = newMode
        started = true
        val activeGeneration = ++generation
        workerHandler.post {
            if (!isActive(activeGeneration)) return@post
            estimator.reset(newMode)
            val newSession = Session(activeGeneration)
            session = newSession
            newSession.start()
        }
    }

    override fun setTrackingMode(trackingMode: TrackingMode) {
        val newMode = effectiveMode(trackingMode)
        if (this.trackingMode == newMode) return
        this.trackingMode = newMode
        workerHandler.post {
            estimator.setTrackingMode(newMode)
            session?.updateSensorRegistration()
            emitEstimate(estimator.estimateAt(SystemClock.elapsedRealtimeNanos()), generation)
        }
    }

    override fun stopUpdates() {
        if (!started) return
        started = false
        generation++
        workerHandler.post {
            session?.stop()
            session = null
        }
        onEstimate = null
        onSatelliteCount = null
        onGnssAvailable = null
        onError = null
    }

    override fun close() {
        stopUpdates()
        workerHandler.post { workerThread.quitSafely() }
    }

    private inner class Session(private val activeGeneration: Int) {
        private val rotationMatrix = FloatArray(9)
        private val orientation = FloatArray(3)
        private var lastRotationTimestampNanos = 0L
        private var rotationReliable = false
        private var sensorsRegistered = false
        private var satelliteCount = 0

        private val estimateTick = object : Runnable {
            override fun run() {
                if (!isCurrent()) return
                emitEstimate(estimator.estimateAt(SystemClock.elapsedRealtimeNanos()), activeGeneration)
                workerHandler.postDelayed(this, OUTPUT_PERIOD_MILLISECONDS)
            }
        }

        private val locationListener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (!isCurrent()) return
                val measurement = createMeasurement(location)
                val estimate = estimator.onGnssMeasurement(measurement)
                emitGnssAvailable(activeGeneration)
                emitEstimate(estimate, activeGeneration)
            }

            override fun onProviderEnabled(provider: String) = Unit

            override fun onProviderDisabled(provider: String) {
                if (provider == LocationManager.GPS_PROVIDER && isCurrent()) {
                    updateSatelliteCount(0)
                    emitError("gps provider disabled", activeGeneration)
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        }

        private val gnssCallback = object : GnssStatus.Callback() {
            override fun onStopped() {
                if (isCurrent()) updateSatelliteCount(0)
            }

            override fun onSatelliteStatusChanged(status: GnssStatus) {
                if (!isCurrent()) return
                updateSatelliteCount((0 until status.satelliteCount).count(status::usedInFix))
            }
        }

        private val sensorListener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (!isCurrent()) return
                when (event.sensor.type) {
                    Sensor.TYPE_ROTATION_VECTOR -> updateRotation(event)
                    Sensor.TYPE_LINEAR_ACCELERATION -> updateAcceleration(event)
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
                if (sensor?.type == Sensor.TYPE_ROTATION_VECTOR &&
                    accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE
                ) {
                    rotationReliable = false
                }
            }
        }

        fun start() {
            updateSatelliteCount(0)
            if (!registerLocationCallbacks()) return
            updateSensorRegistration()
            workerHandler.postDelayed(estimateTick, OUTPUT_PERIOD_MILLISECONDS)
        }

        fun stop() {
            workerHandler.removeCallbacks(estimateTick)
            locationManager.removeUpdates(locationListener)
            runCatching { locationManager.unregisterGnssStatusCallback(gnssCallback) }
            sensorManager.unregisterListener(sensorListener)
            sensorsRegistered = false
            satelliteCount = 0
        }

        fun updateSensorRegistration() {
            sensorManager.unregisterListener(sensorListener)
            sensorsRegistered = false
            lastRotationTimestampNanos = 0L
            rotationReliable = false
            if (trackingMode != TrackingMode.FIXED || !supportsFixedMode || !isCurrent()) return

            val accelerationRegistered = sensorManager.registerListener(
                sensorListener,
                linearAccelerationSensor,
                SENSOR_PERIOD_MICROSECONDS,
                workerHandler
            )
            val rotationRegistered = sensorManager.registerListener(
                sensorListener,
                rotationVectorSensor,
                SENSOR_PERIOD_MICROSECONDS,
                workerHandler
            )
            sensorsRegistered = accelerationRegistered && rotationRegistered
            if (!sensorsRegistered) sensorManager.unregisterListener(sensorListener)
        }

        private fun registerLocationCallbacks(): Boolean {
            if (ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.ACCESS_FINE_LOCATION) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                emitError("Precise location permission required", activeGeneration)
                return false
            }

            return try {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    0L,
                    0f,
                    locationListener,
                    workerHandler.looper
                )
                if (!locationManager.registerGnssStatusCallback(gnssCallback, workerHandler)) {
                    locationManager.removeUpdates(locationListener)
                    emitError("Unable to monitor GNSS status", activeGeneration)
                    false
                } else {
                    true
                }
            } catch (exception: RuntimeException) {
                locationManager.removeUpdates(locationListener)
                emitError("Error starting GPS: ${exception.message}", activeGeneration)
                false
            }
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

            return GnssMeasurement(
                speedMetersPerSecond = speed,
                speedAccuracyMetersPerSecond = speedAccuracy,
                bearingDegrees = bearing,
                bearingAccuracyDegrees = bearingAccuracy,
                horizontalAccuracyMeters = horizontalAccuracy,
                magneticDeclinationDegrees = declination,
                satelliteCount = satelliteCount,
                timestampNanos = location.elapsedRealtimeNanos
            )
        }

        private fun updateRotation(event: SensorEvent) {
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
            SensorManager.getOrientation(rotationMatrix, orientation)
            lastRotationTimestampNanos = event.timestamp
            val headingAccuracy = event.values.getOrNull(4)?.toDouble()
            rotationReliable = event.accuracy != SensorManager.SENSOR_STATUS_UNRELIABLE &&
                (headingAccuracy == null || headingAccuracy < 0.0 ||
                    headingAccuracy <= MAX_HEADING_ACCURACY_RADIANS)
        }

        private fun updateAcceleration(event: SensorEvent) {
            if (!sensorsRegistered || lastRotationTimestampNanos == 0L ||
                abs(event.timestamp - lastRotationTimestampNanos) > MAX_ORIENTATION_AGE_NANOS
            ) return

            val deviceX = event.values[0]
            val deviceY = event.values[1]
            val deviceZ = event.values[2]
            val east = rotationMatrix[0] * deviceX + rotationMatrix[1] * deviceY + rotationMatrix[2] * deviceZ
            val north = rotationMatrix[3] * deviceX + rotationMatrix[4] * deviceY + rotationMatrix[5] * deviceZ
            val up = rotationMatrix[6] * deviceX + rotationMatrix[7] * deviceY + rotationMatrix[8] * deviceZ
            estimator.onMotionMeasurement(
                MotionMeasurement(
                    accelerationEastMetersPerSecondSquared = east.toDouble(),
                    accelerationMagneticNorthMetersPerSecondSquared = north.toDouble(),
                    accelerationUpMetersPerSecondSquared = up.toDouble(),
                    deviceYawRadians = orientation[0].toDouble(),
                    devicePitchRadians = orientation[1].toDouble(),
                    deviceRollRadians = orientation[2].toDouble(),
                    orientationReliable = rotationReliable,
                    timestampNanos = event.timestamp
                )
            )
        }

        private fun updateSatelliteCount(count: Int) {
            satelliteCount = count
            emitSatelliteCount(count, activeGeneration)
        }

        private fun isCurrent(): Boolean = isActive(activeGeneration) && session === this
    }

    private fun emitEstimate(estimate: SpeedEstimate, activeGeneration: Int) {
        mainHandler.post {
            if (isActive(activeGeneration)) onEstimate?.invoke(estimate)
        }
    }

    private fun emitSatelliteCount(count: Int, activeGeneration: Int) {
        mainHandler.post {
            if (isActive(activeGeneration)) onSatelliteCount?.invoke(count)
        }
    }

    private fun emitGnssAvailable(activeGeneration: Int) {
        mainHandler.post {
            if (isActive(activeGeneration)) onGnssAvailable?.invoke()
        }
    }

    private fun emitError(message: String, activeGeneration: Int) {
        mainHandler.post {
            if (isActive(activeGeneration)) onError?.invoke(message)
        }
    }

    private fun effectiveMode(requested: TrackingMode): TrackingMode =
        if (requested == TrackingMode.FIXED && supportsFixedMode) requested else TrackingMode.HANDHELD

    private fun isActive(activeGeneration: Int): Boolean = started && generation == activeGeneration

    private companion object {
        const val SENSOR_PERIOD_MICROSECONDS = 20_000
        const val OUTPUT_PERIOD_MILLISECONDS = 100L
        const val MAX_ORIENTATION_AGE_NANOS = 100_000_000L
        val MAX_HEADING_ACCURACY_RADIANS: Double = Math.toRadians(25.0)
    }
}
