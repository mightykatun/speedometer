package com.mightykatun.speedometer.app.domain.geomagnetic

import java.util.Calendar
import java.util.GregorianCalendar
import java.util.TimeZone
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

data class GeomagneticFieldEstimate(
    val declinationDegrees: Double,
    val horizontalIntensityNanoTesla: Double,
    val totalIntensityNanoTesla: Double
) {
    val hasDefensibleHeadingReference: Boolean
        get() = horizontalIntensityNanoTesla >= HEADING_BLACKOUT_THRESHOLD_NANOTESLA
}

/**
 * Compact double-precision WMM2025 evaluator. Coefficients and validation data are public-domain
 * U.S. Government material published by NOAA NCEI: https://doi.org/10.25921/aqfd-sd83
 */
object WorldMagneticModel2025 {
    fun evaluate(
        latitudeDegrees: Double,
        longitudeDegrees: Double,
        altitudeMeters: Double,
        utcTimeMillis: Long
    ): GeomagneticFieldEstimate? {
        val decimalYear = decimalYear(utcTimeMillis) ?: return null
        return evaluateDecimalYear(
            latitudeDegrees = latitudeDegrees,
            longitudeDegrees = longitudeDegrees,
            altitudeKilometers = altitudeMeters / METERS_PER_KILOMETER,
            decimalYear = decimalYear
        )
    }

    internal fun evaluateDecimalYear(
        latitudeDegrees: Double,
        longitudeDegrees: Double,
        altitudeKilometers: Double,
        decimalYear: Double
    ): GeomagneticFieldEstimate? {
        if (!latitudeDegrees.isFinite() || latitudeDegrees !in -90.0..90.0 ||
            !longitudeDegrees.isFinite() || longitudeDegrees !in -180.0..180.0 ||
            !altitudeKilometers.isFinite() || altitudeKilometers !in -1.0..850.0 ||
            !decimalYear.isFinite() || decimalYear < MODEL_EPOCH || decimalYear >= MODEL_END_YEAR
        ) return null

        val geodeticLatitude = Math.toRadians(latitudeDegrees)
        val longitude = Math.toRadians(longitudeDegrees)
        val sinLatitude = sin(geodeticLatitude)
        val cosLatitude = cos(geodeticLatitude)
        val semiMajorSquared = WGS84_SEMI_MAJOR_AXIS_KILOMETERS *
            WGS84_SEMI_MAJOR_AXIS_KILOMETERS
        val semiMinorSquared = WGS84_SEMI_MINOR_AXIS_KILOMETERS *
            WGS84_SEMI_MINOR_AXIS_KILOMETERS
        val eccentricitySquared = 1.0 - semiMinorSquared / semiMajorSquared
        val radiusOfCurvature = WGS84_SEMI_MAJOR_AXIS_KILOMETERS /
            sqrt(1.0 - eccentricitySquared * sinLatitude * sinLatitude)
        val earthCenteredX = (radiusOfCurvature + altitudeKilometers) * cosLatitude
        val earthCenteredZ = (
            radiusOfCurvature * (1.0 - eccentricitySquared) + altitudeKilometers
            ) * sinLatitude
        val geocentricRadius = hypot(earthCenteredX, earthCenteredZ)
        val geocentricLatitude = asin(earthCenteredZ / geocentricRadius)
        val theta = PI / 2.0 - geocentricLatitude
        val legendre = legendreTable(theta)
        val radiusRatio = EARTH_REFERENCE_RADIUS_KILOMETERS / geocentricRadius
        val relativeRadiusPowers = DoubleArray(MAX_DEGREE + 3) { 1.0 }
        for (index in 1 until relativeRadiusPowers.size) {
            relativeRadiusPowers[index] = relativeRadiusPowers[index - 1] * radiusRatio
        }

        val yearsSinceEpoch = decimalYear - MODEL_EPOCH
        var geocentricNorth = 0.0
        var geocentricEast = 0.0
        var geocentricDown = 0.0
        for (degree in 1..MAX_DEGREE) {
            for (order in 0..degree) {
                val sinLongitude = sin(order * longitude)
                val cosLongitude = cos(order * longitude)
                val g = G[degree][order] + yearsSinceEpoch * DELTA_G[degree][order]
                val h = H[degree][order] + yearsSinceEpoch * DELTA_H[degree][order]
                val common = g * cosLongitude + h * sinLongitude
                val scale = relativeRadiusPowers[degree + 2] * SCHMIDT[degree][order]
                geocentricNorth += scale * common * legendre.derivative[degree][order]
                geocentricEast += scale * order *
                    (g * sinLongitude - h * cosLongitude) *
                    legendre.value[degree][order]
                geocentricDown -= (degree + 1) * scale * common *
                    legendre.value[degree][order]
            }
        }

        val cosGeocentricLatitude = cos(geocentricLatitude)
        geocentricEast = if (kotlin.math.abs(cosGeocentricLatitude) > POLE_EPSILON) {
            geocentricEast / cosGeocentricLatitude
        } else {
            eastAtPole(
                geocentricLatitude = geocentricLatitude,
                longitude = longitude,
                relativeRadiusPowers = relativeRadiusPowers,
                yearsSinceEpoch = yearsSinceEpoch
            )
        }

        val latitudeDifference = geodeticLatitude - geocentricLatitude
        val north = geocentricNorth * cos(latitudeDifference) +
            geocentricDown * sin(latitudeDifference)
        val east = geocentricEast
        val down = -geocentricNorth * sin(latitudeDifference) +
            geocentricDown * cos(latitudeDifference)
        val horizontal = hypot(north, east)
        val total = hypot(horizontal, down)
        if (!north.isFinite() || !east.isFinite() || !down.isFinite() ||
            !horizontal.isFinite() || !total.isFinite()
        ) return null
        return GeomagneticFieldEstimate(
            declinationDegrees = Math.toDegrees(atan2(east, north)),
            horizontalIntensityNanoTesla = horizontal,
            totalIntensityNanoTesla = total
        )
    }

