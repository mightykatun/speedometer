package com.mightykatun.speedometer.app.domain.model

data class MotionMeasurement(
    val accelerationEastMetersPerSecondSquared: Double,
    val accelerationMagneticNorthMetersPerSecondSquared: Double,
    val accelerationUpMetersPerSecondSquared: Double,
    val deviceYawRadians: Double,
    val devicePitchRadians: Double,
    val deviceRollRadians: Double,
    val orientationReliable: Boolean,
    val timestampNanos: Long,
    val orientationTimestampNanos: Long = timestampNanos,
    val accelerationDeviceXMetersPerSecondSquared: Double =
        accelerationEastMetersPerSecondSquared,
    val accelerationDeviceYMetersPerSecondSquared: Double =
        accelerationMagneticNorthMetersPerSecondSquared,
    val accelerationDeviceZMetersPerSecondSquared: Double =
        accelerationUpMetersPerSecondSquared
)
