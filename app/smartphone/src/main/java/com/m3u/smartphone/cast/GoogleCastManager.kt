package com.m3u.smartphone.cast

import androidx.core.net.toUri
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManager
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.common.images.WebImage
import com.m3u.data.cast.CastHttpRelayServer
import com.m3u.data.cast.CastMediaPlan
import com.m3u.data.cast.CastStreamResolver
import com.m3u.data.database.model.Channel
import com.m3u.data.database.model.Playlist
import com.m3u.data.database.model.isSeries
import com.m3u.data.database.model.isVod
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

data class GoogleCastSessionState(
    val isAvailable: Boolean = false,
    val isConnected: Boolean = false,
    val deviceName: String? = null,
    val sessionNonce: Long = 0L
)

@Singleton
class GoogleCastManager @Inject constructor(
    private val castStreamResolver: CastStreamResolver,
    private val castHttpRelayServer: CastHttpRelayServer,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Volatile
    private var attachedContext: CastContext? = null

    @Volatile
    private var sessionManager: SessionManager? = null
    private var lastLoadedRequestKey: String? = null

    private val _sessionState = MutableStateFlow(
        GoogleCastSessionState(
            isAvailable = GoogleCastBootstrap.get() != null
        )
    )
    val sessionState: StateFlow<GoogleCastSessionState> get() = _sessionState.asStateFlow()

    private val sessionManagerListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarting(session: CastSession) {
            syncSessionState()
        }

        override fun onSessionStarted(session: CastSession, sessionId: String) {
            syncSessionState(bumpNonce = true)
        }

        override fun onSessionStartFailed(session: CastSession, error: Int) {
            castHttpRelayServer.clearSessions()
            syncSessionState(bumpNonce = true)
        }

        override fun onSessionEnding(session: CastSession) {
            syncSessionState()
        }

        override fun onSessionEnded(session: CastSession, error: Int) {
            castHttpRelayServer.clearSessions()
            syncSessionState(bumpNonce = true)
        }

        override fun onSessionResuming(session: CastSession, sessionId: String) {
            syncSessionState()
        }

        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {
            syncSessionState(bumpNonce = true)
        }

        override fun onSessionResumeFailed(session: CastSession, error: Int) {
            syncSessionState(bumpNonce = true)
        }

        override fun onSessionSuspended(session: CastSession, reason: Int) {
            syncSessionState()
        }
    }

    init {
        scope.launch {
            GoogleCastBootstrap.castContextFlow.collect { castContext ->
                if (castContext != null && castContext !== attachedContext) {
                    sessionManager?.removeSessionManagerListener(
                        sessionManagerListener,
                        CastSession::class.java
                    )
                    attachedContext = castContext
                    sessionManager = castContext.sessionManager.also { manager ->
                        manager.addSessionManagerListener(
                            sessionManagerListener,
                            CastSession::class.java
                        )
                    }
                }
                syncSessionState()
            }
        }
    }

    suspend fun load(
        channel: Channel,
        playlist: Playlist?,
        startPositionMs: Long,
        onSuccess: (String?) -> Unit,
        onFailure: (String?) -> Unit
    ): Boolean {
        val activeSession = sessionManager?.currentCastSession
        val remoteMediaClient = activeSession?.remoteMediaClient
        if (activeSession == null || remoteMediaClient == null) {
            Timber.e("Google Cast load requested without an active Cast session")
            onFailure(null)
            return false
        }

        val plan = castStreamResolver.resolveForCast(channel, playlist)
            .getOrElse { error ->
                Timber.e(error, "Failed to resolve Cast stream for channel=${channel.id}")
                onFailure(error.localizedMessage)
                return false
            }

        val mediaInfo = plan.toCastMediaInfo(playlist)
        val requestData = MediaLoadRequestData.Builder()
            .setMediaInfo(mediaInfo)
            .setAutoplay(true)
            .setCurrentTime(if (plan.isLive) 0L else startPositionMs.coerceAtLeast(0L))
            .build()

        withContext(Dispatchers.Main.immediate) {
            remoteMediaClient.load(requestData)
                .setResultCallback { result ->
                    if (result.status.isSuccess) {
                        onSuccess(activeSession.castDevice?.friendlyName)
                    } else {
                        Timber.e(
                            "Google Cast load failed with status code %s: %s",
                            result.status.statusCode,
                            result.status.statusMessage
                        )
                        onFailure(result.status.statusMessage)
                    }
                }
        }
        return true
    }

    fun shouldLoad(requestKey: String): Boolean = requestKey != lastLoadedRequestKey

    fun markLoadRequested(requestKey: String) {
        lastLoadedRequestKey = requestKey
    }

    fun clearLastLoadRequest() {
        lastLoadedRequestKey = null
    }

    private fun syncSessionState(bumpNonce: Boolean = false) {
        val currentSession = sessionManager?.currentCastSession
        if (currentSession?.isConnected != true) {
            lastLoadedRequestKey = null
        }
        _sessionState.value = _sessionState.value.copy(
            isAvailable = attachedContext != null || GoogleCastBootstrap.get() != null,
            isConnected = currentSession?.isConnected == true,
            deviceName = currentSession?.castDevice?.friendlyName,
            sessionNonce = if (bumpNonce) _sessionState.value.sessionNonce + 1 else _sessionState.value.sessionNonce
        )
    }
}

private fun CastMediaPlan.toCastMediaInfo(playlist: Playlist?): MediaInfo {
    val builder = MediaInfo.Builder(castUrl)
        .setStreamType(if (isLive) MediaInfo.STREAM_TYPE_LIVE else MediaInfo.STREAM_TYPE_BUFFERED)
        .setContentType(contentType)
        .setMetadata(createCastMetadata(playlist))
    if (isLive) {
        builder.setStreamDuration(MediaInfo.UNKNOWN_DURATION)
    }
    return builder.build()
}

private fun CastMediaPlan.createCastMetadata(playlist: Playlist?): MediaMetadata {
    val mediaType = when {
        playlist?.isSeries == true -> MediaMetadata.MEDIA_TYPE_TV_SHOW
        playlist?.isVod == true -> MediaMetadata.MEDIA_TYPE_MOVIE
        else -> MediaMetadata.MEDIA_TYPE_GENERIC
    }
    return MediaMetadata(mediaType).apply {
        putString(MediaMetadata.KEY_TITLE, title)
        subtitle?.takeIf { it.isNotBlank() }?.let {
            putString(MediaMetadata.KEY_SUBTITLE, it)
        }
        coverUrl?.let { url ->
            addImage(WebImage(url.toUri()))
        }
    }
}