    private fun decimalYear(utcTimeMillis: Long): Double? {
        if (utcTimeMillis <= 0L) return null
        val calendar = GregorianCalendar(UTC).apply { timeInMillis = utcTimeMillis }
        val year = calendar.get(Calendar.YEAR)
        if (year !in MODEL_EPOCH.toInt() until MODEL_END_YEAR.toInt()) return null
        val yearStart = GregorianCalendar(UTC).apply {
            clear()
            set(year, Calendar.JANUARY, 1, 0, 0, 0)
        }.timeInMillis
        val nextYearStart = GregorianCalendar(UTC).apply {
            clear()
            set(year + 1, Calendar.JANUARY, 1, 0, 0, 0)
        }.timeInMillis
        return year + (utcTimeMillis - yearStart).toDouble() / (nextYearStart - yearStart)
    }

    private fun legendreTable(theta: Double): LegendreTable {
        val value = Array(MAX_DEGREE + 1) { DoubleArray(MAX_DEGREE + 1) }
        val derivative = Array(MAX_DEGREE + 1) { DoubleArray(MAX_DEGREE + 1) }
        value[0][0] = 1.0
        val sinTheta = sin(theta)
        val cosTheta = cos(theta)
        for (degree in 1..MAX_DEGREE) {
            for (order in 0..degree) {
                when {
                    degree == order -> {
                        value[degree][order] = sinTheta * value[degree - 1][order - 1]
                        derivative[degree][order] =
                            cosTheta * value[degree - 1][order - 1] +
                            sinTheta * derivative[degree - 1][order - 1]
                    }
                    degree == 1 || order == degree - 1 -> {
                        value[degree][order] = cosTheta * value[degree - 1][order]
                        derivative[degree][order] =
                            -sinTheta * value[degree - 1][order] +
                            cosTheta * derivative[degree - 1][order]
                    }
                    else -> {
                        val factor = ((degree - 1.0) * (degree - 1.0) - order * order) /
                            ((2.0 * degree - 1.0) * (2.0 * degree - 3.0))
                        value[degree][order] = cosTheta * value[degree - 1][order] -
                            factor * value[degree - 2][order]
                        derivative[degree][order] =
                            -sinTheta * value[degree - 1][order] +
                            cosTheta * derivative[degree - 1][order] -
                            factor * derivative[degree - 2][order]
                    }
                }
            }
        }
        return LegendreTable(value, derivative)
    }

