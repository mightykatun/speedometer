package com.mightykatun.speedometer.app.domain.model

enum class TrackingMode(val preferenceValue: String) {
    HANDHELD("handheld"),
    FIXED("fixed"),
    IMU_ONLY("imu_only");

    companion object {
        fun fromPreference(value: String?): TrackingMode =
            entries.firstOrNull { it.preferenceValue == value } ?: HANDHELD
    }
}
