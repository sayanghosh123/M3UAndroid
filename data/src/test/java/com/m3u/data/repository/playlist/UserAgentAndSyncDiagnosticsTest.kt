package com.m3u.data.repository.playlist

import android.util.Log
import com.m3u.data.api.ApiModule
import com.m3u.data.parser.xtream.XtreamInput
import com.m3u.data.parser.xtream.XtreamParserImpl
import com.m3u.data.service.internal.PlaybackResilience
import com.m3u.data.service.internal.withProviderPlaybackHeaders
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserAgentAndSyncDiagnosticsTest {
    @Test
    fun `default okhttp client injects TiviMate user agent and preserves explicit override`() {
        val client = ApiModule.provideOkhttpClient()
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().code(200).body("#EXTM3U\n").build())
            server.enqueue(MockResponse.Builder().code(200).body("#EXTM3U\n").build())

            val url = server.url("/playlist.m3u").toString()
            client.newCall(Request.Builder().url(url).build()).execute().close()
            client.newCall(
                Request.Builder().url(url).header("User-Agent", "CustomApp/2.0").build()
            ).execute().close()

            val first = server.takeRequest()
            val second = server.takeRequest()
            assertEquals(PlaybackResilience.DEFAULT_USER_AGENT, first.headers["User-Agent"])
            assertEquals("TiviMate/4.7.0", first.headers["User-Agent"])
            assertEquals("CustomApp/2.0", second.headers["User-Agent"])
        }
    }

    @Test
    fun `provider playback headers fall back to TiviMate user agent when omitted`() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().code(200).body("OK").build())
            val entryUrl = server.url("/live/master.m3u8").toString()

            val client = OkHttpClient().withProviderPlaybackHeaders(
                entryUrl = entryUrl,
                headers = emptyMap(),
                allowCrossOriginRequests = true,
            )
            client.newCall(Request.Builder().url(entryUrl).build()).execute().close()
            assertEquals("TiviMate/4.7.0", server.takeRequest().headers["User-Agent"])

            val sameOriginRequest = Request.Builder()
                .url(entryUrl)
                .build()
                .withProviderPlaybackHeaders(
                    approvedUrl = entryUrl.toHttpUrl(),
                    headers = emptyMap(),
                )
            assertEquals("TiviMate/4.7.0", sameOriginRequest.header("User-Agent"))
        }
    }

    @Test
    fun `xtream parser sends TiviMate user agent and records http status codes`() = runBlocking {
        val client = ApiModule.provideOkhttpClient()
        val parser = XtreamParserImpl(client)

        MockWebServer().use { server ->
            server.start()
            // getXtreamOutput calls: info, live_categories, vod_categories, series_categories
            server.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .body("""{"user_info":{"allowed_output_formats":["ts"]},"server_info":{"server_protocol":"http","port":"80"}}""")
                    .build()
            )
            server.enqueue(MockResponse.Builder().code(200).body("[]").build())
            server.enqueue(MockResponse.Builder().code(200).body("[]").build())
            server.enqueue(MockResponse.Builder().code(200).body("[]").build())
            // parse calls: live (403 blocked), vod ([]), series ([])
            server.enqueue(MockResponse.Builder().code(403).body("Forbidden").build())
            server.enqueue(MockResponse.Builder().code(200).body("[]").build())
            server.enqueue(MockResponse.Builder().code(200).body("[]").build())

            val baseUrl = server.url("/").toString().trimEnd('/')
            val input = XtreamInput(baseUrl, "alice", "s3cr3t", null)

            parser.clearHttpStatusHistory()
            parser.getXtreamOutput(input)
            val parsed = parser.parse(input).toList()
            val statuses = parser.drainHttpStatusHistory()

            assertEquals(0, parsed.size)
            assertEquals(listOf(200, 200, 200, 200, 403, 200, 200), statuses)

            val requests = List(7) { server.takeRequest() }
            assertTrue(requests.all { it.headers["User-Agent"] == "TiviMate/4.7.0" })
        }
    }

    @Test
    fun `sync diagnostics formats redacted counts and flags zero items with WARN`() {
        val emptySummary = PlaylistSyncSummary(
            source = "xtream",
            httpStatuses = listOf(200, 403),
            liveCount = 0,
            movieCount = 0,
            seriesCount = 0,
            elapsedMs = 245L,
            targetUrl = "http://secret-iptv.example.com:8080/get.php?username=bob&password=pw123",
        )
        assertTrue(emptySummary.isEmptySync)
        assertEquals(Log.WARN, emptySummary.logPriority())
        val warnMsg = emptySummary.formatMessage()
        assertFalse(warnMsg.contains("secret-iptv.example.com"))
        assertFalse(warnMsg.contains("pw123"))
        assertTrue(warnMsg.contains("http=[200,403]"))
        assertTrue(warnMsg.contains("live=0 movies=0 series=0 total=0 elapsedMs=245"))
        assertTrue(warnMsg.contains("possible User-Agent or upstream block"))

        val populatedSummary = PlaylistSyncSummary(
            source = "m3u",
            httpStatuses = listOf(200),
            liveCount = 1200,
            movieCount = 4500,
            seriesCount = 800,
            elapsedMs = 1800L,
            targetUrl = "https://cdn.iptv.example/playlist.m3u?token=tok_xyz",
        )
        assertFalse(populatedSummary.isEmptySync)
        assertEquals(Log.INFO, populatedSummary.logPriority())
        val infoMsg = populatedSummary.formatMessage()
        assertFalse(infoMsg.contains("tok_xyz"))
        assertTrue(infoMsg.contains("live=1200 movies=4500 series=800 total=6500 elapsedMs=1800"))
    }
}
