package com.m3u.smartphone.ui.business.epg

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.CastConnected
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SportsSoccer
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.mediarouter.app.MediaRouteButton
import com.google.android.gms.cast.framework.CastButtonFactory
import com.m3u.core.foundation.util.context.toast
import com.m3u.data.repository.programme.CuratedMatch
import com.m3u.data.repository.programme.MatchBroadcastSource
import com.m3u.data.repository.programme.SportPreset
import com.m3u.data.service.MediaCommand
import com.m3u.i18n.R
import com.m3u.smartphone.cast.GoogleCastSessionState
import com.m3u.smartphone.ui.common.helper.LocalHelper
import com.m3u.smartphone.ui.common.helper.Metadata
import com.m3u.smartphone.ui.material.model.LocalSpacing
import kotlinx.coroutines.launch

@Composable
fun SportsRoute(
    contentPadding: PaddingValues,
    navigateToChannel: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SportsViewModel = hiltViewModel(),
) {
    val title = stringResource(R.string.ui_destination_sports)
    val context = LocalContext.current
    val helper = LocalHelper.current
    val scope = rememberCoroutineScope()
    val mode by viewModel.mode.collectAsStateWithLifecycle()
    val preset by viewModel.preset.collectAsStateWithLifecycle()
    val matches by viewModel.matches.collectAsStateWithLifecycle()
    val selectedChannelByMatch by viewModel.selectedChannelByMatch.collectAsStateWithLifecycle()
    val castState by viewModel.castState.collectAsStateWithLifecycle()

    var castRoutePickerTrigger by remember { mutableStateOf<(() -> Unit)?>(null) }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                viewModel.importEvents(context.contentResolver.readImportText(uri))
            }.onSuccess {
                context.toast(
                    context.getString(
                        R.string.feat_epg_events_import_result,
                        it.imported,
                        it.total,
                        it.unmatched.size,
                    )
                )
            }.onFailure {
                context.toast(context.getString(R.string.feat_epg_import_failed))
            }
        }
    }

    LifecycleResumeEffect(title) {
        Metadata.title = AnnotatedString(title)
        Metadata.color = Color.Unspecified
        Metadata.contentColor = Color.Unspecified
        onPauseOrDispose { }
    }

    Box(modifier = modifier) {
        SportsScreen(
            mode = mode,
            preset = preset,
            matches = matches,
            selectedChannelByMatch = selectedChannelByMatch,
            castState = castState,
            contentPadding = contentPadding,
            onModeChanged = { viewModel.mode.value = it },
            onPresetChanged = { viewModel.preset.value = it },
            onSourceSelected = viewModel::selectSource,
            onImport = { importLauncher.launch(arrayOf("application/json", "text/json")) },
            onClear = {
                scope.launch {
                    viewModel.clearImportedEvents()
                    context.toast(context.getString(R.string.feat_epg_events_cleared))
                }
            },
            onWatch = { source ->
                scope.launch {
                    if (helper.play(MediaCommand.Common(source.channelId))) {
                        navigateToChannel()
                    }
                }
            },
            onOpenExternal = { source ->
                scope.launch {
                    helper.openInExternalPlayer(MediaCommand.Common(source.channelId))
                }
            },
            onCast = { source ->
                viewModel.castSource(
                    channelId = source.channelId,
                    channelTitle = source.channelTitle,
                    onNeedRoutePicker = { castRoutePickerTrigger?.invoke() },
                )
            },
            modifier = Modifier.fillMaxSize(),
        )

        SportsCastRoutePickerBridge(
            onReady = { trigger -> castRoutePickerTrigger = trigger },
            modifier = Modifier
                .size(1.dp)
                .alpha(0f),
        )
    }
}

