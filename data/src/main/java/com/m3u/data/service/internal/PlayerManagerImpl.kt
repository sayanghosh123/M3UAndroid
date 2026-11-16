package com.m3u.data.service.internal

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.datasource.rtmp.RtmpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.MediaExtractorCompat
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.drm.HttpMediaDrmCallback
import androidx.media3.exoplayer.drm.LocalMediaDrmCallback
import androidx.media3.exoplayer.hls.DefaultHlsExtractorFactory
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.trackselection.TrackSelector
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS
import androidx.media3.muxer.FragmentedMp4Muxer
import androidx.media3.muxer.Mp4Muxer
import androidx.media3.session.MediaSession
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.InAppFragmentedMp4Muxer
import androidx.media3.transformer.InAppMp4Muxer
import androidx.media3.transformer.TransformationRequest
import androidx.media3.transformer.Transformer
import com.m3u.core.foundation.architecture.Publisher
import com.m3u.core.foundation.architecture.preferences.PreferencesKeys
import com.m3u.core.foundation.architecture.preferences.ReconnectMode
import com.m3u.core.foundation.architecture.preferences.Settings
import com.m3u.core.foundation.architecture.preferences.get
import com.m3u.data.SSLs
import com.m3u.data.api.OkhttpClient
import com.m3u.data.api.ProviderOkhttpClient
import com.m3u.data.database.model.Channel
import com.m3u.data.database.model.Playlist
import com.m3u.data.database.model.copyXtreamEpisode
import com.m3u.data.database.model.copyXtreamSeries
import com.m3u.data.repository.channel.ChannelRepository
import com.m3u.data.repository.playlist.PlaylistRepository
import com.m3u.data.repository.providerAccountIdOrNull
import com.m3u.data.repository.provider.ProviderOperationException
import com.m3u.data.repository.provider.ProviderPlaybackCloseReason
import com.m3u.data.repository.provider.ProviderPlaybackSession
import com.m3u.data.repository.provider.SubscriptionProviderRepository
import com.m3u.data.service.ExternalPlaybackTarget
import com.m3u.data.service.MediaCommand
import com.m3u.data.service.PlayerManager
import com.m3u.data.service.PlayerTelemetrySnapshot
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.http.Url
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds

class PlayerManagerImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    @OkhttpClient(false) private val okHttpClient: OkHttpClient,
    @param:ProviderOkhttpClient private val providerOkHttpClient: OkHttpClient,
    private val playlistRepository: PlaylistRepository,
    private val channelRepository: ChannelRepository,
    private val subscriptionProviderRepository: SubscriptionProviderRepository,
    private val cache: Cache,
    private val settings: Settings,
    private val publisher: Publisher,
) : PlayerManager, MediaSession.Callback {
    private val timber = Timber.tag("PlayerManagerImpl")
    private val mainCoroutineScope = CoroutineScope(Dispatchers.Main)
    private val ioCoroutineScope = CoroutineScope(Dispatchers.IO)

    private val channelPreferenceProvider = ChannelPreferenceProvider(
        directory = context.cacheDir.resolve("channel-preferences"),
        appVersion = publisher.versionCode
    )

    private val continueWatchingCondition = ContinueWatchingCondition.getInstance<Player>()

    override val player = MutableStateFlow<ExoPlayer?>(null)
    override val size = MutableStateFlow(Rect())

    private val mediaCommand = MutableStateFlow<MediaCommand?>(null)
    private val playbackLifecycleMutex = Mutex()
    private val providerSessionState = ProviderPlaybackSessionState()
    private val providerSessionCloseQueue = ProviderSessionCloseQueue(ioCoroutineScope)
    private val playerLifecycleLock = Any()
    private var activePlayerListener: Player.Listener? = null
    private var activePlayerGeneration: Long? = null
    private var activeUserAgent: String? = null
    private var activeRequestHeaders: Map<String, String> = emptyMap()
    private var activeProviderPlayback = false
    private var activeProviderPlaybackAllowsCrossOrigin = false
    private var preferSoftwareDecoder = false
    private var transientRetryAttempt = 0
    private var behindLiveWindowAttempts = 0
    private var reconnectRetryJob: Job? = null

    private fun defaultUserAgent(): String = PlaybackResilience.DEFAULT_USER_AGENT

    override val playlist: StateFlow<Playlist?> = mediaCommand.flatMapLatest { command ->
        when (command) {
            is MediaCommand.Common -> {
                val channel = channelRepository.get(command.channelId)
                channel?.let { playlistRepository.observe(it.playlistUrl) } ?: flow { }
            }

            is MediaCommand.XtreamEpisode -> {
                val channel = channelRepository.get(command.channelId)
                channel?.let {
                    playlistRepository
                        .observe(it.playlistUrl)
                        .map { prev -> prev?.copyXtreamSeries(channel) }
                } ?: flowOf(null)
            }

            null -> flowOf(null)
        }
    }
        .stateIn(
            scope = ioCoroutineScope,
            initialValue = null,
            started = SharingStarted.WhileSubscribed(5_000L)
        )

    override val channel: StateFlow<Channel?> = mediaCommand
        .onEach { timber.d("received media command: $it") }
        .flatMapLatest { command ->
            when (command) {
                is MediaCommand.Common -> channelRepository.observe(command.channelId)
                is MediaCommand.XtreamEpisode -> channelRepository
                    .observe(command.channelId)
                    .map { it?.copyXtreamEpisode(command.episode) }

                else -> flowOf(null)
            }
        }
        .stateIn(
            scope = ioCoroutineScope,
            initialValue = null,
            started = SharingStarted.WhileSubscribed(5_000L)
        )

    override val playbackState = MutableStateFlow<@Player.State Int>(Player.STATE_IDLE)
    override val playbackException = MutableStateFlow<PlaybackException?>(null)
    override val isPlaying = MutableStateFlow(false)
    override val telemetrySnapshot = MutableStateFlow(PlayerTelemetrySnapshot())
    override val tracksGroups = MutableStateFlow<List<Tracks.Group>>(emptyList())

    private val playbackPosition = MutableStateFlow(-1L)
    private val playbackDuration = MutableStateFlow(-1L)
    private val playbackStateEvent = MutableStateFlow(
        PlaybackStateEvent(
            generation = 0L,
            state = Player.STATE_IDLE,
        )
    )

    init {
        mainCoroutineScope.launch {
            playbackStateEvent.collectLatest { event ->
                if (!providerSessionState.isCurrent(event.generation)) return@collectLatest
                timber.d("onPlaybackStateChanged: ${event.state}")
                when (event.state) {
                    Player.STATE_IDLE -> onPlaybackIdle()
                    Player.STATE_BUFFERING -> onPlaybackBuffering()
                    Player.STATE_READY -> onPlaybackReady(event.generation)
                    Player.STATE_ENDED -> onPlaybackEnded(event.generation)
                }
            }
        }
        mainCoroutineScope.launch {
            var telemetrySampleTick = 0
            while (true) {
                ensureActive()
                val activePlayer = player.value
                playbackPosition.value = activePlayer?.currentPosition ?: -1L
                playbackDuration.value = activePlayer?.duration?.takeIf { it > 0L } ?: -1L
                telemetrySampleTick++
                if (activePlayer != null && telemetrySampleTick % 2 == 0) {
                    updateBufferMetricsSnapshot(activePlayer)
                }
                delay(1.seconds)
            }
        }
    }

    override suspend fun play(
        command: MediaCommand,
        applyContinueWatching: Boolean
    ) {
        val generation = providerSessionState.beginGeneration()
        playbackStateEvent.value = PlaybackStateEvent(
            generation = generation.value,
            state = Player.STATE_IDLE,
        )
        closeProviderSessionAsync(
            session = generation.detachedSession,
            reason = ProviderPlaybackCloseReason.CHANNEL_CHANGED,
        )
        playbackLifecycleMutex.withLock {
            startPlayback(
                generation = generation.value,
                command = command,
                applyContinueWatching = applyContinueWatching,
            )
        }
    }

    private suspend fun startPlayback(
        generation: Long,
        command: MediaCommand,
        applyContinueWatching: Boolean,
    ) {
        timber.d("play")
        if (!providerSessionState.isCurrent(generation)) return
        releasePlayer()
        val commandAccepted = providerSessionState.runIfCurrent(generation) {
            mediaCommand.value = command
        }
        if (!commandAccepted) return
        val channel = when (command) {
            is MediaCommand.Common -> channelRepository.get(command.channelId)
            is MediaCommand.XtreamEpisode -> channelRepository
                .get(command.channelId)
                ?.copyXtreamEpisode(command.episode)
        }
        if (!providerSessionState.isCurrent(generation)) return
        if (channel != null) {
            channel.playlistUrl.providerAccountIdOrNull()?.let { accountId ->
                providerSessionCloseQueue.awaitDrained(accountId)
                if (!providerSessionState.isCurrent(generation)) return
            }
            val providerSource = subscriptionProviderRepository.resolvePlayback(channel.id)
            if (!providerSessionState.isCurrent(generation)) {
                closeProviderSessionAsync(
                    session = providerSource?.session,
                    reason = ProviderPlaybackCloseReason.CHANNEL_CHANGED,
                )
                return
            }
            val channelUrl = providerSource?.url
                ?: channel.url.takeUnless { url -> url == Channel.URL_DYNAMIC }
                ?: throw ProviderOperationException("Dynamic playback reference was not found")
            val kodiMetadata = PlaybackResilience.parseKodiUrl(channelUrl)
            val cleanUrl = kodiMetadata.cleanUrl
            val attachment = providerSessionState.attach(
                generation = generation,
                session = providerSource?.session,
            )
            closeProviderSessionAsync(
                session = attachment.sessionToClose,
                reason = ProviderPlaybackCloseReason.CHANNEL_CHANGED,
            )
            if (!attachment.accepted) return
            try {
                val channelPreference = getChannelPreference(cleanUrl)
                    ?: getChannelPreference(channelUrl)
                if (!providerSessionState.isCurrent(generation)) return
                val licenseType = channel.licenseType.orEmpty()
                val licenseKey = channel.licenseKey.orEmpty()

                channelRepository.reportPlayed(channel.id)
                if (!providerSessionState.isCurrent(generation)) return

                val playlist = playlistRepository.get(channel.playlistUrl)
                if (!providerSessionState.isCurrent(generation)) return
                val resolvedHeaders = PlaybackResilience.resolvePlaybackHeaders(
                    kodi = kodiMetadata,
                    playlistUserAgent = playlist?.userAgent,
                    providerHeaders = providerSource?.headers.orEmpty(),
                    fallbackUserAgent = defaultUserAgent(),
                )
                val sourceAccepted = providerSessionState.runIfCurrent(generation) {
                    activeUserAgent = resolvedHeaders.userAgent
                    activeRequestHeaders = resolvedHeaders.headers
                    activeProviderPlayback = providerSource != null
                    activeProviderPlaybackAllowsCrossOrigin =
                        providerSource?.allowCrossOriginRequests == true
                    this.chain = channelPreference?.mineType
                        ?.let { MimetypeChain.Remembered(cleanUrl, it) }
                        ?: MimetypeChain.Unspecified(cleanUrl)
                }
                if (!sourceAccepted) return

                timber.d("init mimetype chain: ${chain::class.simpleName}")

                tryPlay(
                    generation = generation,
                    url = cleanUrl,
                    userAgent = resolvedHeaders.userAgent,
                    requestHeaders = resolvedHeaders.headers,
                    licenseType = licenseType,
                    licenseKey = licenseKey,
                    applyContinueWatching = applyContinueWatching
                )
            } catch (exception: Exception) {
                val failedSession = providerSessionState.detach(generation)
                withContext(NonCancellable) {
                    closeProviderSession(
                        session = failedSession,
                        reason = ProviderPlaybackCloseReason.PLAYBACK_FAILED,
                    )
                }
                if (providerSessionState.isCurrent(generation)) {
                    releasePlayer()
                }
                throw exception
            }
        }
    }

    private var extractor: MediaExtractorCompat? = null
    private suspend fun tryPlay(
        generation: Long,
        url: String = chain.url,
        userAgent: String? = activeUserAgent ?: getUserAgent(chain.url, playlist.value),
        requestHeaders: Map<String, String> = activeRequestHeaders,
        providerPlayback: Boolean = activeProviderPlayback,
        providerPlaybackAllowsCrossOrigin: Boolean = activeProviderPlaybackAllowsCrossOrigin,
        licenseType: String = channel.value?.licenseType.orEmpty(),
        licenseKey: String = channel.value?.licenseKey.orEmpty(),
        applyContinueWatching: Boolean
    ) {
        if (!providerSessionState.isCurrent(generation)) return
        val rtmp: Boolean = Url(url).protocol.name == "rtmp"
        val tunneling = settings[PreferencesKeys.TUNNELING]

        val mimeType = when (val chain = chain) {
            is MimetypeChain.Remembered -> chain.mimeType
            is MimetypeChain.Trying -> chain.mimetype
            is MimetypeChain.Unspecified -> {
                val nextAccepted = providerSessionState.runIfCurrent(generation) {
                    this.chain = chain.next()
                }
                if (!nextAccepted) return
                return tryPlay(
                    generation = generation,
                    url = url,
                    userAgent = userAgent,
                    requestHeaders = requestHeaders,
                    providerPlayback = providerPlayback,
                    providerPlaybackAllowsCrossOrigin = providerPlaybackAllowsCrossOrigin,
                    licenseType = licenseType,
                    licenseKey = licenseKey,
                    applyContinueWatching = applyContinueWatching,
                )
            }

            is MimetypeChain.Unsupported -> throw UnsupportedOperationException()
        }

        timber.d("tryPlay, mimetype: $mimeType, rtmp: $rtmp")
        val dataSourceFactory = if (rtmp) {
            RtmpDataSource.Factory()
        } else {
            createHttpDataSourceFactory(
                url = url,
                userAgent = userAgent,
                requestHeaders = requestHeaders,
                providerPlayback = providerPlayback,
                providerPlaybackAllowsCrossOrigin = providerPlaybackAllowsCrossOrigin,
            )
        }
        val extractorsFactory = DefaultExtractorsFactory().setTsExtractorFlags(
            FLAG_ALLOW_NON_IDR_KEYFRAMES and FLAG_DETECT_ACCESS_UNITS
        )
        val mediaExtractor = MediaExtractorCompat(extractorsFactory, dataSourceFactory)
        val mediaSourceFactory = when (mimeType) {
            MimeTypes.APPLICATION_M3U8 -> HlsMediaSource.Factory(dataSourceFactory)
                .setAllowChunklessPreparation(false)
                .setExtractorFactory(DefaultHlsExtractorFactory())

            MimeTypes.APPLICATION_SS -> ProgressiveMediaSource.Factory(
                dataSourceFactory,
                extractorsFactory
            )

            MimeTypes.APPLICATION_RTSP -> RtspMediaSource.Factory()
                .setDebugLoggingEnabled(true)
                .setForceUseRtpTcp(true)
                .setSocketFactory(SSLs.TLSTrustAll.socketFactory)

            else -> DefaultMediaSourceFactory(dataSourceFactory, extractorsFactory)
        }
        timber.d("media-source-factory: ${mediaSourceFactory::class.qualifiedName}")
        if (licenseType.isNotEmpty()) {
            val drmCallback = when {
                (licenseType in arrayOf(
                    Channel.LICENSE_TYPE_CLEAR_KEY,
                    Channel.LICENSE_TYPE_CLEAR_KEY_2
                )) && !licenseKey.startsWith("http") -> LocalMediaDrmCallback(licenseKey.toByteArray())

                else -> HttpMediaDrmCallback(
                    licenseKey,
                    dataSourceFactory
                )
            }
            val uuid = when (licenseType) {
                Channel.LICENSE_TYPE_CLEAR_KEY, Channel.LICENSE_TYPE_CLEAR_KEY_2 -> C.CLEARKEY_UUID
                Channel.LICENSE_TYPE_WIDEVINE -> C.WIDEVINE_UUID
                Channel.LICENSE_TYPE_PLAY_READY -> C.PLAYREADY_UUID
                else -> C.UUID_NIL
            }
            if (uuid != C.UUID_NIL && FrameworkMediaDrm.isCryptoSchemeSupported(uuid)) {
                val drmSessionManager = DefaultDrmSessionManager.Builder()
                    .setUuidAndExoMediaDrmProvider(uuid, FrameworkMediaDrm.DEFAULT_PROVIDER)
                    .setMultiSession(
                        licenseType !in arrayOf(
                            Channel.LICENSE_TYPE_CLEAR_KEY,
                            Channel.LICENSE_TYPE_CLEAR_KEY_2
                        )
                    )
                    .build(drmCallback)
                mediaSourceFactory.setDrmSessionManagerProvider { drmSessionManager }
            }
        }
        val mediaItem = MediaItem.fromUri(url)
        val mediaSource: MediaSource = mediaSourceFactory.createMediaSource(mediaItem)
        val preparedPlayer = preparePlayer(
            generation = generation,
            mediaSourceFactory = mediaSourceFactory,
            mediaSource = mediaSource,
            mediaExtractor = mediaExtractor,
            tunneling = tunneling,
        ) ?: return
        mainCoroutineScope.launch {
            if (!providerSessionState.isCurrent(generation)) return@launch
            if (applyContinueWatching) {
                restoreContinueWatching(preparedPlayer, url)
            } else {
                cwPosition.emit(-1L)
            }
        }
    }

    override suspend fun replay() {
        val prev = mediaCommand.value
        val generation = providerSessionState.beginGeneration()
        replay(
            generation = generation,
            command = prev,
        )
    }

    private suspend fun replay(
        generation: ProviderPlaybackGeneration,
        command: MediaCommand?,
    ) {
        playbackStateEvent.value = PlaybackStateEvent(
            generation = generation.value,
            state = Player.STATE_IDLE,
        )
        closeProviderSessionAsync(
            session = generation.detachedSession,
            reason = ProviderPlaybackCloseReason.STOPPED,
        )
        playbackLifecycleMutex.withLock {
            if (command == null) {
                if (providerSessionState.isCurrent(generation.value)) {
                    releasePlayer()
                }
            } else {
                startPlayback(
                    generation = generation.value,
                    command = command,
                    applyContinueWatching = false,
                )
            }
        }
    }

    override fun release() {
        timber.d("release")
        val generation = providerSessionState.beginGeneration()
        playbackStateEvent.value = PlaybackStateEvent(
            generation = generation.value,
            state = Player.STATE_IDLE,
        )
        closeProviderSessionAsync(
            session = generation.detachedSession,
            reason = ProviderPlaybackCloseReason.STOPPED,
        )
        releasePlayer()
    }

    private fun releasePlayer() {
        synchronized(playerLifecycleLock) {
            reconnectRetryJob?.cancel()
            reconnectRetryJob = null
            preferSoftwareDecoder = false
            transientRetryAttempt = 0
            behindLiveWindowAttempts = 0
            extractor = null
            activeUserAgent = null
            activeRequestHeaders = emptyMap()
            activeProviderPlayback = false
            activeProviderPlaybackAllowsCrossOrigin = false
            mediaCommand.value = null
            size.value = Rect()
            playbackState.value = Player.STATE_IDLE
            isPlaying.value = false
            playbackException.value = null
            tracksGroups.value = emptyList()
            telemetrySnapshot.value = PlayerTelemetrySnapshot()
            chain = MimetypeChain.Unsupported("")
            releaseActivePlayerInstanceLocked()
        }
    }

    private fun releaseActivePlayerInstanceLocked() {
        player.value?.let { activePlayer ->
            activePlayerListener?.let(activePlayer::removeListener)
            activePlayer.stop()
            activePlayer.release()
        }
        activePlayerListener = null
        activePlayerGeneration = null
        player.value = null
    }

    override fun clearCache() {
        cache.keys.forEach {
            cache.getCachedSpans(it).forEach { span ->
                cache.removeSpan(span)
            }
        }
    }

    override fun chooseTrack(group: TrackGroup, index: Int) {
        val currentPlayer = player.value ?: return
        val type = group.type
        val override = TrackSelectionOverride(group, index)
        currentPlayer.trackSelectionParameters = currentPlayer.trackSelectionParameters
            .buildUpon()
            .setOverrideForType(override)
            .setTrackTypeDisabled(type, false)
            .build()
    }

    override fun clearTrack(type: @C.TrackType Int) {
        val currentPlayer = player.value ?: return
        val builder = currentPlayer.trackSelectionParameters
            .buildUpon()
            .clearOverridesOfType(type)
        if (type == C.TRACK_TYPE_TEXT) {
            builder.setTrackTypeDisabled(type, true)
        } else {
            builder.setTrackTypeDisabled(type, false)
        }
        currentPlayer.trackSelectionParameters = builder.build()
    }

    override val cacheSpace: Flow<Long> = flow {
        while (true) {
            emit(cache.cacheSpace)
            delay(1.seconds)
        }
    }
        .flowOn(Dispatchers.IO)

    override suspend fun reloadThumbnail(channelUrl: String): Uri? {
        val channelPreference = getChannelPreference(channelUrl)
        return channelPreference?.thumbnail
    }

    private val thumbnailDir by lazy {
        context.cacheDir.resolve("thumbnails").apply {
            if (!exists()) {
                mkdirs()
            }
        }
    }

    override suspend fun syncThumbnail(channelUrl: String): Uri? = withContext(Dispatchers.IO) {
        val thumbnail = Codecs.getThumbnail(context, channelUrl.toUri()) ?: return@withContext null
        val filename = UUID.randomUUID().toString() + ".jpeg"
        val file = File(thumbnailDir, filename)
        while (!file.createNewFile()) {
            ensureActive()
            file.delete()
        }
        FileOutputStream(file).use {
            thumbnail.compress(Bitmap.CompressFormat.JPEG, 50, it)
        }
        val uri = file.toUri()
        addChannelPreference(
            channelUrl,
            getChannelPreference(channelUrl)?.copy(
                thumbnail = uri
            ) ?: ChannelPreference(thumbnail = uri)
        )
        uri
    }

    private fun createPlayer(
        mediaSourceFactory: MediaSource.Factory,
        tunneling: Boolean,
        listener: Player.Listener,
    ): ExoPlayer = ExoPlayer.Builder(context)
        .setMediaSourceFactory(mediaSourceFactory)
        .setRenderersFactory(createRenderersFactory())
        .setLoadControl(createLiveLoadControl())
        .setTrackSelector(createTrackSelector(tunneling))
        .setHandleAudioBecomingNoisy(true)
        .build()
        .apply {
            val attributes = AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build()
            setAudioAttributes(attributes, true)
            playWhenReady = true
            addListener(listener)
        }

    /**
     * Tunes buffering for live IPTV streams: fast 1.5s initial playback start with a 15s-50s
     * forward buffer, 3s rebuffer hysteresis, and a 10s back buffer.
     */
    private fun createLiveLoadControl(): LoadControl =
        DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 15_000,
                /* maxBufferMs = */ 50_000,
                /* bufferForPlaybackMs = */ 1_500,
                /* bufferForPlaybackAfterRebufferMs = */ 3_000,
            )
            .setBackBuffer(
                /* backBufferDurationMs = */ 10_000,
                /* retainBackBufferFromKeyframe = */ true,
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

    private fun preparePlayer(
        generation: Long,
        mediaSourceFactory: MediaSource.Factory,
        mediaSource: MediaSource,
        mediaExtractor: MediaExtractorCompat,
        tunneling: Boolean,
    ): ExoPlayer? {
        var result: ExoPlayer? = null
        providerSessionState.runIfCurrent(generation) {
            synchronized(playerLifecycleLock) {
                val preparedPlayer = player.value ?: run {
                    val listener = createPlayerListener(generation)
                    createPlayer(
                        mediaSourceFactory = mediaSourceFactory,
                        tunneling = tunneling,
                        listener = listener,
                    ).also { createdPlayer ->
                        timber.d("player instance updated")
                        activePlayerListener = listener
                        activePlayerGeneration = generation
                        player.value = createdPlayer
                    }
                }
                if (activePlayerGeneration == generation) {
                    extractor = mediaExtractor
                    preparedPlayer.setMediaSource(mediaSource)
                    preparedPlayer.prepare()
                    result = preparedPlayer
                }
            }
        }
        return result
    }

    private fun createPlayerListener(generation: Long): Player.Listener =
        object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                handleVideoSizeChanged(generation, videoSize)
            }

            override fun onPlaybackStateChanged(state: Int) {
                handlePlaybackStateChanged(generation, state)
            }

            override fun onPlayerErrorChanged(exception: PlaybackException?) {
                handlePlayerErrorChanged(generation, exception)
            }

            override fun onTracksChanged(tracks: Tracks) {
                handleTracksChanged(generation, tracks)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                handleIsPlayingChanged(generation, isPlaying)
            }
        }

    private fun createRenderersFactory(): RenderersFactory =
        Codecs.createRenderersFactory(
            context = context,
            preferExtensionDecoder = preferSoftwareDecoder,
        )

    private fun createTrackSelector(tunneling: Boolean): TrackSelector {
        return DefaultTrackSelector(context).apply {
            setParameters(
                buildUponParameters()
                    .setForceHighestSupportedBitrate(true)
                    .setTunnelingEnabled(tunneling)
            )
        }
    }

    private fun createHttpDataSourceFactory(
        url: String,
        userAgent: String?,
        requestHeaders: Map<String, String>,
        providerPlayback: Boolean,
        providerPlaybackAllowsCrossOrigin: Boolean,
    ): DataSource.Factory {
        val baseClient = if (providerPlayback) {
            providerOkHttpClient.withProviderPlaybackHeaders(
                entryUrl = url,
                headers = requestHeaders,
                allowCrossOriginRequests = providerPlaybackAllowsCrossOrigin,
            )
        } else {
            okHttpClient
        }
        val client = if (providerPlayback) {
            baseClient
        } else {
            baseClient.newBuilder()
                .followRedirects(true)
                .followSslRedirects(true)
                .build()
        }
        val upstream = OkHttpDataSource.Factory(client)
            .setUserAgent(userAgent?.takeIf { it.isNotBlank() } ?: defaultUserAgent())
        if (!providerPlayback) {
            upstream.setDefaultRequestProperties(requestHeaders)
        }
        return upstream
    }

    private fun handleVideoSizeChanged(
        generation: Long,
        videoSize: VideoSize,
    ) {
        providerSessionState.runIfCurrent(generation) {
            timber.d("onVideoSizeChanged, [${videoSize.toRect()}]")
            size.value = videoSize.toRect()
            if (videoSize.width > 0 && videoSize.height > 0) {
                val activePlayer = playerForGeneration(generation)
                telemetrySnapshot.value = telemetrySnapshot.value.copy(
                    resolution = "${videoSize.width}x${videoSize.height}",
                    bufferedPositionMs = activePlayer?.bufferedPosition ?: telemetrySnapshot.value.bufferedPositionMs,
                    currentPositionMs = activePlayer?.currentPosition ?: telemetrySnapshot.value.currentPositionMs,
                    totalBufferedDurationMs = activePlayer?.totalBufferedDuration ?: telemetrySnapshot.value.totalBufferedDurationMs,
                    updatedAtEpochMs = System.currentTimeMillis()
                )
            }
        }
    }

    private fun handlePlaybackStateChanged(
        generation: Long,
        state: Int,
    ) {
        providerSessionState.runIfCurrent(generation) {
            playbackState.value = state
            val activePlayer = playerForGeneration(generation)
            telemetrySnapshot.value = telemetrySnapshot.value.copy(
                playbackState = state,
                bufferedPositionMs = activePlayer?.bufferedPosition ?: telemetrySnapshot.value.bufferedPositionMs,
                currentPositionMs = activePlayer?.currentPosition ?: telemetrySnapshot.value.currentPositionMs,
                totalBufferedDurationMs = activePlayer?.totalBufferedDuration ?: telemetrySnapshot.value.totalBufferedDurationMs,
                updatedAtEpochMs = System.currentTimeMillis()
            )
            if (state == Player.STATE_ENDED) {
                closeProviderSessionAsync(
                    session = providerSessionState.detach(generation),
                    reason = ProviderPlaybackCloseReason.ENDED,
                )
            }
            playbackStateEvent.value = PlaybackStateEvent(
                generation = generation,
                state = state,
            )
        }
    }

    private fun handlePlayerErrorChanged(
        generation: Long,
        exception: PlaybackException?,
    ) {
        providerSessionState.runIfCurrent(generation) {
            updateErrorTelemetrySnapshot(playerForGeneration(generation), exception)
            if (exception == null) {
                playbackException.value = null
                return@runIfCurrent
            }
            when {
                exception.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> {
                    behindLiveWindowAttempts++
                    val activePlayer = playerForGeneration(generation)
                    if (behindLiveWindowAttempts <= MAX_IMMEDIATE_LIVE_WINDOW_RESETS && activePlayer != null) {
                        timber.w(
                            "onPlayerErrorChanged, ERROR_CODE_BEHIND_LIVE_WINDOW (#$behindLiveWindowAttempts), seeking to live edge"
                        )
                        activePlayer.seekToDefaultPosition()
                        activePlayer.prepare()
                    } else if (behindLiveWindowAttempts <= MAX_BEHIND_LIVE_WINDOW_RETRIES) {
                        val backoffAttempt = behindLiveWindowAttempts - MAX_IMMEDIATE_LIVE_WINDOW_RESETS - 1
                        scheduleReconnect(
                            generation = generation,
                            exception = exception,
                            delayMs = PlaybackResilience.exponentialBackoffDelayMs(backoffAttempt),
                        )
                    } else {
                        failPlayback(generation, exception)
                    }
                }

                exception.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ||
                    exception.errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED ||
                    exception.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ||
                    exception.errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED -> {
                    timber.w(
                        "onPlayerErrorChanged, ${PlaybackException.getErrorCodeName(exception.errorCode)}"
                    )
                    when (val chain = chain) {
                        is MimetypeChain.Remembered -> {
                            ioCoroutineScope.launch {
                                val channelPreference = getChannelPreference(chain.url)
                                if (channelPreference != null) {
                                    addChannelPreference(
                                        chain.url,
                                        channelPreference.copy(mineType = null)
                                    )
                                }
                            }
                        }

                        else -> {}
                    }
                    if (chain.hasNext()) {
                        val next = chain.next()
                        chain = next
                        when (next) {
                            is MimetypeChain.Unsupported -> failPlayback(generation, exception)

                            else -> mainCoroutineScope.launch {
                                tryPlay(
                                    generation = generation,
                                    applyContinueWatching = false,
                                )
                            }
                        }
                    } else {
                        failPlayback(generation, exception)
                    }
                }

                PlaybackResilience.isDecoderFailure(exception) && !preferSoftwareDecoder -> {
                    timber.w(
                        exception,
                        "Hardware decoder failed (${PlaybackException.getErrorCodeName(exception.errorCode)}), falling back to software/extension decoder"
                    )
                    preferSoftwareDecoder = true
                    synchronized(playerLifecycleLock) {
                        releaseActivePlayerInstanceLocked()
                    }
                    mainCoroutineScope.launch {
                        if (!providerSessionState.isCurrent(generation)) return@launch
                        tryPlay(
                            generation = generation,
                            applyContinueWatching = false,
                        )
                    }
                }

                PlaybackResilience.isTransientPlaybackError(exception) &&
                    transientRetryAttempt < MAX_TRANSIENT_RECONNECT_ATTEMPTS -> {
                    val delayMs = PlaybackResilience.exponentialBackoffDelayMs(transientRetryAttempt++)
                    scheduleReconnect(
                        generation = generation,
                        exception = exception,
                        delayMs = delayMs,
                    )
                }

                else -> {
                    failPlayback(generation, exception)
                }
            }
        }
    }

    private fun scheduleReconnect(
        generation: Long,
        exception: PlaybackException,
        delayMs: Long,
    ) {
        reconnectRetryJob?.cancel()
        reconnectRetryJob = mainCoroutineScope.launch {
            timber.w(
                exception,
                "Transient playback error (${PlaybackException.getErrorCodeName(exception.errorCode)}); reconnecting in ${delayMs}ms (attempt=$transientRetryAttempt)"
            )
            delay(delayMs)
            if (!providerSessionState.isCurrent(generation)) return@launch
            tryPlay(
                generation = generation,
                applyContinueWatching = false,
            )
        }
    }

    private fun failPlayback(
        generation: Long,
        exception: PlaybackException,
    ) {
        timber.e(exception, PlaybackException.getErrorCodeName(exception.errorCode))
        closeProviderSessionForGenerationAsync(
            generation = generation,
            reason = ProviderPlaybackCloseReason.PLAYBACK_FAILED,
        )
        playbackException.value = exception
    }

    private fun handleTracksChanged(
        generation: Long,
        tracks: Tracks,
    ) {
        providerSessionState.runIfCurrent(generation) {
            tracksGroups.value = tracks.groups
            updateTrackTelemetrySnapshot(playerForGeneration(generation), tracks)
        }
    }

    private fun handleIsPlayingChanged(
        generation: Long,
        isPlaying: Boolean,
    ) {
        providerSessionState.runIfCurrent(generation) {
            this.isPlaying.value = isPlaying
            val activePlayer = playerForGeneration(generation)
            telemetrySnapshot.value = telemetrySnapshot.value.copy(
                isPlaying = isPlaying,
                bufferedPositionMs = activePlayer?.bufferedPosition ?: telemetrySnapshot.value.bufferedPositionMs,
                currentPositionMs = activePlayer?.currentPosition ?: telemetrySnapshot.value.currentPositionMs,
                totalBufferedDurationMs = activePlayer?.totalBufferedDuration ?: telemetrySnapshot.value.totalBufferedDurationMs,
                updatedAtEpochMs = System.currentTimeMillis()
            )
        }
    }

    private fun updateBufferMetricsSnapshot(activePlayer: ExoPlayer) {
        telemetrySnapshot.value = telemetrySnapshot.value.copy(
            bufferedPositionMs = activePlayer.bufferedPosition,
            currentPositionMs = activePlayer.currentPosition,
            totalBufferedDurationMs = activePlayer.totalBufferedDuration,
            updatedAtEpochMs = System.currentTimeMillis()
        )
    }

    private fun updateTrackTelemetrySnapshot(
        activePlayer: ExoPlayer?,
        tracks: Tracks
    ) {
        val videoFormat = findSelectedTrackFormat(tracks, C.TRACK_TYPE_VIDEO)
        val audioFormat = findSelectedTrackFormat(tracks, C.TRACK_TYPE_AUDIO)
        val streamFormat = videoFormat?.containerMimeType
            ?: audioFormat?.containerMimeType
            ?: videoFormat?.sampleMimeType
            ?: telemetrySnapshot.value.streamFormat
        val videoCodec = videoFormat?.codecs
            ?: videoFormat?.sampleMimeType
            ?: telemetrySnapshot.value.videoCodec
        val audioCodec = audioFormat?.codecs
            ?: audioFormat?.sampleMimeType
            ?: telemetrySnapshot.value.audioCodec
        val resolution = if (videoFormat != null && videoFormat.width > 0 && videoFormat.height > 0) {
            "${videoFormat.width}x${videoFormat.height}"
        } else {
            telemetrySnapshot.value.resolution
        }
        telemetrySnapshot.value = telemetrySnapshot.value.copy(
            streamFormat = streamFormat,
            videoCodec = videoCodec,
            audioCodec = audioCodec,
            resolution = resolution,
            bufferedPositionMs = activePlayer?.bufferedPosition ?: telemetrySnapshot.value.bufferedPositionMs,
            currentPositionMs = activePlayer?.currentPosition ?: telemetrySnapshot.value.currentPositionMs,
            totalBufferedDurationMs = activePlayer?.totalBufferedDuration ?: telemetrySnapshot.value.totalBufferedDurationMs,
            updatedAtEpochMs = System.currentTimeMillis()
        )
    }

    private fun findSelectedTrackFormat(
        tracks: Tracks,
        trackType: @C.TrackType Int
    ) = tracks.groups
        .asSequence()
        .filter { it.type == trackType }
        .mapNotNull { group ->
            (0 until group.length)
                .firstOrNull { index -> group.isTrackSupported(index) && group.isTrackSelected(index) }
                ?.let(group::getTrackFormat)
        }
        .firstOrNull()

    private fun updateErrorTelemetrySnapshot(
        activePlayer: ExoPlayer?,
        exception: PlaybackException?
    ) {
        val httpResponseCode = exception?.let { error ->
            generateSequence<Throwable>(error) { it.cause }
                .filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
                .firstOrNull()
                ?.responseCode
        }
        telemetrySnapshot.value = telemetrySnapshot.value.copy(
            httpResponseCode = httpResponseCode ?: telemetrySnapshot.value.httpResponseCode,
            lastErrorCode = exception?.errorCode,
            lastErrorName = exception?.let { PlaybackException.getErrorCodeName(it.errorCode) },
            bufferedPositionMs = activePlayer?.bufferedPosition ?: telemetrySnapshot.value.bufferedPositionMs,
            currentPositionMs = activePlayer?.currentPosition ?: telemetrySnapshot.value.currentPositionMs,
            totalBufferedDurationMs = activePlayer?.totalBufferedDuration ?: telemetrySnapshot.value.totalBufferedDurationMs,
            updatedAtEpochMs = System.currentTimeMillis()
        )
    }

    private fun playerForGeneration(generation: Long): ExoPlayer? =
        synchronized(playerLifecycleLock) {
            player.value.takeIf { activePlayerGeneration == generation }
        }

    override fun pauseOrContinue(value: Boolean) {
        player.value?.apply {
            if (!value) pause() else {
                playWhenReady = true
                prepare()
            }
        }
    }

    override fun updateSpeed(race: Float) {
        player.value?.apply {
            setPlaybackSpeed(race.coerceAtLeast(0.1f))
        }
    }

    override suspend fun recordVideo(uri: Uri) {
        withContext(Dispatchers.Main) {
            try {
                val currentPlayer = player.value ?: return@withContext
                val tracksGroup = currentPlayer.currentTracks.groups.first {
                    it.type == C.TRACK_TYPE_VIDEO
                } ?: return@withContext
                val formats = (0 until tracksGroup.length).mapNotNull {
                    if (!tracksGroup.isTrackSupported(it)) null
                    else tracksGroup.getTrackFormat(it)
                }
                    .mapNotNull { it.containerMimeType ?: it.sampleMimeType }
                val (mimeType, muxerFactory) = when {
                    formats.any { it in FragmentedMp4Muxer.SUPPORTED_VIDEO_SAMPLE_MIME_TYPES } -> {
                        val mimeType =
                            formats.first { it in FragmentedMp4Muxer.SUPPORTED_VIDEO_SAMPLE_MIME_TYPES }
                        val muxerFactory = InAppFragmentedMp4Muxer.Factory()
                        mimeType to muxerFactory
                    }

                    formats.any { it in Mp4Muxer.SUPPORTED_VIDEO_SAMPLE_MIME_TYPES } -> {
                        val mimeType =
                            formats.first { it in Mp4Muxer.SUPPORTED_VIDEO_SAMPLE_MIME_TYPES }
                        val muxerFactory = InAppMp4Muxer.Factory()
                        mimeType to muxerFactory
                    }

                    else -> {
                        timber.e("Failed to record frame, Unsupported video formats: $formats")
                        return@withContext
                    }
                }
                val transformer = Transformer.Builder(context)
                    .setMuxerFactory(muxerFactory)
                    .setVideoMimeType(mimeType)
                    .setEncoderFactory(
                        DefaultEncoderFactory.Builder(context.applicationContext)
                            .setEnableFallback(true)
//                        .setRequestedVideoEncoderSettings(
//                            VideoEncoderSettings.Builder()
//                                .
//                                .build()
//                        )
                            .build()
                    )
                    .addListener(
                        object : Transformer.Listener {
                            override fun onCompleted(
                                composition: Composition,
                                exportResult: ExportResult
                            ) {
                                super.onCompleted(composition, exportResult)
                                timber.d("transformer, onCompleted")
                            }

                            override fun onError(
                                composition: Composition,
                                exportResult: ExportResult,
                                exportException: ExportException
                            ) {
                                super.onError(composition, exportResult, exportException)
                                timber.e(exportException, "transformer, onError")
                            }

                            override fun onFallbackApplied(
                                composition: Composition,
                                originalTransformationRequest: TransformationRequest,
                                fallbackTransformationRequest: TransformationRequest
                            ) {
                                super.onFallbackApplied(
                                    composition,
                                    originalTransformationRequest,
                                    fallbackTransformationRequest
                                )
                            }
                        }
                    )
                    .build()

                withContext(Dispatchers.Main) {
                    transformer.start(
                        MediaItem.fromUri(chain.url),
                        uri.path.orEmpty()
                    )
                }
            } finally {
                timber.d("Record frame completed")
            }
        }
    }

    override val cwPosition = MutableSharedFlow<Long>(replay = 1)

    override suspend fun onResetPlayback(channelUrl: String) {
        cwPosition.emit(-1L)
        resetContinueWatching(channelUrl, ignorePositionCondition = true)
        val currentPlayer = player.value ?: return
        if (currentPlayer.isCommandAvailable(Player.COMMAND_SEEK_TO_DEFAULT_POSITION)) {
            currentPlayer.seekToDefaultPosition()
        }
    }

    override suspend fun getCwPosition(channelUrl: String): Long {
        val channelPreference = getChannelPreference(channelUrl)
        return channelPreference?.cwPosition ?: -1L
    }

    private suspend fun onPlaybackIdle() {}
    private suspend fun onPlaybackBuffering() {}

    private suspend fun onPlaybackReady(generation: Long) {
        if (!providerSessionState.isCurrent(generation)) return
        transientRetryAttempt = 0
        behindLiveWindowAttempts = 0
        playbackException.value = null
        timber.d("onPlaybackReady, trying the playChain $chain")
        when (val chain = chain) {
            is MimetypeChain.Remembered -> {
                storeContinueWatching(chain.url)
            }

            is MimetypeChain.Trying -> {
                val channelPreference = getChannelPreference(chain.url)
                if (!providerSessionState.isCurrent(generation)) return
                addChannelPreference(
                    chain.url,
                    channelPreference?.copy(mineType = chain.mimetype)
                        ?: ChannelPreference(mineType = chain.mimetype)
                )
                storeContinueWatching(chain.url)
            }

            else -> {}
        }
    }

    private suspend fun onPlaybackEnded(generation: Long) {
        if (!providerSessionState.isCurrent(generation)) return
        val channelUrl = chain.url
        if (
            settings[PreferencesKeys.RECONNECT_MODE] == ReconnectMode.RECONNECT &&
            providerSessionState.isCurrent(generation)
        ) {
            val command = mediaCommand.value
            mainCoroutineScope.launch {
                val replayGeneration = providerSessionState.beginGenerationIfCurrent(generation)
                    ?: return@launch
                replay(
                    generation = replayGeneration,
                    command = command,
                )
            }
        }
        if (
            channelUrl.isNotEmpty() &&
            providerSessionState.isCurrent(generation)
        ) {
            resetContinueWatching(channelUrl)
        }
    }

    private fun closeProviderSessionForGenerationAsync(
        generation: Long,
        reason: ProviderPlaybackCloseReason,
    ) {
        closeProviderSessionAsync(
            session = providerSessionState.detach(generation),
            reason = reason,
        )
    }

    private fun closeProviderSessionAsync(
        session: ProviderPlaybackSession?,
        reason: ProviderPlaybackCloseReason,
    ) {
        if (session == null) return
        providerSessionCloseQueue.enqueue(session.accountId) {
            closeProviderSession(session, reason)
        }
    }

    private suspend fun closeProviderSession(
        session: ProviderPlaybackSession?,
        reason: ProviderPlaybackCloseReason,
    ) {
        if (session == null) return
        runCatching {
            subscriptionProviderRepository.closePlayback(session, reason)
        }.onFailure { exception ->
            timber.w(exception, "Failed to close provider playback session")
        }
    }

    @OptIn(FlowPreview::class)
    private suspend fun storeContinueWatching(channelUrl: String) {
        timber.d("start storeContinueWatching")
        // avoid memory leaks caused by loops
        fun checkContinueWatching(): Boolean {
            val currentPlayer = player.value ?: return false
            return continueWatchingCondition.isStoringSupported(currentPlayer)
        }
        if (!checkContinueWatching()) {
            timber.w("failed to storeContinueWatching, playback is not supported.")
            return
        }
        playbackPosition
            .sample(5.seconds)
            .collect { newCwPosition ->
                timber.d("storeContinueWatching, received new position: $newCwPosition")
                if (newCwPosition == -1L) return@collect
                val channelPreference = getChannelPreference(channelUrl)
                addChannelPreference(
                    channelUrl,
                    channelPreference?.copy(cwPosition = newCwPosition)
                        ?: ChannelPreference(cwPosition = newCwPosition)
                )
                val activeChannel = channel.value
                channelRepository.updateWatchProgress(
                    streamUrl = activeChannel?.url ?: channelUrl,
                    positionMs = newCwPosition,
                    durationMs = playbackDuration.value,
                    channelId = activeChannel?.id,
                )
            }
    }

    private suspend fun restoreContinueWatching(player: Player, channelUrl: String) {
        val channelPreference = getChannelPreference(channelUrl)
        val cachedCwPosition = channelPreference?.cwPosition?.takeIf { it != -1L } ?: run {
            cwPosition.emit(-1L)
            return
        }
        withContext(Dispatchers.Main) {
            if (continueWatchingCondition.isRestoringSupported(player)) {
                timber.d("restoreContinueWatching, $cachedCwPosition")
                cwPosition.emit(cachedCwPosition)
                player.seekTo(cachedCwPosition)
            }
        }
    }

    private suspend fun resetContinueWatching(
        channelUrl: String,
        ignorePositionCondition: Boolean = false
    ) {
        timber.d("resetContinueWatching, channelUrl=$channelUrl, ignorePositionCondition=$ignorePositionCondition")
        val channelPreference = getChannelPreference(channelUrl)
        val player = this@PlayerManagerImpl.player.value
        withContext(Dispatchers.Main) {
            if (player != null && continueWatchingCondition.isResettingSupported(
                    player,
                    ignorePositionCondition
                )
            ) {
                addChannelPreference(
                    channelUrl,
                    channelPreference?.copy(cwPosition = -1L) ?: ChannelPreference(cwPosition = -1L)
                )
                val activeChannel = channel.value
                val duration = player.duration.takeIf { it > 0L } ?: -1L
                channelRepository.updateWatchProgress(
                    streamUrl = activeChannel?.url ?: channelUrl,
                    positionMs = -1L,
                    durationMs = duration,
                    channelId = activeChannel?.id,
                )
            }
        }
    }

    private var chain: MimetypeChain = MimetypeChain.Unsupported("")

    /**
     * Get the kodi url options like this:
     * http://host[:port]/directory/file?a=b&c=d|option1=value1&option2=value2
     * Will get:
     * {option1=value1, option2=value2}
     *
     * https://kodi.wiki/view/HTTP
     */
    private fun String.readKodiUrlOptions(): Map<String, String?> {
        val index = this.indexOf('|')
        if (index == -1) return emptyMap()
        val options = this.drop(index + 1).split("&")
        return options
            .filter { it.isNotBlank() }
            .associate {
                val pair = it.split("=")
                val key = pair.getOrNull(0).orEmpty()
                val value = pair.getOrNull(1)
                key to value
            }
    }

    /**
     * Read user-agent appended to the channelUrl.
     * If there is no result from url, it will use playlist user-agent instead.
     */
    private fun getUserAgent(channelUrl: String, playlist: Playlist?): String? {
        val kodiUrlOptions = channelUrl.readKodiUrlOptions()
        val userAgent = kodiUrlOptions[KodiAdaptions.HTTP_OPTION_UA] ?: playlist?.userAgent
        return userAgent
    }

    private suspend fun getChannelPreference(channelUrl: String): ChannelPreference? {
        if (channelUrl.isEmpty()) return null
        return channelPreferenceProvider[channelUrl]
    }

    override suspend fun resolveExternalPlaybackTarget(command: MediaCommand): ExternalPlaybackTarget? {
        val channel = when (command) {
            is MediaCommand.Common -> channelRepository.get(command.channelId)
            is MediaCommand.XtreamEpisode -> channelRepository
                .get(command.channelId)
                ?.copyXtreamEpisode(command.episode)
        } ?: return null
        val providerSource = runCatching {
            subscriptionProviderRepository.resolvePlayback(channel.id)
        }.getOrNull()
        val rawUrl = providerSource?.url
            ?: channel.url.takeUnless { url -> url == Channel.URL_DYNAMIC }
            ?: return null
        val kodi = PlaybackResilience.parseKodiUrl(rawUrl)
        val playlist = playlistRepository.get(channel.playlistUrl)
        val resolvedHeaders = PlaybackResilience.resolvePlaybackHeaders(
            kodi = kodi,
            playlistUserAgent = playlist?.userAgent,
            providerHeaders = providerSource?.headers.orEmpty(),
            fallbackUserAgent = defaultUserAgent(),
        )
        val channelPreference = getChannelPreference(kodi.cleanUrl)
            ?: getChannelPreference(rawUrl)
        channelRepository.reportPlayed(channel.id)
        val headers = resolvedHeaders.headers
        val referer = kodi.referer ?: headers.entries
            .firstOrNull { (key, _) -> key.equals("Referer", ignoreCase = true) }
            ?.value
        return ExternalPlaybackTarget(
            title = channel.title,
            url = kodi.cleanUrl,
            mimeType = PlaybackResilience.inferExternalMimeType(
                url = kodi.cleanUrl,
                rememberedMimeType = channelPreference?.mineType,
            ),
            userAgent = resolvedHeaders.userAgent,
            referer = referer,
            headers = headers,
        )
    }

    private suspend fun addChannelPreference(
        channelUrl: String,
        channelPreference: ChannelPreference
    ) {
        if (channelUrl.isEmpty()) return
        channelPreferenceProvider[channelUrl] = channelPreference
    }

    private companion object {
        private const val MAX_IMMEDIATE_LIVE_WINDOW_RESETS = 2
        private const val MAX_BEHIND_LIVE_WINDOW_RETRIES = 5
        private const val MAX_TRANSIENT_RECONNECT_ATTEMPTS = 4
    }
}

