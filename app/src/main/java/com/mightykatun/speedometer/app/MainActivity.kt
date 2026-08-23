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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat

import com.mightykatun.speedometer.app.di.SpeedometerViewModelFactory
import com.mightykatun.speedometer.app.domain.model.EstimateQuality
import com.mightykatun.speedometer.app.domain.model.SpeedUnit
import com.mightykatun.speedometer.app.domain.model.SpeedometerState
import com.mightykatun.speedometer.app.domain.model.TrackingMode
import java.util.Locale

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
            .takeIf { it != TrackingMode.FIXED || speedRepository.supportsFixedMode }
            ?: TrackingMode.HANDHELD
        trackingMode = TrackingMode.HANDHELD
        
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) 
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
                supportsPip = supportsPictureInPicture(),
                permissionMessage = permissionIssue?.message,
                onSpeedUnitClick = { cycleSpeedUnit() },
                onTrackingModeChange = { changeTrackingMode(it) },
                onEnterPip = { enterPipMode() },
                onRequestPermission = { requestLocationPermission() },
                onOpenSettings = { openAppSettings() }
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

    private fun changeTrackingMode(useFixedMode: Boolean) {
        preferredTrackingMode = if (useFixedMode && speedRepository.supportsFixedMode) {
            TrackingMode.FIXED
        } else {
            TrackingMode.HANDHELD
        }
        getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(TRACKING_MODE_KEY, preferredTrackingMode.preferenceValue)
            .apply()
        speedRepository.setTrackingMode(preferredTrackingMode)
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
                trackingMode = effectiveMode
                viewModel.onWarning(
                    if (preferredTrackingMode == TrackingMode.FIXED &&
                        effectiveMode == TrackingMode.HANDHELD
                    ) {
                        "Motion sensors unavailable; using GNSS only"
                    } else null
                )
            }
        )
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
    supportsPip: Boolean,
    permissionMessage: String?,
    onSpeedUnitClick: () -> Unit,
    onTrackingModeChange: (Boolean) -> Unit,
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
    val pipContainerColor = if (isDark) Color.Gray else Color.LightGray
    val pipContentColor = if (isDark) Color.White else Color.Black

    val statusColor = if (state.satelliteCount >= 3) Color.Green else Color.Red
    val currentSpeed = state.currentSpeedKmh?.let(speedUnit::fromKilometersPerHour)
    val currentAccuracy = state.speedAccuracyKmh?.let(speedUnit::fromKilometersPerHour)
    val maxSpeed = speedUnit.fromKilometersPerHour(state.maxSpeedKmh)

    // Adjust font sizes for PiP mode
    val mainSpeedSize = if (isInPipMode) 64.sp else 120.sp
    val decimalSize = if (isInPipMode) 24.sp else 40.sp
    val unitSize = if (isInPipMode) 14.sp else 24.sp
    val letterSpacing = if (isInPipMode) (-2).sp else (-4).sp

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor)
            .padding(16.dp)
    ) {
        if (permissionMessage != null) {
            PermissionRecovery(
                message = permissionMessage,
                onRequestPermission = onRequestPermission,
                onOpenSettings = onOpenSettings,
                modifier = Modifier.align(Alignment.Center)
            )
        } else if (error != null) {
            Text(
                text = error,
                color = Color.Red,
                textAlign = TextAlign.Center,
                fontSize = 20.sp,
                modifier = Modifier.align(Alignment.Center)
            )
        } else {
            if (warning != null && !isInPipMode) {
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
                                .background(statusColor, shape = androidx.compose.foundation.shape.CircleShape)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
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

                    Row(
                        modifier = Modifier
                            .fillMaxHeight()
                            .toggleable(
                                value = trackingMode == TrackingMode.FIXED,
                                enabled = supportsFixedMode,
                                role = Role.Switch,
                                onValueChange = onTrackingModeChange
                            )
                            .semantics { contentDescription = "Tracking mode" },
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
                            text = if (trackingMode == TrackingMode.FIXED) "gnss+imu" else "gnss",
                            color = if (supportsFixedMode) primaryColor else labelColor,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            maxLines = 1
                        )
                    }
                }
            }

            // --- CENTER: Speedometer ---
            Column(
                modifier = Modifier.align(Alignment.Center),
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
                    accuracy = currentAccuracy,
                    unit = speedUnit.label,
                    isInPipMode = isInPipMode,
                    labelColor = labelColor
                )
            }

            if (!isInPipMode) {
                // --- BOTTOM LEFT: Stats Area ---
                Column(
                    modifier = Modifier.align(Alignment.BottomStart)
                ) {
                    StatRow(
                        label = "top speed",
                        value = "%.1f %s".format(Locale.US, maxSpeed, speedUnit.label),
                        labelColor,
                        primaryColor
                    )
                    StatRow(label = "top satellites", value = "${state.maxSatelliteCount}", labelColor, primaryColor)
                }
                
                // --- BOTTOM RIGHT: PiP Button ---
                if (supportsPip) {
                    Button(
                        onClick = onEnterPip,
                        modifier = Modifier
                            .align(Alignment.BottomEnd),
                        colors = ButtonDefaults.buttonColors(containerColor = pipContainerColor)
                    ) {
                        Text(text = "float", color = pipContentColor)
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
    }
}

@Composable
private fun AccuracyIndicator(
    quality: EstimateQuality,
    accuracy: Float?,
    unit: String,
    isInPipMode: Boolean,
    labelColor: Color
) {
    if (quality == EstimateQuality.ACQUIRING) return

    val color = when (quality) {
        EstimateQuality.TRACKING -> Color.Green
        EstimateQuality.DEGRADED -> Color(0xFFFFA000)
        EstimateQuality.ACQUIRING -> labelColor
        EstimateQuality.UNAVAILABLE -> Color.Red
    }
    val text = when (quality) {
        EstimateQuality.TRACKING, EstimateQuality.DEGRADED ->
            accuracy?.let { "± %.1f %s".format(Locale.US, it, unit) } ?: "estimating"
        EstimateQuality.ACQUIRING -> ""
        EstimateQuality.UNAVAILABLE -> "no signal"
    }

    if (isInPipMode) {
        Box(
            modifier = Modifier
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
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}

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
