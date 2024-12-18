package com.ghosts.of.history.view

import android.app.Application
import com.ghosts.of.history.data.AnchorsDataRepository
import com.ghosts.of.history.dataimpl.AnchorsDataRepositoryImpl
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

class App : Application() {
    lateinit var anchorsDataRepository: AnchorsDataRepository
        private set

    private val applicationScope = MainScope()

    override fun onCreate() {
        super.onCreate()

        anchorsDataRepository = AnchorsDataRepositoryImpl(applicationContext)
        applicationScope.launch {
            anchorsDataRepository.load()
        }
    }
}