    private fun eastAtPole(
        geocentricLatitude: Double,
        longitude: Double,
        relativeRadiusPowers: DoubleArray,
        yearsSinceEpoch: Double
    ): Double {
        val poleLegendre = DoubleArray(MAX_DEGREE + 1)
        poleLegendre[0] = 1.0
        var previousSchmidt = 1.0
        var east = 0.0
        for (degree in 1..MAX_DEGREE) {
            val schmidtOrderZero = previousSchmidt * (2.0 * degree - 1.0) / degree
            val schmidtOrderOne = schmidtOrderZero * sqrt(2.0 * degree / (degree + 1.0))
            previousSchmidt = schmidtOrderZero
            poleLegendre[degree] = if (degree == 1) {
                poleLegendre[degree - 1]
            } else {
                val factor = ((degree - 1.0) * (degree - 1.0) - 1.0) /
                    ((2.0 * degree - 1.0) * (2.0 * degree - 3.0))
                sin(geocentricLatitude) * poleLegendre[degree - 1] -
                    factor * poleLegendre[degree - 2]
            }
            val g = G[degree][1] + yearsSinceEpoch * DELTA_G[degree][1]
            val h = H[degree][1] + yearsSinceEpoch * DELTA_H[degree][1]
            east += relativeRadiusPowers[degree + 2] *
                (g * sin(longitude) - h * cos(longitude)) *
                poleLegendre[degree] * schmidtOrderOne
        }
        return east
    }

    private fun coefficients(column: Int): Array<DoubleArray> {
        val result = Array(MAX_DEGREE + 1) { DoubleArray(MAX_DEGREE + 1) }
        COEFFICIENT_DATA.lineSequence().filter(String::isNotBlank).forEach { line ->
            val fields = line.trim().split(Regex("\\s+"))
            result[fields[0].toInt()][fields[1].toInt()] = fields[column].toDouble()
        }
        return result
    }

    private fun schmidtFactors(): Array<DoubleArray> {
        val result = Array(MAX_DEGREE + 1) { DoubleArray(MAX_DEGREE + 1) }
        result[0][0] = 1.0
        for (degree in 1..MAX_DEGREE) {
            result[degree][0] = result[degree - 1][0] * (2.0 * degree - 1.0) / degree
            for (order in 1..degree) {
                result[degree][order] = result[degree][order - 1] * sqrt(
                    (degree - order + 1.0) * (if (order == 1) 2.0 else 1.0) /
                        (degree + order)
                )
            }
        }
        return result
    }

    private data class LegendreTable(
        val value: Array<DoubleArray>,
        val derivative: Array<DoubleArray>
    )

    private val G = coefficients(2)
    private val H = coefficients(3)
    private val DELTA_G = coefficients(4)
    private val DELTA_H = coefficients(5)
    private val SCHMIDT = schmidtFactors()
    private val UTC = TimeZone.getTimeZone("UTC")

    private const val MODEL_EPOCH = 2025.0
    private const val MODEL_END_YEAR = 2030.0
    private const val MAX_DEGREE = 12
    private const val WGS84_SEMI_MAJOR_AXIS_KILOMETERS = 6378.137
    private const val WGS84_SEMI_MINOR_AXIS_KILOMETERS = 6356.7523142
    private const val EARTH_REFERENCE_RADIUS_KILOMETERS = 6371.2
    private const val METERS_PER_KILOMETER = 1000.0
    private const val POLE_EPSILON = 1.0e-10

