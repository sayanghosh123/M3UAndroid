package com.m3u.smartphone.ui.business.foryou

import android.content.res.Configuration.ORIENTATION_PORTRAIT
import android.text.format.DateUtils
import android.view.KeyEvent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.m3u.business.foryou.ForyouViewModel
import com.m3u.business.foryou.Recommend
import com.m3u.core.foundation.architecture.preferences.PreferencesKeys
import com.m3u.core.foundation.architecture.preferences.mutablePreferenceOf
import com.m3u.core.foundation.architecture.preferences.preferenceOf
import com.m3u.core.foundation.ui.composableOf
import com.m3u.core.foundation.ui.thenIf
import com.m3u.core.foundation.util.basic.title
import com.m3u.core.foundation.wrapper.Resource
import com.m3u.data.database.model.Channel
import com.m3u.data.database.model.Playlist
import com.m3u.data.database.model.PlaylistWithCount
import com.m3u.data.database.model.StreamKind
import com.m3u.data.database.model.WatchHistory
import com.m3u.data.database.model.isSeries
import com.m3u.data.service.MediaCommand
import com.m3u.i18n.R.string
import com.m3u.smartphone.ui.business.foryou.components.HeadlineBackground
import com.m3u.smartphone.ui.business.foryou.components.PlaylistGallery
import com.m3u.smartphone.ui.business.foryou.components.recommend.RecommendGallery
import com.m3u.smartphone.ui.common.helper.Action
import com.m3u.smartphone.ui.common.helper.LocalHelper
import com.m3u.smartphone.ui.common.helper.Metadata
import com.m3u.smartphone.ui.material.components.EpisodesBottomSheet
import com.m3u.smartphone.ui.material.components.MediaSheet
import com.m3u.smartphone.ui.material.components.MediaSheetValue
import com.m3u.smartphone.ui.material.ktx.interceptVolumeEvent
import com.m3u.smartphone.ui.material.model.LocalSpacing
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

