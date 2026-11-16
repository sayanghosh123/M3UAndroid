package com.m3u.data.database.model

import androidx.compose.runtime.Immutable
import androidx.room.ColumnInfo
import androidx.room.Embedded

@Immutable
data class SportsProgrammeCandidate(
    @Embedded(prefix = "programme_")
    val programme: Programme,
    @ColumnInfo(name = "channel_database_id")
    val channelId: Int,
    @ColumnInfo(name = "channel_title")
    val channelTitle: String,
    @ColumnInfo(name = "channel_group")
    val channelGroup: String,
    @ColumnInfo(name = "channel_url")
    val channelUrl: String,
    @ColumnInfo(name = "playlist_title")
    val playlistTitle: String,
    @ColumnInfo(name = "playlist_url")
    val playlistUrl: String,
)

@Immutable
data class SportsChannelCandidate(
    @ColumnInfo(name = "channel_id")
    val channelId: Int,
    @ColumnInfo(name = "channel_title")
    val channelTitle: String,
    @ColumnInfo(name = "channel_group")
    val channelGroup: String,
    @ColumnInfo(name = "channel_url")
    val channelUrl: String,
    @ColumnInfo(name = "channel_cover")
    val channelCover: String?,
    @ColumnInfo(name = "relation_id")
    val relationId: String?,
    @ColumnInfo(name = "playlist_title")
    val playlistTitle: String,
    @ColumnInfo(name = "playlist_url")
    val playlistUrl: String,
)
