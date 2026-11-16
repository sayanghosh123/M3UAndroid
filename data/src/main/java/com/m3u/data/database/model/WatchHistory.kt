package com.m3u.data.database.model

import androidx.compose.runtime.Immutable
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "watch_history",
    indices = [
        Index(value = ["stream_url"], unique = true),
        Index(value = ["watched_at"]),
    ]
)
@Immutable
data class WatchHistory(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Int = 0,
    @ColumnInfo(name = "channel_id")
    val channelId: Int,
    @ColumnInfo(name = "playlist_url")
    val playlistUrl: String,
    @ColumnInfo(name = "stream_url")
    val streamUrl: String,
    @ColumnInfo(name = "title")
    val title: String,
    @ColumnInfo(name = "category")
    val category: String = "",
    @ColumnInfo(name = "cover")
    val cover: String? = null,
    @ColumnInfo(name = "kind", defaultValue = "0")
    val kind: Int = StreamKind.LIVE,
    @ColumnInfo(name = "watched_at")
    val watchedAt: Long = 0L,
    @ColumnInfo(name = "position_ms", defaultValue = "-1")
    val positionMs: Long = -1L,
    @ColumnInfo(name = "duration_ms", defaultValue = "-1")
    val durationMs: Long = -1L,
)
