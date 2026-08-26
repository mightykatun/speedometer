package com.mightykatun.speedometer.app

import android.Manifest
import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Rational
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

import com.mightykatun.speedometer.app.di.SpeedometerViewModelFactory
import com.mightykatun.speedometer.app.data.repository.TrackingModeResult
import com.mightykatun.speedometer.app.domain.model.EstimateQuality
import com.mightykatun.speedometer.app.domain.model.RefreshRate
import com.mightykatun.speedometer.app.domain.model.SpeedUnit
import com.mightykatun.speedometer.app.domain.model.SpeedometerState
import com.mightykatun.speedometer.app.domain.model.SpeedTrendSample
import com.mightykatun.speedometer.app.domain.model.TrackingMode
import java.util.Locale
import kotlin.math.max

class MainActivity : ComponentActivity() {

    private val viewModel: SpeedometerViewModel by viewModels { SpeedometerViewModelFactory.INSTANCE }
    private val repositoryViewModel: SpeedRepositoryViewModel by viewModels()
    private val speedRepository get() = repositoryViewModel.repository

    private var isInPipMode by mutableStateOf(false)
    private var speedUnit by mutableStateOf(SpeedUnit.KILOMETERS_PER_HOUR)
    private var effectiveTrackingMode by mutableStateOf(TrackingMode.HANDHELD)
    private var refreshRate by mutableStateOf(RefreshRate.ONE_SECOND)
    private var requestedTrackingMode = TrackingMode.HANDHELD
    private var latestModeCommandId = 0L
    private var permissionIssue by mutableStateOf<LocationPermissionIssue?>(null)
    private var permissionRequestInFlight = false

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        permissionRequestInFlight = false
        val fineLocation = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false

        if (fineLocation) {
            permissionIssue = null
            startSpeedTracking()
        } else {
            permissionIssue = currentPermissionIssue()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()
        permissionRequestInFlight = savedInstanceState?.getBoolean(PERMISSION_REQUEST_IN_FLIGHT_KEY) == true
        val preferences = getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        speedUnit = SpeedUnit.fromPreference(preferences.getString(SPEED_UNIT_KEY, null))
        refreshRate = RefreshRate.fromPreference(
            preferences.getString(REFRESH_RATE_KEY, null)
        )
        viewModel.onRefreshRateChanged(refreshRate)
        requestedTrackingMode = TrackingMode.fromPreference(preferences.getString(TRACKING_MODE_KEY, null))
        effectiveTrackingMode = requestedTrackingMode.takeIf {
            it != TrackingMode.FIXED || speedRepository.supportsFixedMode
        } ?: TrackingMode.HANDHELD
        
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            if (permissionRequestInFlight) {
                permissionIssue = null
            } else if (preferences.getBoolean(LOCATION_PERMISSION_REQUESTED_KEY, false)) {
                permissionIssue = currentPermissionIssue()
            } else {
                requestLocationPermission()
            }
        }

