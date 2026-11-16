package com.m3u.data.repository

import com.m3u.data.database.model.Channel
import com.m3u.data.database.model.StreamKind
import com.m3u.data.database.model.WatchHistory
import com.m3u.data.parser.m3u.classifyM3uMediaKind
import com.m3u.data.parser.xtream.XtreamLive
import com.m3u.data.parser.xtream.XtreamSerial
import com.m3u.data.parser.xtream.XtreamVod
import com.m3u.data.parser.xtream.asChannel
import com.m3u.data.parser.xtream.toChannel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BoundedJsonlRecordStagingAndStreamKindTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val json = Json { encodeDefaults = true }

    @Test
    fun `xtream models map to expected StreamKind`() {
        val live = XtreamLive(
            categoryId = 10,
            epgChannelId = "sport.hd",
            name = "Sport HD",
            streamIcon = null,
            streamId = 1,
            streamType = "live",
        ).toChannel(
            basicUrl = "http://provider.test",
            username = "u",
            password = "p",
            playlistUrl = "xtream://live",
            category = "Sports",
            containerExtension = "ts",
        )
        val vod = XtreamVod(streamId = 2, name = "Sample Movie", containerExtension = "mkv", categoryId = 20).toChannel(
            basicUrl = "http://provider.test",
            username = "u",
            password = "p",
            playlistUrl = "xtream://vod",
            category = "Action",
        )
        val series = XtreamSerial(seriesId = 3, name = "Sample Series", categoryId = 30).asChannel(
            basicUrl = "http://provider.test",
            username = "u",
            password = "p",
            playlistUrl = "xtream://series",
            category = "Drama",
        )

        assertEquals(StreamKind.LIVE, live.kind)
        assertEquals(StreamKind.MOVIE, vod.kind)
        assertEquals(StreamKind.SERIES, series.kind)
    }

    @Test
    fun `bounded staging streams batches without losing StreamKind`() = runBlocking {
        val totalRecords = 2500
        val kinds = listOf(StreamKind.LIVE, StreamKind.MOVIE, StreamKind.SERIES)
        BoundedJsonlRecordStaging.create(
            cacheDirectory = tempFolder.newFolder("staging"),
            limits = BoundedJsonlRecordStaging.Limits(
                maximumRecords = 10_000,
                maximumBytes = 8L * 1024L * 1024L,
                maximumRecordBytes = 64 * 1024,
            ),
            encode = { json.encodeToString(Channel.serializer(), it) },
            decode = { json.decodeFromString(Channel.serializer(), it) },
        ).use { staging ->
            repeat(totalRecords) { index ->
                staging.append(
                    Channel(
                        url = "http://provider.test/stream/$index.mp4",
                        category = "Group ${index % 5}",
                        title = "Item $index",
                        playlistUrl = "http://provider.test/playlist.m3u",
                        kind = kinds[index % kinds.size],
                    )
                )
            }
            staging.seal()
            assertEquals(totalRecords, staging.recordCount)

            var readCount = 0
            val countsByKind = mutableMapOf<Int, Int>()
            staging.forEachBatch(batchSize = 250) { batch ->
                batch.forEach { channel ->
                    readCount++
                    countsByKind[channel.kind] = (countsByKind[channel.kind] ?: 0) + 1
                }
            }
            assertEquals(totalRecords, readCount)
            assertEquals(totalRecords, countsByKind.values.sum())
            assertEquals(true, countsByKind[StreamKind.LIVE]!! > 0)
            assertEquals(true, countsByKind[StreamKind.MOVIE]!! > 0)
            assertEquals(true, countsByKind[StreamKind.SERIES]!! > 0)
        }
    }

    @Test
    fun `watch history defaults track progress cleanly`() {
        val entry = WatchHistory(
            channelId = 42,
            playlistUrl = "http://provider.test/list.m3u",
            streamUrl = "http://provider.test/movie/u/p/42.mp4",
            title = "Test Movie",
            category = "Action",
            kind = StreamKind.MOVIE,
            watchedAt = 123456L,
        )
        assertEquals(StreamKind.MOVIE, entry.kind)
        assertEquals(-1L, entry.positionMs)
        assertEquals(-1L, entry.durationMs)
    }

    @Test
    fun `m3u media kind classification separates live movies and series`() {
        assertEquals(
            StreamKind.LIVE,
            classifyM3uMediaKind("http://provider.test/live/u/p/1001.ts", "Movies", "HBO Movies HD"),
        )
        assertEquals(
            StreamKind.MOVIE,
            classifyM3uMediaKind("http://provider.test/movie/u/p/2002.mkv", "4K Movies", "Inception"),
        )
        assertEquals(
            StreamKind.SERIES,
            classifyM3uMediaKind("http://provider.test/series/u/p/3003.mp4", "Drama", "Severance S01E01"),
        )
        assertEquals(
            StreamKind.SERIES,
            classifyM3uMediaKind("https://cdn.example.test/vod/pilot.mp4?token=1", "TV Shows", "Pilot"),
        )
        assertEquals(
            StreamKind.MOVIE,
            classifyM3uMediaKind("https://cdn.example.test/films/film.mp4", "Cinema", "Independent Film"),
        )
    }
}
