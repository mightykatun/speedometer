package com.mightykatun.speedometer.app.domain.model

data class SpeedEstimate(
    val speedMetersPerSecond: Double?,
    val uncertaintyMetersPerSecond: Double,
    val quality: EstimateQuality,
    val trustedForMaximum: Boolean,
    val timestampNanos: Long
)
