package com.m3u.data.database.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.m3u.data.database.model.Programme
import com.m3u.data.database.model.ProgrammeRange
import com.m3u.data.database.model.ProgrammeSearchResult
import kotlinx.coroutines.flow.Flow

@Dao
interface ProgrammeDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrReplace(programme: Programme)

    @Query("SELECT * FROM programmes WHERE id = :id")
    suspend fun getById(id: Int): Programme?

    @Query("""SELECT MAX("end") from programmes WHERE epg_url = :epgUrl""")
    suspend fun getMaxEndByEpgUrl(epgUrl: String): Long?

    @Query(
        """
        SELECT * FROM programmes 
        WHERE epg_url = :epgUrl
        AND 
        relation_id = :relationId
        ORDER BY start
        """
    )
    fun pagingProgrammes(
        epgUrl: String?,
        relationId: String
    ): PagingSource<Int, Programme>

    @Query(
        """
        SELECT p.relation_id AS programme_relation_id,
               p.epg_url AS programme_epg_url,
               p.start AS programme_start,
               p.`end` AS programme_end,
               p.title AS programme_title,
               p.description AS programme_description,
               p.icon AS programme_icon,
               p.categories AS programme_categories,
               p.id AS programme_id,
               s.id AS channel_database_id,
               s.title AS channel_title,
               pl.title AS playlist_title,
               s.playlist_url AS playlist_url
        FROM programmes p
        JOIN streams s ON s.relation_id = p.relation_id
        JOIN playlists pl ON pl.url = s.playlist_url
        WHERE (p.title LIKE '%' || :query || '%' COLLATE NOCASE
               OR p.description LIKE '%' || :query || '%' COLLATE NOCASE)
          AND (:category IS NULL
               OR p.categories LIKE '%' || :category || '%' COLLATE NOCASE)
          AND (pl.epg_urls LIKE '%' || p.epg_url || '%' OR pl.source = 'xtream')
        ORDER BY p.start
        """
    )
    fun searchAll(
        query: String,
        category: String?,
    ): PagingSource<Int, ProgrammeSearchResult>

    @Query(
        """
        SELECT p.relation_id AS programme_relation_id,
               p.epg_url AS programme_epg_url,
               p.start AS programme_start,
               p.`end` AS programme_end,
               p.title AS programme_title,
               p.description AS programme_description,
               p.icon AS programme_icon,
               p.categories AS programme_categories,
               p.id AS programme_id,
               s.id AS channel_database_id,
               s.title AS channel_title,
               pl.title AS playlist_title,
               s.playlist_url AS playlist_url
        FROM programmes p
        JOIN streams s ON s.relation_id = p.relation_id
        JOIN playlists pl ON pl.url = s.playlist_url
        WHERE p.start <= :windowEnd AND p.`end` >= :windowStart
          AND (pl.epg_urls LIKE '%' || p.epg_url || '%' OR pl.source = 'xtream')
        ORDER BY p.start
        """
    )
    fun observeAllInWindow(
        windowStart: Long,
        windowEnd: Long,
    ): Flow<List<ProgrammeSearchResult>>

    @Query("DELETE FROM programmes WHERE epg_url = :epgUrl")
    suspend fun cleanByEpgUrl(epgUrl: String)

    @Query("SELECT * FROM programmes ORDER BY start")
    fun observeAll(): Flow<List<Programme>>

    @Query("SELECT id IS NOT NULL FROM programmes WHERE epg_url = :epgUrl LIMIT 1")
    fun observeContainsEpgUrl(epgUrl: String): Flow<Boolean>

    @Query("""
        SELECT id IS NOT NULL 
        FROM programmes 
        WHERE epg_url = :epgUrl 
        AND relation_id = :relationId
        AND start >= :start
        AND `end` <= :end
        LIMIT 1
    """)
    suspend fun checkEpgUrlIsValid(
        epgUrl: String,
        relationId: String,
        start: Long,
        end: Long,
    ): Boolean

    @Query("DELETE FROM programmes WHERE epg_url = :epgUrl")
    suspend fun deleteAllByEpgUrl(epgUrl: String)

    @Query(
        """
        SELECT * FROM programmes 
        WHERE epg_url in (:epgUrls) 
        AND relation_id = :relationId
        AND start <= :time
        AND `end` >= :time
        """
    )
    suspend fun getCurrentByEpgUrlsAndRelationId(
        epgUrls: List<String>,
        relationId: String,
        time: Long
    ): Programme?

    @Query(
        """
        SELECT MIN(start) AS start_edge, MAX(`end`) AS end_edge
        FROM programmes
        WHERE epg_url = :epgUrl
        AND relation_id = :relationId
        """
    )
    fun observeProgrammeRange(
        epgUrl: String,
        relationId: String
    ): Flow<ProgrammeRange>

    @Query(
        """
        SELECT MIN(start) AS start_edge, MAX(`end`) AS end_edge
        FROM programmes
        WHERE epg_url in (:epgUrls)
        """
    )
    fun observeProgrammeRange(
        epgUrls: List<String>
    ): Flow<ProgrammeRange>
}