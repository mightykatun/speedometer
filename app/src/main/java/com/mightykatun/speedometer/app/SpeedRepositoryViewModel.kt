package com.mightykatun.speedometer.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.mightykatun.speedometer.app.data.repository.SpeedRepository
import com.mightykatun.speedometer.app.data.repository.SpeedRepositoryImpl

class SpeedRepositoryViewModel(application: Application) : AndroidViewModel(application) {
    val repository: SpeedRepository = SpeedRepositoryImpl(application)

    override fun onCleared() {
        repository.close()
    }
}
