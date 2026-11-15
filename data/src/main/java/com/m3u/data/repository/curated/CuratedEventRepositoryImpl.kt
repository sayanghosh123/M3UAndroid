package com.m3u.data.repository.curated

import com.m3u.data.database.dao.ChannelDao
import com.m3u.data.database.dao.CuratedEventDao
import com.m3u.data.database.model.CuratedEvent
import com.m3u.data.database.model.CuratedEventImport
import com.m3u.data.database.model.CuratedEventResult
import com.m3u.data.database.model.ImportResult
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import javax.inject.Inject

internal class CuratedEventRepositoryImpl @Inject constructor(
    private val curatedEventDao: CuratedEventDao,
    private val channelDao: ChannelDao,
) : CuratedEventRepository {
    private val json = Json { ignoreUnknownKeys = true }

    override fun observeInWindow(
        windowStart: Long,
        windowEnd: Long,
    ): Flow<List<CuratedEventResult>> = curatedEventDao.observeInWindow(windowStart, windowEnd)

    override suspend fun importJson(content: String): ImportResult {
        val entries = json.decodeFromString<List<CuratedEventImport>>(content)
        val unmatched = mutableListOf<String>()
        var imported = 0

        entries.forEach { entry ->
            val identifier = entry.channelId?.trim().takeUnless { it.isNullOrEmpty() }
                ?: entry.channelName?.trim().takeUnless { it.isNullOrEmpty() }
            val channel = identifier?.let { channelDao.findByRelationIdOrTitle(it) }
            if (channel == null || entry.title.isBlank() || entry.endTime <= entry.startTime) {
                unmatched += entry.title.ifBlank { identifier ?: "Unknown event" }
                return@forEach
            }
            curatedEventDao.insertOrReplace(
                CuratedEvent(
                    title = entry.title.trim(),
                    competition = (entry.competition ?: entry.league)?.trim(),
                    category = (entry.category ?: entry.sport)?.trim(),
                    channelId = channel.id,
                    start = entry.startTime,
                    end = entry.endTime,
                )
            )
            imported++
        }
        return ImportResult(imported, entries.size, unmatched)
    }

    override suspend fun clear() = curatedEventDao.deleteAll()
}