        setContent {
            SpeedometerScreen(
                state = viewModel.state,
                error = viewModel.errorMessage,
                warning = viewModel.warningMessage,
                signalMessage = viewModel.signalMessage,
                isInPipMode = isInPipMode,
                speedUnit = speedUnit,
                trackingMode = effectiveTrackingMode,
                refreshRate = refreshRate,
                trackingModeEnabled = speedRepository.supportsFixedMode ||
                    requestedTrackingMode == TrackingMode.FIXED,
                supportsPip = supportsPictureInPicture(),
                permissionMessage = permissionIssue?.message,
                permissionCanRequest = permissionIssue?.canRequest == true,
                onSpeedUnitClick = { cycleSpeedUnit() },
                onTrackingModeChange = { cycleTrackingMode() },
                onRefreshRateChange = { cycleRefreshRate() },
                onReset = { restartMeasurements() },
                onRetry = { restartMeasurements() },
                onEnterPip = { enterPipMode() },
                onRequestPermission = { requestLocationPermission() },
                onOpenSettings = { openAppSettings() }
            )
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(PERMISSION_REQUEST_IN_FLIGHT_KEY, permissionRequestInFlight)
        super.onSaveInstanceState(outState)
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        }
        isInPipMode = isInPictureInPictureMode
    }

    private fun enterPipMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && supportsPictureInPicture()) {
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(16, 9))
                .build()
            runCatching { enterPictureInPictureMode(params) }
                .onSuccess { entered ->
                    if (!entered) viewModel.onWarning("Unable to enter floating mode")
                }
                .onFailure { viewModel.onWarning("Unable to enter floating mode") }
        }
    }

    private fun cycleSpeedUnit() {
        speedUnit = speedUnit.next()
        getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(SPEED_UNIT_KEY, speedUnit.preferenceValue)
            .apply()
    }

    private fun cycleTrackingMode() {
        val nextMode = when (requestedTrackingMode) {
            TrackingMode.HANDHELD -> if (speedRepository.supportsFixedMode) {
                TrackingMode.FIXED
            } else TrackingMode.HANDHELD
            TrackingMode.FIXED -> TrackingMode.HANDHELD
        }
        changeTrackingMode(nextMode)
    }

    private fun cycleRefreshRate() {
        refreshRate = refreshRate.next()
        viewModel.onRefreshRateChanged(refreshRate)
        getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(REFRESH_RATE_KEY, refreshRate.preferenceValue)
            .apply()
    }

    private fun changeTrackingMode(nextMode: TrackingMode) {
        requestedTrackingMode = nextMode
        getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(TRACKING_MODE_KEY, requestedTrackingMode.preferenceValue)
            .apply()
        latestModeCommandId = speedRepository.setTrackingMode(nextMode)
    }

    override fun onStart() {
        super.onStart()
        viewModel.onSessionStart()
        checkPermissionsAndStart()
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) {
            speedRepository.stopUpdates()
            viewModel.onSessionReset()
        }
    }

    private fun checkPermissionsAndStart() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED) {
            permissionIssue = null
            startSpeedTracking()
        } else if (!permissionRequestInFlight) {
            permissionIssue = currentPermissionIssue()
        }
    }

    private fun startSpeedTracking() {
        latestModeCommandId = speedRepository.startUpdates(
            trackingMode = requestedTrackingMode,
            onEstimate = viewModel::onSpeedEstimateReceived,
            onSatelliteCount = viewModel::onSatelliteCountReceived,
            onGpsProviderEnabled = viewModel::onGpsProviderEnabled,
            onGpsRecoveryAccepted = viewModel::onGpsRecoveryAccepted,
            onPermissionRequired = {
                permissionIssue = currentPermissionIssue()
            },
            onError = viewModel::onRepositoryError,
            onTrackingModeResult = ::acceptTrackingModeResult
        )
    }

    private fun acceptTrackingModeResult(result: TrackingModeResult) {
        if (result.commandId != latestModeCommandId) return
        effectiveTrackingMode = result.effectiveMode
        viewModel.onWarning(
            if (result.requestedMode == TrackingMode.FIXED &&
                result.effectiveMode == TrackingMode.HANDHELD
            ) {
                "Motion sensors unavailable; using GNSS only"
            } else {
                null
            }
        )
    }

    private fun restartMeasurements() {
        speedRepository.stopUpdates()
        viewModel.onSessionReset()
        viewModel.onSessionStart()
        checkPermissionsAndStart()
    }

    private fun requestLocationPermission() {
        if (permissionRequestInFlight) return
        permissionRequestInFlight = true
        runCatching {
            requestPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }.onSuccess {
            getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(LOCATION_PERMISSION_REQUESTED_KEY, true)
                .apply()
        }.onFailure {
            permissionRequestInFlight = false
            permissionIssue = retryPermissionIssue()
        }
    }

    private fun openAppSettings() {
        startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null)
            )
        )
    }

    private fun supportsPictureInPicture(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun currentPermissionIssue(): LocationPermissionIssue {
        val preciseRequired = ActivityCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val canRequest = ActivityCompat.shouldShowRequestPermissionRationale(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        return locationPermissionIssue(preciseRequired, canRequest)
    }

    private fun retryPermissionIssue(): LocationPermissionIssue =
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            LocationPermissionIssue.PRECISE_CAN_RETRY
        } else {
            LocationPermissionIssue.DENIED_CAN_RETRY
        }

    private companion object {
        const val PREFERENCES_NAME = "speedometer_preferences"
        const val SPEED_UNIT_KEY = "speed_unit"
        const val TRACKING_MODE_KEY = "tracking_mode"
        const val REFRESH_RATE_KEY = "refresh_rate"
        const val LOCATION_PERMISSION_REQUESTED_KEY = "location_permission_requested"
        const val PERMISSION_REQUEST_IN_FLIGHT_KEY = "permission_request_in_flight"
    }
}

