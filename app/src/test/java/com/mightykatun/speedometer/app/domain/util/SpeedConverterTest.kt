package com.mightykatun.speedometer.app.domain.util

import org.junit.Test
import org.junit.Assert.assertEquals

class SpeedConverterTest {
    
    @Test
    fun `metersPerSecondToKmh converts correctly`() {
        assertEquals(36.0f, SpeedConverter.metersPerSecondToKmh(10f), 0.01f)
        assertEquals(0.0f, SpeedConverter.metersPerSecondToKmh(0f), 0.01f)
        assertEquals(18.0f, SpeedConverter.metersPerSecondToKmh(5f), 0.01f)
    }
}
