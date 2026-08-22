package com.mightykatun.speedometer.app.domain.model

data class MotionMeasurement(
    val accelerationEastMetersPerSecondSquared: Double,
    val accelerationMagneticNorthMetersPerSecondSquared: Double,
    val accelerationUpMetersPerSecondSquared: Double,
    val deviceYawRadians: Double,
    val devicePitchRadians: Double,
    val deviceRollRadians: Double,
    val orientationReliable: Boolean,
    val timestampNanos: Long
)