@Composable
fun ForyouRoute(
    navigateToPlaylist: (Playlist) -> Unit,
    navigateToChannel: () -> Unit,
    navigateToPlaylistConfiguration: (Playlist) -> Unit,
    navigateToSettingPlaylistManagement: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    viewModel: ForyouViewModel = hiltViewModel()
) {
    val helper = LocalHelper.current
    val coroutineScope = rememberCoroutineScope()

    var rowCount by mutablePreferenceOf(PreferencesKeys.ROW_COUNT)
    val godMode by preferenceOf(PreferencesKeys.GOD_MODE)

    val title = stringResource(string.ui_title_foryou)
    val addContentDescription = stringResource(string.ui_action_add)

    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    val specs by viewModel.specs.collectAsStateWithLifecycle()
    val watchHistory by viewModel.watchHistory.collectAsStateWithLifecycle()
    val episodes by viewModel.episodes.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    val series: Channel? by viewModel.series.collectAsStateWithLifecycle()
    val subscribingPlaylistUrls by viewModel.subscribingPlaylistUrls.collectAsStateWithLifecycle()
    val refreshingEpgUrls by viewModel.refreshingEpgUrls.collectAsStateWithLifecycle(emptyList())

    LifecycleResumeEffect(title) {
        Metadata.title = AnnotatedString(title.title())
        Metadata.color = Color.Unspecified
        Metadata.contentColor = Color.Unspecified
        Metadata.actions = listOf(
            Action(
                icon = Icons.Rounded.Add,
                contentDescription = addContentDescription,
                onClick = navigateToSettingPlaylistManagement
            )
        )
        onPauseOrDispose {
            Metadata.actions = emptyList()
            Metadata.headlineUrl = ""
        }
    }

    Box(modifier) {
        ForyouScreen(
            playlists = playlists,
            subscribingPlaylistUrls = subscribingPlaylistUrls,
            refreshingEpgUrls = refreshingEpgUrls,
            specs = specs,
            watchHistory = watchHistory,
            rowCount = rowCount,
            contentPadding = contentPadding,
            navigateToPlaylist = navigateToPlaylist,
            onPlayChannel = { channel ->
                coroutineScope.launch {
                    val playlist = viewModel.getPlaylist(channel.playlistUrl)
                    when {
                        playlist?.isSeries == true -> {
                            viewModel.series.value = channel
                        }

                        else -> {
                            if (helper.play(MediaCommand.Common(channel.id))) {
                                navigateToChannel()
                            }
                        }
                    }
                }
            },
            onPlayWatchHistory = { entry ->
                coroutineScope.launch {
                    val resolvedChannel = viewModel.resolveChannelForHistory(entry)
                    val targetId = resolvedChannel?.id ?: entry.channelId
                    val playlist = viewModel.getPlaylist(entry.playlistUrl)
                    if (playlist?.isSeries == true && resolvedChannel != null) {
                        viewModel.series.value = resolvedChannel
                    } else if (helper.play(MediaCommand.Common(targetId))) {
                        navigateToChannel()
                    }
                }
            },
            onRemoveWatchHistory = { entry ->
                viewModel.removeWatchHistory(entry)
                coroutineScope.launch {
                    snackbarHostState.currentSnackbarData?.dismiss()
                    val message = context.getString(
                        string.feat_foryou_watch_history_removed,
                        entry.title
                    )
                    val undoLabel = context.getString(string.feat_foryou_watch_history_undo)
                    val result = snackbarHostState.showSnackbar(
                        message = message,
                        actionLabel = undoLabel,
                        duration = SnackbarDuration.Short,
                    )
                    if (result == SnackbarResult.ActionPerformed) {
                        viewModel.restoreWatchHistory(entry)
                    }
                }
            },
            navigateToPlaylistConfiguration = navigateToPlaylistConfiguration,
            onUnsubscribePlaylist = viewModel::onUnsubscribePlaylist,
            modifier = Modifier
                .fillMaxSize()
                .thenIf(godMode) {
                    Modifier.interceptVolumeEvent { event ->
                        rowCount = when (event) {
                            KeyEvent.KEYCODE_VOLUME_UP -> (rowCount - 1).coerceAtLeast(1)
                            KeyEvent.KEYCODE_VOLUME_DOWN -> (rowCount + 1).coerceAtMost(2)
                            else -> return@interceptVolumeEvent
                        }
                    }
                }
        )

        EpisodesBottomSheet(
            series = series,
            episodes = episodes,
            onEpisodeClick = { episode ->
                coroutineScope.launch {
                    series?.let { channel ->
                        val input = MediaCommand.XtreamEpisode(
                            channelId = channel.id,
                            episode = episode
                        )
                        if (helper.play(input)) {
                            navigateToChannel()
                        }
                    }
                }
            },
            onRefresh = { series?.let { viewModel.seriesReplay.value += 1 } },
            onDismissRequest = { viewModel.series.value = null }
        )

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = contentPadding.calculateBottomPadding())
        )
    }
}

