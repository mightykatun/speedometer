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
}