fun VideoSize.toRect(): Rect {
    return Rect(0, 0, width, height)
}

private data class PlaybackStateEvent(
    val generation: Long,
    val state: @Player.State Int,
)

private sealed class MimetypeChain(val url: String) {
    class Remembered(
        url: String,
        val mimeType: String
    ) : MimetypeChain(url)

    class Unspecified(url: String) : MimetypeChain(url)
    class Trying(url: String, val mimetype: String) : MimetypeChain(url)
    class Unsupported(url: String) : MimetypeChain(url)

    companion object {
        val ORDERS = arrayOf(
            MimeTypes.APPLICATION_SS,
            MimeTypes.APPLICATION_M3U8,
            MimeTypes.APPLICATION_MPD,
            MimeTypes.APPLICATION_RTSP
        )
    }

    override fun toString(): String = when (this) {
        is Unspecified -> "Unspecified[$url]"
        is Trying -> "Trying[$url, $mimetype]"
        is Remembered -> "Remembered[$url, $mimeType]"
        is Unsupported -> "Unsupported[$url]"
    }

    operator fun hasNext(): Boolean = this !is Unsupported

    operator fun next(): MimetypeChain = when (this) {
        is Unspecified -> Trying(url, ORDERS.first())
        is Trying -> {
            ORDERS
                .getOrNull(ORDERS.indexOf(mimetype) + 1)
                ?.let { Trying(url, it) }
                ?: Unsupported(url)
        }

        is Remembered -> Unspecified(url)

        else -> throw IllegalArgumentException()
    }
}