@Composable
private fun ForyouScreen(
    rowCount: Int,
    playlists: Map<Playlist, Int>,
    subscribingPlaylistUrls: List<String>,
    refreshingEpgUrls: List<String>,
    specs: List<Recommend.Spec>,
    watchHistory: List<WatchHistory>,
    contentPadding: PaddingValues,
    navigateToPlaylist: (Playlist) -> Unit,
    onPlayChannel: (Channel) -> Unit,
    onPlayWatchHistory: (WatchHistory) -> Unit,
    onRemoveWatchHistory: (WatchHistory) -> Unit,
    navigateToPlaylistConfiguration: (Playlist) -> Unit,
    onUnsubscribePlaylist: (playlistUrl: String) -> Unit,
    modifier: Modifier = Modifier
) {
    val configuration = LocalConfiguration.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var headlineSpec: Recommend.Spec? by remember { mutableStateOf(null) }

    val actualRowCount = remember(rowCount, configuration.orientation) {
        when (configuration.orientation) {
            ORIENTATION_PORTRAIT -> rowCount
            else -> rowCount + 2
        }
    }
    var mediaSheetValue: MediaSheetValue.ForyouScreen by remember {
        mutableStateOf(MediaSheetValue.ForyouScreen())
    }

    LaunchedEffect(headlineSpec) {
        val spec = headlineSpec
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            delay(400.milliseconds)
            Metadata.headlineUrl = when (spec) {
                is Recommend.UnseenSpec -> spec.channel.cover.orEmpty()
                is Recommend.DiscoverSpec -> ""
                is Recommend.NewRelease -> ""
                else -> ""
            }
        }
    }

    Box(modifier) {
        HeadlineBackground()
        val hasHeader = specs.isNotEmpty() || watchHistory.isNotEmpty()
        val header = @Composable {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (specs.isNotEmpty()) {
                    RecommendGallery(
                        specs = specs,
                        navigateToPlaylist = navigateToPlaylist,
                        onPlayChannel = onPlayChannel,
                        onSpecChanged = { spec -> headlineSpec = spec },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                if (watchHistory.isNotEmpty()) {
                    WatchHistoryStrip(
                        entries = watchHistory,
                        onPlay = onPlayWatchHistory,
                        onRemove = onRemoveWatchHistory,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
        PlaylistGallery(
            rowCount = actualRowCount,
            playlists = playlists,
            subscribingPlaylistUrls = subscribingPlaylistUrls,
            refreshingEpgUrls = refreshingEpgUrls,
            onClick = navigateToPlaylist,
            onLongClick = { mediaSheetValue = MediaSheetValue.ForyouScreen(it) },
            header = composableOf(hasHeader, header),
            contentPadding = contentPadding,
            modifier = Modifier.fillMaxSize()
        )
        MediaSheet(
            value = mediaSheetValue,
            onUnsubscribePlaylist = {
                onUnsubscribePlaylist(it.url)
                mediaSheetValue = MediaSheetValue.ForyouScreen()
            },
            onPlaylistConfiguration = navigateToPlaylistConfiguration,
            onDismissRequest = {
                mediaSheetValue = MediaSheetValue.ForyouScreen()
            }
        )
    }
}

@Composable
private fun WatchHistoryStrip(
    entries: List<WatchHistory>,
    onPlay: (WatchHistory) -> Unit,
    onRemove: (WatchHistory) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    Column(
        verticalArrangement = Arrangement.spacedBy(spacing.small),
        modifier = modifier.padding(vertical = spacing.small),
    ) {
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.medium),
        ) {
            Text(
                text = stringResource(string.feat_foryou_watch_history_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(string.feat_foryou_watch_history_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(spacing.small),
            contentPadding = PaddingValues(horizontal = spacing.medium),
            modifier = Modifier.fillMaxWidth(),
        ) {
            items(
                items = entries,
                key = { it.id },
            ) { entry ->
                WatchHistoryCard(
                    entry = entry,
                    onClick = { onPlay(entry) },
                    onDoubleClick = { onRemove(entry) },
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WatchHistoryCard(
    entry: WatchHistory,
    onClick: () -> Unit,
    onDoubleClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    val context = LocalContext.current
    val relativeTime = remember(entry.watchedAt) {
        if (entry.watchedAt > 0L) {
            DateUtils.getRelativeTimeSpanString(
                entry.watchedAt,
                System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS,
                DateUtils.FORMAT_ABBREV_RELATIVE,
            ).toString()
        } else {
            ""
        }
    }
    val progress = remember(entry.positionMs, entry.durationMs) {
        if (entry.durationMs > 0L && entry.positionMs > 0L) {
            (entry.positionMs.toFloat() / entry.durationMs.toFloat()).coerceIn(0f, 1f)
        } else {
            null
        }
    }
    val kindLabel = when (entry.kind) {
        StreamKind.MOVIE -> stringResource(string.feat_playlist_media_movies)
        StreamKind.SERIES -> stringResource(string.feat_playlist_media_series)
        else -> null
    }

    ElevatedCard(
        shape = RoundedCornerShape(12.dp),
        modifier = modifier
            .width(164.dp)
            .combinedClickable(
                onClick = onClick,
                onDoubleClick = onDoubleClick,
            ),
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            ) {
                if (!entry.cover.isNullOrBlank()) {
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(entry.cover)
                            .crossfade(true)
                            .build(),
                        contentDescription = entry.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Text(
                        text = entry.title.take(2).uppercase(),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
                if (kindLabel != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.9f),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(spacing.extraSmall),
                    ) {
                        Text(
                            text = kindLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
                if (progress != null) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.BottomCenter),
                    )
                }
            }
            Column(
                modifier = Modifier.padding(spacing.small),
            ) {
                Text(
                    text = entry.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val subtitle = listOfNotNull(
                    entry.category.takeIf(String::isNotBlank),
                    relativeTime.takeIf(String::isNotBlank),
                ).joinToString(" • ")
                if (subtitle.isNotBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
