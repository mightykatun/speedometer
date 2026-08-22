package com.mightykatun.speedometer.app.domain

interface TimeProvider {
    fun currentTimeMillis(): Long
}
