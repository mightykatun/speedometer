package com.mightykatun.speedometer.app.domain.model

data class RegattaMark(
    val latitudeDegrees: Double,
    val longitudeDegrees: Double,
    val horizontalAccuracyMeters: Float
)

data class RegattaMetrics(
    val signedDistanceToLineMeters: Double? = null,
    val timeToLineSeconds: Double? = null
)
