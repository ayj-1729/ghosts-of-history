package com.ghosts.of.history.dataimpl

import android.content.Context
import com.ghosts.of.history.data.AnchorsDataRepository
import com.ghosts.of.history.data.AnchorsDataState
import com.ghosts.of.history.model.AnchorData
import com.ghosts.of.history.model.AnchorId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class AnchorsDataRepositoryImpl private constructor(
    private val anchorsDataDataSource: AnchorsDataDataSource,
    private val visitedAnchorIdsDataSource: VisitedAnchorIdsDataSource,
) : AnchorsDataRepository {

    private val _anchorsData = MutableStateFlow(AnchorsDataState())

    override val state = _anchorsData.asStateFlow()

    override suspend fun load() {
        val initialAnchorsData = anchorsDataDataSource.load()
        val initialVisitedAnchorIds = visitedAnchorIdsDataSource.load()

        _anchorsData.value =
            AnchorsDataState(
                anchorsData = initialAnchorsData
                    .associateBy { it.anchorId },
                visitedAnchorIds = initialVisitedAnchorIds
            )
    }

    override suspend fun removeAnchorData(anchor: AnchorData) {
        _anchorsData.update { state ->
            val newAnchorsData = state.anchorsData.toMutableMap().apply { remove(anchor.anchorId) }
            anchorsDataDataSource.remove(anchor)
            state.copy(anchorsData = newAnchorsData)
        }
    }

    override suspend fun removeVideo(anchor: AnchorData) {
        anchorsDataDataSource.removeVideo(anchor)
    }

    override suspend fun addVisited(anchorId: AnchorId) {
        _anchorsData.update { state ->
            val newState = state.copy(visitedAnchorIds = state.visitedAnchorIds + anchorId)
            visitedAnchorIdsDataSource.save(visitedAnchorIds = newState.visitedAnchorIds)
            newState
        }
    }

    override suspend fun removeVisited(anchorId: AnchorId) {
        _anchorsData.update { state ->
            val newState = state.copy(visitedAnchorIds = state.visitedAnchorIds - anchorId)
            visitedAnchorIdsDataSource.save(visitedAnchorIds = newState.visitedAnchorIds)
            newState
        }
    }

    override suspend fun updateAnchorData(anchorId: AnchorId, block: suspend (AnchorData) -> AnchorData) {
        _anchorsData.update { state ->
            val anchorData = state.anchorsData[anchorId] ?: return@update state
            val newAnchorData = block(anchorData)
            val newAnchorsData = state.anchorsData + Pair(anchorId, newAnchorData)
            anchorsDataDataSource.save(newAnchorData)
            state.copy(anchorsData = newAnchorsData)
        }
    }

    override suspend fun saveAnchorData(
        anchorId: AnchorId,
        anchorData: AnchorData,
    ) {
        _anchorsData.update { state ->
            val newAnchorsData = state.anchorsData + Pair(anchorId, anchorData)
            anchorsDataDataSource.save(anchorData)
            state.copy(anchorsData = newAnchorsData)
        }
    }

    companion object {
        operator fun invoke(context: Context): AnchorsDataRepository =
            AnchorsDataRepositoryImpl(
                AnchorsDataDataSource(),
                VisitedAnchorIdsDataSource(context),
            )
    }
}