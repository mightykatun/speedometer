package com.mightykatun.speedometer.app.domain.model

enum class SpeedUnit(
    val label: String,
    val preferenceValue: String,
    private val kilometersPerHourMultiplier: Float
) {
    KILOMETERS_PER_HOUR("km/h", "kmh", 1f),
    MILES_PER_HOUR("mph", "mph", 0.6213712f),
    KNOTS("kts", "knots", 0.5399568f),
    METERS_PER_SECOND("m/s", "mps", 1f / 3.6f);

    fun fromKilometersPerHour(speedKmh: Float): Float =
        speedKmh * kilometersPerHourMultiplier

    fun next(): SpeedUnit = entries[(ordinal + 1) % entries.size]

    companion object {
        fun fromPreference(value: String?): SpeedUnit =
            entries.firstOrNull { it.preferenceValue == value } ?: KILOMETERS_PER_HOUR
    }
}
