package com.mightykatun.speedometer.app.data.repository

import com.mightykatun.speedometer.app.domain.model.SpeedEstimate
import com.mightykatun.speedometer.app.domain.model.TrackingMode

data class TrackingModeResult(
    val commandId: Long,
    val requestedMode: TrackingMode,
    val effectiveMode: TrackingMode
)

enum class RepositoryError {
    GPS_PROVIDER_DISABLED,
    RETRYABLE_STARTUP_FAILURE
}

interface SpeedRepository {
    val supportsFixedMode: Boolean

    fun startUpdates(
        trackingMode: TrackingMode,
        onEstimate: (SpeedEstimate) -> Unit,
        onSatelliteCount: (Int) -> Unit,
        onGpsProviderEnabled: () -> Unit,
        onGpsRecoveryAccepted: () -> Unit,
        onPermissionRequired: () -> Unit,
        onError: (RepositoryError) -> Unit,
        onTrackingModeResult: (TrackingModeResult) -> Unit
    ): Long

    fun setTrackingMode(trackingMode: TrackingMode): Long
    fun stopUpdates()
    fun close()
}
