package com.m3u.data.cast

import com.m3u.data.database.model.Channel
import com.m3u.data.database.model.DataSource
import com.m3u.data.database.model.Playlist
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CastStreamResolverAndRelayTest {
    @Test
    fun `resolveForCast adapts ts to m3u8 resolves redirects injects headers and proxies hls segments`(): Unit =
        runBlocking {
            val client = OkHttpClient()
            val relayServer = CastHttpRelayServer(
                baseOkHttpClient = client,
                hostAddressProvider = { "127.0.0.1" },
            )
            val resolver = CastStreamResolver(
                baseOkHttpClient = client,
                relayServer = relayServer,
                subscriptionProviderRepository = null,
                fallbackUserAgent = "M3UAndroid/Test",
            )

            MockWebServer().use { upstream ->
                upstream.start()
                try {
                    val finalHlsUrl = upstream.url("/edge/match_42.m3u8").toString()
                    // 1. Probe HEAD hits .m3u8 URL -> 302 redirect to edge server.
                    upstream.enqueue(
                        MockResponse.Builder()
                            .code(302)
                            .addHeader("Location", finalHlsUrl)
                            .build()
                    )
                    // 2. Probe follows redirect to /edge/match_42.m3u8 -> 200 OK.
                    upstream.enqueue(
                        MockResponse.Builder()
                            .code(200)
                            .addHeader("Content-Type", "application/x-mpegurl")
                            .build()
                    )
                    // 3. Cast fetches local relay URL -> relay requests /live/user/pass/42.m3u8 -> 302.
                    upstream.enqueue(
                        MockResponse.Builder()
                            .code(302)
                            .addHeader("Location", finalHlsUrl)
                            .build()
                    )
                    // 4. Relay follows redirect and receives master playlist with relative segment.
                    upstream.enqueue(
                        MockResponse.Builder()
                            .code(200)
                            .addHeader("Content-Type", "application/x-mpegurl")
                            .body(
                                """
                                #EXTM3U
                                #EXTINF:4.0,
                                chunk_001.ts
                                """.trimIndent()
                            )
                            .build()
                    )
                    // 5. Cast fetches rewritten segment URL through relay -> upstream serves bytes.
                    upstream.enqueue(
                        MockResponse.Builder()
                            .code(200)
                            .addHeader("Content-Type", "video/mp2t")
                            .body("TS-SEGMENT-PAYLOAD")
                            .build()
                    )

                    val channel = Channel(
                        id = 1,
                        url = upstream.url("/live/user/pass/42.ts").toString() +
                            "|User-Agent=CustomIptvUA/9.0&Referer=https://portal.example.com/",
                        category = "Sports",
                        title = "Premier League Live",
                        cover = null,
                        playlistUrl = "xtream://test",
                    )
                    val playlist = Playlist(
                        title = "Live Sports",
                        url = "xtream://test",
                        source = DataSource.Xtream,
                    )

                    val plan = resolver.resolveForCast(channel, playlist).getOrThrow()

                    assertTrue(plan.isLive)
                    assertTrue(plan.proxiedViaLocalRelay)
                    assertEquals(CastStreamAdapter.MIME_HLS, plan.contentType)
                    assertTrue(plan.castUrl.startsWith("http://127.0.0.1:"))

                    // Simulated Chromecast fetching the playlist via the local relay.
                    val playlistBody = client.newCall(
                        Request.Builder().url(plan.castUrl).build()
                    ).execute().use { resp ->
                        assertEquals(200, resp.code)
                        resp.body.string()
                    }
                    val rewrittenSegmentUrl = playlistBody
                        .lineSequence()
                        .first { it.startsWith("http://") }
                    assertTrue(rewrittenSegmentUrl.contains("/asset?u="))

                    // Simulated Chromecast fetching the rewritten child TS segment via relay.
                    val segmentBytes = client.newCall(
                        Request.Builder().url(rewrittenSegmentUrl).build()
                    ).execute().use { resp ->
                        assertEquals(200, resp.code)
                        resp.body.string()
                    }
                    assertEquals("TS-SEGMENT-PAYLOAD", segmentBytes)

                    // Verify upstream received custom User-Agent & Referer on relay requests.
                    val recorded = List(5) { upstream.takeRequest() }
                    assertTrue(
                        recorded.all { req ->
                            req.headers["User-Agent"] == "CustomIptvUA/9.0" &&
                                req.headers["Referer"] == "https://portal.example.com/"
                        }
                    )
                } finally {
                    relayServer.stop()
                }
            }
        }

    @Test
    fun `resolveForCast sends custom headers on relay upstream request and uses direct url when unnecessary`(): Unit =
        runBlocking {
            val client = OkHttpClient()
            val relayServer = CastHttpRelayServer(
                baseOkHttpClient = client,
                hostAddressProvider = { "127.0.0.1" },
            )
            val resolver = CastStreamResolver(
                baseOkHttpClient = client,
                relayServer = relayServer,
                subscriptionProviderRepository = null,
                fallbackUserAgent = "M3UAndroid/Test",
            )

            MockWebServer().use { upstream ->
                upstream.start()
                try {
                    // Direct clean HLS stream: no redirects, default headers.
                    upstream.enqueue(
                        MockResponse.Builder()
                            .code(200)
                            .addHeader("Content-Type", "application/x-mpegurl")
                            .build()
                    )

                    val directUrl = upstream.url("/direct/channel.m3u8").toString()
                    val directChannel = Channel(
                        id = 2,
                        url = directUrl,
                        category = "News",
                        title = "World News",
                        cover = null,
                        playlistUrl = "http://example.com/list.m3u",
                    )
                    val directPlan = resolver.resolveForCast(directChannel, playlist = null).getOrThrow()

                    assertFalse(directPlan.proxiedViaLocalRelay)
                    assertEquals(directUrl, directPlan.castUrl)
                    assertEquals(CastStreamAdapter.MIME_HLS, directPlan.contentType)
                    assertTrue(directPlan.isLive)

                    // Now test fallback when .m3u8 returns 404 while original .ts returns 200.
                    upstream.takeRequest() // consume previous request
                    upstream.enqueue(MockResponse.Builder().code(404).build()) // probe .m3u8
                    upstream.enqueue(
                        MockResponse.Builder()
                            .code(200)
                            .addHeader("Content-Type", "video/mp2t")
                            .build()
                    ) // probe fallback .ts
                    upstream.enqueue(
                        MockResponse.Builder()
                            .code(200)
                            .addHeader("Content-Type", "video/mp2t")
                            .body("RAW-TS")
                            .build()
                    ) // relay GET .ts

                    val customUaChannel = Channel(
                        id = 3,
                        url = upstream.url("/fallback/99.ts").toString(),
                        category = "Sports",
                        title = "Football 4K",
                        cover = null,
                        playlistUrl = "http://example.com/list.m3u",
                    )
                    val customPlaylist = Playlist(
                        title = "IPTV",
                        url = "http://example.com/list.m3u",
                        userAgent = "PlayerCustomUA/3.1",
                    )

                    val relayPlan = resolver.resolveForCast(customUaChannel, customPlaylist).getOrThrow()
                    assertTrue(relayPlan.proxiedViaLocalRelay)

                    val fetched = client.newCall(
                        Request.Builder().url(relayPlan.castUrl).build()
                    ).execute().use { it.body.string() }
                    assertEquals("RAW-TS", fetched)

                    // Check the recorded upstream requests included PlayerCustomUA/3.1
                    val req1 = upstream.takeRequest()
                    val req2 = upstream.takeRequest()
                    val req3 = upstream.takeRequest()
                    assertEquals("PlayerCustomUA/3.1", req1.headers["User-Agent"])
                    assertEquals("PlayerCustomUA/3.1", req2.headers["User-Agent"])
                    assertEquals("PlayerCustomUA/3.1", req3.headers["User-Agent"])
                } finally {
                    relayServer.stop()
                }
            }
        }
}
