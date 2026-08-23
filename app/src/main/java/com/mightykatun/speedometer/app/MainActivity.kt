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
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
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

import com.mightykatun.speedometer.app.di.SpeedometerViewModelFactory
import com.mightykatun.speedometer.app.domain.model.EstimateQuality
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
    private var trackingMode by mutableStateOf(TrackingMode.HANDHELD)
    private var preferredTrackingMode = TrackingMode.HANDHELD
    private var permissionIssue by mutableStateOf<LocationPermissionIssue?>(null)
    private var permissionRequestInFlight = false

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        permissionRequestInFlight = false
        val fineLocation = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val coarseLocation = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false

        if (fineLocation) {
            permissionIssue = null
            startSpeedTracking()
        } else {
            permissionIssue = if (coarseLocation) {
                LocationPermissionIssue.PRECISE_REQUIRED
            } else {
                LocationPermissionIssue.DENIED
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val preferences = getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        speedUnit = SpeedUnit.fromPreference(preferences.getString(SPEED_UNIT_KEY, null))
        preferredTrackingMode = TrackingMode.fromPreference(preferences.getString(TRACKING_MODE_KEY, null))
            .takeIf { mode ->
                when (mode) {
                    TrackingMode.HANDHELD -> true
                    TrackingMode.FIXED -> speedRepository.supportsFixedMode
                    TrackingMode.IMU_ONLY -> speedRepository.supportsImuOnly
                }
            }
            ?: TrackingMode.HANDHELD
        trackingMode = preferredTrackingMode
        
        if (preferredTrackingMode != TrackingMode.IMU_ONLY &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            if (preferences.getBoolean(LOCATION_PERMISSION_REQUESTED_KEY, false)) {
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
                isInPipMode = isInPipMode,
                speedUnit = speedUnit,
                trackingMode = trackingMode,
                supportsFixedMode = speedRepository.supportsFixedMode,
                supportsImuOnly = speedRepository.supportsImuOnly,
                supportsPip = supportsPictureInPicture(),
                permissionMessage = permissionIssue?.message,
                onSpeedUnitClick = { cycleSpeedUnit() },
                onTrackingModeChange = { cycleTrackingMode() },
                onReset = { restartMeasurements() },
                onEnterPip = { enterPipMode() },
                onRequestPermission = { requestLocationPermission() },
                onOpenSettings = { openAppSettings() },
                onUseImu = { changeTrackingMode(TrackingMode.IMU_ONLY) }
            )
        }
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
        val nextMode = when (preferredTrackingMode) {
            TrackingMode.HANDHELD -> when {
                speedRepository.supportsFixedMode -> TrackingMode.FIXED
                speedRepository.supportsImuOnly -> TrackingMode.IMU_ONLY
                else -> TrackingMode.HANDHELD
            }
            TrackingMode.FIXED -> if (speedRepository.supportsImuOnly) {
                TrackingMode.IMU_ONLY
            } else {
                TrackingMode.HANDHELD
            }
            TrackingMode.IMU_ONLY -> TrackingMode.HANDHELD
        }
        changeTrackingMode(nextMode)
    }

    private fun changeTrackingMode(nextMode: TrackingMode) {
        val previousMode = preferredTrackingMode
        preferredTrackingMode = nextMode
        trackingMode = preferredTrackingMode
        getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(TRACKING_MODE_KEY, preferredTrackingMode.preferenceValue)
            .apply()
        if (trackingModeTransitionRequiresReset(previousMode, nextMode)) {
            restartMeasurements()
        } else {
            speedRepository.setTrackingMode(nextMode)
        }
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
        if (preferredTrackingMode == TrackingMode.IMU_ONLY) {
            permissionIssue = null
            startSpeedTracking()
        } else if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED) {
            permissionIssue = null
            startSpeedTracking()
        } else if (!permissionRequestInFlight) {
            permissionIssue = currentPermissionIssue()
        }
    }

    private fun startSpeedTracking() {
        speedRepository.startUpdates(
            trackingMode = preferredTrackingMode,
            onEstimate = viewModel::onSpeedEstimateReceived,
            onSatelliteCount = viewModel::onSatelliteCountReceived,
            onGnssAvailable = viewModel::onGpsAvailable,
            onPermissionRequired = {
                permissionIssue = currentPermissionIssue()
            },
            onError = viewModel::onGpsError,
            onTrackingModeChanged = { effectiveMode ->
                val requestedMode = preferredTrackingMode
                trackingMode = effectiveMode
                if (requestedMode != TrackingMode.HANDHELD && effectiveMode == TrackingMode.HANDHELD) {
                    preferredTrackingMode = TrackingMode.HANDHELD
                    getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
                        .edit()
                        .putString(TRACKING_MODE_KEY, TrackingMode.HANDHELD.preferenceValue)
                        .apply()
                    if (requestedMode == TrackingMode.IMU_ONLY &&
                        ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) !=
                        PackageManager.PERMISSION_GRANTED
                    ) {
                        permissionIssue = currentPermissionIssue()
                    }
                }
                viewModel.onWarning(
                    when {
                        effectiveMode == TrackingMode.IMU_ONLY ->
                            IMU_ZERO_SEED_WARNING
                        requestedMode != TrackingMode.HANDHELD && effectiveMode == TrackingMode.HANDHELD ->
                            "Motion sensors unavailable; using GNSS only"
                        else -> null
                    }
                )
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
        getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(LOCATION_PERMISSION_REQUESTED_KEY, true)
            .apply()
        permissionRequestInFlight = true
        requestPermissionLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )
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

    private fun currentPermissionIssue(): LocationPermissionIssue =
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            LocationPermissionIssue.PRECISE_REQUIRED
        } else {
            LocationPermissionIssue.DENIED
        }

    private companion object {
        const val PREFERENCES_NAME = "speedometer_preferences"
        const val SPEED_UNIT_KEY = "speed_unit"
        const val TRACKING_MODE_KEY = "tracking_mode"
        const val LOCATION_PERMISSION_REQUESTED_KEY = "location_permission_requested"
    }
}

