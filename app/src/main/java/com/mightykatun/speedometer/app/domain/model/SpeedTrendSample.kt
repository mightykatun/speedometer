package com.mightykatun.speedometer.app.domain.model

data class SpeedTrendSample(
    val timestampNanos: Long,
    val speedKmh: Float?
)
