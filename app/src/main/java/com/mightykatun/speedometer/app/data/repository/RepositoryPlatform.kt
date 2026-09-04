package com.mightykatun.speedometer.app.data.repository

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.GnssStatus
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.mightykatun.speedometer.app.domain.geomagnetic.GeomagneticFieldEstimate
import com.mightykatun.speedometer.app.domain.geomagnetic.WorldMagneticModel2025

internal interface RepositoryWorker {
    fun post(block: () -> Unit): Boolean
    fun postIfRunning(block: () -> Unit): Boolean
    fun postDelayed(runnable: Runnable, delayMillis: Long)
    fun removeCallbacks(runnable: Runnable)
    fun elapsedRealtimeNanos(): Long
    fun close()
}

internal fun interface RepositoryMainDispatcher {
    fun post(block: () -> Unit)
}

internal interface RepositoryLocationGateway {
    fun hasFineLocationPermission(): Boolean
    fun requestLocationUpdates(listener: LocationListener)
    fun registerGnssStatusCallback(callback: GnssStatus.Callback): Boolean
    fun removeLocationUpdates(listener: LocationListener)
    fun unregisterGnssStatusCallback(callback: GnssStatus.Callback)
}

internal interface RepositoryMotionGateway {
    val supportsFixedMode: Boolean
    // A failed registration must clean up any listener registered during the attempt.
    fun register(listener: SensorEventListener): Boolean
    fun unregister(listener: SensorEventListener)
}

internal interface RepositoryHeadingListener {
    fun onHeadingSample(sample: HeadingSensorSample)
    fun onHeadingUnavailable()
}

internal interface RepositoryHeadingGateway {
    val supportsHeading: Boolean
    // A failed registration must clean up any listener registered during the attempt.
    fun register(listener: RepositoryHeadingListener): Boolean
    fun unregister()
}

internal fun interface RepositoryGeomagneticModel {
    fun evaluate(
        latitudeDegrees: Double,
        longitudeDegrees: Double,
        altitudeMeters: Double,
        utcTimeMillis: Long
    ): GeomagneticFieldEstimate?
}

internal data class RepositoryDependencies(
    val worker: RepositoryWorker,
    val mainDispatcher: RepositoryMainDispatcher,
    val locationGateway: RepositoryLocationGateway,
    val motionGateway: RepositoryMotionGateway,
    val headingGateway: RepositoryHeadingGateway,
    val geomagneticModel: RepositoryGeomagneticModel
)

internal fun productionRepositoryDependencies(context: Context): RepositoryDependencies {
    val applicationContext = context.applicationContext
    val worker = HandlerRepositoryWorker()
    val mainHandler = Handler(Looper.getMainLooper())
    val locationManager =
        applicationContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    val sensorManager =
        applicationContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    return RepositoryDependencies(
        worker = worker,
        mainDispatcher = RepositoryMainDispatcher { block ->
            mainHandler.post(block)
        },
        locationGateway = AndroidRepositoryLocationGateway(
            applicationContext,
            locationManager,
            worker::handler
        ),
        motionGateway = AndroidRepositoryMotionGateway(sensorManager, worker::handler),
        headingGateway = AndroidRepositoryHeadingGateway(sensorManager, worker::handler),
        geomagneticModel = RepositoryGeomagneticModel(WorldMagneticModel2025::evaluate)
    )
}

private class HandlerRepositoryWorker : RepositoryWorker {
    private val lock = Any()
    private var thread: HandlerThread? = null
    private var workerHandler: Handler? = null
    private var closed = false

    fun handler(): Handler = requireNotNull(handlerOrNull())

    override fun post(block: () -> Unit): Boolean = handlerOrNull()?.post(block) == true

    override fun postIfRunning(block: () -> Unit): Boolean {
        val handler = synchronized(lock) {
            if (closed) null else workerHandler
        } ?: return false
        return handler.post(block)
    }

    override fun postDelayed(runnable: Runnable, delayMillis: Long) {
        handlerOrNull()?.postDelayed(runnable, delayMillis)
    }

    override fun removeCallbacks(runnable: Runnable) {
        synchronized(lock) { workerHandler }?.removeCallbacks(runnable)
    }

    override fun elapsedRealtimeNanos(): Long = SystemClock.elapsedRealtimeNanos()

    override fun close() {
        val threadToClose = synchronized(lock) {
            if (closed) return
            closed = true
            workerHandler = null
            thread.also { thread = null }
        }
        threadToClose?.quitSafely()
    }

    private fun handlerOrNull(): Handler? = synchronized(lock) {
        if (closed) return@synchronized null
        workerHandler ?: Handler(
            HandlerThread("speed-sensors").also {
                it.start()
                thread = it
            }.looper
        ).also { workerHandler = it }
    }
}

