package com.m3u.business.playlist

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.map as pagingMap
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkQuery
import com.m3u.business.playlist.PlaylistMessage.ChannelCoverSaved
import com.m3u.core.foundation.Contracts
import com.m3u.core.foundation.architecture.preferences.PreferencesKeys
import com.m3u.core.foundation.architecture.preferences.Settings
import com.m3u.core.foundation.architecture.preferences.flowOf
import com.m3u.core.foundation.util.coroutine.flatmapCombined
import com.m3u.core.foundation.wrapper.Event
import com.m3u.core.foundation.wrapper.Resource
import com.m3u.core.foundation.wrapper.Sort
import com.m3u.core.foundation.wrapper.handledEvent
import com.m3u.core.foundation.wrapper.mapResource
import com.m3u.core.foundation.wrapper.resource
import com.m3u.data.database.model.Channel
import com.m3u.data.database.model.DataSource
import com.m3u.data.database.model.Playlist
import com.m3u.data.database.model.Programme
import com.m3u.data.database.model.StreamKind
import com.m3u.data.database.model.isSeries
import com.m3u.data.database.model.type
import com.m3u.data.parser.xtream.XtreamEpisodeInfo
import com.m3u.data.parser.xtream.XtreamInput
import com.m3u.data.repository.channel.ChannelRepository
import com.m3u.data.repository.media.MediaRepository
import com.m3u.data.repository.playlist.PlaylistRepository
import com.m3u.data.repository.playlist.PlaylistRefreshReason
import com.m3u.data.repository.programme.ProgrammeRepository
import com.m3u.data.service.MediaCommand
import com.m3u.data.service.Messager
import com.m3u.data.service.PlayerManager
import com.m3u.data.worker.SubscriptionWorker
import com.m3u.data.worker.playlistWorkTag
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private const val GLOBAL_SEARCH_KEY = "__global_search__"

@Immutable
data class ChannelWithProgramme(
    val channel: Channel,
    val programme: Programme?,
)

@Immutable
data class PlaylistMediaSection(
    val kind: Int?,
    val selected: Boolean,
)

private data class PlaylistUrlOverride(
    val forRouteUrl: String,
    val selectedUrl: String,
)

