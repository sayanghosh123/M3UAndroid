package com.m3u.smartphone.ui.business.epg

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.m3u.core.util.context.toast
import com.m3u.data.service.MediaCommand
import com.m3u.i18n.R
import com.m3u.smartphone.ui.common.helper.LocalHelper
import com.m3u.smartphone.ui.material.model.LocalSpacing
import kotlinx.coroutines.launch

@Composable
fun SportsRoute(
    contentPadding: PaddingValues,
    navigateToChannel: () -> Unit,
    viewModel: SportsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val helper = LocalHelper.current
    val scope = rememberCoroutineScope()
    val mode by viewModel.mode.collectAsStateWithLifecycle()
    val items by viewModel.items.collectAsStateWithLifecycle()
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

    SportsScreen(
        mode = mode,
        items = items,
        contentPadding = contentPadding,
        onModeChanged = { viewModel.mode.value = it },
        onImport = { importLauncher.launch(arrayOf("application/json", "text/json")) },
        onClear = {
            scope.launch {
                viewModel.clearImportedEvents()
                context.toast(context.getString(R.string.feat_epg_events_cleared))
            }
        },
        onWatch = {
            scope.launch {
                helper.play(MediaCommand.Common(it.channelId))
                navigateToChannel()
            }
        },
    )
}

@Composable
private fun SportsScreen(
    mode: SportsTimeMode,
    items: List<SportsItem>,
    contentPadding: PaddingValues,
    onModeChanged: (SportsTimeMode) -> Unit,
    onImport: () -> Unit,
    onClear: () -> Unit,
    onWatch: (SportsItem) -> Unit,
) {
    val spacing = LocalSpacing.current
    Column(
        verticalArrangement = Arrangement.spacedBy(spacing.small),
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(horizontal = spacing.medium),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(spacing.small),
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
        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            items(items, key = { "${it.curated}-${it.channelId}-${it.start}-${it.title}" }) { item ->
                ListItem(
                    headlineContent = { Text(item.title) },
                    supportingContent = {
                        Text(
                            listOfNotNull(
                                item.channelTitle,
                                item.category,
                                formatTimeRange(item.start, item.end),
                            ).joinToString(" · ")
                        )
                    },
                    trailingContent = {
                        IconButton(onClick = { onWatch(item) }) {
                            Icon(
                                Icons.Rounded.PlayArrow,
                                contentDescription = stringResource(R.string.feat_epg_watch),
                            )
                        }
                    },
                )
            }
        }
    }
}
