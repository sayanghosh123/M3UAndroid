package com.m3u.data.service

import android.graphics.Rect
import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import com.m3u.data.database.model.Channel
import com.m3u.data.database.model.Playlist
import com.m3u.data.parser.xtream.XtreamEpisodeInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest

/**
 * Thread-safe snapshot of active Media3 player metrics updated on the player's looper.
 */
@Immutable
data class PlayerTelemetrySnapshot(
    val streamFormat: String? = null,
    val videoCodec: String? = null,
    val audioCodec: String? = null,
    val resolution: String? = null,
    val httpResponseCode: Int? = null,
    val playbackState: Int = Player.STATE_IDLE,
    val isPlaying: Boolean = false,
    val bufferedPositionMs: Long = -1L,
    val currentPositionMs: Long = -1L,
    val totalBufferedDurationMs: Long = -1L,
    val lastErrorCode: Int? = null,
    val lastErrorName: String? = null,
    val updatedAtEpochMs: Long = 0L
)

@Immutable
data class ExternalPlaybackTarget(
    val title: String,
    val url: String,
    val mimeType: String,
    val userAgent: String? = null,
    val referer: String? = null,
    val headers: Map<String, String> = emptyMap(),
) {
    /**
     * Converts merged HTTP headers into an alternating `[key, value, ...]` array recognized by VLC
     * and MX Player via the `headers` Intent extra.
     */
    fun toIntentHeadersArray(): Array<String> = buildHeadersArray(
        userAgent = userAgent,
        referer = referer,
        headers = headers,
    )

    companion object {
        fun buildHeadersArray(
            userAgent: String?,
            referer: String?,
            headers: Map<String, String>,
        ): Array<String> {
            val ordered = linkedMapOf<String, String>()
            userAgent?.takeIf { it.isNotBlank() }?.let { ordered["User-Agent"] = it }
            referer?.takeIf { it.isNotBlank() }?.let { ordered["Referer"] = it }
            headers.forEach { (rawKey, value) ->
                val key = rawKey.trim()
                if (key.isEmpty() || value.isBlank()) return@forEach
                val existingKey = ordered.keys.firstOrNull { it.equals(key, ignoreCase = true) }
                if (existingKey == null) {
                    ordered[key] = value
                }
            }
            return ordered.entries
                .flatMap { (k, v) -> listOf(k, v) }
                .toTypedArray()
        }
    }
}

interface PlayerManager {
    val player: StateFlow<Player?>
    val size: StateFlow<Rect>

    val channel: StateFlow<Channel?>
    val playlist: StateFlow<Playlist?>

    val playbackState: StateFlow<@Player.State Int>
    val playbackException: StateFlow<PlaybackException?>
    val isPlaying: StateFlow<Boolean>
    val telemetrySnapshot: StateFlow<PlayerTelemetrySnapshot>

    val tracksGroups: StateFlow<List<Tracks.Group>>
    val cacheSpace: Flow<Long>

    fun chooseTrack(group: TrackGroup, index: Int)
    fun clearTrack(type: @C.TrackType Int)
    suspend fun play(
        command: MediaCommand,
        applyContinueWatching: Boolean = true
    )
    suspend fun resolveExternalPlaybackTarget(command: MediaCommand): ExternalPlaybackTarget?
    suspend fun replay()
    fun release()
    fun clearCache()
    fun pauseOrContinue(value: Boolean)
    fun updateSpeed(race: Float)

    suspend fun recordVideo(uri: Uri)

    val cwPosition: SharedFlow<Long>
    suspend fun onResetPlayback(channelUrl: String)
    suspend fun getCwPosition(channelUrl: String): Long
    suspend fun reloadThumbnail(channelUrl: String): Uri?
    suspend fun syncThumbnail(channelUrl: String): Uri?
}

@Immutable
data class PlayerTrack(
    val type: @C.TrackType Int,
    val group: TrackGroup,
    val index: Int,
    val format: Format
)

@Immutable
sealed class MediaCommand(open val channelId: Int) {
    data class Common(override val channelId: Int) : MediaCommand(channelId)
    data class XtreamEpisode(
        override val channelId: Int,
        val episode: XtreamEpisodeInfo
    ) : MediaCommand(channelId)
}

val PlayerManager.tracks: Flow<Map<@C.TrackType Int, List<PlayerTrack>>>
    get() = tracksGroups.mapLatest { all ->
        // Group all tracks by their type
        all.groupBy { it.type }
            .mapValues { (_, innerGroups) ->
                // For each group, flatten the list of tracks and filter out unsupported tracks
                innerGroups.flatMap { trackGroup ->
                    List(trackGroup.length) { index ->
                        trackGroup.takeIf { it.isTrackSupported(index) }?.let {
                            PlayerTrack(
                                type = it.type,
                                group = it.mediaTrackGroup,
                                index = index,
                                format = it.getTrackFormat(index)
                            )
                        }
                    }.filterNotNull()
                }
            }
    }.flowOn(Dispatchers.IO)

val PlayerManager.currentTracks: Flow<Map<@C.TrackType Int, PlayerTrack?>>
    get() = tracksGroups.mapLatest { currentTracksGroups ->
        currentTracksGroups
            .groupBy { it.type }
            .mapValues { (_, tracksGroups) ->
                tracksGroups
                    .asSequence()
                    .mapNotNull { trackGroup ->
                        (0 until trackGroup.length)
                            .firstOrNull {
                                trackGroup.isTrackSupported(it) &&
                                        trackGroup.isTrackSelected(it)
                            }
                            ?.let {
                                PlayerTrack(
                                    type = trackGroup.type,
                                    group = trackGroup.mediaTrackGroup,
                                    index = it,
                                    format = trackGroup.getTrackFormat(it)
                                )
                            }
                    }
                    .firstOrNull()
            }
    }.flowOn(Dispatchers.IO)