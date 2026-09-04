package com.mightykatun.speedometer.app

import org.junit.Assert.assertEquals
import org.junit.Test

class MainActivityLogicTest {
    @Test
    fun `permission recovery distinguishes retry and settings-only states`() {
        assertEquals(
            LocationPermissionIssue.DENIED_CAN_RETRY,
            locationPermissionIssue(preciseRequired = false, canRequest = true)
        )
        assertEquals(
            LocationPermissionIssue.DENIED_SETTINGS_ONLY,
            locationPermissionIssue(preciseRequired = false, canRequest = false)
        )
        assertEquals(
            LocationPermissionIssue.PRECISE_CAN_RETRY,
            locationPermissionIssue(preciseRequired = true, canRequest = true)
        )
        assertEquals(
            LocationPermissionIssue.PRECISE_SETTINGS_ONLY,
            locationPermissionIssue(preciseRequired = true, canRequest = false)
        )
    }

    @Test
    fun `regatta values use fixed-width heading and whole SI metrics`() {
        assertEquals("005", formattedRegattaHeading(5f))
        assertEquals("000", formattedRegattaHeading(359.6f))
        assertEquals("--", formattedRegattaHeading(Float.NaN))
        assertEquals("24 m", formattedDistanceToLine(23.6))
        assertEquals("-3 m", formattedDistanceToLine(-3.2))
        assertEquals("-- m", formattedDistanceToLine(null))
        assertEquals("18 s", formattedTimeToLine(18.4))
        assertEquals("-- s", formattedTimeToLine(-1.0))
    }
}
