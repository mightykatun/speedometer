package com.mightykatun.speedometer.app.data.repository

import com.mightykatun.speedometer.app.domain.model.VesselHeading
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.sin

internal enum class HeadingSensorSource {
    ROTATION_VECTOR,
    GEOMAGNETIC_ROTATION_VECTOR
}

internal enum class HeadingSensorAccuracy {
    UNRELIABLE,
    LOW,
    MEDIUM,
    HIGH
}

internal data class HeadingSensorSample(
    val magneticDegrees: Double,
    val accuracyDegrees: Double?,
    val source: HeadingSensorSource,
    val timestampNanos: Long
)

internal fun createHeadingSensorSample(
    rotationMatrix: FloatArray,
    reportedAccuracyRadians: Double?,
    sensorAccuracy: HeadingSensorAccuracy,
    source: HeadingSensorSource,
    timestampNanos: Long
): HeadingSensorSample? {
    if (rotationMatrix.size < 9 || rotationMatrix.any { !it.isFinite() } ||
        timestampNanos <= 0L || sensorAccuracy == HeadingSensorAccuracy.UNRELIABLE
    ) return null
    val accuracyDegrees = reportedAccuracyRadians
        ?.takeIf { it.isFinite() && it >= 0.0 }
        ?.let(Math::toDegrees)
    if (accuracyDegrees?.let { it > MAX_HEADING_ACCURACY_DEGREES } == true ||
        (accuracyDegrees == null && sensorAccuracy < HeadingSensorAccuracy.MEDIUM)
    ) return null

    // Android's rotation matrix maps device axes to magnetic East/North/Up. The screen faces
    // aft in portrait, so device -Z is the vessel's bow direction.
    val bowEast = -rotationMatrix[2].toDouble()
    val bowNorth = -rotationMatrix[5].toDouble()
    if (hypot(bowEast, bowNorth) <= MIN_HORIZONTAL_PROJECTION) return null
    return HeadingSensorSample(
        magneticDegrees = normalizeDegrees(Math.toDegrees(atan2(bowEast, bowNorth))),
        accuracyDegrees = accuracyDegrees,
        source = source,
        timestampNanos = timestampNanos
    )
}

internal class HeadingTracker {
    private var filteredMagneticDegrees: Double? = null
    private var latestAccuracyDegrees: Double? = null
    private var latestSource: HeadingSensorSource? = null
    private var latestTimestampNanos = 0L
    private var declinationDegrees: Double? = null
    private var declinationTimestampNanos = 0L

    fun update(sample: HeadingSensorSample) {
        if (sample.timestampNanos <= latestTimestampNanos || !sample.magneticDegrees.isFinite()) return
        val previous = filteredMagneticDegrees
        val elapsedNanos = sample.timestampNanos - latestTimestampNanos
        filteredMagneticDegrees = if (previous == null || sample.source != latestSource ||
            elapsedNanos > HEADING_STALE_NANOS
        ) {
            normalizeDegrees(sample.magneticDegrees)
        } else {
            val elapsedSeconds = elapsedNanos / NANOS_PER_SECOND
            val alpha = 1.0 - exp(-elapsedSeconds / HEADING_FILTER_TIME_CONSTANT_SECONDS)
            val deltaRadians = atan2(
                sin(Math.toRadians(sample.magneticDegrees - previous)),
                cos(Math.toRadians(sample.magneticDegrees - previous))
            )
            normalizeDegrees(previous + alpha * Math.toDegrees(deltaRadians))
        }
        latestAccuracyDegrees = sample.accuracyDegrees
        latestSource = sample.source
        latestTimestampNanos = sample.timestampNanos
    }

    fun updateDeclination(degrees: Double?, timestampNanos: Long) {
        if (timestampNanos < declinationTimestampNanos || degrees?.isFinite() == false) return
        declinationDegrees = degrees
        declinationTimestampNanos = timestampNanos
    }

    fun clearHeading() {
        filteredMagneticDegrees = null
        latestAccuracyDegrees = null
        latestSource = null
        latestTimestampNanos = 0L
    }

    fun reset() {
        clearHeading()
        declinationDegrees = null
        declinationTimestampNanos = 0L
    }

    fun snapshot(timestampNanos: Long): VesselHeading? {
        val magnetic = filteredMagneticDegrees ?: return null
        val declination = declinationDegrees ?: return null
        if (timestampNanos < latestTimestampNanos ||
            timestampNanos - latestTimestampNanos > HEADING_STALE_NANOS ||
            declinationTimestampNanos > latestTimestampNanos
        ) return null
        return VesselHeading(
            trueDegrees = normalizeDegrees(magnetic + declination).toFloat(),
            accuracyDegrees = latestAccuracyDegrees?.toFloat(),
            timestampNanos = latestTimestampNanos
        )
    }
}

internal fun normalizeDegrees(degrees: Double): Double = ((degrees % 360.0) + 360.0) % 360.0

internal const val HEADING_STALE_NANOS = 500_000_000L
private const val NANOS_PER_SECOND = 1_000_000_000.0
private const val HEADING_FILTER_TIME_CONSTANT_SECONDS = 0.25
private const val MAX_HEADING_ACCURACY_DEGREES = 25.0
private const val MIN_HORIZONTAL_PROJECTION = 0.25
