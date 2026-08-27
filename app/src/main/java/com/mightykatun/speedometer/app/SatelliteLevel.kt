package com.mightykatun.speedometer.app

internal enum class SatelliteLevel(val accessibilityLabel: String) {
    NONE("none"),
    LIMITED("limited"),
    GOOD("good")
}

internal fun satelliteLevel(satelliteCount: Int): SatelliteLevel = when {
    satelliteCount <= 0 -> SatelliteLevel.NONE
    satelliteCount <= 5 -> SatelliteLevel.LIMITED
    else -> SatelliteLevel.GOOD
}
