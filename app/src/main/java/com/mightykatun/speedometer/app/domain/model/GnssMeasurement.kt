package com.mightykatun.speedometer.app.domain.model

data class GnssMeasurement(
    val speedMetersPerSecond: Double?,
    val speedAccuracyMetersPerSecond: Double?,
    val courseOverGroundDegrees: Double?,
    val courseOverGroundAccuracyDegrees: Double?,
    val horizontalAccuracyMeters: Double?,
    val magneticDeclinationDegrees: Double?,
    val satelliteCount: Int,
    val timestampNanos: Long
)
