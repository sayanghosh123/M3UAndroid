package com.m3u.data.cast

import com.m3u.data.api.OkhttpClient
import com.m3u.data.database.model.Channel
import com.m3u.data.database.model.Playlist
import com.m3u.data.database.model.isSeries
import com.m3u.data.database.model.isVod
import com.m3u.data.repository.provider.SubscriptionProviderRepository
import com.m3u.data.service.internal.PlaybackResilience
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves a [Channel] + [Playlist] into a Chromecast-ready [CastMediaPlan]:
 * 1. Cleans pipe-delimited Kodi headers and resolves provider sources when needed.
 * 2. Converts live MPEG-TS (`.ts` / `?output=ts`) URLs to HLS (`.m3u8`) endpoints when casting.
 * 3. Probes HTTP redirect chains and custom headers; if redirects or custom `User-Agent`/headers
 *    would block the Cast receiver, registers the stream on [CastHttpRelayServer].
 */
@Singleton
class CastStreamResolver internal constructor(
    baseOkHttpClient: OkHttpClient,
    private val relayServer: CastHttpRelayServer,
    private val subscriptionProviderRepository: SubscriptionProviderRepository?,
    private val fallbackUserAgent: String = PlaybackResilience.DEFAULT_USER_AGENT,
) {
    @Inject
    constructor(
        @OkhttpClient(false) okHttpClient: OkHttpClient,
        relayServer: CastHttpRelayServer,
        subscriptionProviderRepository: SubscriptionProviderRepository,
    ) : this(
        baseOkHttpClient = okHttpClient,
        relayServer = relayServer,
        subscriptionProviderRepository = subscriptionProviderRepository,
        fallbackUserAgent = PlaybackResilience.DEFAULT_USER_AGENT,
    )

    private val timber = Timber.tag("CastStreamResolver")

    private val probeClient: OkHttpClient = baseOkHttpClient.newBuilder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    private data class ProbeResult(
        val finalUrl: String,
        val contentType: String?,
        val statusCode: Int,
        val redirectCount: Int,
    )

    suspend fun resolveForCast(
        channel: Channel,
        playlist: Playlist?,
    ): Result<CastMediaPlan> = withContext(Dispatchers.IO) {
        val isLive = playlist?.let { !it.isVod && !it.isSeries } ?: true
        val providerSource = if (channel.url == Channel.URL_DYNAMIC) {
            runCatching { subscriptionProviderRepository?.resolvePlayback(channel.id) }.getOrNull()
        } else {
            null
        }
        val rawUrl = providerSource?.url
            ?: channel.url.takeUnless { it == Channel.URL_DYNAMIC }
            ?: return@withContext Result.failure(IllegalArgumentException("Unsupported dynamic stream URL"))

        val kodi = PlaybackResilience.parseKodiUrl(rawUrl)
        val cleanUrl = kodi.cleanUrl
        if (!cleanUrl.startsWith("http://", ignoreCase = true) &&
            !cleanUrl.startsWith("https://", ignoreCase = true)
        ) {
            return@withContext Result.failure(IllegalArgumentException("Non-HTTP stream URL"))
        }

        val resolvedHeaders = PlaybackResilience.resolvePlaybackHeaders(
            kodi = kodi,
            playlistUserAgent = playlist?.userAgent,
            providerHeaders = providerSource?.headers.orEmpty(),
            fallbackUserAgent = fallbackUserAgent,
        )
        val requiresHeaderRelay = CastStreamAdapter.hasCustomRelayHeaders(
            headers = resolvedHeaders.headers,
            playlistUserAgent = playlist?.userAgent,
            kodiUserAgent = kodi.userAgent,
            fallbackUserAgent = fallbackUserAgent,
        )

        val hlsCandidate = CastStreamAdapter.adaptUrlForHlsIfLive(cleanUrl, isLive = isLive)
        var targetUrl = hlsCandidate
        var probe = probeStream(hlsCandidate, resolvedHeaders.headers)

        // If the server explicitly rejected the adapted .m3u8 URL (e.g. non-Xtream .ts-only link),
        // fall back cleanly to the original URL so the relay can still serve/passthrough it.
        if (hlsCandidate != cleanUrl && probe != null && probe.statusCode in FALLBACK_ON_STATUS_CODES) {
            timber.d("Adapted HLS URL returned ${probe.statusCode}; falling back to original URL")
            val fallbackProbe = probeStream(cleanUrl, resolvedHeaders.headers)
            if (fallbackProbe == null || fallbackProbe.statusCode in 200..299) {
                targetUrl = cleanUrl
                probe = fallbackProbe
            }
        }

        val resolvedFinalUrl = probe?.finalUrl ?: targetUrl
        val contentType = CastStreamAdapter.inferCastContentType(
            url = resolvedFinalUrl,
            isLive = isLive,
            serverContentType = probe?.contentType,
        )
        val isHls = contentType.equals(CastStreamAdapter.MIME_HLS, ignoreCase = true)

        val shouldRelay = requiresHeaderRelay || (probe?.redirectCount ?: 0) > 0
        val castUrl: String
        val proxied: Boolean

        if (shouldRelay) {
            val relayUrl = relayServer.registerStream(
                upstreamUrl = targetUrl,
                headers = resolvedHeaders.headers,
                isHls = isHls,
            )
            if (relayUrl != null) {
                castUrl = relayUrl
                proxied = true
            } else {
                castUrl = resolvedFinalUrl
                proxied = false
            }
        } else {
            castUrl = resolvedFinalUrl
            proxied = false
        }

        Result.success(
            CastMediaPlan(
                castUrl = castUrl,
                contentType = contentType,
                isLive = isLive,
                title = channel.title,
                subtitle = playlist?.title?.takeIf { it.isNotBlank() },
                coverUrl = channel.cover?.takeIf {
                    it.startsWith("http://", ignoreCase = true) ||
                        it.startsWith("https://", ignoreCase = true)
                },
                proxiedViaLocalRelay = proxied,
            )
        )
    }

    private fun probeStream(url: String, headers: Map<String, String>): ProbeResult? {
        val headResult = executeProbe(url, headers, useRangeGet = false)
        if (headResult != null && headResult.statusCode !in setOf(403, 405, 501)) {
            return headResult
        }
        return executeProbe(url, headers, useRangeGet = true) ?: headResult
    }

    private fun executeProbe(
        url: String,
        headers: Map<String, String>,
        useRangeGet: Boolean,
    ): ProbeResult? {
        val builder = Request.Builder().url(url)
        headers.forEach { (key, value) ->
            if (key.isNotBlank() && value.isNotBlank()) {
                builder.header(key, value)
            }
        }
        if (useRangeGet) {
            builder.header("Range", "bytes=0-0").get()
        } else {
            builder.head()
        }
        return runCatching {
            probeClient.newCall(builder.build()).execute().use { response ->
                ProbeResult(
                    finalUrl = response.request.url.toString(),
                    contentType = response.header("Content-Type"),
                    statusCode = response.code,
                    redirectCount = countRedirects(response),
                )
            }
        }.onFailure { error ->
            timber.d(error, "Cast probe failed for stream")
        }.getOrNull()
    }

    private fun countRedirects(response: Response): Int {
        var count = 0
        var prior = response.priorResponse
        while (prior != null) {
            count++
            prior = prior.priorResponse
        }
        return count
    }

    private companion object {
        private val FALLBACK_ON_STATUS_CODES = setOf(400, 404, 410, 415)
    }
}
