package com.mightykatun.speedometer.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import com.mightykatun.speedometer.app.domain.model.EstimateQuality
import com.mightykatun.speedometer.app.domain.model.PositionFix
import com.mightykatun.speedometer.app.domain.model.RefreshRate
import com.mightykatun.speedometer.app.domain.model.SpeedUnit
import com.mightykatun.speedometer.app.domain.model.SpeedometerState
import com.mightykatun.speedometer.app.domain.model.TrackingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
        var speedFocusToggles = 0

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
                onOpenSettings = {},
                onSpeedDoubleTap = { speedFocusToggles++ }
            )
        }

        composeRule.onNodeWithText("v${BuildConfig.VERSION_NAME}").assertIsDisplayed()
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
            .performClick()
        composeRule.onNodeWithText("float")
            .assertWidthIsAtLeast(48.dp)
            .performClick()
        composeRule.onNodeWithContentDescription("Speed display")
            .performTouchInput { doubleClick() }

        composeRule.runOnIdle {
            assertEquals(1, unitClicks)
            assertEquals(1, modeClicks)
            assertEquals(1, refreshClicks)
            assertEquals(1, resetClicks)
            assertEquals(1, pipClicks)
            assertEquals(1, speedFocusToggles)
        }
    }

    @Test
    fun threeDigitSpeedAndUnitFitOnNarrowPortrait() {
        composeRule.setContent {
            Box(modifier = Modifier.size(320.dp, 600.dp)) {
                SpeedometerScreen(
                    state = SpeedometerState(
                        currentSpeedKmh = 100f,
                        estimateQuality = EstimateQuality.TRACKING,
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
        }

        val speedBounds = composeRule.onNodeWithContentDescription("Speed display")
            .fetchSemanticsNode().boundsInRoot
        val unitBounds = composeRule.onNodeWithText("km/h").fetchSemanticsNode().boundsInRoot
        val screenWidth = with(composeRule.density) { 320.dp.toPx() }
        val touchTargetHeight = with(composeRule.density) { 48.dp.toPx() }

        assertTrue(speedBounds.right <= unitBounds.left)
        assertTrue(unitBounds.right <= screenWidth)
        assertTrue(unitBounds.height <= touchTargetHeight + 1f)
    }

    @Test
    fun focusedSpeedDisplayHidesChromeButKeepsStatusLine() {
        val current = PositionFix(51.0, 4.0, 90f, 5f, 2_000_000_000L)
        composeRule.setContent {
            SpeedometerScreen(
                state = SpeedometerState(
                    currentSpeedKmh = 72f,
                    speedAccuracyKmh = 2f,
                    estimateQuality = EstimateQuality.TRACKING,
                    maxSpeedKmh = 90f,
                    satelliteCount = 6,
                    maxSatelliteCount = 8,
                    currentPosition = current,
                    positionTrail = listOf(current)
                ),
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
                onSpeedUnitClick = {},
                onTrackingModeChange = {},
                onRefreshRateChange = {},
                onReset = {},
                onRetry = {},
                onEnterPip = {},
                onRequestPermission = {},
                onOpenSettings = {},
                isSpeedFocusMode = true
            )
        }

        composeRule.onNodeWithContentDescription("Speed display").assertIsDisplayed()
        composeRule.onNodeWithText("72").assertIsDisplayed()
        composeRule.onNodeWithText(".00").assertIsDisplayed()
        composeRule.onNodeWithText("km/h").assertIsDisplayed()
        composeRule.onNodeWithText("± 2.0 km/h").assertIsDisplayed()
        composeRule.onNodeWithText("v${BuildConfig.VERSION_NAME}").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("North-up position trail").assertDoesNotExist()
        composeRule.onNodeWithText("top speed: ").assertDoesNotExist()
        composeRule.onNodeWithText("reset").assertDoesNotExist()
        composeRule.onNodeWithText("float").assertDoesNotExist()
    }

    @Test
    fun landscapeAlwaysUsesLargeFocusedSpeedDisplay() {
        composeRule.setContent {
            Box(modifier = Modifier.size(800.dp, 400.dp)) {
                SpeedometerScreen(
                    state = SpeedometerState(
                        currentSpeedKmh = 72f,
                        speedAccuracyKmh = 2f,
                        estimateQuality = EstimateQuality.TRACKING,
                        maxSpeedKmh = 90f,
                        satelliteCount = 6,
                        maxSatelliteCount = 8
                    ),
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
                    onSpeedUnitClick = {},
                    onTrackingModeChange = {},
                    onRefreshRateChange = {},
                    onReset = {},
                    onRetry = {},
                    onEnterPip = {},
                    onRequestPermission = {},
                    onOpenSettings = {},
                    isSpeedFocusMode = false
                )
            }
        }

        composeRule.onNodeWithContentDescription("Speed display")
            .assertHeightIsAtLeast(150.dp)
            .assertIsDisplayed()
        composeRule.onNodeWithText("± 2.0 km/h").assertIsDisplayed()
        composeRule.onNodeWithText("v${BuildConfig.VERSION_NAME}").assertDoesNotExist()
        composeRule.onNodeWithText("top speed: ").assertDoesNotExist()
        composeRule.onNodeWithText("reset").assertDoesNotExist()
    }

    @Test
    fun positionTrailExposesNorthUpHeading() {
        var exportCount = 0
        val first = PositionFix(51.0, 4.0, null, 5f, 1_000_000_000L)
        val current = PositionFix(
            51.001,
            4.001,
            45f,
            5f,
            2_000_000_000L,
            altitudeMeters = 123.4
        )
        composeRule.setContent {
            PositionTrailMap(
                trail = listOf(first, current),
                current = current,
                isStationary = false,
                primaryColor = Color.White,
                secondaryColor = Color.Gray,
                onDoubleTap = { exportCount++ },
                modifier = Modifier.size(300.dp, 180.dp)
            )
        }

        composeRule.onNodeWithContentDescription("North-up position trail")
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    "Altitude 123 m, Heading 045\u00b0 NE"
                )
            )
            .assertIsDisplayed()
            .performTouchInput { doubleClick() }
        composeRule.onNodeWithText("123 m").assertIsDisplayed()
        composeRule.onNodeWithText("045\u00b0 NE").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(1, exportCount) }
    }

    @Test
    fun stationaryPositionUsesDotSemanticsWithoutHeadingLabel() {
        val current = PositionFix(51.0, 4.0, 45f, 5f, 1_000_000_000L)
        composeRule.setContent {
            PositionTrailMap(
                trail = listOf(current),
                current = current,
                isStationary = true,
                primaryColor = Color.White,
                secondaryColor = Color.Gray,
                modifier = Modifier.size(300.dp, 180.dp)
            )
        }

        composeRule.onNodeWithContentDescription("North-up position trail")
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    "Altitude unavailable, Stationary"
                )
            )
            .assertIsDisplayed()
        composeRule.onNodeWithText("-- m").assertIsDisplayed()
        composeRule.onNodeWithText("045\u00b0 NE").assertDoesNotExist()
    }

    @Test
    fun positionTrailStaysAboveCenteredSpeedWithWarning() {
        val first = PositionFix(51.0, 4.0, 45f, 5f, 1_000_000_000L)
        val current = PositionFix(51.001, 4.001, 45f, 5f, 2_000_000_000L)
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = 1f)
            ) {
                Box(modifier = Modifier.size(320.dp, 800.dp)) {
                    SpeedometerScreen(
                        state = SpeedometerState(
                            currentPosition = current,
                            positionTrail = listOf(first, current)
                        ),
                        error = null,
                        warning = "Motion sensors unavailable; using GNSS only",
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
                        onRetry = {},
                        onEnterPip = {},
                        onRequestPermission = {},
                        onOpenSettings = {}
                    )
                }
            }
        }

        val mapBounds = composeRule.onNodeWithContentDescription("North-up position trail")
            .fetchSemanticsNode().boundsInRoot
        val speedBounds = composeRule.onNodeWithText("--").fetchSemanticsNode().boundsInRoot
        assertTrue(mapBounds.bottom <= speedBounds.top)
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
