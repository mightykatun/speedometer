package com.mightykatun.speedometer.app.domain

import com.mightykatun.speedometer.app.domain.model.PositionFix
import com.mightykatun.speedometer.app.domain.model.RegattaMark
import com.mightykatun.speedometer.app.domain.model.RegattaMetrics
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

fun calculateRegattaMetrics(
    boat: RegattaMark?,
    pin: RegattaMark?,
    current: PositionFix?
): RegattaMetrics {
    if (boat == null || pin == null || current == null ||
        !boat.isUsable() || !pin.isUsable() || !current.isUsableForRegatta()
    ) return RegattaMetrics(null, null)

    val line = toEastNorth(pin.latitudeDegrees, pin.longitudeDegrees, boat)
    val position = toEastNorth(current.latitudeDegrees, current.longitudeDegrees, boat)
    val lineLength = hypot(line.eastMeters, line.northMeters)
    val endpointVectorUncertainty = hypot(
        boat.horizontalAccuracyMeters.toDouble(),
        pin.horizontalAccuracyMeters.toDouble()
    )
    val minimumDefensibleLength = maxOf(
        boat.horizontalAccuracyMeters.toDouble() + pin.horizontalAccuracyMeters,
        CONFIDENCE_SIGMAS * endpointVectorUncertainty
    )
    if (!lineLength.isFinite() || lineLength <= minimumDefensibleLength) {
        return RegattaMetrics(null, null)
    }

    // Boat-to-pin is directed portward when viewed toward the course. Its left side is pre-start.
    val signedDistance = cross(line, position) / lineLength
    if (!signedDistance.isFinite()) return RegattaMetrics(null, null)
    val alongLineFraction = (
        line.eastMeters * position.eastMeters + line.northMeters * position.northMeters
        ) / (lineLength * lineLength)
    val crossTrackPositionUncertainty = hypot(
        current.horizontalAccuracyMeters.toDouble(),
        hypot(
            pin.horizontalAccuracyMeters * alongLineFraction,
            boat.horizontalAccuracyMeters * (1.0 - alongLineFraction)
        )
    )
    val lineAngleConfidenceBound = asin(
        (CONFIDENCE_SIGMAS * endpointVectorUncertainty / lineLength).coerceIn(0.0, 1.0)
    )

    val timeToLine = calculateTimeToLine(
        signedDistanceMeters = signedDistance,
        crossTrackPositionUncertaintyMeters = crossTrackPositionUncertainty,
        lineAngleConfidenceBoundRadians = lineAngleConfidenceBound,
        line = line,
        lineLengthMeters = lineLength,
        current = current
    )
    return RegattaMetrics(signedDistance, timeToLine)
}

private fun calculateTimeToLine(
    signedDistanceMeters: Double,
    crossTrackPositionUncertaintyMeters: Double,
    lineAngleConfidenceBoundRadians: Double,
    line: EastNorth,
    lineLengthMeters: Double,
    current: PositionFix
): Double? {
    if (signedDistanceMeters <= CONFIDENCE_SIGMAS * crossTrackPositionUncertaintyMeters) return null
    if (!current.groundVelocityAccepted) return null
    val speed = current.groundSpeedMetersPerSecond?.toDouble()
        ?.takeIf { it.isFinite() && it >= MINIMUM_TTL_SPEED_METERS_PER_SECOND } ?: return null
    val speedAccuracy = current.groundSpeedAccuracyMetersPerSecond?.toDouble()
        ?.takeIf { it.isFinite() && it in 0.0..MAX_SPEED_ACCURACY_METERS_PER_SECOND }
        ?: return null
    val course = current.courseOverGroundDegrees?.toDouble()
        ?.takeIf { it.isFinite() } ?: return null
    val courseAccuracy = current.courseAccuracyDegrees?.toDouble()
        ?.takeIf { it.isFinite() && it in 0.0..MAX_COURSE_ACCURACY_DEGREES }
        ?: return null

    val courseRadians = Math.toRadians(course)
    val courseAngleUncertainty = Math.toRadians(courseAccuracy)
    val combinedAngleUncertainty = hypot(
        courseAngleUncertainty,
        lineAngleConfidenceBoundRadians / CONFIDENCE_SIGMAS
    )
    val combinedAngleConfidenceBound = hypot(
        CONFIDENCE_SIGMAS * courseAngleUncertainty,
        lineAngleConfidenceBoundRadians
    )
    val direction = EastNorth(sin(courseRadians), cos(courseRadians))
    val directionDerivative = EastNorth(cos(courseRadians), -sin(courseRadians))
    val closingFactor = -cross(line, direction) / lineLengthMeters
    val closingSpeed = speed * closingFactor
    val closingAngle = acos(closingFactor.coerceIn(-1.0, 1.0))
    val conservativeClosingAngle = closingAngle + combinedAngleConfidenceBound
    if (conservativeClosingAngle >= PI / 2.0) return null
    val conservativeClosingFactor = cos(conservativeClosingAngle)
    val conservativeSpeed = speed - CONFIDENCE_SIGMAS * speedAccuracy
    if (conservativeClosingFactor <= 0.0 || conservativeSpeed <= 0.0) return null
    val closingFactorDerivative = -cross(line, directionDerivative) / lineLengthMeters
    val closingSpeedUncertainty = hypot(
        closingFactor * speedAccuracy,
        speed * closingFactorDerivative * combinedAngleUncertainty
    )
    if (!closingSpeed.isFinite() || !closingSpeedUncertainty.isFinite() ||
        closingSpeed <= CONFIDENCE_SIGMAS * closingSpeedUncertainty
    ) return null

    return (signedDistanceMeters / closingSpeed).takeIf { it.isFinite() && it >= 0.0 }
}

