package com.ghosts.of.history.data

import com.ghosts.of.history.model.AnchorData
import com.ghosts.of.history.model.AnchorId
import kotlinx.coroutines.flow.StateFlow

data class AnchorsDataState(
    val anchorsData: Map<AnchorId, AnchorData> = emptyMap(),
    val visitedAnchorIds: Set<AnchorId> = setOf(),
)

interface AnchorsDataRepository {
    val state: StateFlow<AnchorsDataState>

    suspend fun load()
    suspend fun addVisited(anchorId: AnchorId)
    suspend fun removeVisited(anchorId: AnchorId)

    suspend fun saveAnchorData(
        anchorId: AnchorId,
        anchorData: AnchorData,
    )

    suspend fun updateAnchorData(anchorId: AnchorId, block: suspend (AnchorData) -> AnchorData)

    suspend fun removeAnchorData(anchor: AnchorData)

    suspend fun removeVideo(anchor: AnchorData)
}


