package com.mightykatun.speedometer.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeedUnitTest {

    @Test
    fun `converts kilometers per hour to every supported unit`() {
        val speedKmh = 36f

        assertEquals(36f, SpeedUnit.KILOMETERS_PER_HOUR.fromKilometersPerHour(speedKmh), 0.01f)
        assertEquals(22.37f, SpeedUnit.MILES_PER_HOUR.fromKilometersPerHour(speedKmh), 0.01f)
        assertEquals(19.44f, SpeedUnit.KNOTS.fromKilometersPerHour(speedKmh), 0.01f)
        assertEquals(10f, SpeedUnit.METERS_PER_SECOND.fromKilometersPerHour(speedKmh), 0.01f)
    }

    @Test
    fun `cycles through units in display order`() {
        assertEquals(SpeedUnit.MILES_PER_HOUR, SpeedUnit.KILOMETERS_PER_HOUR.next())
        assertEquals(SpeedUnit.KNOTS, SpeedUnit.MILES_PER_HOUR.next())
        assertEquals(SpeedUnit.METERS_PER_SECOND, SpeedUnit.KNOTS.next())
        assertEquals(SpeedUnit.KILOMETERS_PER_HOUR, SpeedUnit.METERS_PER_SECOND.next())
    }

    @Test
    fun `restores persisted units and falls back to kilometers per hour`() {
        SpeedUnit.entries.forEach { unit ->
            assertEquals(unit, SpeedUnit.fromPreference(unit.preferenceValue))
        }

        assertEquals(SpeedUnit.KILOMETERS_PER_HOUR, SpeedUnit.fromPreference(null))
        assertEquals(SpeedUnit.KILOMETERS_PER_HOUR, SpeedUnit.fromPreference("unsupported"))
    }
}
