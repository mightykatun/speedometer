package com.mightykatun.speedometer.app.domain.model

data class SessionConfig(
    val warmupPeriodMillis: Long = 5000L,
    val minSatellitesForTracking: Int = 3
)
