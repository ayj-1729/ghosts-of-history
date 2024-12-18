package com.ghosts.of.history.dataimpl

import com.ghosts.of.history.model.AnchorData
import com.ghosts.of.history.utils.getAnchorsDataFromFirebase
import com.ghosts.of.history.utils.removeAnchorDataFromFirebase
import com.ghosts.of.history.utils.removeVideoFromStorage
import com.ghosts.of.history.utils.saveAnchorSetToFirebase

class AnchorsDataDataSource {
    suspend fun save(anchorData: AnchorData) =
        saveAnchorSetToFirebase(anchorData)

    suspend fun load() =
        getAnchorsDataFromFirebase()

    suspend fun remove(anchorData: AnchorData) =
        removeAnchorDataFromFirebase(anchorData)

    suspend fun removeVideo(anchorData: AnchorData) =
        removeVideoFromStorage(anchorData)
}