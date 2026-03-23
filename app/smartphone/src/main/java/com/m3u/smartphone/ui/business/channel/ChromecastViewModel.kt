package com.m3u.smartphone.ui.business.channel

import android.content.Context
import androidx.annotation.StringRes
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.m3u.data.database.model.Channel
import com.m3u.data.database.model.Playlist
import com.m3u.data.service.Messager
import com.m3u.data.service.PlayerManager
import com.m3u.i18n.R.string
import com.m3u.smartphone.cast.GoogleCastManager
import com.m3u.smartphone.cast.GoogleCastSessionState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ChromecastViewModel @Inject constructor(
    private val playerManager: PlayerManager,
    private val messager: Messager,
    private val googleCastManager: GoogleCastManager,
    @param:ApplicationContext private val context: Context
) : ViewModel() {
    val castState: StateFlow<GoogleCastSessionState> = googleCastManager.sessionState

    init {
        observeCastRequests()
    }

    fun openGoogleCastDevices(onOpenDialog: () -> Unit) {
        val castState = castState.value
        if (!castState.isAvailable) {
            emitMessage(string.feat_channel_cast_error_unavailable)
            return
        }
        if (castState.isConnected) {
            onOpenDialog()
            return
        }

        val channel = playerManager.channel.value
        if (channel == null) {
            emitMessage(string.feat_channel_cast_error_no_channel)
            return
        }
        val playlist = playerManager.playlist.value
        when (val validation = validate(channel, playlist)) {
            is CastValidation.Blocked -> {
                emitMessage(validation.messageResId)
                return
            }

            is CastValidation.Warning -> emitMessage(validation.messageResId)
            CastValidation.Ready -> Unit
        }
        onOpenDialog()
    }

    private fun observeCastRequests() {
        viewModelScope.launch {
            combine(
                googleCastManager.sessionState,
                playerManager.channel,
                playerManager.playlist
            ) { castState, channel, playlist ->
                CastRequest(
                    castState = castState,
                    channel = channel,
                    playlist = playlist
                )
            }.collect { request ->
                if (!request.castState.isConnected) {
                    googleCastManager.clearLastLoadRequest()
                    return@collect
                }

                val channel = request.channel ?: return@collect
                val requestKey = "${request.castState.sessionNonce}:${channel.url}"
                if (!googleCastManager.shouldLoad(requestKey)) return@collect

                when (val validation = validate(channel, request.playlist)) {
                    is CastValidation.Blocked -> {
                        emitMessage(validation.messageResId)
                        return@collect
                    }

                    is CastValidation.Warning -> emitMessage(validation.messageResId)
                    CastValidation.Ready -> Unit
                }

                googleCastManager.markLoadRequested(requestKey)
                googleCastManager.load(
                    channel = channel,
                    playlist = request.playlist,
                    startPositionMs = playerManager.player.value?.currentPosition ?: 0L,
                    onSuccess = { deviceName ->
                        playerManager.pauseOrContinue(false)
                        emitMessage(
                            string.feat_channel_cast_started,
                            deviceName ?: request.castState.deviceName ?: "Chromecast"
                        )
                    },
                    onFailure = { reason ->
                        googleCastManager.clearLastLoadRequest()
                        if (reason.isNullOrBlank()) {
                            emitMessage(string.feat_channel_cast_error_load)
                        } else {
                            emitMessage(string.feat_channel_cast_error_load_with_reason, reason)
                        }
                    }
                )
            }
        }
    }

    private fun validate(channel: Channel, playlist: Playlist?): CastValidation {
        val streamUri = channel.url.toUri()
        if (streamUri.scheme !in setOf("http", "https")) {
            return CastValidation.Blocked(string.feat_channel_cast_error_non_http)
        }
        if (!channel.licenseKey.isNullOrBlank()) {
            return CastValidation.Blocked(string.feat_channel_cast_error_drm)
        }
        if (!playlist?.userAgent.isNullOrBlank()) {
            return CastValidation.Warning(string.feat_channel_cast_warning_user_agent)
        }
        return CastValidation.Ready
    }

    private fun emitMessage(@StringRes resId: Int, vararg args: Any) {
        messager.emit(context.getString(resId, *args))
    }
}

private data class CastRequest(
    val castState: GoogleCastSessionState,
    val channel: Channel?,
    val playlist: Playlist?
)

private sealed interface CastValidation {
    data object Ready : CastValidation
    data class Warning(@param:StringRes val messageResId: Int) : CastValidation
    data class Blocked(@param:StringRes val messageResId: Int) : CastValidation
}