@HiltViewModel
class PlaylistViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val channelRepository: ChannelRepository,
    private val playlistRepository: PlaylistRepository,
    private val mediaRepository: MediaRepository,
    private val programmeRepository: ProgrammeRepository,
    private val messager: Messager,
    private val playerManager: PlayerManager,
    settings: Settings,
    workManager: WorkManager,
) : ViewModel() {
    private val timber = Timber.tag("PlaylistViewModel")

    private val routePlaylistUrl: StateFlow<String> = savedStateHandle
        .getStateFlow(PlaylistNavigation.TYPE_URL, "")

    private val playlistUrlOverride = MutableStateFlow<PlaylistUrlOverride?>(null)

    val playlistUrl: StateFlow<String> = combine(
        routePlaylistUrl,
        playlistUrlOverride,
    ) { routeUrl, override ->
        if (override != null && override.forRouteUrl == routeUrl) {
            override.selectedUrl
        } else {
            routeUrl
        }
    }
        .stateIn(
            scope = viewModelScope,
            initialValue = routePlaylistUrl.value,
            started = SharingStarted.WhileSubscribed(5_000L)
        )

    private val selectedStreamKind = MutableStateFlow<Int?>(null)

    val playlist: StateFlow<Playlist?> = playlistUrl.flatMapLatest {
        playlistRepository.observe(it)
    }
        .stateIn(
            scope = viewModelScope,
            initialValue = null,
            started = SharingStarted.WhileSubscribed(5_000L)
        )

    val zapping: StateFlow<Channel?> = combine(
        settings.flowOf(PreferencesKeys.ZAPPING_MODE),
        playerManager.channel,
        playlistUrl.flatMapLatest { channelRepository.observeAllByPlaylistUrl(it) }
    ) { zappingMode, channel, channels ->
        if (!zappingMode) null
        else channels.find { it.url == channel?.url }
    }
        .stateIn(
            scope = viewModelScope,
            initialValue = null,
            started = SharingStarted.WhileSubscribed(5_000)
        )

    val subscribingOrRefreshing: StateFlow<Boolean> = workManager
        .getWorkInfosFlow(
            WorkQuery.fromStates(
                WorkInfo.State.RUNNING,
                WorkInfo.State.ENQUEUED,
            )
        )
        .combine(playlist) { infos, playlist ->
            val playlistUrl = playlist?.url ?: return@combine false
            val workTag = playlistWorkTag(playlistUrl)
            infos.any { info ->
                SubscriptionWorker.TAG in info.tags &&
                    (
                        workTag in info.tags ||
                            // Compatibility with work enqueued before hashed tags shipped.
                            playlistUrl in info.tags
                        )
            }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(
            scope = viewModelScope,
            initialValue = false,
            started = SharingStarted.WhileSubscribed(5000)
        )

    fun refresh(background: Boolean = false) {
        val url = playlistUrl.value
        viewModelScope.launch {
            playlistRepository.refresh(
                url = url,
                reason = if (background) {
                    PlaylistRefreshReason.BACKGROUND
                } else {
                    PlaylistRefreshReason.USER
                },
            )
        }
    }

    fun favourite(id: Int) {
        viewModelScope.launch {
            channelRepository.favouriteOrUnfavourite(id)
        }
    }

    fun savePicture(id: Int) {
        viewModelScope.launch {
            val channel = channelRepository.get(id)
            if (channel == null) {
                messager.emit(PlaylistMessage.ChannelNotFound)
                return@launch
            }
            val cover = channel.cover
            if (cover.isNullOrEmpty()) {
                messager.emit(PlaylistMessage.ChannelCoverNotFound)
                return@launch
            }
            resource { mediaRepository.savePicture(cover) }
                .onEach { resource ->
                    when (resource) {
                        Resource.Loading -> {}
                        is Resource.Success -> {
                            messager.emit(ChannelCoverSaved(resource.data.displayPath))
                        }

                        is Resource.Failure -> {
                            messager.emit(resource.message.orEmpty())
                        }
                    }
                }
                .launchIn(this)
        }
    }

    fun hide(id: Int) {
        viewModelScope.launch {
            val channel = channelRepository.get(id)
            if (channel == null) {
                messager.emit(PlaylistMessage.ChannelNotFound)
            } else {
                channelRepository.hide(channel.id, true)
            }
        }
    }

    fun createShortcut(context: Context, id: Int) {
        val shortcutId = "channel_$id"
        viewModelScope.launch {
            val channel = channelRepository.get(id) ?: return@launch
            val bitmap = channel.cover?.let { mediaRepository.loadDrawable(it)?.toBitmap() }
            val shortcutInfo = ShortcutInfoCompat.Builder(context, shortcutId)
                .setShortLabel(channel.title)
                .setLongLabel(channel.url)
                .setIcon(
                    bitmap
                        ?.let { IconCompat.createWithBitmap(it) }
                        ?: IconCompat.createWithResource(context, R.drawable.round_play_arrow_24)
                )
                .setIntent(
                    Intent(Intent.ACTION_VIEW).apply {
                        component = ComponentName.createRelative(
                            context,
                            Contracts.PLAYER_ACTIVITY
                        )
                        putExtra(Contracts.PLAYER_SHORTCUT_CHANNEL_ID, channel.id)
                    }
                )
                .build()
            ShortcutManagerCompat.pushDynamicShortcut(context, shortcutInfo)
        }
    }

    private val sortIndex: MutableStateFlow<Int> = MutableStateFlow(0)

    val sort: StateFlow<Sort> = sortIndex
        .map { Sort.entries[it] }
        .stateIn(
            scope = viewModelScope,
            initialValue = Sort.UNSPECIFIED,
            started = SharingStarted.WhileSubscribed(5_000L)
        )

    fun sort(sort: Sort) {
        sortIndex.value = Sort.entries.indexOf(sort).coerceAtLeast(0)
    }

    val query = MutableStateFlow("")
    @OptIn(FlowPreview::class)
    private val effectiveQuery: StateFlow<String> = query
        .map { it.trim() }
        .let { flow ->
            merge(
                flow.take(1),
                flow.drop(1).debounce(300.milliseconds)
            )
        }
        .stateIn(
            scope = viewModelScope,
            initialValue = "",
            started = SharingStarted.Lazily
        )
    val isQueryActive: StateFlow<Boolean> = effectiveQuery
        .map { it.isNotEmpty() }
        .stateIn(
            scope = viewModelScope,
            initialValue = false,
            started = SharingStarted.Lazily
        )
    val scrollUp: MutableStateFlow<Event<Unit>> = MutableStateFlow(handledEvent())

    @Immutable
    data class ChannelParameters(
        val playlistUrl: String,
        val hiddenCategories: List<String>,
        val query: String,
        val sort: Sort,
        val kind: Int?,
        val categories: List<String>,
    )

    private val xtreamSiblings: StateFlow<List<Playlist>> = combine(
        playlistUrl,
        playlist,
        playlistRepository.observeAll(),
    ) { currentUrl, currentPlaylist, allPlaylists ->
        if (currentPlaylist?.source != DataSource.Xtream) return@combine emptyList()
        val currentInput = XtreamInput.decodeFromPlaylistUrlOrNull(currentUrl) ?: return@combine emptyList()
        allPlaylists.filter { candidate ->
            if (candidate.source != DataSource.Xtream) return@filter false
            val input = XtreamInput.decodeFromPlaylistUrlOrNull(candidate.url) ?: return@filter false
            input.basicUrl == currentInput.basicUrl &&
                input.username == currentInput.username &&
                input.password == currentInput.password
        }
    }
        .stateIn(
            scope = viewModelScope,
            initialValue = emptyList(),
            started = SharingStarted.WhileSubscribed(5_000L)
        )

    private val streamKinds: StateFlow<List<Int>> = playlistUrl
        .flatMapLatest { url ->
            channelRepository.observeStreamKindsByPlaylistUrl(url)
        }
        .stateIn(
            scope = viewModelScope,
            initialValue = emptyList(),
            started = SharingStarted.WhileSubscribed(5_000L)
        )

    val mediaSections: StateFlow<List<PlaylistMediaSection>> = combine(
        playlistUrl,
        xtreamSiblings,
        streamKinds,
        selectedStreamKind,
    ) { currentUrl, siblings, kinds, currentKind ->
        if (siblings.size > 1) {
            listOf(
                DataSource.Xtream.TYPE_LIVE to StreamKind.LIVE,
                DataSource.Xtream.TYPE_VOD to StreamKind.MOVIE,
                DataSource.Xtream.TYPE_SERIES to StreamKind.SERIES,
            ).mapNotNull { (xtreamType, kind) ->
                val match = siblings.firstOrNull { it.type == xtreamType } ?: return@mapNotNull null
                PlaylistMediaSection(
                    kind = kind,
                    selected = match.url == currentUrl,
                )
            }
        } else {
            val orderedKinds = listOf(StreamKind.LIVE, StreamKind.MOVIE, StreamKind.SERIES)
                .filter { it in kinds }
            if (orderedKinds.size <= 1) {
                emptyList()
            } else {
                val activeKind = currentKind?.takeIf { it in orderedKinds }
                buildList {
                    add(PlaylistMediaSection(kind = null, selected = activeKind == null))
                    orderedKinds.forEach { kind ->
                        add(PlaylistMediaSection(kind = kind, selected = activeKind == kind))
                    }
                }
            }
        }
    }
        .stateIn(
            scope = viewModelScope,
            initialValue = emptyList(),
            started = SharingStarted.WhileSubscribed(5_000L)
        )

    fun selectMediaSection(kind: Int?) {
        val siblings = xtreamSiblings.value
        if (siblings.size > 1 && kind != null) {
            val targetType = when (kind) {
                StreamKind.LIVE -> DataSource.Xtream.TYPE_LIVE
                StreamKind.MOVIE -> DataSource.Xtream.TYPE_VOD
                StreamKind.SERIES -> DataSource.Xtream.TYPE_SERIES
                else -> null
            }
            val targetPlaylist = siblings.firstOrNull { it.type == targetType }
            if (targetPlaylist != null && targetPlaylist.url != playlistUrl.value) {
                playlistUrlOverride.value = PlaylistUrlOverride(
                    forRouteUrl = routePlaylistUrl.value,
                    selectedUrl = targetPlaylist.url,
                )
                selectedStreamKind.value = null
                return
            }
        }
        selectedStreamKind.value = kind
    }

    val effectiveStreamKind: StateFlow<Int?> = combine(
        xtreamSiblings,
        streamKinds,
        selectedStreamKind,
    ) { siblings, kinds, currentKind ->
        if (siblings.size > 1) null else currentKind?.takeIf { it in kinds }
    }
        .stateIn(
            scope = viewModelScope,
            initialValue = null,
            started = SharingStarted.Lazily
        )

    @OptIn(FlowPreview::class)
    private val categories: StateFlow<List<String>> =
        flatmapCombined(
            playlistUrl,
            effectiveQuery,
            sort,
            effectiveStreamKind,
        ) { playlistUrl, query, sort, kind ->
            when {
                query.isNotEmpty() -> flowOf(listOf(GLOBAL_SEARCH_KEY))
                sort == Sort.MIXED -> flowOf(emptyList())
                else -> playlistRepository.observeCategoriesByPlaylistUrlIgnoreHidden(
                    url = playlistUrl,
                    query = query,
                    kind = kind,
                )
            }
        }
            .debounceAfterFirst(1.seconds)
            .stateIn(
                scope = viewModelScope,
                initialValue = emptyList(),
                started = SharingStarted.Lazily
            )

    private val currentProgrammes: StateFlow<Map<String, Programme>> = playlistUrl
        .flatMapLatest { playlistUrl ->
            channelRepository.observeRelationIdsByPlaylistUrl(playlistUrl)
                .map { it.toSet() }
                .distinctUntilChanged()
                .debounceAfterFirst(1.seconds)
                .mapLatest {
                    programmeRepository.getProgrammesCurrently(playlistUrl)
                }
        }
        .stateIn(
            scope = viewModelScope,
            initialValue = emptyMap(),
            started = SharingStarted.WhileSubscribed(5_000L)
        )

    private val channelPages: StateFlow<Map<String, Flow<PagingData<Channel>>>> = combine(
        playlistUrl,
        playlist,
        categories,
        effectiveQuery,
        sort,
        effectiveStreamKind,
    ) { values ->
        val playlistUrl = values[0] as String
        val playlist = values[1] as Playlist?
        @Suppress("UNCHECKED_CAST")
        val categories = values[2] as List<String>
        val query = values[3] as String
        val sort = values[4] as Sort
        val kind = values[5] as Int?
        ChannelParameters(
            playlistUrl = playlistUrl,
            hiddenCategories = playlist?.hiddenCategories.orEmpty(),
            query = query,
            sort = sort,
            kind = kind,
            categories = categories,
        )
    }
        .mapLatest { (playlistUrl, hiddenCategories, query, sort, kind, categories) ->
            when {
                query.isNotEmpty() -> {
                    mapOf(
                        GLOBAL_SEARCH_KEY to Pager(PagingConfig(15)) {
                            channelRepository.searchByPlaylist(
                                playlistUrl = playlistUrl,
                                query = query,
                                hiddenCategories = hiddenCategories,
                                kind = kind,
                            )
                        }
                            .flow
                            .cachedIn(viewModelScope)
                    )
                }

                sort == Sort.MIXED -> {
                    mapOf(
                        "" to Pager(PagingConfig(15)) {
                            channelRepository.pagingAllByPlaylistUrl(
                                playlistUrl,
                                "",
                                query,
                                sort,
                                kind,
                            )
                        }
                            .flow
                            .cachedIn(viewModelScope)
                    )
                }

                else -> categories.associate { category ->
                    category to Pager(PagingConfig(15)) {
                        channelRepository.pagingAllByPlaylistUrl(
                            playlistUrl,
                            category,
                            query,
                            sort,
                            kind,
                        )
                    }
                        .flow
                        .cachedIn(viewModelScope)
                }
            }
        }
        .stateIn(
            scope = viewModelScope,
            initialValue = emptyMap(),
            started = SharingStarted.Lazily
        )

    val channels: StateFlow<Map<String, Flow<PagingData<ChannelWithProgramme>>>> = combine(
        channelPages,
        currentProgrammes
    ) { pages, currentProgrammes ->
        pages.mapValues { (_, flow) ->
            flow.withProgrammes(currentProgrammes)
        }
    }
        .stateIn(
            scope = viewModelScope,
            initialValue = emptyMap(),
            started = SharingStarted.Lazily
        )

    private fun Flow<PagingData<Channel>>.withProgrammes(
        currentProgrammes: Map<String, Programme>
    ): Flow<PagingData<ChannelWithProgramme>> {
        return map { data ->
            // Recreate the transform so paged channel items show refreshed programme metadata.
            data.pagingMap { channel ->
                ChannelWithProgramme(
                    channel = channel,
                    programme = channel.relationId?.let(currentProgrammes::get)
                )
            }
        }
    }

    private fun <T> Flow<T>.debounceAfterFirst(timeout: Duration): Flow<T> = channelFlow {
        var isFirst = true
        collectLatest { value ->
            if (isFirst) {
                isFirst = false
            } else {
                delay(timeout)
            }
            send(value)
        }
    }

    val pinnedCategories: StateFlow<List<String>> = playlist
        .map { it?.pinnedCategories ?: emptyList() }
        .stateIn(
            scope = viewModelScope,
            initialValue = emptyList(),
            started = SharingStarted.WhileSubscribed(5_000L)
        )

    fun onPinOrUnpinCategory(category: String) {
        val currentPlaylistUrl = playlistUrl.value
        viewModelScope.launch {
            playlistRepository.pinOrUnpinCategory(currentPlaylistUrl, category)
        }
    }

    fun onHideCategory(category: String) {
        val currentPlaylistUrl = playlistUrl.value
        viewModelScope.launch {
            playlistRepository.hideOrUnhideCategory(currentPlaylistUrl, category)
        }
    }

    fun setup(
        channelId: Int,
        onPlayMediaCommand: (MediaCommand) -> Unit
    ) {
        viewModelScope.launch {
            val channel = channelRepository.get(channelId) ?: return@launch
            val playlist = playlistRepository.get(channel.playlistUrl)
            savedStateHandle[PlaylistNavigation.TYPE_URL] = channel.playlistUrl

            if (playlist?.isSeries == false) {
                onPlayMediaCommand(MediaCommand.Common(channel.id))
            } else {
                series.value = channel
            }
        }
    }

    suspend fun reloadThumbnail(channelUrl: String): Uri? {
        return playerManager.reloadThumbnail(channelUrl)
    }

    suspend fun syncThumbnail(channelUrl: String): Uri? {
        return playerManager.syncThumbnail(channelUrl)
    }

    val series = MutableStateFlow<Channel?>(null)
    val seriesReplay = MutableStateFlow(0)

    val episodes: StateFlow<Resource<List<XtreamEpisodeInfo>>> = series
        .combine(seriesReplay) { series, _ -> series }
        .flatMapLatest { series ->
            if (series == null) flow {}
            else resource { playlistRepository.readEpisodesOrThrow(series) }
                .mapResource { it }
        }
        .stateIn(
            scope = viewModelScope,
            initialValue = Resource.Loading,
            // don't lose
            started = SharingStarted.Lazily
        )
}
