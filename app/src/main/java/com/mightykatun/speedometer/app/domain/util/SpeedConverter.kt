package com.mightykatun.speedometer.app.domain.util

object SpeedConverter {
    fun metersPerSecondToKmh(mps: Float): Float = mps * 3.6f
}
