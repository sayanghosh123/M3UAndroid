package com.m3u.smartphone.ui.business.epg

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.m3u.data.database.model.ImportResult
import com.m3u.data.repository.curated.CuratedEventRepository
import com.m3u.data.repository.programme.ProgrammeRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

enum class SportsTimeMode { LIVE, SOON }

@Immutable
data class SportsItem(
    val title: String,
    val channelId: Int,
    val channelTitle: String,
    val playlistTitle: String,
    val category: String?,
    val start: Long,
    val end: Long,
    val curated: Boolean,
)

@HiltViewModel
class SportsViewModel @Inject constructor(
    private val programmeRepository: ProgrammeRepository,
    private val curatedEventRepository: CuratedEventRepository,
) : ViewModel() {
    val mode = MutableStateFlow(SportsTimeMode.LIVE)

    private val clock: Flow<Long> = flow {
        while (true) {
            emit(Clock.System.now().toEpochMilliseconds())
            delay(1.minutes)
        }
    }

    val items = combine(clock, mode) { now, mode -> now to mode }
        .flatMapLatest { (now, mode) ->
            val end = when (mode) {
                SportsTimeMode.LIVE -> now
                SportsTimeMode.SOON -> now + 3.hours.inWholeMilliseconds
            }
            combine(
                programmeRepository.observeSports(now, end),
                curatedEventRepository.observeInWindow(now, end),
            ) { programmes, curated ->
                val epgItems = programmes
                    .filter { mode == SportsTimeMode.LIVE || it.programme.start >= now }
                    .map {
                        SportsItem(
                            title = it.programme.title,
                            channelId = it.channelId,
                            channelTitle = it.channelTitle,
                            playlistTitle = it.playlistTitle,
                            category = it.programme.categories.firstOrNull(),
                            start = it.programme.start,
                            end = it.programme.end,
                            curated = false,
                        )
                    }
                val importedItems = curated
                    .filter { mode == SportsTimeMode.LIVE || it.event.start >= now }
                    .filterNot { event ->
                        epgItems.any {
                            it.channelId == event.event.channelId &&
                                    it.start <= event.event.end && it.end >= event.event.start
                        }
                    }
                    .map {
                        SportsItem(
                            title = it.event.title,
                            channelId = it.event.channelId,
                            channelTitle = it.channelTitle,
                            playlistTitle = it.playlistTitle,
                            category = it.event.competition ?: it.event.category,
                            start = it.event.start,
                            end = it.event.end,
                            curated = true,
                        )
                    }
                (epgItems + importedItems).sortedBy(SportsItem::start)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    suspend fun importEvents(content: String): ImportResult =
        curatedEventRepository.importJson(content)

    suspend fun clearImportedEvents() = curatedEventRepository.clear()
}
