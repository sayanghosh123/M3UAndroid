package com.m3u.data.repository.channel

import androidx.paging.PagingSource
import com.m3u.core.foundation.architecture.preferences.Settings
import com.m3u.core.foundation.wrapper.Sort
import com.m3u.data.database.dao.ChannelDao
import com.m3u.data.database.dao.PlaylistDao
import com.m3u.data.database.dao.WatchHistoryDao
import com.m3u.data.database.model.AdjacentChannels
import com.m3u.data.database.model.Channel
import com.m3u.data.database.model.ImportResult
import com.m3u.data.database.model.WatchHistory
import com.m3u.data.repository.playlist.PlaylistDataMaintenanceCoordinator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import javax.inject.Inject
import kotlin.time.Clock
import kotlin.time.Duration

internal class ChannelRepositoryImpl @Inject constructor(
    private val channelDao: ChannelDao,
    private val playlistDao: PlaylistDao,
    private val watchHistoryDao: WatchHistoryDao,
    private val settings: Settings,
) : ChannelRepository {
    private companion object {
        const val HIDDEN_CATEGORY_SENTINEL = "__m3u_hidden_category_sentinel__"
    }

    override fun observe(id: Int): Flow<Channel?> = channelDao
        .observeById(id)
        .catch { emit(null) }

    override fun observeAllByPlaylistUrl(playlistUrl: String): Flow<List<Channel>> = channelDao
        .observeAllByPlaylistUrl(playlistUrl)
        .catch { emit(emptyList()) }

    override fun observeStreamKindsByPlaylistUrl(playlistUrl: String): Flow<List<Int>> = channelDao
        .observeDistinctKindsByPlaylistUrl(playlistUrl)
        .catch { emit(emptyList()) }

    override fun observeRelationIdsByPlaylistUrl(playlistUrl: String): Flow<List<String>> = channelDao
        .observeRelationIdsByPlaylistUrl(playlistUrl)
        .catch { emit(emptyList()) }

    override fun pagingAll(query: String): PagingSource<Int, Channel> {
        return channelDao.pagingAll(query)
    }

    override fun pagingAllByPlaylistUrl(
        url: String,
        category: String,
        query: String,
        sort: Sort,
        kind: Int?,
    ): PagingSource<Int, Channel> = when (sort) {
        Sort.UNSPECIFIED -> channelDao.pagingAllByPlaylistUrl(url, category, query, kind)
        Sort.ASC -> channelDao.pagingAllByPlaylistUrlAsc(url, category, query, kind)
        Sort.DESC -> channelDao.pagingAllByPlaylistUrlDesc(url, category, query, kind)
        Sort.RECENTLY -> channelDao.pagingAllByPlaylistUrlRecently(url, category, query, kind)
        Sort.MIXED -> channelDao.pagingAllByPlaylistUrlMixed(url, query, kind)
    }

    override suspend fun get(id: Int): Channel? = channelDao.get(id)

    override fun observeAdjacentChannels(
        channelId: Int,
        playlistUrl: String,
        category: String,
    ): Flow<AdjacentChannels> = channelDao.observeAdjacentChannels(
        channelId = channelId,
        playlistUrl = playlistUrl,
        category = category
    )

    override suspend fun getByPlaylistUrl(playlistUrl: String): List<Channel> = channelDao.getByPlaylistUrl(playlistUrl)

    override suspend fun getByRelationIds(relationIds: List<String>): List<Channel> =
        if (relationIds.isEmpty()) emptyList() else channelDao.getByRelationIds(relationIds)

    override suspend fun favouriteOrUnfavourite(id: Int) {
        val selected = channelDao.get(id) ?: return
        PlaylistDataMaintenanceCoordinator.withExclusive {
            val current = resolveCurrentVersion(selected) ?: return@withExclusive
            channelDao.favouriteOrUnfavourite(current.id, !current.favourite)
        }
    }

    override suspend fun importFavorites(content: String): ImportResult {
        val identifiers = parseFavoriteIdentifiers(content)
        val matched = identifiers.mapNotNull { identifier ->
            channelDao.findByRelationIdOrTitle(identifier)
        }.distinctBy(Channel::id)
        if (matched.isNotEmpty()) {
            PlaylistDataMaintenanceCoordinator.withExclusive {
                channelDao.favouriteAll(matched.map(Channel::id))
            }
        }
        val matchedKeys = matched.flatMap { listOfNotNull(it.relationId, it.title) }
            .map { it.lowercase() }
            .toSet()
        val unmatched = identifiers.filterNot { it.lowercase() in matchedKeys }
        return ImportResult(matched.size, identifiers.size, unmatched)
    }

    override suspend fun hide(id: Int, target: Boolean) {
        val selected = channelDao.get(id) ?: return
        PlaylistDataMaintenanceCoordinator.withExclusive {
            val current = resolveCurrentVersion(selected) ?: return@withExclusive
            channelDao.hide(current.id, target)
        }
    }

    private fun parseFavoriteIdentifiers(content: String): List<String> {
        val trimmed = content.trim()
        val values = if (trimmed.startsWith("[")) {
            val array = Json.parseToJsonElement(trimmed) as? JsonArray
                ?: throw IllegalArgumentException("Favorites JSON must be an array")
            array.mapNotNull { (it as? JsonPrimitive)?.content }
        } else {
            trimmed.lineSequence().toList()
        }
        return values.map(String::trim).filter(String::isNotEmpty).distinct()
    }

    override suspend fun reportPlayed(id: Int) {
        val selected = channelDao.get(id) ?: return
        PlaylistDataMaintenanceCoordinator.withExclusive {
            val currentVersion = resolveCurrentVersion(selected) ?: return@withExclusive
            val current = kotlin.time.Clock.System.now().toEpochMilliseconds()
            channelDao.updateSeen(currentVersion.id, current)
            watchHistoryDao.recordWatch(
                WatchHistory(
                    channelId = currentVersion.id,
                    playlistUrl = currentVersion.playlistUrl,
                    streamUrl = currentVersion.url,
                    title = currentVersion.title,
                    category = currentVersion.category,
                    cover = currentVersion.cover,
                    kind = currentVersion.kind,
                    watchedAt = current,
                )
            )
        }
    }

    private suspend fun resolveCurrentVersion(selected: Channel): Channel? {
        val relationId = selected.relationId?.takeIf(String::isNotBlank)
        val sameId = channelDao.get(selected.id)
        val sameStableIdentity = if (relationId != null) {
            sameId?.relationId == relationId
        } else {
            sameId?.url == selected.url
        }
        if (
            sameId?.playlistUrl == selected.playlistUrl &&
            sameStableIdentity
        ) {
            return sameId
        }
        val sameUrl = channelDao.getByPlaylistUrlAndUrl(
            playlistUrl = selected.playlistUrl,
            url = selected.url,
        )
        if (
            sameUrl != null &&
            (relationId == null || sameUrl.relationId == relationId)
        ) {
            return sameUrl
        }
        return relationId?.let { stableId ->
            channelDao.getByPlaylistUrlAndRelationId(
                playlistUrl = selected.playlistUrl,
                relationId = stableId,
            )
        }
    }

    override suspend fun getPlayedRecently(): Channel? {
        return channelDao.getPlayedRecently()
    }

    override fun observePlayedRecently(): Flow<Channel?> = channelDao.observePlayedRecently()

    override fun observeWatchHistory(limit: Int): Flow<List<WatchHistory>> =
        watchHistoryDao.observeRecent(limit).catch { emit(emptyList()) }

    override suspend fun updateWatchProgress(
        streamUrl: String,
        positionMs: Long,
        durationMs: Long,
        channelId: Int?,
    ) {
        if (streamUrl.isBlank()) return
        val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
        val updated = watchHistoryDao.updateProgress(
            streamUrl = streamUrl,
            positionMs = positionMs,
            durationMs = durationMs,
            watchedAt = now,
        )
        if (updated == 0 && channelId != null) {
            val parentUrl = channelDao.get(channelId)?.url
            if (!parentUrl.isNullOrBlank() && parentUrl != streamUrl) {
                watchHistoryDao.updateProgress(
                    streamUrl = parentUrl,
                    positionMs = positionMs,
                    durationMs = durationMs,
                    watchedAt = now,
                )
            }
        }
    }

    override suspend fun deleteWatchHistory(id: Int) {
        watchHistoryDao.deleteById(id)
    }

    override suspend fun restoreWatchHistory(entry: WatchHistory) {
        watchHistoryDao.insertOrReplace(entry)
    }

    override suspend fun resolveChannelIdForHistory(entry: WatchHistory): Int? {
        val existingById = channelDao.get(entry.channelId)
        if (existingById != null && existingById.url == entry.streamUrl) {
            return existingById.id
        }
        return channelDao.getByPlaylistUrlAndUrl(entry.playlistUrl, entry.streamUrl)?.id
            ?: existingById?.id
    }

    override fun observeAllUnseenFavorites(limit: Duration): Flow<List<Channel>> =
        channelDao.observeAllUnseenFavorites(
            limit = limit.inWholeMilliseconds,
            current = Clock.System.now().toEpochMilliseconds()
        )
            .catch { emit(emptyList()) }

    override fun observeAllFavorite(): Flow<List<Channel>> = channelDao.observeAllFavorite()
        .catch { emit(emptyList()) }

    override fun pagingAllFavorite(sort: Sort): PagingSource<Int, Channel> {
        return when (sort) {
            Sort.ASC -> channelDao.pagingAllFavoriteAsc()
            Sort.DESC -> channelDao.pagingAllFavoriteDesc()
            Sort.RECENTLY -> channelDao.pagingAllFavoriteRecently()
            else -> channelDao.pagingAllFavorite()
        }
    }

    override fun observeAllHidden(): Flow<List<Channel>> = channelDao.observeAllHidden()
        .catch { emit(emptyList()) }

    override fun search(query: String): PagingSource<Int, Channel> {
        val normalizedQuery = query.trim()
        return channelDao.query(
            rawQuery = normalizedQuery,
            escapedQuery = normalizedQuery.escapeForSqlLike()
        )
    }

    override fun searchByPlaylist(
        playlistUrl: String,
        query: String,
        hiddenCategories: List<String>,
        kind: Int?,
    ): PagingSource<Int, Channel> {
        val normalizedQuery = query.trim()
        return channelDao.queryByPlaylist(
            playlistUrl = playlistUrl,
            rawQuery = normalizedQuery,
            escapedQuery = normalizedQuery.escapeForSqlLike(),
            hiddenCategories = hiddenCategories.ifEmpty { listOf(HIDDEN_CATEGORY_SENTINEL) },
            kind = kind,
        )
    }
}

private fun String.escapeForSqlLike(): String = buildString(length) {
    this@escapeForSqlLike.forEach { character ->
        when (character) {
            '\\' -> append("\\\\")
            '%', '_' -> {
                append('\\')
                append(character)
            }

            else -> append(character)
        }
    }
}
