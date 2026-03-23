package com.m3u.tv.screens.playlist

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Star
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.IconButton
import androidx.tv.material3.IconButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.m3u.data.database.model.Channel
import com.m3u.tv.screens.dashboard.rememberChildPadding
import com.m3u.tv.theme.JetStreamButtonShape
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChannelDetail(
    channel: Channel,
    contentTypeLabel: String,
    summary: String,
    primaryActionLabel: String,
    primaryActionEnabled: Boolean,
    onPrimaryAction: () -> Unit,
    updateFavorite: () -> Unit,
) {
    val childPadding = rememberChildPadding()
    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    val coroutineScope = rememberCoroutineScope()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(432.dp)
            .bringIntoViewRequester(bringIntoViewRequester)
    ) {
        ChannelImageWithGradients(
            channel = channel,
            modifier = Modifier.fillMaxSize()
        )

        Column(modifier = Modifier.fillMaxWidth(0.58f)) {
            Spacer(modifier = Modifier.height(108.dp))
            Column(
                modifier = Modifier.padding(start = childPadding.start)
            ) {
                ContentTypeLabel(text = contentTypeLabel)
                ChannelLargeTitle(channelTitle = channel.title)

                if (summary.isNotBlank()) {
                    ChannelDescription(description = summary)
                }
                PrimaryActionsRow(
                    channel = channel,
                    primaryActionLabel = primaryActionLabel,
                    primaryActionEnabled = primaryActionEnabled,
                    onPrimaryAction = onPrimaryAction,
                    updateFavorite = updateFavorite,
                    modifier = Modifier.onFocusChanged {
                        if (it.isFocused) {
                            coroutineScope.launch { bringIntoViewRequester.bringIntoView() }
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun PrimaryActionsRow(
    channel: Channel,
    primaryActionLabel: String,
    primaryActionEnabled: Boolean,
    onPrimaryAction: () -> Unit,
    updateFavorite: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.padding(top = 24.dp),
    ) {
        Button(
            onClick = onPrimaryAction,
            enabled = primaryActionEnabled,
            contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
            shape = ButtonDefaults.shape(shape = JetStreamButtonShape)
        ) {
            Icon(
                imageVector = Icons.Outlined.PlayArrow,
                contentDescription = null
            )
            Spacer(Modifier.size(16.dp))
            Text(
                text = primaryActionLabel,
                style = MaterialTheme.typography.titleSmall
            )
        }
        Spacer(Modifier.size(16.dp))
        IconButton(
            onClick = updateFavorite,
            shape = ButtonDefaults.shape(shape = JetStreamButtonShape),
            colors = IconButtonDefaults.colors(
                contentColor = if (channel.favourite) Color(0xffffcd3c)
                else MaterialTheme.colorScheme.onSurface,
                focusedContentColor = if (channel.favourite) Color(0xffffcd3c)
                else MaterialTheme.colorScheme.inverseOnSurface
            )
        ) {
            Icon(
                imageVector = Icons.Outlined.Star,
                contentDescription = null
            )
        }
    }
}

@Composable
private fun ContentTypeLabel(
    text: String,
) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelLarge.copy(
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.2.sp
        ),
        modifier = Modifier
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                shape = JetStreamButtonShape
            )
            .padding(horizontal = 12.dp, vertical = 6.dp)
    )
}

@Composable
private fun ChannelDescription(description: String) {
    Text(
        text = description,
        style = MaterialTheme.typography.titleSmall.copy(
            fontSize = 15.sp,
            fontWeight = FontWeight.Normal
        ),
        modifier = Modifier.padding(top = 16.dp),
        maxLines = 4
    )
}

@Composable
private fun ChannelLargeTitle(channelTitle: String) {
    Text(
        text = channelTitle,
        style = MaterialTheme.typography.displayMedium.copy(
            fontWeight = FontWeight.Bold
        ),
        modifier = Modifier.padding(top = 16.dp),
        maxLines = 2
    )
}

@Composable
private fun ChannelImageWithGradients(
    channel: Channel,
    modifier: Modifier = Modifier,
    gradientColor: Color = MaterialTheme.colorScheme.surface,
) {
    AsyncImage(
        model = ImageRequest.Builder(LocalContext.current)
            .data(channel.cover)
            .crossfade(true)
            .build(),
        contentDescription = channel.title,
        contentScale = ContentScale.Crop,
        modifier = modifier.drawWithContent {
            drawContent()
            drawRect(
                Brush.verticalGradient(
                    colors = listOf(Color.Transparent, gradientColor),
                    startY = 600f
                )
            )
            drawRect(
                Brush.horizontalGradient(
                    colors = listOf(gradientColor, Color.Transparent),
                    endX = 1000f,
                    startX = 300f
                )
            )
            drawRect(
                Brush.linearGradient(
                    colors = listOf(gradientColor, Color.Transparent),
                    start = Offset(x = 500f, y = 500f),
                    end = Offset(x = 1000f, y = 0f)
                )
            )
        }
    )
}
