package com.mightykatun.speedometer.app.data.repository

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
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

internal interface RepositoryWorker {
    fun post(block: () -> Unit): Boolean
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
    fun register(listener: SensorEventListener): Boolean
    fun unregister(listener: SensorEventListener)
}

internal data class RepositoryDependencies(
    val worker: RepositoryWorker,
    val mainDispatcher: RepositoryMainDispatcher,
    val locationGateway: RepositoryLocationGateway,
    val motionGateway: RepositoryMotionGateway
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
            worker.handler
        ),
        motionGateway = AndroidRepositoryMotionGateway(sensorManager, worker.handler)
    )
}

private class HandlerRepositoryWorker : RepositoryWorker {
    private val thread = HandlerThread("speed-sensors").apply { start() }
    val handler = Handler(thread.looper)

    override fun post(block: () -> Unit): Boolean = handler.post(block)

    override fun postDelayed(runnable: Runnable, delayMillis: Long) {
        handler.postDelayed(runnable, delayMillis)
    }

    override fun removeCallbacks(runnable: Runnable) {
        handler.removeCallbacks(runnable)
    }

    override fun elapsedRealtimeNanos(): Long = SystemClock.elapsedRealtimeNanos()

    override fun close() {
        thread.quitSafely()
    }
}

private class AndroidRepositoryLocationGateway(
    private val context: Context,
    private val locationManager: LocationManager,
    private val workerHandler: Handler
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
            workerHandler.looper
        )
    }

    @SuppressLint("MissingPermission")
    override fun registerGnssStatusCallback(callback: GnssStatus.Callback): Boolean =
        locationManager.registerGnssStatusCallback(callback, workerHandler)

    override fun removeLocationUpdates(listener: LocationListener) {
        locationManager.removeUpdates(listener)
    }

    override fun unregisterGnssStatusCallback(callback: GnssStatus.Callback) {
        locationManager.unregisterGnssStatusCallback(callback)
    }
}

private class AndroidRepositoryMotionGateway(
    private val sensorManager: SensorManager,
    private val workerHandler: Handler
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
                workerHandler
            )
            val rotationRegistered = sensorManager.registerListener(
                listener,
                requireNotNull(rotationVectorSensor),
                SENSOR_PERIOD_MICROSECONDS,
                workerHandler
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
