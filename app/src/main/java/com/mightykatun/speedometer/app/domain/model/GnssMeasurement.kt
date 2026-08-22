package com.mightykatun.speedometer.app.domain.model

data class GnssMeasurement(
    val speedMetersPerSecond: Double?,
    val speedAccuracyMetersPerSecond: Double?,
    val bearingDegrees: Double?,
    val bearingAccuracyDegrees: Double?,
    val horizontalAccuracyMeters: Double?,
    val magneticDeclinationDegrees: Double?,
    val satelliteCount: Int,
    val timestampNanos: Long
)
