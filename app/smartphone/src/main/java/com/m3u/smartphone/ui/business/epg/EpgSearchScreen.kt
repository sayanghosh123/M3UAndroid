package com.m3u.smartphone.ui.business.epg

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
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.LazyPagingItems
import com.m3u.data.database.model.ProgrammeSearchResult
import com.m3u.i18n.R
import com.m3u.smartphone.ui.common.helper.LocalHelper
import com.m3u.smartphone.ui.common.helper.Metadata
import com.m3u.smartphone.ui.material.model.LocalSpacing
import com.m3u.data.service.MediaCommand
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

@Composable
fun EpgSearchRoute(
    contentPadding: PaddingValues,
    navigateToChannel: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: EpgSearchViewModel = hiltViewModel(),
) {
    val title = stringResource(R.string.ui_destination_epg_search)
    val query by viewModel.query.collectAsStateWithLifecycle()
    val category by viewModel.category.collectAsStateWithLifecycle()
    val results = viewModel.results.collectAsLazyPagingItems()
    val helper = LocalHelper.current
    val scope = rememberCoroutineScope()

    LifecycleResumeEffect(title) {
        Metadata.title = AnnotatedString(title)
        Metadata.color = Color.Unspecified
        Metadata.contentColor = Color.Unspecified
        onPauseOrDispose { }
    }

    EpgSearchScreen(
        query = query,
        category = category,
        results = results,
        contentPadding = contentPadding,
        modifier = modifier,
        onQueryChanged = { viewModel.query.value = it },
        onCategoryChanged = { viewModel.category.value = it },
        onWatch = {
            scope.launch {
                if (helper.play(MediaCommand.Common(it.channelId))) {
                    navigateToChannel()
                }
            }
        },
    )
}

@Composable
private fun EpgSearchScreen(
    query: String,
    category: String,
    results: LazyPagingItems<ProgrammeSearchResult>,
    contentPadding: PaddingValues,
    onQueryChanged: (String) -> Unit,
    onCategoryChanged: (String) -> Unit,
    onWatch: (ProgrammeSearchResult) -> Unit,
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
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.small)) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChanged,
                label = { Text(stringResource(R.string.feat_epg_search_query)) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = category,
                onValueChange = onCategoryChanged,
                label = { Text(stringResource(R.string.feat_epg_search_category)) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }
        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            items(results.itemCount) { index ->
                val result = results[index]
                result ?: return@items
                ProgrammeItem(result = result, onWatch = { onWatch(result) })
            }
        }
    }
}

@Composable
private fun ProgrammeItem(
    result: ProgrammeSearchResult,
    onWatch: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(result.programme.title) },
        supportingContent = {
            Text(
                "${result.channelTitle} · ${result.playlistTitle}\n" +
                        formatTimeRange(result.programme.start, result.programme.end)
            )
        },
        trailingContent = {
            IconButton(onClick = onWatch) {
                Icon(
                    Icons.Rounded.PlayArrow,
                    contentDescription = stringResource(R.string.feat_epg_watch),
                )
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

internal fun formatTimeRange(start: Long, end: Long): String {
    val formatter = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
    return "${formatter.format(Instant.ofEpochMilli(start))}–${formatter.format(Instant.ofEpochMilli(end))}"
}
