package com.m3u.data.repository.channel

import androidx.paging.PagingData
import androidx.paging.PagingSource
import com.m3u.core.foundation.wrapper.Sort
import com.m3u.data.database.dao.WatchHistoryDao
import com.m3u.data.database.model.AdjacentChannels
import com.m3u.data.database.model.Channel
import com.m3u.data.database.model.ImportResult
import com.m3u.data.database.model.WatchHistory
import kotlinx.coroutines.flow.Flow
import kotlin.time.Duration

interface ChannelRepository {
    fun observe(id: Int): Flow<Channel?>

    fun observeAllByPlaylistUrl(playlistUrl: String): Flow<List<Channel>>
    fun observeStreamKindsByPlaylistUrl(playlistUrl: String): Flow<List<Int>>
    fun observeRelationIdsByPlaylistUrl(playlistUrl: String): Flow<List<String>>
    fun pagingAll(query: String): PagingSource<Int, Channel>
    fun pagingAllByPlaylistUrl(
        url: String,
        category: String,
        query: String,
        sort: Sort,
        kind: Int? = null,
    ): PagingSource<Int, Channel>

    suspend fun get(id: Int): Channel?
    fun observeAdjacentChannels(
        channelId: Int,
        playlistUrl: String,
        category: String,
    ): Flow<AdjacentChannels>

    suspend fun getByPlaylistUrl(playlistUrl: String): List<Channel>
    suspend fun getByRelationIds(relationIds: List<String>): List<Channel>
    suspend fun favouriteOrUnfavourite(id: Int)
    suspend fun importFavorites(content: String): ImportResult
    suspend fun hide(id: Int, target: Boolean)
    suspend fun reportPlayed(id: Int)
    suspend fun getPlayedRecently(): Channel?
    fun observePlayedRecently(): Flow<Channel?>
    fun observeWatchHistory(limit: Int = WatchHistoryDao.MAX_WATCH_HISTORY_ENTRIES): Flow<List<WatchHistory>>
    suspend fun updateWatchProgress(
        streamUrl: String,
        positionMs: Long,
        durationMs: Long,
        channelId: Int? = null,
    )
    suspend fun deleteWatchHistory(id: Int)
    suspend fun restoreWatchHistory(entry: WatchHistory)
    suspend fun resolveChannelIdForHistory(entry: WatchHistory): Int?
    fun observeAllUnseenFavorites(limit: Duration): Flow<List<Channel>>
    fun observeAllFavorite(): Flow<List<Channel>>
    fun pagingAllFavorite(sort: Sort): PagingSource<Int, Channel>
    fun observeAllHidden(): Flow<List<Channel>>
    fun search(query: String): PagingSource<Int, Channel>
    fun searchByPlaylist(
        playlistUrl: String,
        query: String,
        hiddenCategories: List<String> = emptyList(),
        kind: Int? = null,
    ): PagingSource<Int, Channel>
}
