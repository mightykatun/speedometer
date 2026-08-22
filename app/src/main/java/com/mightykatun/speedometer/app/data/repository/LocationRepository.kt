package com.mightykatun.speedometer.app.data.repository

import com.mightykatun.speedometer.app.domain.model.GpsReading

interface LocationRepository {
    suspend fun startLocationUpdates(
        onReadingUpdate: (GpsReading) -> Unit,
        onGpsError: (String?) -> Unit
    )
    suspend fun stopLocationUpdates()
}
