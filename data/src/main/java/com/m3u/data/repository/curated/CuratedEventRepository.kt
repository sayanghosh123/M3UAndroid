package com.m3u.data.repository.curated

import com.m3u.data.database.model.CuratedEventResult
import com.m3u.data.database.model.ImportResult
import kotlinx.coroutines.flow.Flow

interface CuratedEventRepository {
    fun observeInWindow(windowStart: Long, windowEnd: Long): Flow<List<CuratedEventResult>>
    suspend fun importJson(content: String): ImportResult
    suspend fun clear()
}
