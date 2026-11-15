package com.m3u.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.m3u.data.database.model.CuratedEvent
import com.m3u.data.database.model.CuratedEventResult
import kotlinx.coroutines.flow.Flow

@Dao
interface CuratedEventDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrReplace(event: CuratedEvent)

    @Query(
        """
        SELECT e.*, s.title AS channel_title, p.title AS playlist_title,
               s.playlist_url AS playlist_url
        FROM curated_events e
        JOIN streams s ON s.id = e.channel_id
        JOIN playlists p ON p.url = s.playlist_url
        WHERE e.start <= :windowEnd AND e.`end` >= :windowStart
        ORDER BY e.start
        """
    )
    fun observeInWindow(windowStart: Long, windowEnd: Long): Flow<List<CuratedEventResult>>

    @Query("DELETE FROM curated_events")
    suspend fun deleteAll()
}
