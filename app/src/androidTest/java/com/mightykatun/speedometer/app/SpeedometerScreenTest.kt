package com.mightykatun.speedometer.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import com.mightykatun.speedometer.app.domain.model.EstimateQuality
import com.mightykatun.speedometer.app.domain.model.PositionFix
import com.mightykatun.speedometer.app.domain.model.PortraitDisplayMode
import com.mightykatun.speedometer.app.domain.model.RefreshRate
import com.mightykatun.speedometer.app.domain.model.SpeedUnit
import com.mightykatun.speedometer.app.domain.model.SpeedometerState
import com.mightykatun.speedometer.app.domain.model.TrackingMode
import com.mightykatun.speedometer.app.domain.model.VesselHeading
import com.mightykatun.speedometer.app.domain.model.RegattaMark
import com.mightykatun.speedometer.app.domain.model.RegattaMetrics
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
        var displayCycles = 0

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
                onPortraitDisplayCycle = { displayCycles++ }
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
            assertEquals(1, displayCycles)
        }
    }

    @Test
    fun threeDigitSpeedAndUnitFitOnNarrowPortrait() {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 1f)) {
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
                    positionTrail = listOf(current),
                    portraitDisplayMode = PortraitDisplayMode.SPEED_FOCUS
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
                onOpenSettings = {}
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
                    onOpenSettings = {}
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
    fun landscapeTemporarilyOverridesSelectedRegattaDisplay() {
        composeRule.setContent {
            Box(modifier = Modifier.size(800.dp, 400.dp)) {
                SpeedometerScreen(
                    state = SpeedometerState(
                        currentSpeedKmh = 72f,
                        estimateQuality = EstimateQuality.TRACKING,
                        portraitDisplayMode = PortraitDisplayMode.REGATTA,
                        vesselHeading = VesselHeading(5f, 2f, 1L),
                        regattaMetrics = RegattaMetrics(12.0, 3.0)
                    ),
                    error = null,
                    warning = null,
                    signalMessage = null,
                    isInPipMode = false,
                    speedUnit = SpeedUnit.MILES_PER_HOUR,
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

        composeRule.onNodeWithText("mph").assertIsDisplayed()
        composeRule.onNodeWithText("kts").assertDoesNotExist()
        composeRule.onNodeWithText("005").assertDoesNotExist()
        composeRule.onNodeWithText("DTL").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Pin line point").assertDoesNotExist()
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
    fun regattaDisplayUsesFixedKnotsMetricsAndPointGestures() {
        var pinCaptures = 0
        var pinClears = 0
        var boatCaptures = 0
        var boatClears = 0
        var displayCycles = 0
        var screenState by mutableStateOf(
            SpeedometerState(
                currentSpeedKmh = 18.52f,
                speedAccuracyKmh = 1.852f,
                estimateQuality = EstimateQuality.TRACKING,
                satelliteCount = 7,
                portraitDisplayMode = PortraitDisplayMode.REGATTA,
                vesselHeading = VesselHeading(5f, 3f, 1L),
                pinMark = RegattaMark(51.0, 4.0, 3f),
                regattaMetrics = RegattaMetrics(12.2, 18.4)
            )
        )
        composeRule.setContent {
            Box(modifier = Modifier.size(360.dp, 760.dp)) {
                SpeedometerScreen(
                    state = screenState,
                    error = null,
                    warning = null,
                    signalMessage = null,
                    isInPipMode = false,
                    speedUnit = SpeedUnit.MILES_PER_HOUR,
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
                    onPortraitDisplayCycle = { displayCycles++ },
                    onPinMarkCapture = {
                        pinCaptures++
                        screenState = screenState.copy(pinMark = RegattaMark(51.0, 4.0, 3f))
                    },
                    onBoatMarkCapture = {
                        boatCaptures++
                        screenState = screenState.copy(boatMark = RegattaMark(51.0, 4.001, 3f))
                    },
                    onPinMarkClear = {
                        pinClears++
                        screenState = screenState.copy(pinMark = null)
                    },
                    onBoatMarkClear = {
                        boatClears++
                        screenState = screenState.copy(boatMark = null)
                    }
                )
            }
        }

        composeRule.onNodeWithText("v${BuildConfig.VERSION_NAME}").assertDoesNotExist()
        composeRule.onNodeWithText("005").assertIsDisplayed()
        composeRule.onNodeWithText("deg").assertIsDisplayed()
        composeRule.onNodeWithText("10").assertIsDisplayed()
        composeRule.onNodeWithText(".00").assertIsDisplayed()
        composeRule.onNodeWithText("kts").assertIsDisplayed()
        composeRule.onNodeWithText("\u00b1 1.0 kts").assertDoesNotExist()
        composeRule.onNodeWithText("DTL").assertIsDisplayed()
        composeRule.onNodeWithText("12 m").assertIsDisplayed()
        composeRule.onNodeWithText("TTL").assertIsDisplayed()
        composeRule.onNodeWithText("18 s").assertIsDisplayed()
        composeRule.onNodeWithText("top speed: ").assertDoesNotExist()
        composeRule.onNodeWithText("reset").assertDoesNotExist()
        composeRule.onNodeWithText("float").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("North-up position trail").assertDoesNotExist()
        val headingBounds = composeRule.onNodeWithText("005").fetchSemanticsNode().boundsInRoot
        val speedBounds = composeRule.onNodeWithText("10").fetchSemanticsNode().boundsInRoot
        val metricsBounds = composeRule.onNodeWithContentDescription("DTL 12 m")
            .fetchSemanticsNode().boundsInRoot
        assertTrue(kotlin.math.abs(headingBounds.height - speedBounds.height) <= 1f)
        val headingToSpeed = speedBounds.center.y - headingBounds.center.y
        val speedToMetrics = metricsBounds.center.y - speedBounds.center.y
        val spacingTolerance = with(composeRule.density) { 24.dp.toPx() }
        assertTrue(
            "Uneven regatta spacing: heading-to-speed centers=$headingToSpeed, " +
                "speed-to-metrics centers=$speedToMetrics",
            kotlin.math.abs(headingToSpeed - speedToMetrics) <= spacingTolerance
        )
        composeRule.onNodeWithContentDescription("True heading display")
            .performTouchInput { doubleClick() }

        val pin = composeRule.onNodeWithContentDescription("Pin line point")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "set"))
        val boat = composeRule.onNodeWithContentDescription("Boat line point")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "not set"))
        pin.performTouchInput { click() }
        composeRule.mainClock.advanceTimeBy(500)
        boat.performTouchInput { click() }
        composeRule.mainClock.advanceTimeBy(500)
        composeRule.waitForIdle()
        boat.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "set"))
        pin.performTouchInput { doubleClick() }
        boat.performTouchInput { doubleClick() }

        composeRule.runOnIdle {
            assertEquals(0, pinCaptures)
            assertEquals(1, pinClears)
            assertEquals(1, boatCaptures)
            assertEquals(1, boatClears)
            assertEquals(1, displayCycles)
        }
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
    fun providerDisabledUsesInlineStatusOnRegattaHud() {
        composeRule.setContent {
            SpeedometerScreen(
                state = SpeedometerState(
                    currentSpeedKmh = 72f,
                    estimateQuality = EstimateQuality.TRACKING,
                    portraitDisplayMode = PortraitDisplayMode.REGATTA
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
        composeRule.onNodeWithText("kts").assertIsDisplayed()
        composeRule.onNodeWithText("reset").assertDoesNotExist()
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