internal enum class LocationPermissionIssue(val message: String, val canRequest: Boolean) {
    DENIED_CAN_RETRY("Location permission is required to measure speed.", true),
    DENIED_SETTINGS_ONLY("Location permission is required to measure speed.", false),
    PRECISE_CAN_RETRY("Precise location must be enabled for GPS speed accuracy.", true),
    PRECISE_SETTINGS_ONLY("Precise location must be enabled for GPS speed accuracy.", false)
}

internal fun locationPermissionIssue(
    preciseRequired: Boolean,
    canRequest: Boolean
): LocationPermissionIssue = when {
    preciseRequired && canRequest -> LocationPermissionIssue.PRECISE_CAN_RETRY
    preciseRequired -> LocationPermissionIssue.PRECISE_SETTINGS_ONLY
    canRequest -> LocationPermissionIssue.DENIED_CAN_RETRY
    else -> LocationPermissionIssue.DENIED_SETTINGS_ONLY
}

@Composable
fun SpeedometerScreen(
    state: SpeedometerState,
    error: String?,
    warning: String?,
    signalMessage: String?,
    isInPipMode: Boolean,
    speedUnit: SpeedUnit,
    trackingMode: TrackingMode,
    refreshRate: RefreshRate,
    trackingModeEnabled: Boolean,
    supportsPip: Boolean,
    permissionMessage: String?,
    permissionCanRequest: Boolean,
    onSpeedUnitClick: () -> Unit,
    onTrackingModeChange: () -> Unit,
    onRefreshRateChange: () -> Unit,
    onReset: () -> Unit,
    onRetry: () -> Unit,
    onEnterPip: () -> Unit,
    onRequestPermission: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val isDark = isSystemInDarkTheme()
    val backgroundColor = if (isDark) Color.Black else Color.White
    val primaryColor = if (isDark) Color.White else Color.Black
    val secondaryColor = if (isDark) Color.LightGray else Color.DarkGray
    val tertiaryColor = if (isDark) Color.DarkGray else Color.Gray
    val labelColor = if (isDark) Color.Gray else Color.DarkGray

    val displayedSatelliteCount = if (signalMessage == null) state.satelliteCount else 0
    val displayedSpeedKmh = state.currentSpeedKmh.takeIf { signalMessage == null }
    val displayedQuality = if (signalMessage == null) {
        state.estimateQuality
    } else {
        EstimateQuality.UNAVAILABLE
    }
    val unavailableText = signalMessage ?: if (displayedSatelliteCount == 0) {
        "no signal"
    } else {
        "speed unavailable"
    }
    val statusColor = if (displayedSatelliteCount >= 3) Color.Green else Color.Red
    val currentSpeed = displayedSpeedKmh?.let(speedUnit::fromKilometersPerHour)
    val currentAccuracy = state.speedAccuracyKmh?.let(speedUnit::fromKilometersPerHour)
    val maxSpeed = speedUnit.fromKilometersPerHour(state.maxSpeedKmh)

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor)
            .padding(16.dp)
    ) {
        val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
        val fontAwareHeight = maxHeight / fontScale
        val compactLayout = fontAwareHeight < 480.dp
        val veryCompactLayout = fontAwareHeight < 340.dp
        val showTrend = !isInPipMode && fontAwareHeight >= 300.dp
        val showStats = !isInPipMode && fontAwareHeight >= 300.dp
        val compactActions = !isInPipMode && fontAwareHeight < 300.dp
        val compactWarning = warning?.takeIf { compactLayout }
        val baselineCompact = maxHeight < 480.dp
        val baselineVeryCompact = maxHeight < 340.dp
        fun displaySize(baselineSize: Float) =
            (baselineSize * fontScale.coerceAtMost(2f) / fontScale).sp
        val baselineMainSize = when {
            isInPipMode -> 64f
            baselineVeryCompact -> 56f
            baselineCompact -> 72f
            else -> 120f
        }
        val baselineDecimalSize = when {
            isInPipMode -> 24f
            baselineVeryCompact -> 20f
            baselineCompact -> 24f
            else -> 40f
        }
        val baselineUnitSize = when {
            isInPipMode || baselineVeryCompact -> 14f
            baselineCompact -> 16f
            else -> 24f
        }
        val mainSpeedSize = displaySize(baselineMainSize)
        val decimalSize = displaySize(baselineDecimalSize)
        val unitSize = displaySize(baselineUnitSize)
        val letterSpacing = ((if (isInPipMode || compactLayout) -2f else -4f) / fontScale).sp
        if (permissionMessage != null) {
            PermissionRecovery(
                message = permissionMessage,
                canRequest = permissionCanRequest,
                onRequestPermission = onRequestPermission,
                onOpenSettings = onOpenSettings,
                modifier = Modifier.align(Alignment.Center)
            )
        } else if (error != null) {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = error,
                    color = Color.Red,
                    textAlign = TextAlign.Center,
                    fontSize = 20.sp
                )
                Button(onClick = onRetry) {
                    Text("retry")
                }
            }
        } else {
            if (!isInPipMode) {
                Column(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(96.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp),
                            verticalAlignment = Alignment.Bottom
                        ) {
                            Text(
                                text = "speedometer v${BuildConfig.VERSION_NAME}",
                                color = labelColor,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                fontSize = 14.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(vertical = 2.dp)
                            )
                            HudSelector(
                                label = "mode",
                                value = trackingMode.displayLabel,
                                contentDescription = "Tracking mode",
                                stateDescription = trackingMode.displayLabel,
                                labelColor = labelColor,
                                valueColor = if (trackingModeEnabled) primaryColor else labelColor,
                                enabled = trackingModeEnabled,
                                contentAlignment = Alignment.BottomEnd,
                                onClick = onTrackingModeChange
                            )
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .background(
                                            if (compactWarning != null) Color(0xFFFFA000) else statusColor,
                                            shape = androidx.compose.foundation.shape.CircleShape
                                        )
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                if (compactWarning != null) {
                                    Text(
                                        text = compactWarning,
                                        color = primaryColor,
                                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 11.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.semantics {
                                            contentDescription = compactWarning
                                        }
                                    )
                                } else {
                                    Text(
                                        text = "satellites: ",
                                        color = labelColor,
                                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                        fontSize = 14.sp,
                                        maxLines = 1
                                    )
                                    Text(
                                        text = "$displayedSatelliteCount",
                                        color = primaryColor,
                                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp,
                                        maxLines = 1
                                    )
                                }
                            }
                            HudSelector(
                                label = "refresh",
                                value = refreshRate.displayLabel,
                                contentDescription = "Refresh rate",
                                stateDescription = refreshRate.accessibilityLabel,
                                labelColor = labelColor,
                                valueColor = primaryColor,
                                enabled = true,
                                contentAlignment = Alignment.TopEnd,
                                onClick = onRefreshRateChange
                            )
                        }
                    }
                    if (warning != null && !compactLayout) {
                        Text(
                            text = warning,
                            color = Color(0xFFFFA000),
                            textAlign = TextAlign.Center,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            }

            if (showTrend) {
                LiveSpeedTrendChart(
                    samples = state.speedTrend,
                    currentSpeedKmh = displayedSpeedKmh,
                    refreshRate = refreshRate,
                    color = primaryColor,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(
                            when {
                                veryCompactLayout -> 148.dp
                                compactLayout -> 164.dp
                                else -> 204.dp
                            }
                        )
                        .padding(bottom = 116.dp)
                        .semantics {
                            contentDescription = speedTrendDescription(
                                state.speedTrend,
                                displayedSpeedKmh,
                                speedUnit
                            )
                        }
                )
            }

            // --- CENTER: Speedometer ---
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(
                        y = when {
                            compactActions -> 32.dp
                            else -> 0.dp
                        }
                    ),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val formattedSpeed = currentSpeed?.let { "%.2f".format(Locale.US, it) }
                val parts = formattedSpeed?.split(".")
                val intPart = parts?.get(0) ?: "--"
                val decPart = parts?.getOrNull(1)
                val placeholderAlpha = speedPlaceholderAlpha(
                    enabled = intPart == "--"
                )

                Row {
                    // Integer Part
                    Text(
                        text = intPart,
                        style = MaterialTheme.typography.displayLarge.copy(
                            fontSize = mainSpeedSize, 
                            fontWeight = FontWeight.Bold,
                            letterSpacing = letterSpacing,
                            color = primaryColor
                        ),
                        modifier = Modifier
                            .alignByBaseline()
                            .graphicsLayer { alpha = placeholderAlpha.value }
                    )
                    
                    // Decimal Part
                    if (decPart != null) {
                        Text(
                            text = ".$decPart",
                            style = MaterialTheme.typography.headlineMedium.copy(
                                fontSize = decimalSize,
                                fontWeight = FontWeight.Bold,
                                color = secondaryColor
                            ),
                            modifier = Modifier
                                .alignByBaseline()
                                .padding(start = 2.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(4.dp))

                    // Unit
                    Text(
                        text = speedUnit.label,
                        style = MaterialTheme.typography.headlineMedium.copy(
                            fontSize = unitSize,
                            color = tertiaryColor
                        ),
                        modifier = Modifier
                            .alignByBaseline()
                            .clickable(
                                onClickLabel = "Change speed unit",
                                role = Role.Button,
                                onClick = onSpeedUnitClick
                            )
                            .minimumInteractiveComponentSize()
                    )
                }

                if (!compactActions) {
                    AccuracyIndicator(
                        quality = displayedQuality,
                        speed = currentSpeed,
                        accuracy = currentAccuracy,
                        unit = speedUnit.label,
                        isInPipMode = isInPipMode,
                        unavailableText = unavailableText
                    )
                }
            }

            if (!isInPipMode) {
                // --- BOTTOM: Stats and actions ---
                if (showStats) {
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                    ) {
                        BottomHudRow(
                            label = "top speed",
                            value = "%.1f %s".format(Locale.US, maxSpeed, speedUnit.label),
                            labelColor = labelColor,
                            valueColor = primaryColor,
                            actionText = "reset",
                            onAction = onReset
                        )
                        BottomHudRow(
                            label = "top satellites",
                            value = "${state.maxSatelliteCount}",
                            labelColor = labelColor,
                            valueColor = primaryColor,
                            actionText = if (supportsPip) "float" else null,
                            onAction = onEnterPip
                        )
                    }
                }

                if (compactActions) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .height(48.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        AccuracyIndicator(
                            quality = displayedQuality,
                            speed = currentSpeed,
                            accuracy = currentAccuracy,
                            unit = speedUnit.label,
                            isInPipMode = false,
                            unavailableText = unavailableText
                        )
                    }
                    Row(modifier = Modifier.align(Alignment.BottomEnd)) {
                        StatAction(text = "reset", color = primaryColor, onClick = onReset)
                        if (supportsPip) {
                            StatAction(text = "float", color = primaryColor, onClick = onEnterPip)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun speedPlaceholderAlpha(enabled: Boolean): State<Float> {
    if (!enabled) return remember { mutableFloatStateOf(1f) }
    val transition = rememberInfiniteTransition(label = "speed unavailable")
    return transition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "speed placeholder opacity"
    )
}

@Composable
private fun HudSelector(
    label: String,
    value: String,
    contentDescription: String,
    stateDescription: String,
    labelColor: Color,
    valueColor: Color,
    enabled: Boolean,
    contentAlignment: Alignment,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics {
                this.contentDescription = contentDescription
                this.stateDescription = stateDescription
            },
        contentAlignment = contentAlignment
    ) {
        Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)) {
            Text(
                text = "$label: ",
                color = labelColor,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                fontSize = 14.sp,
                maxLines = 1
            )
            Text(
                text = value,
                color = valueColor,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun PermissionRecovery(
    message: String,
    canRequest: Boolean,
    onRequestPermission: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = message,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
            fontSize = 20.sp
        )
        if (canRequest) {
            Button(onClick = onRequestPermission) {
                Text("grant location")
            }
        } else {
            Button(onClick = onOpenSettings) {
                Text("open settings")
            }
        }
    }
}

@Composable
private fun AccuracyIndicator(
    quality: EstimateQuality,
    speed: Float?,
    accuracy: Float?,
    unit: String,
    isInPipMode: Boolean,
    unavailableText: String = "no signal"
) {
    if (quality == EstimateQuality.ACQUIRING) return

    val isDark = isSystemInDarkTheme()
    val level = if (quality == EstimateQuality.UNAVAILABLE) {
        AccuracyLevel.POOR
    } else {
        accuracyLevel(speed, accuracy)
    }
    val color = when {
        quality == EstimateQuality.UNAVAILABLE ->
            if (isDark) Color(0xFFFF6B6B) else Color(0xFFB3261E)
        level == AccuracyLevel.GOOD ->
            if (isDark) Color(0xFF69F0AE) else Color(0xFF087F23)
        level == AccuracyLevel.FAIR ->
            if (isDark) Color(0xFFFFC857) else Color(0xFF8A5A00)
        else -> if (isDark) Color(0xFFFF6B6B) else Color(0xFFB3261E)
    }
    val text = when (quality) {
        EstimateQuality.TRACKING, EstimateQuality.DEGRADED ->
            accuracy?.let { "± %.1f %s".format(Locale.US, it, unit) } ?: "estimating"
        EstimateQuality.ACQUIRING -> ""
        EstimateQuality.UNAVAILABLE -> unavailableText
    }
    val accessibilityText = when (quality) {
        EstimateQuality.UNAVAILABLE -> if (unavailableText == "speed unavailable") {
            unavailableText
        } else {
            "$unavailableText, speed unavailable"
        }
        EstimateQuality.TRACKING, EstimateQuality.DEGRADED ->
            "$text, ${level.name.lowercase(Locale.US)} accuracy"
        EstimateQuality.ACQUIRING -> "Acquiring speed"
    }

    if (isInPipMode) {
        Box(
            modifier = Modifier
                .semantics { contentDescription = accessibilityText }
                .padding(top = 2.dp)
                .size(6.dp)
                .background(color, androidx.compose.foundation.shape.CircleShape)
        )
    } else {
        Text(
            text = text,
            color = color,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .semantics { contentDescription = accessibilityText }
                .padding(top = 8.dp)
        )
    }
}

@Composable
private fun StatAction(
    text: String,
    color: Color,
    onClick: () -> Unit,
    matchStatRowHeight: Boolean = false
) {
    val targetSize = if (matchStatRowHeight) {
        Modifier.sizeIn(minWidth = 48.dp)
    } else {
        Modifier.minimumInteractiveComponentSize()
    }
    Text(
        text = text,
        color = color,
        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp,
        modifier = Modifier
            .clickable(role = Role.Button, onClick = onClick)
            .then(targetSize)
            .padding(horizontal = 8.dp, vertical = 2.dp)
    )
}

@Composable
private fun BottomHudRow(
    label: String,
    value: String,
    labelColor: Color,
    valueColor: Color,
    actionText: String?,
    onAction: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        StatRow(label, value, labelColor, valueColor)
        if (actionText != null) {
            StatAction(
                text = actionText,
                color = valueColor,
                onClick = onAction,
                matchStatRowHeight = true
            )
        }
    }
}

@Composable
private fun LiveSpeedTrendChart(
    samples: List<SpeedTrendSample>,
    currentSpeedKmh: Float?,
    refreshRate: RefreshRate,
    color: Color,
    modifier: Modifier = Modifier
) {
    val latest = samples.lastOrNull()
    val targetSpeed = latest?.speedKmh?.takeIf { currentSpeedKmh != null }

    if (latest == null || targetSpeed == null) {
        SpeedTrendChart(samples, rememberUpdatedState<Float?>(null), color, modifier)
        return
    }

    key(refreshRate) {
        val animatedSpeed = animateFloatAsState(
            targetValue = targetSpeed,
            animationSpec = tween(
                durationMillis = refreshRate.intervalMillis,
                easing = FastOutSlowInEasing
            ),
            label = "graph speed"
        )
        SpeedTrendChart(samples, animatedSpeed, color, modifier)
    }
}

@Composable
private fun SpeedTrendChart(
    samples: List<SpeedTrendSample>,
    currentSpeedKmh: State<Float?>,
    color: Color,
    modifier: Modifier = Modifier
) {
    val latestTimestamp = samples.lastOrNull()?.timestampNanos
    val visible = remember(samples, latestTimestamp) {
        latestTimestamp?.let { latest ->
            samples.filter { it.timestampNanos >= latest - TREND_WINDOW_NANOS }
        }.orEmpty()
    }
    Box(
        modifier = modifier.drawWithCache {
            val startTimestamp = (latestTimestamp ?: 0L) - TREND_WINDOW_NANOS
            val brush = Brush.horizontalGradient(
                colors = listOf(color.copy(alpha = 0.04f), color.copy(alpha = 0.45f), color),
                startX = 0f,
                endX = size.width
            )
            val stroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            val segment = ArrayList<Offset>(visible.size)
            val path = Path()
            val slopes = FloatArray(visible.size.coerceAtLeast(1))
            val tangents = FloatArray(visible.size.coerceAtLeast(1))
            val pointInset = 7.dp.toPx()
            val pointRadius = 3.5.dp.toPx()
            val chartRight = max(0f, size.width - pointInset)

            onDrawBehind {
                val currentSpeed = currentSpeedKmh.value
                var minimum = Float.POSITIVE_INFINITY
                var maximum = Float.NEGATIVE_INFINITY
                visible.forEachIndexed { index, sample ->
                    val speed = if (index == visible.lastIndex && currentSpeed != null) {
                        currentSpeed
                    } else {
                        sample.speedKmh
                    } ?: return@forEachIndexed
                    minimum = minOf(minimum, speed)
                    maximum = maxOf(maximum, speed)
                }
                if (!minimum.isFinite() || !maximum.isFinite()) return@onDrawBehind

                val range = max(1f, maximum - minimum)
                val lower = max(0f, minimum - range * 0.2f)
                val upper = maximum + range * 0.2f
                val verticalRange = max(1f, upper - lower)

                fun drawCurrentSegment() {
                    if (segment.size < 2) {
                        segment.clear()
                        return
                    }
                    path.reset()
                    path.moveTo(segment.first().x, segment.first().y)
                    if (segment.size == 2) {
                        path.lineTo(segment.last().x, segment.last().y)
                    } else {
                        for (index in 0 until segment.lastIndex) {
                            val dx = segment[index + 1].x - segment[index].x
                            slopes[index] = if (dx == 0f) {
                                0f
                            } else {
                                (segment[index + 1].y - segment[index].y) / dx
                            }
                        }
                        tangents[0] = slopes[0]
                        tangents[segment.lastIndex] = slopes[segment.lastIndex - 1]
                        for (index in 1 until segment.lastIndex) {
                            val before = slopes[index - 1]
                            val after = slopes[index]
                            tangents[index] = if (before == 0f || after == 0f || before * after <= 0f) {
                                0f
                            } else {
                                2f * before * after / (before + after)
                            }
                        }
                        for (index in 0 until segment.lastIndex) {
                            val start = segment[index]
                            val end = segment[index + 1]
                            val dx = end.x - start.x
                            path.cubicTo(
                                start.x + dx / 3f,
                                start.y + tangents[index] * dx / 3f,
                                end.x - dx / 3f,
                                end.y - tangents[index + 1] * dx / 3f,
                                end.x,
                                end.y
                            )
                        }
                    }
                    drawPath(path, brush, style = stroke)
                    segment.clear()
                }

                visible.forEachIndexed { index, sample ->
                    val speed = if (index == visible.lastIndex && currentSpeed != null) {
                        currentSpeed
                    } else {
                        sample.speedKmh
                    }
                    if (speed == null) {
                        drawCurrentSegment()
                        return@forEachIndexed
                    }
                    val x = ((sample.timestampNanos - startTimestamp).toDouble() / TREND_WINDOW_NANOS)
                        .toFloat().coerceIn(0f, 1f) * chartRight
                    val y = (size.height -
                        ((speed - lower) / verticalRange).coerceIn(0f, 1f) * size.height)
                        .coerceIn(pointInset, size.height - pointInset)
                    segment += Offset(x, y)
                }
                drawCurrentSegment()

                currentSpeed ?: return@onDrawBehind
                val currentY = size.height -
                    ((currentSpeed - lower) / verticalRange).coerceIn(0f, 1f) * size.height
                val currentPoint = Offset(
                    chartRight,
                    currentY.coerceIn(pointInset, size.height - pointInset)
                )
                drawCircle(color.copy(alpha = 0.22f), radius = pointInset, center = currentPoint)
                drawCircle(color, radius = pointRadius, center = currentPoint)
            }
        }
    )
}

private val TrackingMode.displayLabel: String
    get() = when (this) {
        TrackingMode.HANDHELD -> "gnss"
        TrackingMode.FIXED -> "gnss+imu"
    }

internal fun speedTrendDescription(
    samples: List<SpeedTrendSample>,
    currentSpeedKmh: Float?,
    speedUnit: SpeedUnit
): String {
    val latestTimestamp = samples.lastOrNull()?.timestampNanos
    val visibleSpeeds = latestTimestamp?.let { latest ->
        samples.asSequence()
            .filter { it.timestampNanos >= latest - TREND_WINDOW_NANOS }
            .mapNotNull(SpeedTrendSample::speedKmh)
            .toList()
    }.orEmpty()
    val latestSpeedKmh = currentSpeedKmh ?: visibleSpeeds.lastOrNull()
        ?: return "30 second speed trend unavailable"
    val firstSpeedKmh = visibleSpeeds.firstOrNull() ?: latestSpeedKmh
    val direction = when {
        latestSpeedKmh - firstSpeedKmh > TREND_DIRECTION_THRESHOLD_KMH -> "rising"
        firstSpeedKmh - latestSpeedKmh > TREND_DIRECTION_THRESHOLD_KMH -> "falling"
        else -> "steady"
    }
    val latestSpeed = speedUnit.fromKilometersPerHour(latestSpeedKmh)
    return "30 second speed trend, $direction, latest %.2f %s"
        .format(Locale.US, latestSpeed, speedUnit.label)
}

private const val TREND_WINDOW_NANOS = 30_000_000_000L
private const val TREND_DIRECTION_THRESHOLD_KMH = 0.5f

@Composable
fun StatRow(label: String, value: String, labelColor: Color, valueColor: Color) {
    Row(modifier = Modifier.padding(vertical = 2.dp)) {
        Text(
            text = "$label: ",
            color = labelColor,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            fontSize = 14.sp
        )
        Text(
            text = value,
            color = valueColor,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp
        )
    }
}
