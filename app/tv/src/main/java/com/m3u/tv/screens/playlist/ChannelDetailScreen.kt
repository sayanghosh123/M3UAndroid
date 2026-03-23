package com.m3u.tv.screens.playlist

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.m3u.core.foundation.components.CircularProgressIndicator
import com.m3u.core.wrapper.Resource
import com.m3u.data.database.model.Channel
import com.m3u.data.database.model.Playlist
import com.m3u.data.parser.xtream.XtreamChannelInfo
import com.m3u.data.service.MediaCommand
import com.m3u.i18n.R.string
import com.m3u.tv.common.contentType
import com.m3u.tv.screens.dashboard.rememberChildPadding
import com.m3u.tv.utils.LocalHelper
import kotlinx.coroutines.launch

object ChannelDetailScreen {
    const val ChannelIdBundleKey = "channelId"
}

@Composable
fun ChannelDetailScreen(
    navigateToChannel: () -> Unit,
    onBackPressed: () -> Unit,
    viewModel: ChannelDetailViewModel = hiltViewModel()
) {
    val helper = LocalHelper.current
    val coroutineScope = rememberCoroutineScope()
    val channel by viewModel.channel.collectAsStateWithLifecycle()
    val playlist by viewModel.playlist.collectAsStateWithLifecycle()
    val seriesPresentation by viewModel.seriesPresentation.collectAsStateWithLifecycle()

    when {
        channel == null || playlist == null -> CircularProgressIndicator()
        else -> {
            Details(
                channel = checkNotNull(channel),
                playlist = checkNotNull(playlist),
                seriesPresentation = seriesPresentation,
                onPlayMedia = { command ->
                    coroutineScope.launch {
                        helper.play(command)
                    }
                    navigateToChannel()
                },
                updateFavorite = viewModel::updateFavorite,
                onRetrySeries = viewModel::retrySeriesPresentation,
                onBackPressed = onBackPressed,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun Details(
    channel: Channel,
    playlist: Playlist,
    seriesPresentation: Resource<SeriesPresentation>?,
    onPlayMedia: (MediaCommand) -> Unit,
    updateFavorite: () -> Unit,
    onRetrySeries: () -> Unit,
    onBackPressed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val childPadding = rememberChildPadding()
    val contentType = playlist.contentType()
    val series = (seriesPresentation as? Resource.Success)?.data
    val primaryEpisode = series?.episodes?.firstOrNull()
    val primaryAction: (() -> Unit)? = when (contentType) {
        com.m3u.tv.common.PlaylistContentType.Series -> primaryEpisode?.let { episode ->
            { onPlayMedia(MediaCommand.XtreamEpisode(channel.id, episode)) }
        }

        else -> {
            { onPlayMedia(MediaCommand.Common(channel.id)) }
        }
    }

    val summary = when {
        !series?.info?.plot.isNullOrBlank() -> series?.info?.plot.orEmpty()
        channel.category.isNotBlank() -> channel.category
        else -> playlist.title
    }

    val metadata = buildList {
        add(stringResource(string.ui_label_type) to stringResource(contentType.labelResId))
        playlist.title
            .takeIf { it.isNotBlank() }
            ?.let { add(stringResource(string.ui_label_playlist) to it) }
        channel.category
            .takeIf { it.isNotBlank() }
            ?.let { add(stringResource(string.ui_label_category) to it) }
        series?.info?.genre
            ?.takeIf { it.isNotBlank() }
            ?.let { add(stringResource(string.ui_label_genre) to it) }
        series?.info?.rating
            ?.takeIf { it.isNotBlank() }
            ?.let { add(stringResource(string.ui_label_rating) to it) }
        series?.info?.releaseDate
            ?.takeIf { it.isNotBlank() }
            ?.let { add(stringResource(string.ui_label_release_date) to it) }
    }

    BackHandler(onBack = onBackPressed)
    LazyColumn(
        contentPadding = PaddingValues(bottom = 135.dp),
        modifier = modifier,
    ) {
        item {
            ChannelDetail(
                channel = channel,
                contentTypeLabel = stringResource(contentType.labelResId),
                summary = summary,
                primaryActionLabel = stringResource(contentType.primaryActionResId),
                primaryActionEnabled = primaryAction != null,
                onPrimaryAction = { primaryAction?.invoke() },
                updateFavorite = updateFavorite,
            )
        }

        item {
            Box(
                modifier = Modifier
                    .padding(horizontal = childPadding.start)
                    .padding(BottomDividerPadding)
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f))
            )
        }

        if (metadata.isNotEmpty()) {
            item {
                MetadataSection(
                    metadata = metadata,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = childPadding.start)
                )
            }
        }

        if (contentType == com.m3u.tv.common.PlaylistContentType.Series) {
            when (seriesPresentation) {
                null,
                Resource.Loading -> {
                    item {
                        Box(
                            modifier = Modifier
                                .padding(horizontal = childPadding.start)
                                .padding(top = 40.dp)
                        ) {
                            CircularProgressIndicator(size = 24.dp)
                        }
                    }
                }

                is Resource.Failure -> {
                    item {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            modifier = Modifier
                                .padding(horizontal = childPadding.start)
                                .padding(top = 40.dp)
                        ) {
                            Text(
                                text = seriesPresentation.message
                                    ?: stringResource(string.ui_error_unknown),
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            Button(onClick = onRetrySeries) {
                                Text(text = stringResource(string.data_worker_subscription_action_retry))
                            }
                        }
                    }
                }

                is Resource.Success -> {
                    val episodes = seriesPresentation.data.episodes
                    if (episodes.isNotEmpty()) {
                        item {
                            SeriesEpisodesSection(
                                episodes = episodes,
                                onEpisodeClick = { episode ->
                                    onPlayMedia(MediaCommand.XtreamEpisode(channel.id, episode))
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MetadataSection(
    metadata: List<Pair<String, String>>,
    modifier: Modifier = Modifier,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(24.dp),
        modifier = modifier
    ) {
        metadata.chunked(3).forEach { row ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                row.forEach { (title, value) ->
                    TitleValueText(
                        modifier = Modifier.weight(1f),
                        title = title,
                        value = value
                    )
                }
            }
        }
    }
}

@Composable
private fun SeriesEpisodesSection(
    episodes: List<XtreamChannelInfo.Episode>,
    onEpisodeClick: (XtreamChannelInfo.Episode) -> Unit,
) {
    val childPadding = rememberChildPadding()
    Text(
        text = stringResource(string.ui_label_episodes),
        style = MaterialTheme.typography.headlineLarge,
        modifier = Modifier
            .padding(horizontal = childPadding.start)
            .padding(top = 40.dp, bottom = 20.dp)
    )
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(
            start = childPadding.start,
            end = childPadding.end
        )
    ) {
        items(
            items = episodes,
            key = { episode -> episode.id ?: "${episode.episodeNum}-${episode.title}" }
        ) { episode ->
            EpisodeButton(
                episode = episode,
                onClick = { onEpisodeClick(episode) }
            )
        }
    }
}

@Composable
private fun EpisodeButton(
    episode: XtreamChannelInfo.Episode,
    onClick: () -> Unit,
) {
    val title = episode.title
        ?.takeIf { it.isNotBlank() }
        ?: stringResource(
            string.ui_label_episode_number,
            episode.episodeNum ?: "?"
        )
    Button(
        onClick = onClick,
        modifier = Modifier
            .width(280.dp)
            .height(120.dp)
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            episode.episodeNum
                ?.takeIf { it.isNotBlank() }
                ?.let {
                    Text(
                        text = stringResource(string.ui_label_episode_number, it),
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2
            )
        }
    }
}

private val BottomDividerPadding = PaddingValues(vertical = 48.dp)
