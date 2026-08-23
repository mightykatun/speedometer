package com.mightykatun.speedometer.app.domain

interface MonotonicClock {
    fun elapsedRealtimeMillis(): Long
}
