package com.m3u.data.cast

import java.net.URI
import java.util.Locale

data class CastMediaPlan(
    val castUrl: String,
    val contentType: String,
    val isLive: Boolean,
    val title: String,
    val subtitle: String? = null,
    val coverUrl: String? = null,
    val proxiedViaLocalRelay: Boolean = false,
)

/**
 * Pure URL/playlist transformation helpers for adapting IPTV live and VOD streams to
 * Chromecast Default Media Receiver requirements (HLS `.m3u8`, `application/x-mpegurl`,
 * header detection, and nested HLS manifest rewriting).
 */
object CastStreamAdapter {
    const val MIME_HLS = "application/x-mpegurl"
    const val MIME_DASH = "application/dash+xml"
    const val MIME_SMOOTH_STREAMING = "application/vnd.ms-sstr+xml"
    const val MIME_MP4 = "video/mp4"
    const val MIME_MKV = "video/x-matroska"
    const val MIME_WEBM = "video/webm"
    const val MIME_MP3 = "audio/mpeg"
    const val MIME_AAC = "audio/aac"
    const val MIME_MPEG_TS = "video/mp2t"

    private val LIVE_TS_SUFFIX_REGEX = Regex("""(?i)\.(ts|m2ts|mts)$""")
    private val TS_QUERY_PARAM_REGEX =
        Regex("""(?i)([?&](?:output|extension|format|container)=)(?:mpegts|ts)(?=&|$)""")
    private val EXT_X_URI_ATTR_REGEX = Regex("""URI="([^"]+)"""")
    private val DIGITS_ONLY_REGEX = Regex("""^\d+$""")

    /**
     * Adapts live MPEG-TS stream URLs (`.ts`, `?output=ts`, `?output=mpegts`, and extensionless
     * Xtream live paths) into HLS `.m3u8` URLs so Chromecast receivers can ingest them.
     */
    fun adaptUrlForHlsIfLive(cleanUrl: String, isLive: Boolean): String {
        if (!isLive || cleanUrl.isBlank()) return cleanUrl
        val hashIndex = cleanUrl.indexOf('#')
        val withoutFragment = if (hashIndex >= 0) cleanUrl.substring(0, hashIndex) else cleanUrl
        val fragment = if (hashIndex >= 0) cleanUrl.substring(hashIndex) else ""

        val queryIndex = withoutFragment.indexOf('?')
        val basePart = if (queryIndex >= 0) withoutFragment.substring(0, queryIndex) else withoutFragment
        val queryPart = if (queryIndex >= 0) withoutFragment.substring(queryIndex) else ""

        var adaptedBase = basePart
        if (LIVE_TS_SUFFIX_REGEX.containsMatchIn(basePart)) {
            adaptedBase = LIVE_TS_SUFFIX_REGEX.replace(basePart, ".m3u8")
        } else if (isExtensionlessXtreamLivePath(basePart)) {
            adaptedBase = "$basePart.m3u8"
        }

        val adaptedQuery = if (queryPart.isNotEmpty()) {
            TS_QUERY_PARAM_REGEX.replace(queryPart) { match -> "${match.groupValues[1]}m3u8" }
        } else {
            queryPart
        }

        return adaptedBase + adaptedQuery + fragment
    }

    fun inferCastContentType(url: String, isLive: Boolean, serverContentType: String? = null): String {
        val normalizedServer = serverContentType
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase(Locale.ROOT)
        if (!normalizedServer.isNullOrBlank()) {
            when {
                "mpegurl" in normalizedServer -> return MIME_HLS
                normalizedServer == MIME_DASH -> return MIME_DASH
                normalizedServer == "video/mp4" -> return MIME_MP4
                normalizedServer == "video/webm" -> return MIME_WEBM
            }
        }

        val uri = runCatching { URI(url) }.getOrNull()
        val queryParams = parseQueryParams(uri?.rawQuery)
        val extension = listOfNotNull(
            queryParams["extension"],
            queryParams["container"],
            queryParams["format"],
            queryParams["output"],
            uri?.path
                ?.substringAfterLast('/', "")
                ?.substringAfterLast('.', "")
                ?.takeIf { it.isNotBlank() },
        )
            .firstOrNull { it.isNotBlank() && !it.equals("php", ignoreCase = true) }
            .orEmpty()
            .lowercase(Locale.ROOT)

        return when (extension) {
            "m3u8", "m3u", "hls" -> MIME_HLS
            "mpd" -> MIME_DASH
            "ism", "isml" -> MIME_SMOOTH_STREAMING
            "mp4", "m4v" -> MIME_MP4
            "mkv" -> MIME_MKV
            "webm" -> MIME_WEBM
            "mp3" -> MIME_MP3
            "aac" -> MIME_AAC
            "ts", "m2ts", "mts" -> if (isLive) MIME_HLS else MIME_MPEG_TS
            else -> if (isLive) MIME_HLS else MIME_MP4
        }
    }

    fun hasCustomRelayHeaders(
        headers: Map<String, String>,
        playlistUserAgent: String?,
        kodiUserAgent: String?,
        fallbackUserAgent: String,
    ): Boolean {
        if (!playlistUserAgent.isNullOrBlank() || !kodiUserAgent.isNullOrBlank()) {
            return true
        }
        return headers.any { (key, value) ->
            if (value.isBlank()) return@any false
            if (key.equals("User-Agent", ignoreCase = true)) {
                value != fallbackUserAgent
            } else {
                true
            }
        }
    }

    /**
     * Rewrites relative/absolute segment, sub-playlist, key, and init-map URIs in an `#EXTM3U`
     * manifest so Chromecast requests every nested resource back through the local relay proxy.
     */
    fun rewriteHlsPlaylist(
        manifestText: String,
        baseUrl: String,
        assetUrlBuilder: (resolvedUpstreamUrl: String) -> String,
    ): String {
        val baseUri = runCatching { URI(baseUrl) }.getOrNull() ?: return manifestText
        val lines = manifestText.split('\n')
        return lines.joinToString("\n") { rawLine ->
            val hasCarriageReturn = rawLine.endsWith('\r')
            val line = if (hasCarriageReturn) rawLine.dropLast(1) else rawLine
            val trimmed = line.trim()
            val rewritten = when {
                trimmed.isEmpty() -> line
                trimmed.startsWith("#") -> {
                    if (trimmed.contains("URI=\"")) {
                        EXT_X_URI_ATTR_REGEX.replace(line) { match ->
                            val rawRef = match.groupValues[1]
                            val resolved = resolveUrl(baseUri, rawRef)
                            """URI="${assetUrlBuilder(resolved)}""""
                        }
                    } else {
                        line
                    }
                }

                else -> {
                    val resolved = resolveUrl(baseUri, trimmed)
                    assetUrlBuilder(resolved)
                }
            }
            if (hasCarriageReturn) "$rewritten\r" else rewritten
        }
    }

    private fun resolveUrl(baseUri: URI, reference: String): String =
        runCatching { baseUri.resolve(reference).toString() }.getOrDefault(reference)

    private fun isExtensionlessXtreamLivePath(basePart: String): Boolean {
        val uri = runCatching { URI(basePart) }.getOrNull() ?: return false
        val segments = uri.path
            ?.split('/')
            ?.filter { it.isNotEmpty() }
            .orEmpty()
        if (segments.size < 3) return false
        val last = segments.last()
        if ('.' in last) return false
        if (!DIGITS_ONLY_REGEX.matches(last)) return false
        // Exclude explicit VOD/series Xtream prefixes.
        val first = segments.first().lowercase(Locale.ROOT)
        return first != "movie" && first != "series"
    }

    private fun parseQueryParams(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrBlank()) return emptyMap()
        val result = linkedMapOf<String, String>()
        rawQuery.split('&').forEach { pair ->
            val eqIndex = pair.indexOf('=')
            if (eqIndex > 0) {
                val key = pair.substring(0, eqIndex).lowercase(Locale.ROOT)
                val value = pair.substring(eqIndex + 1)
                if (value.isNotBlank()) {
                    result[key] = value
                }
            }
        }
        return result
    }
}
