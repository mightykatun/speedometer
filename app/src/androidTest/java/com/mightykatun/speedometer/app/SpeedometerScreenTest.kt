package com.mightykatun.speedometer.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.mightykatun.speedometer.app.domain.model.SpeedUnit
import com.mightykatun.speedometer.app.domain.model.SpeedometerState
import com.mightykatun.speedometer.app.domain.model.TrackingMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SpeedometerScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun controlsInvokeTheirCallbacks() {
        var unitClicks = 0
        var requestedFixedMode: Boolean? = null
        var pipClicks = 0

        composeRule.setContent {
            SpeedometerScreen(
                state = SpeedometerState(),
                error = null,
                warning = null,
                isInPipMode = false,
                speedUnit = SpeedUnit.KILOMETERS_PER_HOUR,
                trackingMode = TrackingMode.HANDHELD,
                supportsFixedMode = true,
                supportsPip = true,
                permissionMessage = null,
                onSpeedUnitClick = { unitClicks++ },
                onTrackingModeChange = { requestedFixedMode = it },
                onEnterPip = { pipClicks++ },
                onRequestPermission = {},
                onOpenSettings = {}
            )
        }

        composeRule.onNodeWithText("km/h").performClick()
        composeRule.onNodeWithContentDescription("Tracking mode").performClick()
        composeRule.onNodeWithText("float").performClick()

        composeRule.runOnIdle {
            assertEquals(1, unitClicks)
            assertEquals(true, requestedFixedMode)
            assertEquals(1, pipClicks)
        }
    }

    @Test
    fun permissionRecoveryExposesRetryAndSettingsActions() {
        var retryClicks = 0
        var settingsClicks = 0

        composeRule.setContent {
            SpeedometerScreen(
                state = SpeedometerState(),
                error = null,
                warning = null,
                isInPipMode = false,
                speedUnit = SpeedUnit.KILOMETERS_PER_HOUR,
                trackingMode = TrackingMode.HANDHELD,
                supportsFixedMode = true,
                supportsPip = true,
                permissionMessage = "Precise location is required",
                onSpeedUnitClick = {},
                onTrackingModeChange = {},
                onEnterPip = {},
                onRequestPermission = { retryClicks++ },
                onOpenSettings = { settingsClicks++ }
            )
        }

        composeRule.onNodeWithText("Precise location is required").assertIsDisplayed()
        composeRule.onNodeWithText("grant location").performClick()
        composeRule.onNodeWithText("open settings").performClick()
        composeRule.onNodeWithText("float").assertDoesNotExist()

        composeRule.runOnIdle {
            assertEquals(1, retryClicks)
            assertEquals(1, settingsClicks)
        }
    }
}
