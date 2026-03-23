package com.m3u.tv.screens.foryou

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.CompactCard
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.m3u.business.foryou.ForyouViewModel
import com.m3u.business.foryou.Recommend
import com.m3u.core.foundation.components.AbsoluteSmoothCornerShape
import com.m3u.core.foundation.ui.SugarColors
import com.m3u.data.database.model.Channel
import com.m3u.data.database.model.DataSource
import com.m3u.data.database.model.Playlist
import com.m3u.i18n.R
import com.m3u.tv.common.PlaylistContentType
import com.m3u.tv.common.contentType
import com.m3u.tv.screens.dashboard.rememberChildPadding
import com.m3u.tv.theme.LexendExa
import kotlinx.coroutines.launch

@Composable
fun ForyouScreen(
    navigateToPlaylist: (playlistUrl: String) -> Unit,
    navigateToChannel: (channelId: Int) -> Unit,
    navigateToChannelDetail: (channelId: Int) -> Unit,
    onScroll: (isTopBarVisible: Boolean) -> Unit,
    isTopBarVisible: Boolean,
    viewModel: ForyouViewModel = hiltViewModel(),
) {
    val coroutineScope = rememberCoroutineScope()
    val playlists: Map<Playlist, Int> by viewModel.playlists.collectAsStateWithLifecycle()
    val specs: List<Recommend.Spec> by viewModel.specs.collectAsStateWithLifecycle()
    Box(Modifier.fillMaxSize()) {
        Catalog(
            playlists = playlists,
            specs = specs,
            onScroll = onScroll,
            navigateToPlaylist = navigateToPlaylist,
            onSpecChannelClick = { channel ->
                coroutineScope.launch {
                    val playlist = viewModel.getPlaylist(channel.playlistUrl)
                    if (playlist == null || playlist.contentType().opensDetails) {
                        navigateToChannelDetail(channel.id)
                    } else {
                        navigateToChannel(channel.id)
                    }
                }
            },
            isTopBarVisible = isTopBarVisible,
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
private fun Catalog(
    playlists: Map<Playlist, Int>,
    specs: List<Recommend.Spec>,
    onScroll: (isTopBarVisible: Boolean) -> Unit,
    navigateToPlaylist: (playlistUrl: String) -> Unit,
    onSpecChannelClick: (channel: Channel) -> Unit,
    modifier: Modifier = Modifier,
    isTopBarVisible: Boolean = true,
) {

    val lazyListState = rememberLazyListState()
    val childPadding = rememberChildPadding()

    val shouldShowTopBar by remember {
        derivedStateOf {
            lazyListState.firstVisibleItemIndex == 0 &&
                    lazyListState.firstVisibleItemScrollOffset < 300
        }
    }

    LaunchedEffect(shouldShowTopBar) {
        onScroll(shouldShowTopBar)
    }
    LaunchedEffect(isTopBarVisible) {
        if (isTopBarVisible) lazyListState.animateScrollToItem(0)
    }

    LazyColumn(
        state = lazyListState,
        contentPadding = PaddingValues(bottom = 108.dp),
        modifier = modifier
    ) {
        if (specs.isNotEmpty()) {
            item(contentType = "FeaturedChannelsCarousel") {
                FeaturedSpecsCarousel(
                    specs = specs,
                    padding = childPadding,
                    onClickSpec = { spec ->
                        when (spec) {
                            is Recommend.UnseenSpec -> onSpecChannelClick(spec.channel)

                            is Recommend.DiscoverSpec -> TODO()
                            is Recommend.NewRelease -> TODO()
                            is Recommend.CwSpec -> onSpecChannelClick(spec.channel)
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(324.dp)
                    /*
                     Setting height for the FeaturedChannelCarousel to keep it rendered with same height,
                     regardless of the top bar's visibility
                     */
                )
            }
        }

        val allEntries = playlists.entries
            .filter { (playlist, count) ->
                count > 0 && playlist.source != DataSource.EPG
            }
            .sortedBy { (playlist, _) -> playlist.title.lowercase() }
        PlaylistContentType.entries.forEach { contentType ->
            val entries = allEntries.filter { (playlist, _) ->
                playlist.contentType() == contentType
            }
            if (entries.isNotEmpty()) {
                item(contentType = "PlaylistsRow-${contentType.name}") {
                    PlaylistSectionRow(
                        contentType = contentType,
                        entries = entries,
                        navigateToPlaylist = navigateToPlaylist,
                    )
                }
            }
        }
    }
}

@Composable
private fun PlaylistSectionRow(
    contentType: PlaylistContentType,
    entries: List<Map.Entry<Playlist, Int>>,
    navigateToPlaylist: (playlistUrl: String) -> Unit,
) {
    val startPadding: Dp = rememberChildPadding().start
    val endPadding: Dp = rememberChildPadding().end
    val shape = AbsoluteSmoothCornerShape(16.dp, 100)
    Column(
        modifier = Modifier.padding(top = 16.dp)
    ) {
        Text(
            text = stringResource(contentType.sectionTitleResId),
            style = MaterialTheme.typography.headlineLarge.copy(
                fontWeight = FontWeight.SemiBold,
                fontSize = 30.sp
            ),
            modifier = Modifier
                .padding(start = startPadding, end = endPadding)
                .padding(bottom = 16.dp)
        )
        LazyRow(
            modifier = Modifier.focusGroup(),
            contentPadding = PaddingValues(start = startPadding, end = endPadding)
        ) {
            items(entries.size) { index ->
                val (playlist, count) = entries[index]
                val (color, _) = remember {
                    SugarColors.entries.random()
                }
                CompactCard(
                    onClick = { navigateToPlaylist(playlist.url) },
                    title = {
                        Column(
                            modifier = Modifier.padding(16.dp)
                        ) {
                            Text(
                                text = playlist.title,
                                fontSize = 32.sp,
                                lineHeight = 32.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = LexendExa,
                                maxLines = 2
                            )
                            Text(
                                text = stringResource(R.string.ui_playlist_item_count, count),
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.padding(top = 12.dp)
                            )
                        }
                    },
                    colors = CardDefaults.compactCardColors(
                        containerColor = color,
                        contentColor = MaterialTheme.colorScheme.background
                    ),
                    shape = CardDefaults.shape(shape),
                    border = CardDefaults.border(
                        border = Border(
                            BorderStroke(
                                width = 2.dp,
                                color = MaterialTheme.colorScheme.border
                            ),
                            shape = shape
                        ),
                        focusedBorder = Border(
                            BorderStroke(width = 4.dp, color = Color.White),
                            shape = shape
                        ),
                        pressedBorder = Border(
                            BorderStroke(
                                width = 4.dp,
                                color = MaterialTheme.colorScheme.border
                            ),
                            shape = shape
                        )
                    ),
                    image = {},
                    modifier = Modifier
                        .width(320.dp)
                        .heightIn(min = 156.dp)
                )
            }
        }
    }
}
