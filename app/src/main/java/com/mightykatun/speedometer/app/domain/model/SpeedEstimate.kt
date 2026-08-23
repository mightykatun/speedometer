package com.mightykatun.speedometer.app.domain.model

data class SpeedEstimate(
    val speedMetersPerSecond: Double?,
    val uncertaintyMetersPerSecond: Double,
    val quality: EstimateQuality,
    val timestampNanos: Long,
    val maximumWarmupStartTimestampNanos: Long = 0L,
    val maximumCandidateChanges: List<MaximumCandidateChange> = emptyList()
)
