package com.mightykatun.speedometer.app.domain.model

data class VesselHeading(
    val trueDegrees: Float,
    val accuracyDegrees: Float?,
    val timestampNanos: Long
)