    private const val COEFFICIENT_DATA = """
1 0 -29351.8 0.0 12.0 0.0
1 1 -1410.8 4545.4 9.7 -21.5
2 0 -2556.6 0.0 -11.6 0.0
2 1 2951.1 -3133.6 -5.2 -27.7
2 2 1649.3 -815.1 -8.0 -12.1
3 0 1361.0 0.0 -1.3 0.0
3 1 -2404.1 -56.6 -4.2 4.0
3 2 1243.8 237.5 0.4 -0.3
3 3 453.6 -549.5 -15.6 -4.1
4 0 895.0 0.0 -1.6 0.0
4 1 799.5 278.6 -2.4 -1.1
4 2 55.7 -133.9 -6.0 4.1
4 3 -281.1 212.0 5.6 1.6
4 4 12.1 -375.6 -7.0 -4.4
5 0 -233.2 0.0 0.6 0.0
5 1 368.9 45.4 1.4 -0.5
5 2 187.2 220.2 0.0 2.2
5 3 -138.7 -122.9 0.6 0.4
5 4 -142.0 43.0 2.2 1.7
5 5 20.9 106.1 0.9 1.9
6 0 64.4 0.0 -0.2 0.0
6 1 63.8 -18.4 -0.4 0.3
6 2 76.9 16.8 0.9 -1.6
6 3 -115.7 48.8 1.2 -0.4
6 4 -40.9 -59.8 -0.9 0.9
6 5 14.9 10.9 0.3 0.7
6 6 -60.7 72.7 0.9 0.9
7 0 79.5 0.0 -0.0 0.0
7 1 -77.0 -48.9 -0.1 0.6
7 2 -8.8 -14.4 -0.1 0.5
7 3 59.3 -1.0 0.5 -0.8
7 4 15.8 23.4 -0.1 0.0
7 5 2.5 -7.4 -0.8 -1.0
7 6 -11.1 -25.1 -0.8 0.6
7 7 14.2 -2.3 0.8 -0.2
8 0 23.2 0.0 -0.1 0.0
8 1 10.8 7.1 0.2 -0.2
8 2 -17.5 -12.6 0.0 0.5
8 3 2.0 11.4 0.5 -0.4
8 4 -21.7 -9.7 -0.1 0.4
8 5 16.9 12.7 0.3 -0.5
8 6 15.0 0.7 0.2 -0.6
8 7 -16.8 -5.2 -0.0 0.3
8 8 0.9 3.9 0.2 0.2
9 0 4.6 0.0 -0.0 0.0
9 1 7.8 -24.8 -0.1 -0.3
9 2 3.0 12.2 0.1 0.3
9 3 -0.2 8.3 0.3 -0.3
9 4 -2.5 -3.3 -0.3 0.3
9 5 -13.1 -5.2 0.0 0.2
9 6 2.4 7.2 0.3 -0.1
9 7 8.6 -0.6 -0.1 -0.2
9 8 -8.7 0.8 0.1 0.4
9 9 -12.9 10.0 -0.1 0.1
10 0 -1.3 0.0 0.1 0.0
10 1 -6.4 3.3 0.0 0.0
10 2 0.2 0.0 0.1 -0.0
10 3 2.0 2.4 0.1 -0.2
10 4 -1.0 5.3 -0.0 0.1
10 5 -0.6 -9.1 -0.3 -0.1
10 6 -0.9 0.4 0.0 0.1
10 7 1.5 -4.2 -0.1 0.0
10 8 0.9 -3.8 -0.1 -0.1
10 9 -2.7 0.9 -0.0 0.2
10 10 -3.9 -9.1 -0.0 -0.0
11 0 2.9 0.0 0.0 0.0
11 1 -1.5 0.0 -0.0 -0.0
11 2 -2.5 2.9 0.0 0.1
11 3 2.4 -0.6 0.0 -0.0
11 4 -0.6 0.2 0.0 0.1
11 5 -0.1 0.5 -0.1 -0.0
11 6 -0.6 -0.3 0.0 -0.0
11 7 -0.1 -1.2 -0.0 0.1
11 8 1.1 -1.7 -0.1 -0.0
11 9 -1.0 -2.9 -0.1 0.0
11 10 -0.2 -1.8 -0.1 0.0
11 11 2.6 -2.3 -0.1 0.0
12 0 -2.0 0.0 0.0 0.0
12 1 -0.2 -1.3 0.0 -0.0
12 2 0.3 0.7 -0.0 0.0
12 3 1.2 1.0 -0.0 -0.1
12 4 -1.3 -1.4 -0.0 0.1
12 5 0.6 -0.0 -0.0 -0.0
12 6 0.6 0.6 0.1 -0.0
12 7 0.5 -0.1 -0.0 -0.0
12 8 -0.1 0.8 0.0 0.0
12 9 -0.4 0.1 0.0 -0.0
12 10 -0.2 -1.0 -0.1 -0.0
12 11 -1.3 0.1 -0.0 0.0
12 12 -0.7 0.2 -0.1 -0.1
"""
}

private const val HEADING_BLACKOUT_THRESHOLD_NANOTESLA = 2_000.0
