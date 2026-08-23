package com.m3u.data.database.model

import androidx.compose.runtime.Immutable
import androidx.room.ColumnInfo
import androidx.room.Embedded

@Immutable
data class ProgrammeSearchResult(
    @Embedded(prefix = "programme_")
    val programme: Programme,
    @ColumnInfo(name = "channel_database_id")
    val channelId: Int,
    @ColumnInfo(name = "channel_title")
    val channelTitle: String,
    @ColumnInfo(name = "playlist_title")
    val playlistTitle: String,
    @ColumnInfo(name = "playlist_url")
    val playlistUrl: String,
)
