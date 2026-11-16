package com.m3u.data.service.internal

import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import com.m3u.data.service.ExternalPlaybackTarget
import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaybackResilienceTest {
    @Test
    fun `parseKodiUrl extracts clean url decoded user agent referer and headers`() {
        val raw =
            "https://cdn.example.net/live/101.m3u8?token=abc|User-Agent=VLC%2F3.0.20+Android&Referer=https%3A%2F%2Fportal.example.net%2F&Cookie=sid%3D42"

        val parsed = PlaybackResilience.parseKodiUrl(raw)

        assertEquals("https://cdn.example.net/live/101.m3u8?token=abc", parsed.cleanUrl)
        assertEquals("VLC/3.0.20 Android", parsed.userAgent)
        assertEquals("https://portal.example.net/", parsed.referer)
        assertEquals("sid=42", parsed.extraHeaders["Cookie"])
    }

    @Test
    fun `parseKodiUrl handles url without pipe options`() {
        val raw = "https://cdn.example.net/live/stream.ts"

        val parsed = PlaybackResilience.parseKodiUrl(raw)

        assertEquals(raw, parsed.cleanUrl)
        assertNull(parsed.userAgent)
        assertNull(parsed.referer)
        assertTrue(parsed.extraHeaders.isEmpty())
    }

    @Test
    fun `resolvePlaybackHeaders respects precedence kodi ua over playlist ua and fallback`() {
        val kodi = KodiUrlMetadata(
            cleanUrl = "https://stream.example.net/live.ts",
            userAgent = "CustomKodiUA/1.0",
            referer = "https://ref.example.net/",
            extraHeaders = mapOf("Origin" to "https://origin.example.net"),
        )

        val resolved = PlaybackResilience.resolvePlaybackHeaders(
            kodi = kodi,
            playlistUserAgent = "PlaylistUA/2.0",
            providerHeaders = mapOf("X-Session" to "token-99"),
            fallbackUserAgent = "M3UAndroid/1.0",
        )

        assertEquals("CustomKodiUA/1.0", resolved.userAgent)
        assertEquals("https://ref.example.net/", resolved.headers["Referer"])
        assertEquals("https://origin.example.net", resolved.headers["Origin"])
        assertEquals("token-99", resolved.headers["X-Session"])
        assertEquals("CustomKodiUA/1.0", resolved.headers["User-Agent"])
    }

    @Test
    fun `resolvePlaybackHeaders falls back to TiviMate user agent by default`() {
        val kodi = PlaybackResilience.parseKodiUrl("https://stream.example.net/live/100.ts")
        val resolved = PlaybackResilience.resolvePlaybackHeaders(
            kodi = kodi,
            playlistUserAgent = null,
            providerHeaders = emptyMap(),
        )

        assertEquals("TiviMate/4.7.0", PlaybackResilience.DEFAULT_USER_AGENT)
        assertEquals("TiviMate/4.7.0", resolved.userAgent)
        assertEquals("TiviMate/4.7.0", resolved.headers["User-Agent"])
    }

    @Test
    fun `inferExternalMimeType maps hls ts dash remembered types and falls back cleanly`() {
        assertEquals(
            "application/x-mpegURL",
            PlaybackResilience.inferExternalMimeType("https://cdn.example.net/stream.m3u8?auth=1")
        )
        assertEquals(
            "video/mp2t",
            PlaybackResilience.inferExternalMimeType("https://cdn.example.net/live/12345.ts")
        )
        assertEquals(
            "application/dash+xml",
            PlaybackResilience.inferExternalMimeType("https://cdn.example.net/manifest.mpd")
        )
        assertEquals(
            "application/x-mpegURL",
            PlaybackResilience.inferExternalMimeType(
                url = "https://cdn.example.net/live/12345",
                rememberedMimeType = MimeTypes.APPLICATION_M3U8,
            )
        )
        assertEquals(
            "video/*",
            PlaybackResilience.inferExternalMimeType("https://cdn.example.net/live/12345")
        )
    }

    @Test
    fun `exponentialBackoffDelayMs doubles exponentially up to max cap`() {
        assertEquals(1_000L, PlaybackResilience.exponentialBackoffDelayMs(0))
        assertEquals(2_000L, PlaybackResilience.exponentialBackoffDelayMs(1))
        assertEquals(4_000L, PlaybackResilience.exponentialBackoffDelayMs(2))
        assertEquals(8_000L, PlaybackResilience.exponentialBackoffDelayMs(3))
        assertEquals(16_000L, PlaybackResilience.exponentialBackoffDelayMs(4))
        assertEquals(16_000L, PlaybackResilience.exponentialBackoffDelayMs(7))
    }

    @Test
    fun `external headers array deduplicates keys case-insensitively`() {
        val target = ExternalPlaybackTarget(
            title = "Match Feed",
            url = "https://cdn.example.net/live.m3u8",
            mimeType = "application/x-mpegURL",
            userAgent = "M3UAndroid/2.0",
            referer = "https://portal.example.net/",
            headers = mapOf(
                "user-agent" to "DuplicateUA",
                "Referer" to "https://ignored.example.net/",
                "Origin" to "https://portal.example.net",
            ),
        )

        assertContentEquals(
            arrayOf(
                "User-Agent",
                "M3UAndroid/2.0",
                "Referer",
                "https://portal.example.net/",
                "Origin",
                "https://portal.example.net",
            ),
            target.toIntentHeadersArray(),
        )
    }

    @Test
    fun `error classification distinguishes transient network drops http codes and decoder failures`() {
        assertTrue(
            PlaybackResilience.isTransientPlaybackError(
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
            )
        )
        assertTrue(
            PlaybackResilience.isTransientPlaybackError(
                PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW
            )
        )
        assertTrue(
            PlaybackResilience.isTransientPlaybackError(
                errorCode = PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
                httpResponseCode = 503,
            )
        )
        assertFalse(
            PlaybackResilience.isTransientPlaybackError(
                errorCode = PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
                httpResponseCode = 404,
            )
        )
        assertFalse(
            PlaybackResilience.isTransientPlaybackError(
                PlaybackException.ERROR_CODE_DECODER_INIT_FAILED
            )
        )
        assertFalse(
            PlaybackResilience.isTransientPlaybackError(
                PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND
            )
        )

        assertTrue(
            PlaybackResilience.isDecoderFailure(
                PlaybackException.ERROR_CODE_DECODER_INIT_FAILED
            )
        )
        assertFalse(
            PlaybackResilience.isDecoderFailure(
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
            )
        )
    }
}
