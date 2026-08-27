package com.mightykatun.speedometer.app

import com.mightykatun.speedometer.app.domain.model.PositionFix
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class GpxTrailEncoderTest {
    @Test
    fun `encoder writes valid segmented GPX with optional elevation and UTC time`() {
        val snapshot = PositionTrailSnapshot(
            points = listOf(
                fix(51.0, 4.0, 1L, altitude = 12.5, utcTimeMillis = 1_704_067_200_000L),
                fix(51.1, 4.1, 2L),
                fix(52.0, 180.0, 3L)
            ),
            segmentStarts = listOf(3L)
        )

        val xml = encodeGpx(snapshot)
        val document = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
        val segments = document.getElementsByTagNameNS(GPX_NAMESPACE, "trkseg")
        val points = document.getElementsByTagNameNS(GPX_NAMESPACE, "trkpt")

        assertEquals(2, segments.length)
        assertEquals(3, points.length)
        assertEquals("51", points.item(0).attributes.getNamedItem("lat").nodeValue)
        assertEquals("4", points.item(0).attributes.getNamedItem("lon").nodeValue)
        assertEquals("-180", points.item(2).attributes.getNamedItem("lon").nodeValue)
        assertEquals("12.5", document.getElementsByTagNameNS(GPX_NAMESPACE, "ele").item(0).textContent)
        assertEquals(
            "2024-01-01T00:00:00.000Z",
            document.getElementsByTagNameNS(GPX_NAMESPACE, "time").item(0).textContent
        )
        assertFalse(xml.contains("E+"))
    }

    private fun fix(
        latitude: Double,
        longitude: Double,
        timestampNanos: Long,
        altitude: Double? = null,
        utcTimeMillis: Long? = null
    ) = PositionFix(
        latitudeDegrees = latitude,
        longitudeDegrees = longitude,
        headingDegrees = null,
        horizontalAccuracyMeters = 5f,
        timestampNanos = timestampNanos,
        altitudeMeters = altitude,
        utcTimeMillis = utcTimeMillis
    )

    private companion object {
        const val GPX_NAMESPACE = "http://www.topografix.com/GPX/1/1"
    }
}
