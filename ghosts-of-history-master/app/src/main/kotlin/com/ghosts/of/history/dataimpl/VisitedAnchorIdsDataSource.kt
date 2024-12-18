package com.ghosts.of.history.dataimpl

import android.content.Context
import com.google.common.reflect.TypeToken
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

import com.ghosts.of.history.model.AnchorId

class VisitedAnchorIdsDataSource(private val context: Context) {
    suspend fun load(): Set<String> =
        withContext(Dispatchers.IO) {
            val file = File(context.filesDir, FILE_NAME)
            if (file.exists()) {
                val jsonString = file.readText()
                val type = object : TypeToken<HashSet<String>>() {}.type
                Gson().fromJson<Set<String>>(jsonString, type)
            } else {
                HashSet()
            }
        }

    suspend fun save(visitedAnchorIds: Set<AnchorId>) {
        withContext(Dispatchers.IO) {
            val jsonString = Gson().toJson(visitedAnchorIds)
            val file = File(context.filesDir, FILE_NAME)
            file.writeText(jsonString)
        }
    }

    companion object {
        private const val FILE_NAME = "markers.json"
    }
}