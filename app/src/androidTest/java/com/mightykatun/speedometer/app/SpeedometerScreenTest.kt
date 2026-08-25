package com.mightykatun.speedometer.app

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.mightykatun.speedometer.app.domain.model.EstimateQuality
import com.mightykatun.speedometer.app.domain.model.RefreshRate
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
        var modeClicks = 0
        var refreshClicks = 0
        var resetClicks = 0
        var pipClicks = 0

        composeRule.setContent {
            SpeedometerScreen(
                state = SpeedometerState(),
                error = null,
                warning = null,
                signalMessage = null,
                isInPipMode = false,
                speedUnit = SpeedUnit.KILOMETERS_PER_HOUR,
                trackingMode = TrackingMode.HANDHELD,
                refreshRate = RefreshRate.ONE_SECOND,
                supportsFixedMode = true,
                supportsPip = true,
                permissionMessage = null,
                onSpeedUnitClick = { unitClicks++ },
                onTrackingModeChange = { modeClicks++ },
                onRefreshRateChange = { refreshClicks++ },
                onReset = { resetClicks++ },
                onEnterPip = { pipClicks++ },
                onRequestPermission = {},
                onOpenSettings = {}
            )
        }

        composeRule.onNodeWithText("speedometer v${BuildConfig.VERSION_NAME}").assertIsDisplayed()
        composeRule.onNodeWithText("--").assertIsDisplayed()
        composeRule.onNodeWithText("km/h").performClick()
        composeRule.onNodeWithContentDescription("Tracking mode")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "gnss"))
            .performClick()
        composeRule.onNodeWithContentDescription("Refresh rate")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "1 second"))
            .performClick()
        composeRule.onNodeWithText("reset").performClick()
        composeRule.onNodeWithText("float").performClick()

        composeRule.runOnIdle {
            assertEquals(1, unitClicks)
            assertEquals(1, modeClicks)
            assertEquals(1, refreshClicks)
            assertEquals(1, resetClicks)
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
                signalMessage = null,
                isInPipMode = false,
                speedUnit = SpeedUnit.KILOMETERS_PER_HOUR,
                trackingMode = TrackingMode.HANDHELD,
                refreshRate = RefreshRate.ONE_SECOND,
                supportsFixedMode = true,
                supportsPip = true,
                permissionMessage = "Precise location is required",
                onSpeedUnitClick = {},
                onTrackingModeChange = {},
                onRefreshRateChange = {},
                onReset = {},
                onEnterPip = {},
                onRequestPermission = { retryClicks++ },
                onOpenSettings = { settingsClicks++ }
            )
        }

        composeRule.onNodeWithText("Precise location is required").assertIsDisplayed()
        composeRule.onNodeWithText("grant location").performClick()
        composeRule.onNodeWithText("open settings").performClick()
        composeRule.onNodeWithText("reset").assertDoesNotExist()
        composeRule.onNodeWithText("float").assertDoesNotExist()

        composeRule.runOnIdle {
            assertEquals(1, retryClicks)
            assertEquals(1, settingsClicks)
        }
    }

    @Test
    fun providerDisabledUsesInlineStatusOnMainHud() {
        composeRule.setContent {
            SpeedometerScreen(
                state = SpeedometerState(
                    currentSpeedKmh = 72f,
                    estimateQuality = EstimateQuality.TRACKING
                ),
                error = null,
                warning = null,
                signalMessage = "gps provider disabled",
                isInPipMode = false,
                speedUnit = SpeedUnit.KILOMETERS_PER_HOUR,
                trackingMode = TrackingMode.HANDHELD,
                refreshRate = RefreshRate.ONE_SECOND,
                supportsFixedMode = true,
                supportsPip = false,
                permissionMessage = null,
                onSpeedUnitClick = {},
                onTrackingModeChange = {},
                onRefreshRateChange = {},
                onReset = {},
                onEnterPip = {},
                onRequestPermission = {},
                onOpenSettings = {}
            )
        }

        composeRule.onNodeWithText("gps provider disabled").assertIsDisplayed()
        composeRule.onNodeWithText("--").assertIsDisplayed()
        composeRule.onNodeWithText("reset").assertIsDisplayed()
    }

    @Test
    fun blockingGpsErrorHasNoReset() {
        composeRule.setContent {
            SpeedometerScreen(
                state = SpeedometerState(),
                error = "Unable to monitor GNSS status",
                warning = null,
                signalMessage = null,
                isInPipMode = false,
                speedUnit = SpeedUnit.KILOMETERS_PER_HOUR,
                trackingMode = TrackingMode.HANDHELD,
                refreshRate = RefreshRate.ONE_SECOND,
                supportsFixedMode = true,
                supportsPip = true,
                permissionMessage = null,
                onSpeedUnitClick = {},
                onTrackingModeChange = {},
                onRefreshRateChange = {},
                onReset = {},
                onEnterPip = {},
                onRequestPermission = {},
                onOpenSettings = {}
            )
        }

        composeRule.onNodeWithText("Unable to monitor GNSS status").assertIsDisplayed()
        composeRule.onNodeWithText("reset").assertDoesNotExist()
    }

    @Test
    fun unavailableSpeedWithSatellitesDoesNotClaimNoSignal() {
        composeRule.setContent {
            SpeedometerScreen(
                state = SpeedometerState(
                    estimateQuality = EstimateQuality.UNAVAILABLE,
                    satelliteCount = 6
                ),
                error = null,
                warning = null,
                signalMessage = null,
                isInPipMode = false,
                speedUnit = SpeedUnit.KILOMETERS_PER_HOUR,
                trackingMode = TrackingMode.HANDHELD,
                refreshRate = RefreshRate.ONE_SECOND,
                supportsFixedMode = true,
                supportsPip = false,
                permissionMessage = null,
                onSpeedUnitClick = {},
                onTrackingModeChange = {},
                onRefreshRateChange = {},
                onReset = {},
                onEnterPip = {},
                onRequestPermission = {},
                onOpenSettings = {}
            )
        }

        composeRule.onNodeWithText("6").assertIsDisplayed()
        composeRule.onNodeWithText("speed unavailable").assertIsDisplayed()
        composeRule.onNodeWithText("no signal").assertDoesNotExist()
    }
}
