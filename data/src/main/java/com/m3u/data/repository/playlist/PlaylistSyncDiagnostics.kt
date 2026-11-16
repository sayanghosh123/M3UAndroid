package com.m3u.data.repository.playlist

import android.util.Log
import com.m3u.data.telemetry.CredentialRedactor

internal data class PlaylistSyncSummary(
    val source: String,
    val httpStatuses: List<Int>,
    val liveCount: Int,
    val movieCount: Int,
    val seriesCount: Int,
    val elapsedMs: Long,
    val targetUrl: String,
) {
    val totalCount: Int get() = liveCount + movieCount + seriesCount
    val isEmptySync: Boolean get() = totalCount == 0

    fun logPriority(): Int = if (isEmptySync) Log.WARN else Log.INFO

    fun formatMessage(): String {
        val statusText = if (httpStatuses.isEmpty()) "none" else httpStatuses.joinToString(",")
        val redactedUrl = CredentialRedactor.redact(targetUrl)
        val base = buildString {
            append("Playlist sync [")
            append(source)
            append("] ")
            if (isEmptySync) {
                append("returned 0 items (")
            }
            append("http=[")
            append(statusText)
            append("] live=")
            append(liveCount)
            append(" movies=")
            append(movieCount)
            append(" series=")
            append(seriesCount)
            append(" total=")
            append(totalCount)
            append(" elapsedMs=")
            append(elapsedMs.coerceAtLeast(0L))
            append(" url=")
            append(redactedUrl)
            if (isEmptySync) {
                append("); possible User-Agent or upstream block")
            }
        }
        return CredentialRedactor.redact(base)
    }
}
