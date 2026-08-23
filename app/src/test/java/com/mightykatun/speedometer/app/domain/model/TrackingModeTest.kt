package com.mightykatun.speedometer.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class TrackingModeTest {
    @Test
    fun `restores persisted mode and defaults to handheld`() {
        assertEquals(TrackingMode.FIXED, TrackingMode.fromPreference("fixed"))
        assertEquals(TrackingMode.HANDHELD, TrackingMode.fromPreference("handheld"))
        assertEquals(TrackingMode.HANDHELD, TrackingMode.fromPreference(null))
        assertEquals(TrackingMode.HANDHELD, TrackingMode.fromPreference("unsupported"))
    }
}
