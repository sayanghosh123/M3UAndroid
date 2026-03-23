package com.m3u.tv.screens.playlist

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.m3u.core.wrapper.Resource
import com.m3u.core.wrapper.mapResource
import com.m3u.core.wrapper.resource
import com.m3u.data.database.model.Channel
import com.m3u.data.database.model.Playlist
import com.m3u.data.database.model.isSeries
import com.m3u.data.parser.xtream.XtreamChannelInfo
import com.m3u.data.repository.channel.ChannelRepository
import com.m3u.data.repository.playlist.PlaylistRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SeriesPresentation(
    val info: XtreamChannelInfo.Info?,
    val episodes: List<XtreamChannelInfo.Episode>,
)

@HiltViewModel
class ChannelDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val channelRepository: ChannelRepository,
    private val playlistRepository: PlaylistRepository,
) : ViewModel() {
    val channel: StateFlow<Channel?> = savedStateHandle
        .getStateFlow(ChannelDetailScreen.ChannelIdBundleKey, -1)
        .flatMapLatest { id -> channelRepository.observe(id) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = null
        )

    val playlist: StateFlow<Playlist?> = channel
        .flatMapLatest { channel ->
            if (channel == null) {
                flowOf(null)
            } else {
                playlistRepository.observe(channel.playlistUrl)
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = null
        )

    private val seriesReplay = MutableStateFlow(0)

    val seriesPresentation: StateFlow<Resource<SeriesPresentation>?> = combine(
        channel,
        playlist,
        seriesReplay
    ) { channel, playlist, _ ->
        channel to playlist
    }
        .flatMapLatest { (channel, playlist) ->
            if (channel == null || playlist?.isSeries != true) {
                flowOf(null)
            } else {
                resource { playlistRepository.readSeriesInfoOrThrow(channel) }
                    .mapResource { info ->
                        val episodes = info.episodes.entries
                            .sortedBy { (season, _) -> season.toIntOrNull() ?: Int.MAX_VALUE }
                            .flatMap { (_, seasonEpisodes) ->
                                seasonEpisodes.sortedBy { episode ->
                                    episode.episodeNum?.toIntOrNull() ?: Int.MAX_VALUE
                                }
                            }
                        SeriesPresentation(
                            info = info.info,
                            episodes = episodes
                        )
                    }
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = null
        )

    fun updateFavorite() {
        val channel = channel.value ?: return
        viewModelScope.launch {
            channelRepository.favouriteOrUnfavourite(channel.id)
        }
    }

    fun retrySeriesPresentation() {
        seriesReplay.value += 1
    }
}
