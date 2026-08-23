package com.mightykatun.speedometer.app.domain.model

import com.mightykatun.speedometer.app.trackingModeTransitionRequiresReset
import org.junit.Assert.assertEquals
import org.junit.Test

class TrackingModeTest {
    @Test
    fun `restores persisted mode and defaults to handheld`() {
        assertEquals(TrackingMode.FIXED, TrackingMode.fromPreference("fixed"))
        assertEquals(TrackingMode.IMU_ONLY, TrackingMode.fromPreference("imu_only"))
        assertEquals(TrackingMode.HANDHELD, TrackingMode.fromPreference("handheld"))
        assertEquals(TrackingMode.HANDHELD, TrackingMode.fromPreference(null))
        assertEquals(TrackingMode.HANDHELD, TrackingMode.fromPreference("unsupported"))
    }

    @Test
    fun `only location-free IMU boundaries require a session reset`() {
        assertEquals(false, trackingModeTransitionRequiresReset(TrackingMode.HANDHELD, TrackingMode.FIXED))
        assertEquals(true, trackingModeTransitionRequiresReset(TrackingMode.FIXED, TrackingMode.IMU_ONLY))
        assertEquals(true, trackingModeTransitionRequiresReset(TrackingMode.IMU_ONLY, TrackingMode.HANDHELD))
    }
}
