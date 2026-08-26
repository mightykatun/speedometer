package com.mightykatun.speedometer.app

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
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
                trackingModeEnabled = true,
                supportsPip = true,
                permissionMessage = null,
                permissionCanRequest = false,
                onSpeedUnitClick = { unitClicks++ },
                onTrackingModeChange = { modeClicks++ },
                onRefreshRateChange = { refreshClicks++ },
                onReset = { resetClicks++ },
                onRetry = {},
                onEnterPip = { pipClicks++ },
                onRequestPermission = {},
                onOpenSettings = {}
            )
        }

        composeRule.onNodeWithText("speedometer v${BuildConfig.VERSION_NAME}").assertIsDisplayed()
        composeRule.onNodeWithText("--").assertIsDisplayed()
        composeRule.onNodeWithText("km/h")
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        composeRule.onNodeWithContentDescription("Tracking mode")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "gnss"))
            .performClick()
        composeRule.onNodeWithContentDescription("Refresh rate")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "1 second"))
            .performClick()
        composeRule.onNodeWithText("reset")
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        composeRule.onNodeWithText("float")
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
            .performClick()

        composeRule.runOnIdle {
            assertEquals(1, unitClicks)
            assertEquals(1, modeClicks)
            assertEquals(1, refreshClicks)
            assertEquals(1, resetClicks)
            assertEquals(1, pipClicks)
        }
    }

    @Test
    fun permissionRecoveryExposesOnlyRetryWhenPermissionCanBeRequested() {
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
                trackingModeEnabled = true,
                supportsPip = true,
                permissionMessage = "Precise location is required",
                permissionCanRequest = true,
                onSpeedUnitClick = {},
                onTrackingModeChange = {},
                onRefreshRateChange = {},
                onReset = {},
                onRetry = {},
                onEnterPip = {},
                onRequestPermission = { retryClicks++ },
                onOpenSettings = { settingsClicks++ }
            )
        }

        composeRule.onNodeWithText("Precise location is required").assertIsDisplayed()
        composeRule.onNodeWithText("grant location").performClick()
        composeRule.onNodeWithText("open settings").assertDoesNotExist()
        composeRule.onNodeWithText("reset").assertDoesNotExist()
        composeRule.onNodeWithText("float").assertDoesNotExist()

        composeRule.runOnIdle {
            assertEquals(1, retryClicks)
            assertEquals(0, settingsClicks)
        }
    }

    @Test
    fun permanentPermissionDenialExposesOnlySettings() {
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
                trackingModeEnabled = true,
                supportsPip = true,
                permissionMessage = "Location permission is required",
                permissionCanRequest = false,
                onSpeedUnitClick = {},
                onTrackingModeChange = {},
                onRefreshRateChange = {},
                onReset = {},
                onRetry = {},
                onEnterPip = {},
                onRequestPermission = {},
                onOpenSettings = { settingsClicks++ }
            )
        }

        composeRule.onNodeWithText("grant location").assertDoesNotExist()
        composeRule.onNodeWithText("open settings").performClick()
        composeRule.runOnIdle { assertEquals(1, settingsClicks) }
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
                trackingModeEnabled = true,
                supportsPip = false,
                permissionMessage = null,
                permissionCanRequest = false,
                onSpeedUnitClick = {},
                onTrackingModeChange = {},
                onRefreshRateChange = {},
                onReset = {},
                onRetry = {},
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
    fun blockingGpsErrorExposesRetry() {
        var retryClicks = 0
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
                trackingModeEnabled = true,
                supportsPip = true,
                permissionMessage = null,
                permissionCanRequest = false,
                onSpeedUnitClick = {},
                onTrackingModeChange = {},
                onRefreshRateChange = {},
                onReset = {},
                onRetry = { retryClicks++ },
                onEnterPip = {},
                onRequestPermission = {},
                onOpenSettings = {}
            )
        }

        composeRule.onNodeWithText("Unable to monitor GNSS status").assertIsDisplayed()
        composeRule.onNodeWithText("retry").performClick()
        composeRule.onNodeWithText("reset").assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(1, retryClicks) }
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
                trackingModeEnabled = true,
                supportsPip = false,
                permissionMessage = null,
                permissionCanRequest = false,
                onSpeedUnitClick = {},
                onTrackingModeChange = {},
                onRefreshRateChange = {},
                onReset = {},
                onRetry = {},
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
