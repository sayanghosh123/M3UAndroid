package com.m3u.data.parser.m3u

import androidx.core.net.toUri
import com.m3u.data.database.model.Channel
import com.m3u.data.database.model.StreamKind
import dev.oxyroid.parser.protocol.ParsedChannel
import java.util.Locale

internal typealias M3UData = ParsedChannel

internal fun M3UData.toChannel(
    playlistUrl: String,
    seen: Long = 0L
): Channel {
    val fileScheme = "file:///"
    val absoluteUrl = if (!url.startsWith(fileScheme)) url
    else {
        with(playlistUrl.toUri()) {
            val paths = pathSegments.dropLast(1) + url.drop(fileScheme.length)
            buildUpon()
                .path(
                    paths.joinToString("/", "", "")
                )
                .build()
                .toString()
        }
    }

    /**
     * kodi adaptive: 'tvg-id' corresponds to 'channel-id' field in the EPG xml file.
     * If missing from the M3U file, the addon will use the 'tvg-name' tag to map the channel to the EPG.
     *
     * https://kodi.wiki/view/Add-on:PVR_IPTV_Simple_Client#Usage
     */
    val relationId = id.ifEmpty { name }

    return Channel(
        url = absoluteUrl,
        category = group,
        title = title,
        cover = cover,
        playlistUrl = playlistUrl,
        seen = seen,
        licenseType = licenseType,
        licenseKey = licenseKey,
        relationId = relationId,
        kind = classifyM3uMediaKind(
            url = absoluteUrl,
            group = group,
            title = title,
        ),
    )
}

internal fun classifyM3uMediaKind(
    url: String,
    group: String,
    title: String,
): Int {
    val cleanUrl = url.substringBefore('?').substringBefore('#').lowercase(Locale.ROOT)
    if (cleanUrl.contains("/live/")) return StreamKind.LIVE
    if (cleanUrl.contains("/series/")) return StreamKind.SERIES
    if (cleanUrl.contains("/movie/")) return StreamKind.MOVIE
    if (cleanUrl.contains("/vod/")) {
        return if (isM3uSeriesIndicator(group, title)) StreamKind.SERIES else StreamKind.MOVIE
    }

    val ext = cleanUrl.substringAfterLast('/', "").substringAfterLast('.', "")
    if (ext in M3U_VOD_FILE_EXTENSIONS) {
        return if (isM3uSeriesIndicator(group, title)) StreamKind.SERIES else StreamKind.MOVIE
    }
    return StreamKind.LIVE
}

private fun isM3uSeriesIndicator(group: String, title: String): Boolean {
    val lowerGroup = group.lowercase(Locale.ROOT)
    return M3U_SERIES_EPISODE_REGEX.containsMatchIn(title) ||
        lowerGroup.contains("series") ||
        lowerGroup.contains("tv show") ||
        lowerGroup.contains("shows") ||
        lowerGroup.contains("episodes")
}

private val M3U_VOD_FILE_EXTENSIONS = setOf("mp4", "mkv", "avi", "mov", "m4v", "wmv", "flv")
private val M3U_SERIES_EPISODE_REGEX = Regex("""\bs\d{1,2}\s*e\d{1,3}\b""", RegexOption.IGNORE_CASE)
