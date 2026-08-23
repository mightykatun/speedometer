package com.mightykatun.speedometer.app.domain.model

data class MaximumCandidate(
    val id: Long,
    val speedMetersPerSecond: Double,
    val timestampNanos: Long,
    val satelliteCount: Int
)

sealed interface MaximumCandidateChange {
    val id: Long

    data class Upsert(val candidate: MaximumCandidate) : MaximumCandidateChange {
        override val id: Long = candidate.id
    }

    data class Retract(override val id: Long) : MaximumCandidateChange

    data class Finalize(
        override val id: Long,
        val candidate: MaximumCandidate?
    ) : MaximumCandidateChange
}
