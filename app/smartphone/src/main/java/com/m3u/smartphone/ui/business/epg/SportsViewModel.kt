package com.m3u.smartphone.ui.business.epg

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.m3u.data.database.model.ImportResult
import com.m3u.data.repository.channel.ChannelRepository
import com.m3u.data.repository.curated.CuratedEventRepository
import com.m3u.data.repository.playlist.PlaylistRepository
import com.m3u.data.repository.programme.CuratedMatch
import com.m3u.data.repository.programme.ProgrammeRepository
import com.m3u.data.repository.programme.SportPreset
import com.m3u.data.service.Messager
import com.m3u.i18n.R
import com.m3u.smartphone.cast.GoogleCastManager
import com.m3u.smartphone.cast.GoogleCastSessionState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

enum class SportsTimeMode { LIVE, SOON }

@HiltViewModel
class SportsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val programmeRepository: ProgrammeRepository,
    private val curatedEventRepository: CuratedEventRepository,
    private val channelRepository: ChannelRepository,
    private val playlistRepository: PlaylistRepository,
    private val googleCastManager: GoogleCastManager,
    private val messager: Messager,
) : ViewModel() {
    val mode = MutableStateFlow(SportsTimeMode.LIVE)
    val preset = MutableStateFlow(SportPreset.FOOTBALL)

    private val _selectedChannelByMatch = MutableStateFlow<Map<String, Int>>(emptyMap())
    val selectedChannelByMatch: StateFlow<Map<String, Int>> = _selectedChannelByMatch.asStateFlow()

    val castState: StateFlow<GoogleCastSessionState> = googleCastManager.sessionState

    private var pendingCastChannelId: Int? = null

    init {
        viewModelScope.launch {
            googleCastManager.sessionState.collect { state ->
                val pendingId = pendingCastChannelId
                if (state.isConnected && pendingId != null) {
                    pendingCastChannelId = null
                    performCast(pendingId)
                }
            }
        }
    }

    private val clock: Flow<Long> = flow {
        while (true) {
            emit(Clock.System.now().toEpochMilliseconds())
            delay(1.minutes)
        }
    }

    val matches: StateFlow<List<CuratedMatch>> = combine(clock, mode, preset) { now, currentMode, currentPreset ->
        Triple(now, currentMode, currentPreset)
    }.flatMapLatest { (now, currentMode, currentPreset) ->
        val end = when (currentMode) {
            SportsTimeMode.LIVE -> now
            SportsTimeMode.SOON -> now + 12.hours.inWholeMilliseconds
        }
        programmeRepository.observeSportsMatches(
            now = now,
            windowStart = now,
            windowEnd = end,
            preset = currentPreset,
            includeLiveUnscheduledChannels = currentMode == SportsTimeMode.LIVE,
            upcomingOnly = currentMode == SportsTimeMode.SOON,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun selectSource(matchId: String, channelId: Int) {
        _selectedChannelByMatch.update { current -> current + (matchId to channelId) }
    }

    fun castSource(
        channelId: Int,
        channelTitle: String,
        onNeedRoutePicker: () -> Unit,
    ) {
        if (!googleCastManager.sessionState.value.isConnected) {
            pendingCastChannelId = channelId
            messager.emit(context.getString(R.string.feat_epg_sports_cast_select_device, channelTitle))
            onNeedRoutePicker()
            return
        }
        viewModelScope.launch {
            performCast(channelId)
        }
    }

    private suspend fun performCast(channelId: Int) {
        val channel = channelRepository.get(channelId) ?: run {
            messager.emit(context.getString(R.string.feat_epg_sports_cast_failed))
            return
        }
        val playlist = playlistRepository.get(channel.playlistUrl)
        googleCastManager.load(
            channel = channel,
            playlist = playlist,
            startPositionMs = 0L,
            onSuccess = { deviceName ->
                messager.emit(
                    context.getString(
                        R.string.feat_epg_sports_casting_started,
                        channel.title,
                        deviceName ?: googleCastManager.sessionState.value.deviceName ?: "Chromecast",
                    )
                )
            },
            onFailure = { errorMessage ->
                if (!errorMessage.isNullOrBlank()) {
                    messager.emit(errorMessage)
                } else {
                    messager.emit(context.getString(R.string.feat_epg_sports_cast_failed))
                }
            },
        )
    }

    suspend fun importEvents(content: String): ImportResult =
        curatedEventRepository.importJson(content)

    suspend fun clearImportedEvents() = curatedEventRepository.clear()
}
