package com.m3u.smartphone.ui.business.channel.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.m3u.core.util.basic.title
import com.m3u.i18n.R.string
import com.m3u.core.foundation.components.CircularProgressIndicator
import androidx.compose.material3.Icon
import com.m3u.smartphone.ui.material.components.mask.MaskState
import com.m3u.smartphone.ui.material.model.LocalSpacing
import com.m3u.smartphone.ui.material.components.UnstableBadge
import com.m3u.smartphone.ui.material.components.UnstableValue
import net.mm2d.upnp.Device

@Composable
internal fun DlnaDevicesBottomSheet(
    isDevicesVisible: Boolean,
    devices: List<Device>,
    searching: Boolean,
    isGoogleCastAvailable: Boolean,
    connectedGoogleCastDeviceName: String?,
    maskState: MaskState,
    onDismiss: () -> Unit,
    connectDlnaDevice: (device: Device) -> Unit,
    openGoogleCastDevices: () -> Unit,
    openInExternalPlayer: () -> Unit,
    modifier: Modifier = Modifier
) {
    val spacing = LocalSpacing.current
    val state = rememberModalBottomSheetState()

    val castTargetsString = stringResource(string.feat_channel_dialog_cast_targets)
    val googleCastString = stringResource(string.feat_channel_dialog_google_cast)
    val openInExternalPlayerString = stringResource(string.feat_channel_open_in_external_app)

    LaunchedEffect(isDevicesVisible) {
        if (isDevicesVisible) state.show()
        else state.hide()
    }
    if (isDevicesVisible) {
        ModalBottomSheet(
            sheetState = state,
            onDismissRequest = onDismiss,
            modifier = modifier,
//            windowInsets = WindowInsets(0)
        ) {
            LaunchedEffect(devices, state.isVisible) {
                if (state.isVisible) state.expand()
            }
            LaunchedEffect(Unit) {
                maskState.sleep()
            }

            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(spacing.small),
                    modifier = Modifier.padding(
                        horizontal = spacing.medium,
                        vertical = spacing.small
                    )
                ) {
                    Text(
                        text = castTargetsString,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                LazyColumn(
                    modifier = Modifier
                        .sizeIn(
                            maxHeight = 320.dp
                        )
                ) {
                    if (isGoogleCastAvailable) {
                        item {
                            ListItem(
                                headlineContent = {
                                    Text(googleCastString)
                                },
                                supportingContent = {
                                    Text(
                                        connectedGoogleCastDeviceName?.let {
                                            stringResource(
                                                string.feat_channel_dialog_google_cast_connected,
                                                it
                                            )
                                        } ?: stringResource(string.feat_channel_dialog_google_cast_hint)
                                    )
                                },
                                trailingContent = {
                                    Icon(
                                        imageVector = Icons.Rounded.Cast,
                                        contentDescription = googleCastString
                                    )
                                },
                                modifier = Modifier.clickable {
                                    openGoogleCastDevices()
                                }
                            )
                        }
                    }
                    item {
                        ListItem(
                            headlineContent = {
                                Text(openInExternalPlayerString.title())
                            },
                            trailingContent = {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                                    contentDescription = openInExternalPlayerString
                                )
                            },
                            modifier = Modifier.clickable {
                                openInExternalPlayer()
                            }
                        )
                    }
                    item {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(spacing.small),
                            modifier = Modifier.padding(
                                horizontal = spacing.medium,
                                vertical = spacing.small
                            )
                        ) {
                            Text(
                                text = stringResource(string.feat_channel_dialog_dlna_devices),
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleSmall,
                            )
                            UnstableBadge(UnstableValue.EXPERIMENTAL)
                            Spacer(modifier = Modifier.weight(1f))
                            AnimatedVisibility(
                                visible = searching
                            ) {
                                CircularProgressIndicator()
                            }
                        }
                    }
                    items(devices) { device ->
                        DlnaDeviceItem(
                            device = device,
                            onClick = { connectDlnaDevice(device) },
                        )
                    }
                    item {
                        Spacer(modifier = Modifier.navigationBarsPadding())
                    }
                }
            }
        }
    }
}
