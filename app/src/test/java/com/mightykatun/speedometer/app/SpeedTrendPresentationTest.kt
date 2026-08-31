package com.mightykatun.speedometer.app

import com.mightykatun.speedometer.app.domain.model.RefreshRate
import org.junit.Assert.assertEquals
import org.junit.Test

class SpeedTrendPresentationTest {
    @Test
    fun `half second refresh overlaps graph interpolation`() {
        assertEquals(1_000, speedTrendAnimationDurationMillis(RefreshRate.HALF_SECOND))
        assertEquals(1_000, speedTrendAnimationDurationMillis(RefreshRate.ONE_SECOND))
        assertEquals(2_000, speedTrendAnimationDurationMillis(RefreshRate.TWO_SECONDS))
    }
}
