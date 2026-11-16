package com.m3u.data.repository.programme

import androidx.paging.PagingData
import com.m3u.data.database.model.Programme
import com.m3u.data.database.model.ProgrammeRange
import com.m3u.data.database.model.ProgrammeSearchResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface ProgrammeRepository {
    fun pagingProgrammes(
        playlistUrl: String,
        relationId: String
    ): Flow<PagingData<Programme>>

    fun searchAll(
        query: String,
        category: String? = null,
    ): Flow<PagingData<ProgrammeSearchResult>>

    fun observeSports(
        windowStart: Long,
        windowEnd: Long,
    ): Flow<List<ProgrammeSearchResult>>

    fun observeSportsMatches(
        now: Long,
        windowStart: Long,
        windowEnd: Long,
        preset: SportPreset,
        includeLiveUnscheduledChannels: Boolean,
        upcomingOnly: Boolean,
    ): Flow<List<CuratedMatch>>

    fun observeProgrammeRange(
        playlistUrl: String,
        relationId: String
    ): Flow<ProgrammeRange>

    fun observeProgrammeRange(
        playlistUrl: String
    ): Flow<ProgrammeRange>

    val refreshingEpgUrls: StateFlow<List<String>>
    fun checkOrRefreshProgrammesOrThrow(
        vararg playlistUrls: String,
        ignoreCache: Boolean
    ): Flow<Int>

    suspend fun getById(id: Int): Programme?
    suspend fun getProgrammeCurrently(channelId: Int): Programme?
    suspend fun getProgrammesCurrently(playlistUrl: String): Map<String, Programme>

    companion object {
        val SPORTS_KEYWORDS = listOf(
            "sport", "football", "soccer", "basketball", "cricket", "tennis",
            "rugby", "nfl", "nba", "mma", "boxing", "motorsport",
        )
    }
}
