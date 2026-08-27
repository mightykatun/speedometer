package com.mightykatun.speedometer.app.domain.model

data class PositionFix(
    val latitudeDegrees: Double,
    val longitudeDegrees: Double,
    val headingDegrees: Float?,
    val horizontalAccuracyMeters: Float,
    val timestampNanos: Long,
    val altitudeMeters: Double? = null
)
