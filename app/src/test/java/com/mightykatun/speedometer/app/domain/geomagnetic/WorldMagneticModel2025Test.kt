package com.mightykatun.speedometer.app.domain.geomagnetic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class WorldMagneticModel2025Test {
    @Test
    fun `UTC timestamp is converted to the model epoch`() {
        val field = requireNotNull(
            WorldMagneticModel2025.evaluate(
                latitudeDegrees = 14.0,
                longitudeDegrees = 143.0,
                altitudeMeters = 66_000.0,
                utcTimeMillis = 1_735_689_600_000L
            )
        )

        assertEquals(-0.19, field.declinationDegrees, 0.0051)
        assertEquals(35_003.635649, field.horizontalIntensityNanoTesla, 0.001)
    }

    @Test
    fun `matches official NOAA WMM2025 validation vectors`() {
        assertField(2025.0, 66.0, 14.0, 143.0, -0.19, 35_003.635649)
        assertField(2025.5, 44.0, 33.0, -118.0, 11.10, 23_678.743422)
        assertField(2026.5, 12.0, 33.0, -145.0, 11.96, 24_672.289649)
        assertField(2027.5, 0.0, -13.0, -59.0, -17.49, 22_401.493010)
        assertField(2028.5, 55.0, 86.0, 70.0, 67.64, 2_370.361097)
        assertField(2029.5, 77.0, -18.0, 138.0, 4.45, 31_847.600506)
    }

    @Test
    fun `rejects dates and coordinates outside model validity`() {
        assertNull(WorldMagneticModel2025.evaluateDecimalYear(0.0, 0.0, 0.0, 2024.999))
        assertNull(WorldMagneticModel2025.evaluateDecimalYear(0.0, 0.0, 0.0, 2030.0))
        assertNull(WorldMagneticModel2025.evaluateDecimalYear(91.0, 0.0, 0.0, 2026.0))
        assertNull(WorldMagneticModel2025.evaluateDecimalYear(0.0, 181.0, 0.0, 2026.0))
    }

    @Test
    fun `polar blackout evidence is exposed`() {
        val northPole = requireNotNull(
            WorldMagneticModel2025.evaluateDecimalYear(90.0, 0.0, 0.0, 2025.0)
        )

        assertFalse(northPole.hasDefensibleHeadingReference)
    }

    private fun assertField(
        year: Double,
        altitudeKilometers: Double,
        latitude: Double,
        longitude: Double,
        expectedDeclination: Double,
        expectedHorizontalIntensity: Double
    ) {
        val field = requireNotNull(
            WorldMagneticModel2025.evaluateDecimalYear(
                latitudeDegrees = latitude,
                longitudeDegrees = longitude,
                altitudeKilometers = altitudeKilometers,
                decimalYear = year
            )
        )
        assertEquals(expectedDeclination, field.declinationDegrees, 0.0051)
        assertEquals(expectedHorizontalIntensity, field.horizontalIntensityNanoTesla, 0.001)
    }
}
