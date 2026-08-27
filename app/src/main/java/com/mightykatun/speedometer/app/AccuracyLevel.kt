package com.mightykatun.speedometer.app

import kotlin.math.atan

internal enum class AccuracyLevel {
    GOOD,
    FAIR,
    POOR
}

internal fun accuracyLevel(
    currentSpeedMetersPerSecond: Float?,
    uncertaintyMetersPerSecond: Float?
): AccuracyLevel {
    if (currentSpeedMetersPerSecond == null || uncertaintyMetersPerSecond == null ||
        !currentSpeedMetersPerSecond.isFinite() || !uncertaintyMetersPerSecond.isFinite() ||
        currentSpeedMetersPerSecond <= 0f || uncertaintyMetersPerSecond < 0f
    ) {
        return AccuracyLevel.POOR
    }
    val speed = currentSpeedMetersPerSecond.toDouble()
    val percentage = uncertaintyMetersPerSecond / currentSpeedMetersPerSecond * 100f
    return when {
        percentage <= greenUncertaintyThreshold(speed) -> AccuracyLevel.GOOD
        percentage <= orangeUncertaintyThreshold(speed) -> AccuracyLevel.FAIR
        else -> AccuracyLevel.POOR
    }
}

internal fun greenUncertaintyThreshold(speedMetersPerSecond: Double): Double =
    20.0 / (1.0 + speedMetersPerSecond * speedMetersPerSecond) +
        10.0 - 5.0 * atan(speedMetersPerSecond / 10.0)

internal fun orangeUncertaintyThreshold(speedMetersPerSecond: Double): Double =
    20.0 / (1.0 + speedMetersPerSecond * speedMetersPerSecond) +
        20.0 - 10.0 * atan(speedMetersPerSecond / 5.0)
