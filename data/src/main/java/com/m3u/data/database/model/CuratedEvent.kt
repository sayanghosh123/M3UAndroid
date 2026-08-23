package com.m3u.data.database.model

import androidx.compose.runtime.Immutable
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Entity(
    tableName = "curated_events",
    indices = [
        Index(value = ["channel_id"]),
        Index(value = ["channel_id", "start", "end", "title"], unique = true),
    ],
)
@Immutable
data class CuratedEvent(
    @ColumnInfo(name = "title")
    val title: String,
    @ColumnInfo(name = "competition")
    val competition: String? = null,
    @ColumnInfo(name = "category")
    val category: String? = null,
    @ColumnInfo(name = "channel_id")
    val channelId: Int,
    @ColumnInfo(name = "start")
    val start: Long,
    @ColumnInfo(name = "end")
    val end: Long,
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Int = 0,
)

@Immutable
data class CuratedEventResult(
    @Embedded
    val event: CuratedEvent,
    @ColumnInfo(name = "channel_title")
    val channelTitle: String,
    @ColumnInfo(name = "playlist_title")
    val playlistTitle: String,
    @ColumnInfo(name = "playlist_url")
    val playlistUrl: String,
)

@Serializable
data class CuratedEventImport(
    val title: String,
    val competition: String? = null,
    val league: String? = null,
    @SerialName("channelId")
    val channelId: String? = null,
    @SerialName("channelName")
    val channelName: String? = null,
    val startTime: Long,
    val endTime: Long,
    val category: String? = null,
    val sport: String? = null,
)

data class ImportResult(
    val imported: Int,
    val total: Int,
    val unmatched: List<String>,
)