private fun RegattaMark.isUsable(): Boolean =
    latitudeDegrees.isFinite() && latitudeDegrees in -90.0..90.0 &&
        longitudeDegrees.isFinite() && longitudeDegrees in -180.0..180.0 &&
        horizontalAccuracyMeters.isFinite() &&
        horizontalAccuracyMeters in 0f..MAX_REGATTA_ACCURACY_METERS

private fun PositionFix.isUsableForRegatta(): Boolean =
    timestampNanos > 0L &&
        latitudeDegrees.isFinite() && latitudeDegrees in -90.0..90.0 &&
        longitudeDegrees.isFinite() && longitudeDegrees in -180.0..180.0 &&
        horizontalAccuracyMeters.isFinite() &&
        horizontalAccuracyMeters in 0f..MAX_REGATTA_ACCURACY_METERS

private fun toEastNorth(
    latitudeDegrees: Double,
    longitudeDegrees: Double,
    origin: RegattaMark
): EastNorth {
    val latitude = latitudeDegrees * RADIANS_PER_DEGREE
    val longitude = longitudeDegrees * RADIANS_PER_DEGREE
    val originLatitude = origin.latitudeDegrees * RADIANS_PER_DEGREE
    val originLongitude = origin.longitudeDegrees * RADIANS_PER_DEGREE
    val point = toEarthCentered(latitude, longitude)
    val originPoint = toEarthCentered(originLatitude, originLongitude)
    val deltaX = point.x - originPoint.x
    val deltaY = point.y - originPoint.y
    val deltaZ = point.z - originPoint.z
    return EastNorth(
        eastMeters = -sin(originLongitude) * deltaX + cos(originLongitude) * deltaY,
        northMeters = -sin(originLatitude) * cos(originLongitude) * deltaX -
            sin(originLatitude) * sin(originLongitude) * deltaY +
            cos(originLatitude) * deltaZ
    )
}

private fun toEarthCentered(latitudeRadians: Double, longitudeRadians: Double): EarthCentered {
    val sinLatitude = sin(latitudeRadians)
    val radius = WGS84_SEMI_MAJOR_AXIS_METERS /
        kotlin.math.sqrt(1.0 - WGS84_ECCENTRICITY_SQUARED * sinLatitude * sinLatitude)
    return EarthCentered(
        x = radius * cos(latitudeRadians) * cos(longitudeRadians),
        y = radius * cos(latitudeRadians) * sin(longitudeRadians),
        z = radius * (1.0 - WGS84_ECCENTRICITY_SQUARED) * sinLatitude
    )
}

private fun cross(first: EastNorth, second: EastNorth): Double =
    first.eastMeters * second.northMeters - first.northMeters * second.eastMeters

private data class EastNorth(val eastMeters: Double, val northMeters: Double)

private data class EarthCentered(val x: Double, val y: Double, val z: Double)

const val MAX_REGATTA_ACCURACY_METERS = 10f
private const val MAX_SPEED_ACCURACY_METERS_PER_SECOND = 2.0
private const val MAX_COURSE_ACCURACY_DEGREES = 20.0
private const val MINIMUM_TTL_SPEED_METERS_PER_SECOND = 0.2
private const val CONFIDENCE_SIGMAS = 2.0
private const val WGS84_SEMI_MAJOR_AXIS_METERS = 6_378_137.0
private const val WGS84_ECCENTRICITY_SQUARED = 6.69437999014e-3
private const val RADIANS_PER_DEGREE = PI / 180.0
