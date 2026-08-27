package com.mightykatun.speedometer.app

import androidx.compose.ui.geometry.Offset
import com.mightykatun.speedometer.app.domain.model.PositionFix
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal data class ProjectedPositionTrail(
    val trace: List<Offset>,
    val current: Offset
)

internal fun projectPositionTrail(
    trail: List<PositionFix>,
    current: PositionFix,
    width: Float,
    height: Float,
    padding: Float,
    minimumSpanMeters: Double = 40.0
): ProjectedPositionTrail? {
    if (!width.isFinite() || !height.isFinite() || !padding.isFinite() ||
        !minimumSpanMeters.isFinite() || padding < 0f || minimumSpanMeters <= 0.0 ||
        width <= 2f * padding || height <= 2f * padding
    ) return null

    val replacesLast = trail.lastOrNull()?.timestampNanos == current.timestampNanos
    val pointCount = trail.size + if (replacesLast) 0 else 1
    fun fixAt(index: Int): PositionFix = when {
        replacesLast && index == trail.lastIndex -> current
        index < trail.size -> trail[index]
        else -> current
    }

    var latitudeTotal = 0.0
    for (index in 0 until pointCount) {
        val fix = fixAt(index)
        if (!fix.latitudeDegrees.isFinite() || fix.latitudeDegrees !in -90.0..90.0 ||
            !fix.longitudeDegrees.isFinite() || fix.longitudeDegrees !in -180.0..180.0
        ) return null
        latitudeTotal += fix.latitudeDegrees
    }

    val origin = fixAt(0)
    val referenceLatitudeRadians = (latitudeTotal / pointCount)
        .coerceIn(-89.0, 89.0) * RADIANS_PER_DEGREE
    val eastNorthMeters = DoubleArray(pointCount * 2)
    var minEast = Double.POSITIVE_INFINITY
    var maxEast = Double.NEGATIVE_INFINITY
    var minNorth = Double.POSITIVE_INFINITY
    var maxNorth = Double.NEGATIVE_INFINITY
    var previousLongitude = origin.longitudeDegrees
    var unwrappedLongitude = origin.longitudeDegrees
    for (index in 0 until pointCount) {
        val fix = fixAt(index)
        if (index > 0) {
            unwrappedLongitude += Math.IEEEremainder(
                fix.longitudeDegrees - previousLongitude,
                360.0
            )
            previousLongitude = fix.longitudeDegrees
        }
        val east = (unwrappedLongitude - origin.longitudeDegrees) * RADIANS_PER_DEGREE *
            EARTH_RADIUS_METERS * cos(referenceLatitudeRadians)
        val north = (fix.latitudeDegrees - origin.latitudeDegrees) *
            RADIANS_PER_DEGREE * EARTH_RADIUS_METERS
        eastNorthMeters[index * 2] = east
        eastNorthMeters[index * 2 + 1] = north
        minEast = min(minEast, east)
        maxEast = max(maxEast, east)
        minNorth = min(minNorth, north)
        maxNorth = max(maxNorth, north)
    }

    val eastSpan = max(maxEast - minEast, minimumSpanMeters)
    val northSpan = max(maxNorth - minNorth, minimumSpanMeters)
    val scale = min(
        (width - 2f * padding) / eastSpan.toFloat(),
        (height - 2f * padding) / northSpan.toFloat()
    )
    val centerEast = (minEast + maxEast) / 2.0
    val centerNorth = (minNorth + maxNorth) / 2.0
    val projected = ArrayList<Offset>(pointCount)
    for (index in 0 until pointCount) {
        projected += Offset(
            x = width / 2f + ((eastNorthMeters[index * 2] - centerEast) * scale).toFloat(),
            y = height / 2f - ((eastNorthMeters[index * 2 + 1] - centerNorth) * scale).toFloat()
        )
    }

    return ProjectedPositionTrail(
        trace = projected,
        current = projected.last()
    )
}

internal fun headingLabel(headingDegrees: Float): String {
    val normalized = ((headingDegrees % 360f) + 360f) % 360f
    val cardinal = CARDINAL_DIRECTIONS[((normalized + 22.5f) / 45f).toInt() % 8]
    val rounded = normalized.roundToInt() % 360
    return "%03d\u00b0 %s".format(java.util.Locale.US, rounded, cardinal)
}

internal fun altitudeLabel(altitudeMeters: Double): String =
    "%.0f m".format(java.util.Locale.US, altitudeMeters)

private const val EARTH_RADIUS_METERS = 6_371_000.0
private const val RADIANS_PER_DEGREE = PI / 180.0
private val CARDINAL_DIRECTIONS = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
