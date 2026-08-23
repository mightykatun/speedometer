package com.mightykatun.speedometer.app.domain.time

import android.os.SystemClock
import com.mightykatun.speedometer.app.domain.MonotonicClock

class AndroidElapsedRealtimeClock : MonotonicClock {
    override fun elapsedRealtimeMillis(): Long = SystemClock.elapsedRealtime()
}
