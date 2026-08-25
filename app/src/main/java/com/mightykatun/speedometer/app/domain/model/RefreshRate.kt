package com.mightykatun.speedometer.app.domain.model

enum class RefreshRate(
    val intervalMillis: Int,
    val preferenceValue: String,
    val displayLabel: String,
    val accessibilityLabel: String
) {
    HALF_SECOND(500, "500", "0.5s", "0.5 seconds"),
    ONE_SECOND(1_000, "1000", "1s", "1 second"),
    TWO_SECONDS(2_000, "2000", "2s", "2 seconds");

    val intervalNanos: Long
        get() = intervalMillis * 1_000_000L

    fun next(): RefreshRate = entries[(ordinal + 1) % entries.size]

    companion object {
        fun fromPreference(value: String?): RefreshRate =
            entries.firstOrNull { it.preferenceValue == value } ?: ONE_SECOND
    }
}
