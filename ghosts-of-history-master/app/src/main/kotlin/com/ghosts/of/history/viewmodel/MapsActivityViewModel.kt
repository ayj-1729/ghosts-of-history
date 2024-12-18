package com.ghosts.of.history.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.ghosts.of.history.data.*
import com.ghosts.of.history.view.App

class MapsActivityViewModel constructor(
    anchorsDataRepository: AnchorsDataRepository
) : ViewModel() {
    val anchorsDataState = anchorsDataRepository.state

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val anchorsDataRepository =
                    (this[APPLICATION_KEY] as App).anchorsDataRepository
                MapsActivityViewModel(
                    anchorsDataRepository
                )
            }
        }
    }

}