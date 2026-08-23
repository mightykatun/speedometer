package com.mightykatun.speedometer.app

internal enum class AccuracyLevel {
    GOOD,
    FAIR,
    POOR
}

internal fun accuracyLevel(currentSpeed: Float?, accuracy: Float?): AccuracyLevel {
    if (currentSpeed == null || accuracy == null || currentSpeed <= 0f || accuracy < 0f) {
        return AccuracyLevel.POOR
    }
    val percentage = accuracy / currentSpeed * 100f
    return when {
        percentage <= 10f -> AccuracyLevel.GOOD
        percentage <= 20f -> AccuracyLevel.FAIR
        else -> AccuracyLevel.POOR
    }
}
