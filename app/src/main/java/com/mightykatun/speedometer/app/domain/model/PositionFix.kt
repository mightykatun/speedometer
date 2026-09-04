package com.mightykatun.speedometer.app.domain.model

data class PositionFix(
    val latitudeDegrees: Double,
    val longitudeDegrees: Double,
    val courseOverGroundDegrees: Float?,
    val horizontalAccuracyMeters: Float,
    val timestampNanos: Long,
    val altitudeMeters: Double? = null,
    val utcTimeMillis: Long? = null,
    val groundSpeedMetersPerSecond: Float? = null,
    val groundSpeedAccuracyMetersPerSecond: Float? = null,
    val courseAccuracyDegrees: Float? = null,
    val groundVelocityAccepted: Boolean = false
)
