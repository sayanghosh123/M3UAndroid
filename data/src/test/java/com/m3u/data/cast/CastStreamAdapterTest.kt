package com.m3u.data.cast

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CastStreamAdapterTest {
    @Test
    fun `adaptUrlForHlsIfLive converts ts urls and query outputs to m3u8`() {
        assertEquals(
            "http://iptv.example.net:8080/live/user/pass/9988.m3u8",
            CastStreamAdapter.adaptUrlForHlsIfLive(
                "http://iptv.example.net:8080/live/user/pass/9988.ts",
                isLive = true,
            )
        )
        assertEquals(
            "http://iptv.example.net:8080/live/user/pass/9988.m3u8",
            CastStreamAdapter.adaptUrlForHlsIfLive(
                "http://iptv.example.net:8080/live/user/pass/9988",
                isLive = true,
            )
        )
        assertEquals(
            "https://cdn.example.net/get.php?type=live&output=m3u8&id=42",
            CastStreamAdapter.adaptUrlForHlsIfLive(
                "https://cdn.example.net/get.php?type=live&output=mpegts&id=42",
                isLive = true,
            )
        )
        // VOD streams must remain untouched.
        assertEquals(
            "http://iptv.example.net:8080/movie/user/pass/9988.mp4",
            CastStreamAdapter.adaptUrlForHlsIfLive(
                "http://iptv.example.net:8080/movie/user/pass/9988.mp4",
                isLive = false,
            )
        )
    }

    @Test
    fun `inferCastContentType returns application x-mpegurl for live hls and adapted ts`() {
        assertEquals(
            CastStreamAdapter.MIME_HLS,
            CastStreamAdapter.inferCastContentType(
                url = "https://cdn.example.net/live/101.m3u8?token=xyz",
                isLive = true,
            )
        )
        assertEquals(
            CastStreamAdapter.MIME_HLS,
            CastStreamAdapter.inferCastContentType(
                url = "https://cdn.example.net/live/101.ts",
                isLive = true,
            )
        )
        assertEquals(
            CastStreamAdapter.MIME_MP4,
            CastStreamAdapter.inferCastContentType(
                url = "https://cdn.example.net/movie/101.mp4",
                isLive = false,
            )
        )
        assertEquals(
            CastStreamAdapter.MIME_HLS,
            CastStreamAdapter.inferCastContentType(
                url = "https://cdn.example.net/opaque",
                isLive = true,
                serverContentType = "application/vnd.apple.mpegurl; charset=utf-8",
            )
        )
    }

    @Test
    fun `rewriteHlsPlaylist rewrites relative and attribute uris against base url`() {
        val manifest = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-KEY:METHOD=AES-128,URI="keys/live.key"
            #EXT-X-MAP:URI="/init/segment0.mp4"
            #EXTINF:4.0,
            720p/seg_001.ts?token=abc
            #EXTINF:4.0,
            https://other-edge.example.net/720p/seg_002.ts
        """.trimIndent()

        val rewritten = CastStreamAdapter.rewriteHlsPlaylist(
            manifestText = manifest,
            baseUrl = "https://edge1.example.net/stream/master/index.m3u8",
        ) { upstream -> "http://192.168.1.50:5555/asset?u=$upstream" }

        assertTrue(
            rewritten.contains(
                """#EXT-X-KEY:METHOD=AES-128,URI="http://192.168.1.50:5555/asset?u=https://edge1.example.net/stream/master/keys/live.key""""
            )
        )
        assertTrue(
            rewritten.contains(
                """#EXT-X-MAP:URI="http://192.168.1.50:5555/asset?u=https://edge1.example.net/init/segment0.mp4""""
            )
        )
        assertTrue(
            rewritten.contains(
                "http://192.168.1.50:5555/asset?u=https://edge1.example.net/stream/master/720p/seg_001.ts?token=abc"
            )
        )
        assertTrue(
            rewritten.contains(
                "http://192.168.1.50:5555/asset?u=https://other-edge.example.net/720p/seg_002.ts"
            )
        )
    }

    @Test
    fun `hasCustomRelayHeaders detects playlist user agent or non-default headers`() {
        assertFalse(
            CastStreamAdapter.hasCustomRelayHeaders(
                headers = mapOf("User-Agent" to "M3UAndroid/1.0"),
                playlistUserAgent = null,
                kodiUserAgent = null,
                fallbackUserAgent = "M3UAndroid/1.0",
            )
        )
        assertTrue(
            CastStreamAdapter.hasCustomRelayHeaders(
                headers = mapOf("User-Agent" to "VLC/3.0"),
                playlistUserAgent = "VLC/3.0",
                kodiUserAgent = null,
                fallbackUserAgent = "M3UAndroid/1.0",
            )
        )
        assertTrue(
            CastStreamAdapter.hasCustomRelayHeaders(
                headers = mapOf(
                    "User-Agent" to "M3UAndroid/1.0",
                    "Referer" to "https://portal.example.net/",
                ),
                playlistUserAgent = null,
                kodiUserAgent = null,
                fallbackUserAgent = "M3UAndroid/1.0",
            )
        )
    }
}