@Composable
private fun SportsScreen(
    mode: SportsTimeMode,
    preset: SportPreset,
    matches: List<CuratedMatch>,
    selectedChannelByMatch: Map<String, Int>,
    castState: GoogleCastSessionState,
    contentPadding: PaddingValues,
    onModeChanged: (SportsTimeMode) -> Unit,
    onPresetChanged: (SportPreset) -> Unit,
    onSourceSelected: (matchId: String, channelId: Int) -> Unit,
    onImport: () -> Unit,
    onClear: () -> Unit,
    onWatch: (MatchBroadcastSource) -> Unit,
    onOpenExternal: (MatchBroadcastSource) -> Unit,
    onCast: (MatchBroadcastSource) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    Column(
        verticalArrangement = Arrangement.spacedBy(spacing.small),
        modifier = modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(horizontal = spacing.medium),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(spacing.small),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            FilterChip(
                selected = mode == SportsTimeMode.LIVE,
                onClick = { onModeChanged(SportsTimeMode.LIVE) },
                label = { Text(stringResource(R.string.feat_epg_sports_live)) },
            )
            FilterChip(
                selected = mode == SportsTimeMode.SOON,
                onClick = { onModeChanged(SportsTimeMode.SOON) },
                label = { Text(stringResource(R.string.feat_epg_sports_soon)) },
            )
            Spacer(modifier = Modifier.weight(1f))
            IconButton(onClick = onImport) {
                Icon(
                    Icons.Rounded.UploadFile,
                    contentDescription = stringResource(R.string.feat_epg_events_import),
                )
            }
            IconButton(onClick = onClear) {
                Icon(
                    Icons.Rounded.Delete,
                    contentDescription = stringResource(R.string.feat_epg_events_clear),
                )
            }
        }

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(spacing.small),
            modifier = Modifier.fillMaxWidth(),
        ) {
            items(
                items = SportPreset.entries,
                key = { it.id },
            ) { currentPreset ->
                FilterChip(
                    selected = preset == currentPreset,
                    onClick = { onPresetChanged(currentPreset) },
                    label = { Text(stringResource(currentPreset.labelRes())) },
                )
            }
        }

        if (matches.isEmpty()) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(spacing.large),
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(spacing.small),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.SportsSoccer,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(36.dp),
                    )
                    Text(
                        text = stringResource(R.string.feat_epg_sports_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(spacing.medium),
                contentPadding = PaddingValues(bottom = spacing.medium),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                items(matches, key = { it.id }) { match ->
                    val selectedChannelId = selectedChannelByMatch[match.id]
                    val activeSource = match.sources.firstOrNull { it.channelId == selectedChannelId }
                        ?: match.sources.first()
                    CuratedMatchCard(
                        match = match,
                        selectedSource = activeSource,
                        castConnected = castState.isConnected,
                        onSelectSource = { source ->
                            onSourceSelected(match.id, source.channelId)
                        },
                        onWatch = { onWatch(activeSource) },
                        onOpenExternal = { onOpenExternal(activeSource) },
                        onCast = { onCast(activeSource) },
                    )
                }
            }
        }
    }
}

@Composable
private fun CuratedMatchCard(
    match: CuratedMatch,
    selectedSource: MatchBroadcastSource,
    castConnected: Boolean,
    onSelectSource: (MatchBroadcastSource) -> Unit,
    onWatch: () -> Unit,
    onOpenExternal: () -> Unit,
    onCast: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    ElevatedCard(
        shape = RoundedCornerShape(16.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(spacing.small),
            modifier = Modifier.padding(spacing.medium),
        ) {
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = match.competition ?: selectedSource.playlistTitle,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(modifier = Modifier.width(spacing.small))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(spacing.extraSmall),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (match.isLive) {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = RoundedCornerShape(6.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.feat_epg_sports_live_badge),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                    Text(
                        text = if (match.start > 0L) {
                            formatTimeRange(match.start, match.end)
                        } else {
                            stringResource(R.string.feat_epg_sports_unscheduled)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (!match.homeTeam.isNullOrBlank() && !match.awayTeam.isNullOrBlank()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(spacing.small),
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            shape = RoundedCornerShape(12.dp),
                        )
                        .padding(horizontal = spacing.medium, vertical = spacing.small),
                ) {
                    Text(
                        text = match.homeTeam.orEmpty(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text(
                            text = "VS",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                    Text(
                        text = match.awayTeam.orEmpty(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            } else {
                Text(
                    text = match.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (match.sources.size > 1) {
                Text(
                    text = stringResource(R.string.feat_epg_sports_sources_count, match.sources.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(spacing.small),
                modifier = Modifier.fillMaxWidth(),
            ) {
                items(
                    items = match.sources,
                    key = { it.channelId },
                ) { source ->
                    val label = listOfNotNull(
                        source.channelTitle,
                        source.qualityLabel,
                    ).joinToString(" · ")
                    FilterChip(
                        selected = source.channelId == selectedSource.channelId,
                        onClick = { onSelectSource(source) },
                        label = {
                            Text(
                                text = label,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                    )
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(spacing.small),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                FilledTonalButton(
                    onClick = onWatch,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.feat_epg_sports_watch))
                }
                OutlinedButton(
                    onClick = onCast,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        imageVector = if (castConnected) {
                            Icons.Rounded.CastConnected
                        } else {
                            Icons.Rounded.Cast
                        },
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.feat_epg_sports_cast))
                }
                IconButton(onClick = onOpenExternal) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                        contentDescription = stringResource(R.string.feat_epg_sports_external),
                    )
                }
            }
        }
    }
}

@Composable
private fun SportsCastRoutePickerBridge(
    onReady: (() -> Unit) -> Unit,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        factory = { context ->
            MediaRouteButton(context).apply {
                CastButtonFactory.setUpMediaRouteButton(context, this)
                onReady { performClick() }
            }
        },
        modifier = modifier,
    )
}

private fun SportPreset.labelRes(): Int = when (this) {
    SportPreset.FOOTBALL -> R.string.feat_epg_preset_football
    SportPreset.ALL_SPORTS -> R.string.feat_epg_preset_all_sports
    SportPreset.BASKETBALL -> R.string.feat_epg_preset_basketball
    SportPreset.MOTORSPORT -> R.string.feat_epg_preset_motorsport
    SportPreset.COMBAT -> R.string.feat_epg_preset_combat
    SportPreset.TENNIS -> R.string.feat_epg_preset_tennis
    SportPreset.CRICKET -> R.string.feat_epg_preset_cricket
}
