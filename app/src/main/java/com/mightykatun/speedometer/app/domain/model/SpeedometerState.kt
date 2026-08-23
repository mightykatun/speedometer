package com.mightykatun.speedometer.app.domain.model

data class SpeedometerState(
    val currentSpeedKmh: Float? = null,
    val speedAccuracyKmh: Float? = null,
    val estimateQuality: EstimateQuality = EstimateQuality.ACQUIRING,
    val maxSpeedKmh: Float = 0f,
    val satelliteCount: Int = 0,
    val maxSatelliteCount: Int = 0,
    val speedTrend: List<SpeedTrendSample> = emptyList()
)
