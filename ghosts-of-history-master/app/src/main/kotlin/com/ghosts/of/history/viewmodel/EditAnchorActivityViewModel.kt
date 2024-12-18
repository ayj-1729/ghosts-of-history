package com.ghosts.of.history.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.ghosts.of.history.data.AnchorsDataRepository
import com.ghosts.of.history.model.AnchorData
import com.ghosts.of.history.view.App

class EditAnchorActivityViewModel constructor(
    private val anchorsDataRepository: AnchorsDataRepository
) : ViewModel() {

    val anchorsDataState = anchorsDataRepository.state

    suspend fun saveAnchorData(data: AnchorData) {
        anchorsDataRepository.saveAnchorData(data.anchorId, data)
    }

    suspend fun removeAnchorData(anchorData: AnchorData, removeVideo: Boolean) {
        anchorsDataRepository.removeAnchorData(anchorData)
        if (removeVideo) {
            removeVideo(anchorData)
        }
    }

    suspend fun removeVideo(anchorData: AnchorData) {
        anchorsDataRepository.removeVideo(anchorData)
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val anchorsDataRepository =
                        (this[APPLICATION_KEY] as App).anchorsDataRepository
                EditAnchorActivityViewModel(
                        anchorsDataRepository
                )
            }
        }
    }

}
