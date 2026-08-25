package com.mightykatun.speedometer.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class RefreshRateTest {
    @Test
    fun `cycles through supported refresh rates`() {
        assertEquals(RefreshRate.ONE_SECOND, RefreshRate.HALF_SECOND.next())
        assertEquals(RefreshRate.TWO_SECONDS, RefreshRate.ONE_SECOND.next())
        assertEquals(RefreshRate.HALF_SECOND, RefreshRate.TWO_SECONDS.next())
    }

    @Test
    fun `restores persisted refresh rate and defaults to one second`() {
        assertEquals(RefreshRate.HALF_SECOND, RefreshRate.fromPreference("500"))
        assertEquals(RefreshRate.ONE_SECOND, RefreshRate.fromPreference("1000"))
        assertEquals(RefreshRate.TWO_SECONDS, RefreshRate.fromPreference("2000"))
        assertEquals(RefreshRate.ONE_SECOND, RefreshRate.fromPreference(null))
        assertEquals(RefreshRate.ONE_SECOND, RefreshRate.fromPreference("unsupported"))
    }
}