private class AndroidRepositoryLocationGateway(
    private val context: Context,
    private val locationManager: LocationManager,
    private val workerHandler: () -> Handler
) : RepositoryLocationGateway {
    override fun hasFineLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    override fun requestLocationUpdates(listener: LocationListener) {
        locationManager.requestLocationUpdates(
            LocationManager.GPS_PROVIDER,
            0L,
            0f,
            listener,
            workerHandler().looper
        )
    }

    @SuppressLint("MissingPermission")
    override fun registerGnssStatusCallback(callback: GnssStatus.Callback): Boolean =
        locationManager.registerGnssStatusCallback(callback, workerHandler())

    override fun removeLocationUpdates(listener: LocationListener) {
        locationManager.removeUpdates(listener)
    }

    override fun unregisterGnssStatusCallback(callback: GnssStatus.Callback) {
        locationManager.unregisterGnssStatusCallback(callback)
    }
}

private class AndroidRepositoryMotionGateway(
    private val sensorManager: SensorManager,
    private val workerHandler: () -> Handler
) : RepositoryMotionGateway {
    private val linearAccelerationSensor =
        sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    private val rotationVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    override val supportsFixedMode: Boolean =
        linearAccelerationSensor != null && rotationVectorSensor != null

    override fun register(listener: SensorEventListener): Boolean {
        if (!supportsFixedMode) return false
        var complete = false
        try {
            val accelerationRegistered = sensorManager.registerListener(
                listener,
                linearAccelerationSensor,
                SENSOR_PERIOD_MICROSECONDS,
                workerHandler()
            )
            val rotationRegistered = sensorManager.registerListener(
                listener,
                requireNotNull(rotationVectorSensor),
                SENSOR_PERIOD_MICROSECONDS,
                workerHandler()
            )
            complete = accelerationRegistered && rotationRegistered
            return complete
        } finally {
            if (!complete) sensorManager.unregisterListener(listener)
        }
    }

    override fun unregister(listener: SensorEventListener) {
        sensorManager.unregisterListener(listener)
    }

    private companion object {
        const val SENSOR_PERIOD_MICROSECONDS = 20_000
    }
}

private class AndroidRepositoryHeadingGateway(
    private val sensorManager: SensorManager,
    private val workerHandler: () -> Handler
) : RepositoryHeadingGateway {
    private val primarySensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val fallbackSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)
    private var activeBridge: SensorEventListener? = null

    override val supportsHeading: Boolean = primarySensor != null || fallbackSensor != null

    override fun register(listener: RepositoryHeadingListener): Boolean {
        unregister()
        val candidates = listOfNotNull(
            primarySensor?.let { it to HeadingSensorSource.ROTATION_VECTOR },
            fallbackSensor?.takeIf { it !== primarySensor }
                ?.let { it to HeadingSensorSource.GEOMAGNETIC_ROTATION_VECTOR }
        )
        for ((sensor, source) in candidates) {
            val bridge = HeadingSensorBridge(listener, source)
            val registered = runCatching {
                sensorManager.registerListener(
                    bridge,
                    sensor,
                    HEADING_SENSOR_PERIOD_MICROSECONDS,
                    workerHandler()
                )
            }.getOrDefault(false)
            if (registered) {
                activeBridge = bridge
                return true
            }
            runCatching { sensorManager.unregisterListener(bridge) }
        }
        return false
    }

    override fun unregister() {
        val bridge = activeBridge
        activeBridge = null
        if (bridge != null) runCatching { sensorManager.unregisterListener(bridge) }
    }

    private class HeadingSensorBridge(
        private val listener: RepositoryHeadingListener,
        private val source: HeadingSensorSource
    ) : SensorEventListener {
        private val rotationMatrix = FloatArray(9)

        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR &&
                event.sensor.type != Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR
            ) return
            val sample = runCatching {
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                createHeadingSensorSample(
                    rotationMatrix = rotationMatrix,
                    reportedAccuracyRadians = event.values.getOrNull(4)?.toDouble(),
                    sensorAccuracy = event.accuracy.toHeadingSensorAccuracy(),
                    source = source,
                    timestampNanos = event.timestamp
                )
            }.getOrNull()
            if (sample == null) listener.onHeadingUnavailable() else listener.onHeadingSample(sample)
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
            if (accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE) {
                listener.onHeadingUnavailable()
            }
        }
    }

    private companion object {
        const val HEADING_SENSOR_PERIOD_MICROSECONDS = 20_000
    }
}

private fun Int.toHeadingSensorAccuracy(): HeadingSensorAccuracy = when (this) {
    SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> HeadingSensorAccuracy.HIGH
    SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> HeadingSensorAccuracy.MEDIUM
    SensorManager.SENSOR_STATUS_ACCURACY_LOW -> HeadingSensorAccuracy.LOW
    else -> HeadingSensorAccuracy.UNRELIABLE
}
