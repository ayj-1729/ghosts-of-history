package com.ghosts.of.history.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.ghosts.of.history.data.AnchorsDataRepository
import com.ghosts.of.history.model.AnchorId
import com.ghosts.of.history.view.App

class AnchorListActivityViewModel constructor(
        private val anchorsDataRepository: AnchorsDataRepository
) : ViewModel() {
    suspend fun setEnabled(anchorId: AnchorId, enabled: Boolean) {
        anchorsDataRepository.updateAnchorData(anchorId) {
            it.copy(isEnabled=enabled)
        }
    }
    val anchorsDataState = anchorsDataRepository.state

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val anchorsDataRepository =
                        (this[APPLICATION_KEY] as App).anchorsDataRepository
                AnchorListActivityViewModel(
                        anchorsDataRepository
                )
            }
        }
    }
}
