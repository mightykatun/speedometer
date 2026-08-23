package com.mightykatun.speedometer.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.mightykatun.speedometer.app.data.repository.SpeedRepositoryImpl

class SpeedRepositoryViewModel(application: Application) : AndroidViewModel(application) {
    val repository = SpeedRepositoryImpl(application)

    override fun onCleared() {
        repository.close()
    }
}
