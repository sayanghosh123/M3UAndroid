package com.m3u.smartphone.cast

import android.net.Uri
import android.webkit.MimeTypeMap
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.common.images.WebImage
import com.m3u.data.database.model.Channel
import com.m3u.data.database.model.DataSource
import com.m3u.data.database.model.Playlist
import com.m3u.data.database.model.isSeries
import com.m3u.data.database.model.isVod
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

data class GoogleCastSessionState(
    val isAvailable: Boolean = false,
    val isConnected: Boolean = false,
    val deviceName: String? = null,
    val sessionNonce: Long = 0L
)

@Singleton
class GoogleCastManager @Inject constructor() {
    private val castContext = GoogleCastBootstrap.get()
    private val sessionManager = castContext?.sessionManager
    private var lastLoadedRequestKey: String? = null

    private val _sessionState = MutableStateFlow(
        GoogleCastSessionState(
            isAvailable = castContext != null
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
            syncSessionState(bumpNonce = true)
        }

        override fun onSessionEnding(session: CastSession) {
            syncSessionState()
        }

        override fun onSessionEnded(session: CastSession, error: Int) {
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
        sessionManager?.addSessionManagerListener(
            sessionManagerListener,
            CastSession::class.java
        )
        syncSessionState()
    }

    fun load(
        channel: Channel,
        playlist: Playlist?,
        startPositionMs: Long,
        onSuccess: (String?) -> Unit,
        onFailure: (String?) -> Unit
    ): Boolean {
        val session = sessionManager?.currentCastSession
        val remoteMediaClient = session?.remoteMediaClient
        if (session == null || remoteMediaClient == null) {
            onFailure(null)
            return false
        }

        val requestData = MediaLoadRequestData.Builder()
            .setMediaInfo(channel.toCastMediaInfo(playlist))
            .setAutoplay(true)
            .setCurrentTime(startPositionMs.coerceAtLeast(0L))
            .build()
        remoteMediaClient.load(requestData)
            .setResultCallback { result ->
                if (result.status.isSuccess) {
                    onSuccess(session.castDevice?.friendlyName)
                } else {
                    Timber.w(
                        "Google Cast load failed with status code %s: %s",
                        result.status.statusCode,
                        result.status.statusMessage
                    )
                    onFailure(result.status.statusMessage)
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
            isAvailable = castContext != null,
            isConnected = currentSession?.isConnected == true,
            deviceName = currentSession?.castDevice?.friendlyName,
            sessionNonce = if (bumpNonce) _sessionState.value.sessionNonce + 1 else _sessionState.value.sessionNonce
        )
    }
}

private fun Channel.toCastMediaInfo(playlist: Playlist?): MediaInfo {
    return MediaInfo.Builder(url)
        .setStreamType(playlist.castStreamType())
        .setContentType(castMimeType(playlist))
        .setMetadata(createCastMetadata(playlist))
        .build()
}

private fun Channel.createCastMetadata(playlist: Playlist?): MediaMetadata {
    val mediaType = when {
        playlist?.isSeries == true -> MediaMetadata.MEDIA_TYPE_TV_SHOW
        playlist?.isVod == true -> MediaMetadata.MEDIA_TYPE_MOVIE
        else -> MediaMetadata.MEDIA_TYPE_GENERIC
    }
    return MediaMetadata(mediaType).apply {
        putString(MediaMetadata.KEY_TITLE, title)
        playlist?.title?.takeIf { it.isNotBlank() }?.let {
            putString(MediaMetadata.KEY_SUBTITLE, it)
        }
        cover.asWebImageOrNull()?.let(::addImage)
    }
}

private fun Playlist?.castStreamType(): Int {
    return when {
        this?.isSeries == true || this?.isVod == true -> MediaInfo.STREAM_TYPE_BUFFERED
        else -> MediaInfo.STREAM_TYPE_LIVE
    }
}

private fun Channel.castMimeType(playlist: Playlist?): String {
    val uri = Uri.parse(url)
    val extension = listOfNotNull(
        uri.getQueryParameter("extension"),
        uri.getQueryParameter("container"),
        uri.getQueryParameter("format"),
        uri.getQueryParameter("output"),
        uri.lastPathSegment
            ?.substringAfterLast('.', "")
            ?.takeIf { it.isNotBlank() },
        MimeTypeMap.getFileExtensionFromUrl(url)
    )
        .firstOrNull {
            it.isNotBlank() && !it.equals("php", ignoreCase = true)
        }
        .orEmpty()
        .lowercase(Locale.ROOT)
    return when (extension) {
        "m3u8" -> "application/x-mpegURL"
        "mpd" -> "application/dash+xml"
        "ism", "isml" -> "application/vnd.ms-sstr+xml"
        "mp4", "m4v" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "mp3" -> "audio/mpeg"
        "aac" -> "audio/aac"
        "ts", "m2ts" -> "video/mp2t"
        else -> when {
            playlist?.source == DataSource.Xtream && !playlist.isVod && !playlist.isSeries -> "video/mp2t"
            playlist?.isVod == true || playlist?.isSeries == true -> "video/mp4"
            url.contains("m3u8", ignoreCase = true) -> "application/x-mpegURL"
            else -> "application/x-mpegURL"
        }
    }
}

private fun String?.asWebImageOrNull(): WebImage? {
    val uri = this?.takeIf { value ->
        value.startsWith("http://", ignoreCase = true) ||
                value.startsWith("https://", ignoreCase = true)
    }?.let(Uri::parse)
    return uri?.let(::WebImage)
}
