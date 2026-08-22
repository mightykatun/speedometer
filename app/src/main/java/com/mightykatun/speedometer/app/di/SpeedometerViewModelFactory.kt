package com.mightykatun.speedometer.app.di

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.mightykatun.speedometer.app.SpeedometerViewModel
import com.mightykatun.speedometer.app.domain.GpsSignalFilter
import com.mightykatun.speedometer.app.domain.SessionStatisticsTracker
import com.mightykatun.speedometer.app.domain.TimeProvider
import com.mightykatun.speedometer.app.domain.model.SessionConfig
import com.mightykatun.speedometer.app.domain.time.ProductionTimeProvider
import android.os.SystemClock

class SpeedometerViewModelFactory : ViewModelProvider.Factory {
    
    companion object {
        val INSTANCE = SpeedometerViewModelFactory()
    }
    
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(SpeedometerViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return createSpeedometerViewModel() as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
    
    private fun createSpeedometerViewModel(): SpeedometerViewModel {
        val config = SessionConfig()
        val timeProvider = ProductionTimeProvider()
        val sessionTracker = SessionStatisticsTracker(config, timeProvider)
        val gpsSignalFilter = GpsSignalFilter(config)
        
        return SpeedometerViewModel(sessionTracker, gpsSignalFilter)
    }
}
