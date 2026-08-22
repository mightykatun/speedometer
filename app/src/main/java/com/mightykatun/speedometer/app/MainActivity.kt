package com.mightykatun.speedometer.app

import android.Manifest
import android.app.PictureInPictureParams
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat

import com.mightykatun.speedometer.app.data.repository.SpeedRepositoryImpl
import com.mightykatun.speedometer.app.di.SpeedometerViewModelFactory
import com.mightykatun.speedometer.app.domain.model.EstimateQuality
import com.mightykatun.speedometer.app.domain.model.SpeedUnit
import com.mightykatun.speedometer.app.domain.model.SpeedometerState
import com.mightykatun.speedometer.app.domain.model.TrackingMode
import java.util.Locale

class MainActivity : ComponentActivity() {

    private val viewModel: SpeedometerViewModel by viewModels { SpeedometerViewModelFactory.INSTANCE }
    
    private lateinit var speedRepository: SpeedRepositoryImpl

    private var isInPipMode by mutableStateOf(false)
    private var speedUnit by mutableStateOf(SpeedUnit.KILOMETERS_PER_HOUR)
    private var trackingMode by mutableStateOf(TrackingMode.HANDHELD)

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineLocation = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val coarseLocation = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false

        if (fineLocation) {
            startSpeedTracking()
        } else {
            viewModel.onError(if (coarseLocation) {
                "Precise Location required for GPS speed accuracy.\nPlease allow 'Precise' in settings."
            } else {
                "Location permission denied.\nApp requires GPS access to function."
            })
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        speedRepository = SpeedRepositoryImpl(this)
        val preferences = getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        speedUnit = SpeedUnit.fromPreference(preferences.getString(SPEED_UNIT_KEY, null))
        trackingMode = TrackingMode.fromPreference(preferences.getString(TRACKING_MODE_KEY, null))
            .takeIf { it != TrackingMode.FIXED || speedRepository.supportsFixedMode }
            ?: TrackingMode.HANDHELD
        
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) 
            != PackageManager.PERMISSION_GRANTED) {
            requestPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }

        setContent {
            SpeedometerScreen(
                state = viewModel.state,
                error = viewModel.errorMessage,
                isInPipMode = isInPipMode,
                speedUnit = speedUnit,
                trackingMode = trackingMode,
                supportsFixedMode = speedRepository.supportsFixedMode,
                onSpeedUnitClick = { cycleSpeedUnit() },
                onTrackingModeChange = { changeTrackingMode(it) },
                onEnterPip = { enterPipMode() }
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(16, 9))
                .build()
            enterPictureInPictureMode(params)
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
        trackingMode = if (useFixedMode && speedRepository.supportsFixedMode) {
            TrackingMode.FIXED
        } else {
            TrackingMode.HANDHELD
        }
        getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(TRACKING_MODE_KEY, trackingMode.preferenceValue)
            .apply()
        speedRepository.setTrackingMode(trackingMode)
    }

    override fun onStart() {
        super.onStart()
        viewModel.onSessionStart()
        checkPermissionsAndStart()
    }

    override fun onStop() {
        super.onStop()
        speedRepository.stopUpdates()

        if (!isChangingConfigurations) {
            viewModel.onSessionReset()
        }
    }

    override fun onDestroy() {
        speedRepository.close()
        super.onDestroy()
    }

    private fun checkPermissionsAndStart() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED) {
            startSpeedTracking()
        }
    }

    private fun startSpeedTracking() {
        speedRepository.startUpdates(
            trackingMode = trackingMode,
            onEstimate = viewModel::onSpeedEstimateReceived,
            onSatelliteCount = viewModel::onSatelliteCountReceived,
            onGnssAvailable = viewModel::onGpsAvailable,
            onError = viewModel::onError
        )
    }

    private companion object {
        const val PREFERENCES_NAME = "speedometer_preferences"
        const val SPEED_UNIT_KEY = "speed_unit"
        const val TRACKING_MODE_KEY = "tracking_mode"
    }
}

@Composable
fun SpeedometerScreen(
    state: SpeedometerState,
    error: String?,
    isInPipMode: Boolean,
    speedUnit: SpeedUnit,
    trackingMode: TrackingMode,
    supportsFixedMode: Boolean,
    onSpeedUnitClick: () -> Unit,
    onTrackingModeChange: (Boolean) -> Unit,
    onEnterPip: () -> Unit
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
        if (error != null) {
            Text(
                text = error,
                color = Color.Red,
                textAlign = TextAlign.Center,
                fontSize = 20.sp,
                modifier = Modifier.align(Alignment.Center)
            )
        } else {
            if (!isInPipMode) {
                // --- TOP LEFT: Satellite Status ---
                Row(
                    modifier = Modifier.align(Alignment.TopStart),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .background(statusColor, shape = androidx.compose.foundation.shape.CircleShape)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "satellites: ",
                        color = labelColor,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        fontSize = 16.sp
                    )
                    Text(
                        text = "${state.satelliteCount}",
                        color = primaryColor,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                }

                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .heightIn(min = 48.dp)
                        .toggleable(
                            value = trackingMode == TrackingMode.FIXED,
                            enabled = supportsFixedMode,
                            role = Role.Switch,
                            onValueChange = onTrackingModeChange
                        ),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "mode: ",
                        color = labelColor,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        fontSize = 14.sp
                    )
                    Text(
                        text = if (trackingMode == TrackingMode.FIXED) "fixed" else "handheld",
                        color = if (supportsFixedMode) primaryColor else labelColor,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
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
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
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
private fun AccuracyIndicator(
    quality: EstimateQuality,
    accuracy: Float?,
    unit: String,
    isInPipMode: Boolean,
    labelColor: Color
) {
    val color = when (quality) {
        EstimateQuality.TRACKING -> Color.Green
        EstimateQuality.DEGRADED -> Color(0xFFFFA000)
        EstimateQuality.ACQUIRING -> labelColor
        EstimateQuality.UNAVAILABLE -> Color.Red
    }
    val text = when (quality) {
        EstimateQuality.TRACKING, EstimateQuality.DEGRADED ->
            accuracy?.let { "+/- %.1f %s".format(Locale.US, it, unit) } ?: "estimating"
        EstimateQuality.ACQUIRING -> "gps..."
        EstimateQuality.UNAVAILABLE -> "no signal"
    }

    Row(
        modifier = Modifier.padding(top = if (isInPipMode) 2.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(if (isInPipMode) 6.dp else 8.dp)
                .background(color, androidx.compose.foundation.shape.CircleShape)
        )
        if (!isInPipMode) {
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = text,
                color = color,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                fontSize = 13.sp
            )
        }
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
