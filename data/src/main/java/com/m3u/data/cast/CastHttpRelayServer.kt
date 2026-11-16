package com.m3u.data.cast

import com.m3u.data.api.OkhttpClient
import com.m3u.data.service.internal.PlaybackResilience
import com.m3u.data.tv.Utils
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.request.header
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondOutputStream
import io.ktor.server.response.respondText
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get
import io.ktor.server.routing.head
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import timber.log.Timber
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lightweight embedded HTTP relay server that allows Chromecast receivers on the LAN to play
 * IPTV streams behind custom `User-Agent`/`Referer`/`Cookie` requirements or multi-hop redirects.
 * Rewrites nested `#EXTM3U` segment/sub-playlist/key URIs on the fly so every asset fetch
 * preserves the provider headers without buffering entire streams in memory.
 */
@Singleton
class CastHttpRelayServer internal constructor(
    baseOkHttpClient: OkHttpClient,
    private val hostAddressProvider: () -> String?,
    private val defaultUserAgent: String = PlaybackResilience.DEFAULT_USER_AGENT,
) {
    @Inject
    constructor(
        @OkhttpClient(false) okHttpClient: OkHttpClient,
    ) : this(
        baseOkHttpClient = okHttpClient,
        hostAddressProvider = { Utils.getLocalHostAddress() },
        defaultUserAgent = PlaybackResilience.DEFAULT_USER_AGENT,
    )

    internal data class RelaySession(
        val token: String,
        val upstreamUrl: String,
        val headers: Map<String, String>,
        val createdAtEpochMs: Long = System.currentTimeMillis(),
    )

    private val timber = Timber.tag("CastHttpRelayServer")
    private val sessions = ConcurrentHashMap<String, RelaySession>()
    private val startLock = Any()

    private val relayClient: OkHttpClient = baseOkHttpClient.newBuilder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var server: EmbeddedServer<*, *>? = null

    @Volatile
    private var boundPort: Int = -1

    /**
     * Registers [upstreamUrl] + [headers] with the local relay server and returns the LAN URL
     * reachable by the Chromecast receiver, or `null` if no site-local host address exists.
     */
    fun registerStream(
        upstreamUrl: String,
        headers: Map<String, String>,
        isHls: Boolean,
    ): String? {
        val lanHost = hostAddressProvider()?.takeIf { it.isNotBlank() } ?: return null
        val port = ensureStarted()
        evictOldSessionsIfNeeded()

        val token = UUID.randomUUID().toString().replace("-", "")
        sessions[token] = RelaySession(
            token = token,
            upstreamUrl = upstreamUrl,
            headers = headers.toMap(),
        )
        val pathSuffix = if (isHls) "stream.m3u8" else "stream"
        return "http://$lanHost:$port/cast/$token/$pathSuffix"
    }

    fun clearSessions() {
        sessions.clear()
    }

    fun stop() {
        synchronized(startLock) {
            sessions.clear()
            runCatching { server?.stop(gracePeriodMillis = 100L, timeoutMillis = 500L) }
            server = null
            boundPort = -1
        }
    }

    private fun ensureStarted(): Int {
        server?.let {
            if (boundPort > 0) return boundPort
        }
        synchronized(startLock) {
            server?.let {
                if (boundPort > 0) return boundPort
            }
            val port = Utils.findPort()
            val instance = embeddedServer(Netty, port = port, host = "0.0.0.0") {
                install(CORS) {
                    anyHost()
                    allowSameOrigin = true
                    allowHeader(HttpHeaders.Range)
                    exposeHeader(HttpHeaders.ContentRange)
                    exposeHeader(HttpHeaders.ContentLength)
                    exposeHeader(HttpHeaders.AcceptRanges)
                }
                routing {
                    get("/cast/{token}/stream") {
                        handleStreamEntry(isHead = false)
                    }
                    get("/cast/{token}/stream.m3u8") {
                        handleStreamEntry(isHead = false)
                    }
                    head("/cast/{token}/stream") {
                        handleStreamEntry(isHead = true)
                    }
                    head("/cast/{token}/stream.m3u8") {
                        handleStreamEntry(isHead = true)
                    }
                    get("/cast/{token}/asset") {
                        handleAssetRequest(isHead = false)
                    }
                    head("/cast/{token}/asset") {
                        handleAssetRequest(isHead = true)
                    }
                }
            }.apply { start(wait = false) }
            server = instance
            boundPort = port
            return port
        }
    }

    private suspend fun RoutingContext.handleStreamEntry(isHead: Boolean) {
        val token = call.parameters["token"]
        val session = token?.let(sessions::get)
        if (session == null) {
            call.respond(HttpStatusCode.NotFound, "Cast relay session expired")
            return
        }
        proxyUpstream(
            session = session,
            targetUrl = session.upstreamUrl,
            isHead = isHead,
        )
    }

    private suspend fun RoutingContext.handleAssetRequest(isHead: Boolean) {
        val token = call.parameters["token"]
        val encodedUrl = call.request.queryParameters["u"]
        val session = token?.let(sessions::get)
        if (session == null || encodedUrl.isNullOrBlank()) {
            call.respond(HttpStatusCode.NotFound, "Invalid cast asset request")
            return
        }
        val targetUrl = decodeUrl(encodedUrl)
        if (targetUrl == null || (!targetUrl.startsWith("http://", true) && !targetUrl.startsWith("https://", true))) {
            call.respond(HttpStatusCode.BadRequest, "Unsupported cast asset URL")
            return
        }
        proxyUpstream(
            session = session,
            targetUrl = targetUrl,
            isHead = isHead,
        )
    }

    private suspend fun RoutingContext.proxyUpstream(
        session: RelaySession,
        targetUrl: String,
        isHead: Boolean,
    ) {
        val clientRange = call.request.header(HttpHeaders.Range)
        val requestBuilder = Request.Builder().url(targetUrl)
        session.headers.forEach { (key, value) ->
            if (key.isNotBlank() && value.isNotBlank()) {
                requestBuilder.header(key, value)
            }
        }
        val hasUserAgent = session.headers.any { (key, value) ->
            key.equals("User-Agent", ignoreCase = true) && value.isNotBlank()
        }
        if (!hasUserAgent) {
            requestBuilder.header("User-Agent", defaultUserAgent)
        }
        if (!clientRange.isNullOrBlank()) {
            requestBuilder.header("Range", clientRange)
        }
        if (isHead) {
            requestBuilder.head()
        } else {
            requestBuilder.get()
        }

        val upstreamResponse: Response = try {
            withContext(Dispatchers.IO) {
                relayClient.newCall(requestBuilder.build()).execute()
            }
        } catch (exception: Exception) {
            timber.w(exception, "Cast relay upstream request failed")
            call.respond(HttpStatusCode.BadGateway, "Upstream stream request failed")
            return
        }

        upstreamResponse.use { response ->
            val statusCode = HttpStatusCode.fromValue(response.code)
            val contentTypeHeader = response.header("Content-Type")
            val finalUrl = response.request.url.toString()

            val isHlsManifest = !isHead && isHlsPlaylistResponse(
                url = finalUrl,
                contentTypeHeader = contentTypeHeader,
            )

            if (isHead) {
                contentTypeHeader?.let { call.response.header(HttpHeaders.ContentType, it) }
                response.header("Accept-Ranges")?.let { call.response.header(HttpHeaders.AcceptRanges, it) }
                response.header("Content-Length")?.let { call.response.header(HttpHeaders.ContentLength, it) }
                response.header("Content-Range")?.let { call.response.header(HttpHeaders.ContentRange, it) }
                call.respond(statusCode)
                return
            }

            if (!response.isSuccessful) {
                call.respond(statusCode, "Upstream returned ${response.code}")
                return
            }

            if (isHlsManifest) {
                val manifestText = withContext(Dispatchers.IO) { response.body.string() }
                val baseHostHeader = call.request.header(HttpHeaders.Host)
                val relayPrefix = if (!baseHostHeader.isNullOrBlank()) {
                    "http://$baseHostHeader/cast/${session.token}/asset?u="
                } else {
                    val host = hostAddressProvider() ?: "127.0.0.1"
                    "http://$host:$boundPort/cast/${session.token}/asset?u="
                }
                val rewritten = CastStreamAdapter.rewriteHlsPlaylist(
                    manifestText = manifestText,
                    baseUrl = finalUrl,
                ) { resolvedUpstream ->
                    relayPrefix + encodeUrl(resolvedUpstream)
                }
                call.respondText(
                    text = rewritten,
                    contentType = ContentType.parse(CastStreamAdapter.MIME_HLS),
                    status = statusCode,
                )
                return
            }

            val parsedContentType = contentTypeHeader
                ?.let { runCatching { ContentType.parse(it) }.getOrNull() }
                ?: ContentType.Application.OctetStream
            response.header("Accept-Ranges")?.let { call.response.header(HttpHeaders.AcceptRanges, it) }
            response.header("Content-Length")?.let { call.response.header(HttpHeaders.ContentLength, it) }
            response.header("Content-Range")?.let { call.response.header(HttpHeaders.ContentRange, it) }

            call.respondOutputStream(
                contentType = parsedContentType,
                status = statusCode,
            ) {
                withContext(Dispatchers.IO) {
                    response.body.byteStream().use { input ->
                        val buffer = ByteArray(16 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            write(buffer, 0, read)
                        }
                        flush()
                    }
                }
            }
        }
    }

    private fun isHlsPlaylistResponse(
        url: String,
        contentTypeHeader: String?,
    ): Boolean {
        val normalizedCt = contentTypeHeader
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase(Locale.ROOT)
            .orEmpty()
        if ("mpegurl" in normalizedCt) return true
        val path = url.substringBefore('?').substringBefore('#').lowercase(Locale.ROOT)
        return path.endsWith(".m3u8") || path.endsWith(".m3u")
    }

    private fun evictOldSessionsIfNeeded() {
        while (sessions.size >= MAX_ACTIVE_SESSIONS) {
            val oldestKey = sessions.entries
                .minByOrNull { it.value.createdAtEpochMs }
                ?.key
                ?: break
            sessions.remove(oldestKey)
        }
    }

    private fun encodeUrl(url: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(url.toByteArray(StandardCharsets.UTF_8))

    private fun decodeUrl(encoded: String): String? =
        runCatching {
            String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8)
        }.getOrNull()

    private companion object {
        private const val MAX_ACTIVE_SESSIONS = 4
    }
}
