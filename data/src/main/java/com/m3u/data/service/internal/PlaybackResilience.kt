package com.m3u.data.service.internal

import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.HttpDataSource
import com.m3u.data.service.ExternalPlaybackTarget
import java.net.URLDecoder
import kotlin.math.min

internal data class KodiUrlMetadata(
    val cleanUrl: String,
    val userAgent: String? = null,
    val referer: String? = null,
    val extraHeaders: Map<String, String> = emptyMap(),
)

internal data class ResolvedPlaybackHeaders(
    val userAgent: String,
    val headers: Map<String, String>,
)

internal object PlaybackResilience {
    const val DEFAULT_USER_AGENT = "TiviMate/4.7.0"
    private val TRANSIENT_HTTP_STATUS_CODES = setOf(408, 425, 429, 500, 502, 503, 504, 520, 521, 522, 523, 524)

    /**
     * Extracts pipe-delimited Kodi HTTP options (e.g. `https://host/stream.ts|user-agent=...&referer=...`)
     * case-insensitively and returns the cleaned transport URL without the `|...` suffix.
     */
    fun parseKodiUrl(rawUrl: String): KodiUrlMetadata {
        val separatorIndex = rawUrl.indexOf('|')
        if (separatorIndex == -1) {
            return KodiUrlMetadata(cleanUrl = rawUrl)
        }
        val cleanUrl = rawUrl.substring(0, separatorIndex)
        val rawOptions = rawUrl.substring(separatorIndex + 1)
        if (rawOptions.isBlank()) {
            return KodiUrlMetadata(cleanUrl = cleanUrl)
        }

        var userAgent: String? = null
        var referer: String? = null
        val extraHeaders = linkedMapOf<String, String>()

        rawOptions.split('&')
            .filter { it.isNotBlank() }
            .forEach { entry ->
                val eqIndex = entry.indexOf('=')
                if (eqIndex <= 0) return@forEach
                val rawKey = decodeOptionToken(entry.substring(0, eqIndex)).trim()
                val value = decodeOptionToken(entry.substring(eqIndex + 1)).trim()
                if (value.isEmpty()) return@forEach
                when (rawKey.lowercase()) {
                    "user-agent", "ua" -> userAgent = value
                    "referer", "referrer" -> referer = value
                    "cookie" -> extraHeaders["Cookie"] = value
                    "origin" -> extraHeaders["Origin"] = value
                    else -> extraHeaders[rawKey] = value
                }
            }

        return KodiUrlMetadata(
            cleanUrl = cleanUrl,
            userAgent = userAgent,
            referer = referer,
            extraHeaders = extraHeaders,
        )
    }

    fun resolvePlaybackHeaders(
        kodi: KodiUrlMetadata,
        playlistUserAgent: String?,
        providerHeaders: Map<String, String>,
        fallbackUserAgent: String = DEFAULT_USER_AGENT,
    ): ResolvedPlaybackHeaders {
        val merged = linkedMapOf<String, String>()
        merged.putAll(providerHeaders)
        kodi.extraHeaders.forEach { (key, value) ->
            if (merged.keys.none { it.equals(key, ignoreCase = true) }) {
                merged[key] = value
            }
        }
        kodi.referer?.let { referer ->
            if (merged.keys.none { it.equals("Referer", ignoreCase = true) }) {
                merged["Referer"] = referer
            }
        }
        val existingProviderUa = merged.entries
            .firstOrNull { (k, _) -> k.equals("User-Agent", ignoreCase = true) }
            ?.value
        val resolvedUa = kodi.userAgent?.takeIf { it.isNotBlank() }
            ?: existingProviderUa?.takeIf { it.isNotBlank() }
            ?: playlistUserAgent?.takeIf { it.isNotBlank() }
            ?: fallbackUserAgent

        if (merged.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
            merged["User-Agent"] = resolvedUa
        }
        return ResolvedPlaybackHeaders(
            userAgent = resolvedUa,
            headers = merged,
        )
    }

    fun isTransientPlaybackError(exception: PlaybackException): Boolean =
        isTransientPlaybackError(
            errorCode = exception.errorCode,
            httpResponseCode = extractHttpResponseCode(exception),
        )

    fun isTransientPlaybackError(
        errorCode: Int,
        httpResponseCode: Int? = null,
    ): Boolean {
        if (errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS) {
            return httpResponseCode == null || httpResponseCode in TRANSIENT_HTTP_STATUS_CODES
        }
        return errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
            errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
            errorCode == PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE ||
            errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED ||
            errorCode == PlaybackException.ERROR_CODE_TIMEOUT ||
            errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW
    }

    fun isDecoderFailure(exception: PlaybackException): Boolean =
        isDecoderFailure(exception.errorCode)

    fun isDecoderFailure(errorCode: Int): Boolean =
        when (errorCode) {
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES -> true

            else -> false
        }

    fun extractHttpResponseCode(exception: PlaybackException?): Int? =
        exception?.let { error ->
            generateSequence<Throwable>(error) { it.cause }
                .filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
                .firstOrNull()
                ?.responseCode
        }

    fun exponentialBackoffDelayMs(
        attempt: Int,
        baseDelayMs: Long = 1_000L,
        maxDelayMs: Long = 16_000L,
    ): Long {
        val safeAttempt = attempt.coerceIn(0, 10)
        val multiplier = 1L shl safeAttempt
        return min(baseDelayMs * multiplier, maxDelayMs)
    }

    /**
     * Chooses an explicit external player MIME type tailored to VLC / MX Player while falling back
     * safely to `video/\*` when a live container extension is ambiguous.
     */
    fun inferExternalMimeType(
        url: String,
        rememberedMimeType: String? = null,
    ): String {
        when (rememberedMimeType) {
            MimeTypes.APPLICATION_M3U8 -> return "application/x-mpegURL"
            MimeTypes.APPLICATION_MPD -> return "application/dash+xml"
        }
        val cleanPath = url
            .substringBefore('|')
            .substringBefore('#')
            .substringBefore('?')
            .lowercase()
        return when {
            cleanPath.endsWith(".m3u8") || cleanPath.endsWith(".m3u") -> "application/x-mpegURL"
            cleanPath.endsWith(".ts") || cleanPath.endsWith(".m2ts") || cleanPath.endsWith(".mts") -> "video/mp2t"
            cleanPath.endsWith(".mpd") -> "application/dash+xml"
            else -> "video/*"
        }
    }

    /**
     * Converts merged headers into a flat alternating `[key, value, ...]` array recognized by VLC
     * and MX Player via the `headers` Intent extra.
     */
    fun buildExternalHeadersArray(
        userAgent: String?,
        referer: String?,
        headers: Map<String, String>,
    ): Array<String> = ExternalPlaybackTarget.buildHeadersArray(
        userAgent = userAgent,
        referer = referer,
        headers = headers,
    )

    private fun decodeOptionToken(raw: String): String =
        if ('%' in raw || '+' in raw) {
            runCatching { URLDecoder.decode(raw, Charsets.UTF_8.name()) }.getOrDefault(raw)
        } else {
            raw
        }
}
