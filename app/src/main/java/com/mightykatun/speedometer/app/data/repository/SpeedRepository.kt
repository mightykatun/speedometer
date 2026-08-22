package com.mightykatun.speedometer.app.data.repository

import com.mightykatun.speedometer.app.domain.model.SpeedEstimate
import com.mightykatun.speedometer.app.domain.model.TrackingMode

interface SpeedRepository {
    val supportsFixedMode: Boolean

    fun startUpdates(
        trackingMode: TrackingMode,
        onEstimate: (SpeedEstimate) -> Unit,
        onSatelliteCount: (Int) -> Unit,
        onGnssAvailable: () -> Unit,
        onError: (String) -> Unit
    )

    fun setTrackingMode(trackingMode: TrackingMode)
    fun stopUpdates()
    fun close()
}
