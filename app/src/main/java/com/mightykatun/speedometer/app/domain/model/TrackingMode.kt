package com.mightykatun.speedometer.app.domain.model

enum class TrackingMode(val preferenceValue: String) {
    HANDHELD("handheld"),
    FIXED("fixed");

    companion object {
        fun fromPreference(value: String?): TrackingMode =
            entries.firstOrNull { it.preferenceValue == value } ?: HANDHELD
    }
}