private enum class LocationPermissionIssue(val message: String) {
    DENIED("Location permission is required to measure speed."),
    PRECISE_REQUIRED("Precise location must be enabled for GPS speed accuracy.")
}

@Composable
fun SpeedometerScreen(
    state: SpeedometerState,
    error: String?,
    warning: String?,
    isInPipMode: Boolean,
    speedUnit: SpeedUnit,
    trackingMode: TrackingMode,
    supportsFixedMode: Boolean,
    supportsImuOnly: Boolean,
    supportsPip: Boolean,
    permissionMessage: String?,
    onSpeedUnitClick: () -> Unit,
    onTrackingModeChange: () -> Unit,
    onReset: () -> Unit,
    onEnterPip: () -> Unit,
    onRequestPermission: () -> Unit,
    onOpenSettings: () -> Unit,
    onUseImu: () -> Unit
) {
    val isDark = isSystemInDarkTheme()
    val backgroundColor = if (isDark) Color.Black else Color.White
    val primaryColor = if (isDark) Color.White else Color.Black
    val secondaryColor = if (isDark) Color.LightGray else Color.DarkGray
    val tertiaryColor = if (isDark) Color.DarkGray else Color.Gray
    val labelColor = if (isDark) Color.Gray else Color.DarkGray

    val statusColor = when {
        trackingMode == TrackingMode.IMU_ONLY -> Color(0xFF48D7FF)
        state.satelliteCount >= 3 -> Color.Green
        else -> Color.Red
    }
    val currentSpeed = state.currentSpeedKmh?.let(speedUnit::fromKilometersPerHour)
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
        val showStats = !isInPipMode && fontAwareHeight >= 240.dp
        val compactActions = fontAwareHeight < 240.dp
        val compactWarning = warning?.takeIf {
            compactActions && it != IMU_ZERO_SEED_WARNING
        }
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
                onRequestPermission = onRequestPermission,
                onOpenSettings = onOpenSettings,
                onUseImu = onUseImu,
                supportsImu = supportsImuOnly,
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
                MinimalAction(text = "reset", color = primaryColor, onClick = onReset)
            }
        } else {
            if (warning != null && !isInPipMode && !compactActions) {
                Text(
                    text = warning,
                    color = Color(0xFFFFA000),
                    textAlign = TextAlign.Center,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 52.dp)
                )
            }
            if (!isInPipMode) {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .height(48.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
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
                        } else if (trackingMode == TrackingMode.IMU_ONLY) {
                            Text(
                                text = "zero-seeded imu",
                                color = primaryColor,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                maxLines = 1
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
                                text = "${state.satelliteCount}",
                                color = primaryColor,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                maxLines = 1
                            )
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxHeight()
                            .clickable(
                                enabled = supportsFixedMode || supportsImuOnly,
                                role = Role.Button,
                                onClick = onTrackingModeChange
                            )
                            .semantics {
                                contentDescription = "Tracking mode"
                                stateDescription = trackingMode.accessibilityDescription
                            },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "mode: ",
                            color = labelColor,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            fontSize = 14.sp,
                            maxLines = 1
                        )
                        Text(
                            text = trackingMode.displayLabel,
                            color = if (supportsFixedMode || supportsImuOnly) primaryColor else labelColor,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            maxLines = 1
                        )
                    }
                }
            }

            if (showTrend) {
                SpeedTrendChart(
                    samples = state.speedTrend,
                    currentSpeedKmh = state.currentSpeedKmh,
                    color = primaryColor,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(
                            when {
                                veryCompactLayout -> 128.dp
                                compactLayout -> 144.dp
                                else -> 184.dp
                            }
                        )
                        .padding(bottom = 96.dp)
                        .semantics {
                            contentDescription = speedTrendDescription(
                                state.speedTrend,
                                state.currentSpeedKmh,
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
                            compactActions -> 8.dp
                            isInPipMode || !compactLayout || !showTrend -> 0.dp
                            veryCompactLayout -> (-24).dp
                            else -> (-36).dp
                        }
                    ),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val formattedSpeed = currentSpeed?.let { "%.2f".format(Locale.US, it) }
                val parts = formattedSpeed?.split(".")
                val intPart = parts?.get(0) ?: "--"
                val decPart = parts?.getOrNull(1)

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
                        modifier = Modifier.alignByBaseline()
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
                    )
                }

                AccuracyIndicator(
                    quality = state.estimateQuality,
                    speed = currentSpeed,
                    accuracy = currentAccuracy,
                    unit = speedUnit.label,
                    isInPipMode = isInPipMode
                )
            }

            if (!isInPipMode) {
                // --- BOTTOM LEFT: Stats Area ---
                if (showStats) {
                    Column(
                        modifier = Modifier.align(Alignment.BottomStart)
                    ) {
                        StatRow(
                            label = "top speed",
                            value = if (trackingMode == TrackingMode.IMU_ONLY) {
                                "gnss only"
                            } else {
                                "%.1f %s".format(Locale.US, maxSpeed, speedUnit.label)
                            },
                            labelColor,
                            primaryColor
                        )
                        if (trackingMode != TrackingMode.IMU_ONLY) {
                            StatRow(
                                label = "top satellites",
                                value = "${state.maxSatelliteCount}",
                                labelColor,
                                primaryColor
                            )
                        }
                    }
                }

                if (compactActions) {
                    Row(
                        modifier = Modifier.align(Alignment.BottomEnd),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        MinimalAction(text = "reset", color = primaryColor, onClick = onReset)
                        if (supportsPip) {
                            MinimalAction(text = "float", color = primaryColor, onClick = onEnterPip)
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier.align(Alignment.BottomEnd),
                        horizontalAlignment = Alignment.End
                    ) {
                        MinimalAction(text = "reset", color = primaryColor, onClick = onReset)
                        if (supportsPip) {
                            MinimalAction(text = "float", color = primaryColor, onClick = onEnterPip)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionRecovery(
    message: String,
    onRequestPermission: () -> Unit,
    onOpenSettings: () -> Unit,
    onUseImu: () -> Unit,
    supportsImu: Boolean,
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
        Button(onClick = onRequestPermission) {
            Text("grant location")
        }
        Button(onClick = onOpenSettings) {
            Text("open settings")
        }
        if (supportsImu) {
            Button(onClick = onUseImu) {
                Text("use imu only")
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
    isInPipMode: Boolean
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
        EstimateQuality.UNAVAILABLE -> "no signal"
    }
    val accessibilityText = when (quality) {
        EstimateQuality.UNAVAILABLE -> "Speed unavailable"
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
            modifier = Modifier
                .semantics { contentDescription = accessibilityText }
                .padding(top = 8.dp)
        )
    }
}

@Composable
private fun MinimalAction(text: String, color: Color, onClick: () -> Unit) {
    Text(
        text = text,
        color = color,
        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp,
        modifier = Modifier
            .clickable(role = Role.Button, onClick = onClick)
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .padding(horizontal = 8.dp, vertical = 5.dp)
    )
}

@Composable
private fun SpeedTrendChart(
    samples: List<SpeedTrendSample>,
    currentSpeedKmh: Float?,
    color: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val latestTimestamp = samples.lastOrNull()?.timestampNanos ?: return@Canvas
        val visible = samples.filter { it.timestampNanos >= latestTimestamp - TREND_WINDOW_NANOS }
        val speeds = visible.mapNotNull(SpeedTrendSample::speedKmh).toMutableList()
        currentSpeedKmh?.let(speeds::add)
        if (speeds.isEmpty()) return@Canvas

        val minimum = speeds.minOrNull() ?: return@Canvas
        val maximum = speeds.maxOrNull() ?: return@Canvas
        val naturalRange = maximum - minimum
        val range = max(1f, naturalRange)
        val lower = max(0f, minimum - range * 0.2f)
        val upper = maximum + range * 0.2f
        val verticalRange = max(1f, upper - lower)
        val startTimestamp = latestTimestamp - TREND_WINDOW_NANOS
        val brush = Brush.horizontalGradient(
            colors = listOf(color.copy(alpha = 0.04f), color.copy(alpha = 0.45f), color),
            startX = 0f,
            endX = size.width
        )
        val stroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        var path: Path? = null
        var previous: Offset? = null

        fun drawCurrentPath() {
            previous?.let { last -> path?.lineTo(last.x, last.y) }
            path?.let { drawPath(it, brush, style = stroke) }
            path = null
            previous = null
        }

        val pointInset = 7.dp.toPx()
        val chartRight = max(0f, size.width - pointInset)
        visible.forEachIndexed { index, sample ->
            val speedKmh = if (index == visible.lastIndex && currentSpeedKmh != null) {
                currentSpeedKmh
            } else {
                sample.speedKmh
            }
            val speed = speedKmh
            if (speed == null) {
                drawCurrentPath()
                return@forEachIndexed
            }
            val x = ((sample.timestampNanos - startTimestamp).toDouble() / TREND_WINDOW_NANOS)
                .toFloat().coerceIn(0f, 1f) * chartRight
            val y = (size.height - ((speed - lower) / verticalRange).coerceIn(0f, 1f) * size.height)
                .coerceIn(pointInset, size.height - pointInset)
            val point = Offset(x, y)
            val currentPath = path ?: Path().also {
                it.moveTo(point.x, point.y)
                path = it
            }
            previous?.let { prior ->
                val middle = Offset((prior.x + point.x) / 2f, (prior.y + point.y) / 2f)
                currentPath.quadraticBezierTo(prior.x, prior.y, middle.x, middle.y)
            }
            previous = point
        }
        drawCurrentPath()

        val currentSpeed = currentSpeedKmh ?: return@Canvas
        val currentY = size.height -
            ((currentSpeed - lower) / verticalRange).coerceIn(0f, 1f) * size.height
        val currentPoint = Offset(chartRight, currentY.coerceIn(pointInset, size.height - pointInset))
        drawCircle(color.copy(alpha = 0.22f), radius = pointInset, center = currentPoint)
        drawCircle(color, radius = 3.5.dp.toPx(), center = currentPoint)
    }
}

private val TrackingMode.displayLabel: String
    get() = when (this) {
        TrackingMode.HANDHELD -> "gnss"
        TrackingMode.FIXED -> "gnss+imu"
        TrackingMode.IMU_ONLY -> "imu"
    }

private val TrackingMode.accessibilityDescription: String
    get() = when (this) {
        TrackingMode.IMU_ONLY -> "imu, starts at zero; reset only while stopped"
        else -> displayLabel
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

internal fun trackingModeTransitionRequiresReset(
    previousMode: TrackingMode,
    nextMode: TrackingMode
): Boolean = previousMode == TrackingMode.IMU_ONLY || nextMode == TrackingMode.IMU_ONLY

private const val TREND_WINDOW_NANOS = 30_000_000_000L
private const val TREND_DIRECTION_THRESHOLD_KMH = 0.5f
private const val IMU_ZERO_SEED_WARNING = "IMU starts at 0; reset only while stopped"

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
