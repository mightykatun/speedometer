package com.mightykatun.speedometer.app

import com.mightykatun.speedometer.app.domain.model.PositionFix
import java.math.BigDecimal
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

internal data class PositionTrailSnapshot(
    val points: List<PositionFix>,
    val segmentStarts: List<Long>
)

internal fun encodeGpx(snapshot: PositionTrailSnapshot): String {
    require(snapshot.points.isNotEmpty()) { "A GPX trace requires at least one point" }
    val segments = splitPositionTrail(snapshot.points, snapshot.segmentStarts)
    val utcFormatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    return buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<gpx version=\"1.1\" creator=\"Speedometer\" ")
        append("xmlns=\"http://www.topografix.com/GPX/1/1\" ")
        append("xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" ")
        append("xsi:schemaLocation=\"http://www.topografix.com/GPX/1/1 ")
        append("http://www.topografix.com/GPX/1/1/gpx.xsd\">\n")
        append("  <trk>\n")
        append("    <name>Speedometer trace</name>\n")
        segments.forEach { segment ->
            append("    <trkseg>\n")
            segment.forEach { point ->
                require(point.latitudeDegrees.isFinite() && point.latitudeDegrees in -90.0..90.0)
                require(point.longitudeDegrees.isFinite() && point.longitudeDegrees in -180.0..180.0)
                val longitude = if (point.longitudeDegrees == 180.0) -180.0 else {
                    point.longitudeDegrees
                }
                append("      <trkpt lat=\"")
                append(decimal(point.latitudeDegrees))
                append("\" lon=\"")
                append(decimal(longitude))
                append("\">\n")
                point.altitudeMeters?.takeIf { it.isFinite() }?.let { altitude ->
                    append("        <ele>")
                    append(decimal(altitude))
                    append("</ele>\n")
                }
                point.utcTimeMillis?.takeIf { it > 0L }?.let { utcTimeMillis ->
                    append("        <time>")
                    append(utcFormatter.format(Date(utcTimeMillis)))
                    append("</time>\n")
                }
                append("      </trkpt>\n")
            }
            append("    </trkseg>\n")
        }
        append("  </trk>\n")
        append("</gpx>\n")
    }
}

private fun splitPositionTrail(
    points: List<PositionFix>,
    segmentStarts: List<Long>
): List<List<PositionFix>> {
    if (points.isEmpty()) return emptyList()
    val sortedStarts = segmentStarts.sorted()
    val segments = ArrayList<List<PositionFix>>()
    var segment = ArrayList<PositionFix>()
    var nextStart = 0
    points.forEachIndexed { index, point ->
        val previousTimestamp = points.getOrNull(index - 1)?.timestampNanos ?: Long.MIN_VALUE
        var startsNewSegment = false
        while (nextStart < sortedStarts.size && sortedStarts[nextStart] <= point.timestampNanos) {
            if (index > 0 && sortedStarts[nextStart] > previousTimestamp) {
                startsNewSegment = true
            }
            nextStart++
        }
        if (startsNewSegment && segment.isNotEmpty()) {
            segments += segment
            segment = ArrayList()
        }
        segment += point
    }
    if (segment.isNotEmpty()) segments += segment
    return segments
}

private fun decimal(value: Double): String =
    BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
