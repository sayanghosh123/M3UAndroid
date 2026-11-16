package com.m3u.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.m3u.data.database.model.WatchHistory
import kotlinx.coroutines.flow.Flow

@Dao
interface WatchHistoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrReplace(entry: WatchHistory): Long

    @Query("SELECT * FROM watch_history WHERE stream_url = :streamUrl LIMIT 1")
    suspend fun getByStreamUrl(streamUrl: String): WatchHistory?

    @Query(
        """
        UPDATE watch_history
        SET channel_id = :channelId,
            playlist_url = :playlistUrl,
            title = :title,
            category = :category,
            cover = :cover,
            kind = :kind,
            watched_at = :watchedAt
        WHERE id = :id
        """
    )
    suspend fun updateMetadata(
        id: Int,
        channelId: Int,
        playlistUrl: String,
        title: String,
        category: String,
        cover: String?,
        kind: Int,
        watchedAt: Long,
    ): Int

    @Transaction
    suspend fun recordWatch(
        entry: WatchHistory,
        maxEntries: Int = MAX_WATCH_HISTORY_ENTRIES,
    ) {
        val existing = getByStreamUrl(entry.streamUrl)
        if (existing != null) {
            updateMetadata(
                id = existing.id,
                channelId = entry.channelId,
                playlistUrl = entry.playlistUrl,
                title = entry.title,
                category = entry.category,
                cover = entry.cover,
                kind = entry.kind,
                watchedAt = entry.watchedAt,
            )
        } else {
            insertOrReplace(entry)
        }
        trimToLimit(maxEntries)
    }

    @Query("SELECT * FROM watch_history ORDER BY watched_at DESC LIMIT :limit")
    fun observeRecent(limit: Int = MAX_WATCH_HISTORY_ENTRIES): Flow<List<WatchHistory>>

    @Query(
        """
        UPDATE watch_history
        SET position_ms = :positionMs,
            duration_ms = :durationMs,
            watched_at = :watchedAt
        WHERE stream_url = :streamUrl
        """
    )
    suspend fun updateProgress(
        streamUrl: String,
        positionMs: Long,
        durationMs: Long,
        watchedAt: Long,
    ): Int

    @Query("DELETE FROM watch_history WHERE id = :id")
    suspend fun deleteById(id: Int): Int

    @Query(
        """
        DELETE FROM watch_history
        WHERE id NOT IN (
            SELECT id FROM watch_history
            ORDER BY watched_at DESC
            LIMIT :limit
        )
        """
    )
    suspend fun trimToLimit(limit: Int = MAX_WATCH_HISTORY_ENTRIES): Int

    companion object {
        const val MAX_WATCH_HISTORY_ENTRIES = 50
    }
}
