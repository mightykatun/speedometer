package com.mightykatun.speedometer.app

import org.junit.Assert.assertEquals
import org.junit.Test

class SatelliteLevelTest {
    @Test
    fun `satellite status uses requested count boundaries`() {
        assertEquals(SatelliteLevel.NONE, satelliteLevel(0))
        assertEquals(SatelliteLevel.LIMITED, satelliteLevel(1))
        assertEquals(SatelliteLevel.LIMITED, satelliteLevel(5))
        assertEquals(SatelliteLevel.GOOD, satelliteLevel(6))
    }
}
