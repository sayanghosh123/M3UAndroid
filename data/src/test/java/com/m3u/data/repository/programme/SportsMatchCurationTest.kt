package com.m3u.data.repository.programme

import com.m3u.data.database.model.Programme
import com.m3u.data.database.model.SportsChannelCandidate
import com.m3u.data.database.model.SportsProgrammeCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SportsMatchCurationTest {
    @Test
    fun `groups multi-channel broadcasts and unscheduled ppv feed for same fixture`() {
        val now = 1_000_000_000L
        val epgProgrammes = listOf(
            SportsProgrammeCandidate(
                programme = Programme(
                    channelId = "sky.epl",
                    epgUrl = "http://epg.test/xml",
                    start = now - 600_000L,
                    end = now + 4_800_000L,
                    title = "Premier League: Arsenal vs Chelsea",
                    description = "Live match coverage",
                    categories = listOf("Football"),
                ),
                channelId = 101,
                channelTitle = "Sky Sports Premier League FHD",
                channelGroup = "UK | Sports",
                channelUrl = "http://provider.test/live/u/p/101.ts",
                playlistTitle = "Main IPTV",
                playlistUrl = "http://provider.test/playlist.m3u",
            ),
            SportsProgrammeCandidate(
                programme = Programme(
                    channelId = "bein.1",
                    epgUrl = "http://epg.test/xml",
                    start = now - 300_000L,
                    end = now + 5_100_000L,
                    title = "LIVE - Arsenal v Chelsea [4K]",
                    description = "English Premier League",
                    categories = listOf("Soccer"),
                ),
                channelId = 102,
                channelTitle = "beIN SPORTS 1 4K 50fps",
                channelGroup = "VIP Football",
                channelUrl = "http://provider.test/live/u/p/102.ts",
                playlistTitle = "Main IPTV",
                playlistUrl = "http://provider.test/playlist.m3u",
            ),
            SportsProgrammeCandidate(
                programme = Programme(
                    channelId = "espn.nba",
                    epgUrl = "http://epg.test/xml",
                    start = now - 600_000L,
                    end = now + 3_600_000L,
                    title = "NBA: Lakers vs Celtics",
                    description = "Regular season basketball",
                    categories = listOf("Basketball"),
                ),
                channelId = 201,
                channelTitle = "ESPN HD",
                channelGroup = "US | Sports",
                channelUrl = "http://provider.test/live/u/p/201.ts",
                playlistTitle = "Main IPTV",
                playlistUrl = "http://provider.test/playlist.m3u",
            ),
        )
        val ppvChannels = listOf(
            SportsChannelCandidate(
                channelId = 103,
                channelTitle = "PPV 04 | Arsenal x Chelsea HD",
                channelGroup = "UK EPL PPV",
                channelUrl = "http://provider.test/live/u/p/103.ts",
                channelCover = null,
                relationId = null,
                playlistTitle = "Main IPTV",
                playlistUrl = "http://provider.test/playlist.m3u",
            ),
        )

        val footballMatches = SportsMatchCuration.curateMatches(
            programmes = epgProgrammes,
            sportsChannels = ppvChannels,
            curatedEvents = emptyList(),
            preset = SportPreset.FOOTBALL,
            now = now,
            includeLiveUnscheduledChannels = true,
            upcomingOnly = false,
        )

        assertEquals(1, footballMatches.size)
        val match = footballMatches.single()
        assertEquals("Arsenal", match.homeTeam)
        assertEquals("Chelsea", match.awayTeam)
        assertEquals("Premier League", match.competition)
        assertTrue(match.isLive)
        assertEquals(listOf(102, 101, 103), match.sources.map { it.channelId })
        assertEquals("4K 50fps", match.sources[0].qualityLabel)
        assertEquals("FHD", match.sources[1].qualityLabel)
        assertEquals("HD", match.sources[2].qualityLabel)

        val basketballMatches = SportsMatchCuration.curateMatches(
            programmes = epgProgrammes,
            sportsChannels = ppvChannels,
            curatedEvents = emptyList(),
            preset = SportPreset.BASKETBALL,
            now = now,
            includeLiveUnscheduledChannels = true,
            upcomingOnly = false,
        )
        assertEquals(1, basketballMatches.size)
        assertEquals("Lakers", basketballMatches.single().homeTeam)
        assertEquals("Celtics", basketballMatches.single().awayTeam)
    }

    @Test
    fun `upcoming mode excludes active live fixtures and unscheduled channels`() {
        val now = 2_000_000_000L
        val epgProgrammes = listOf(
            SportsProgrammeCandidate(
                programme = Programme(
                    channelId = "c1",
                    epgUrl = "http://epg.test/xml",
                    start = now - 10_000L,
                    end = now + 3_000_000L,
                    title = "Liverpool vs Man City",
                    description = "Premier League",
                    categories = listOf("Football"),
                ),
                channelId = 1,
                channelTitle = "Sky Sports HD",
                channelGroup = "UK Sports",
                channelUrl = "http://provider.test/1.ts",
                playlistTitle = "Main",
                playlistUrl = "http://provider.test/p.m3u",
            ),
            SportsProgrammeCandidate(
                programme = Programme(
                    channelId = "c2",
                    epgUrl = "http://epg.test/xml",
                    start = now + 1_800_000L,
                    end = now + 7_200_000L,
                    title = "UEFA Champions League: Real Madrid vs Bayern Munich",
                    description = "Semi Final",
                    categories = listOf("Football"),
                ),
                channelId = 2,
                channelTitle = "TNT Sports 1 FHD",
                channelGroup = "UK Sports",
                channelUrl = "http://provider.test/2.ts",
                playlistTitle = "Main",
                playlistUrl = "http://provider.test/p.m3u",
            ),
        )

        val upcoming = SportsMatchCuration.curateMatches(
            programmes = epgProgrammes,
            sportsChannels = emptyList(),
            curatedEvents = emptyList(),
            preset = SportPreset.FOOTBALL,
            now = now,
            includeLiveUnscheduledChannels = false,
            upcomingOnly = true,
        )

        assertEquals(1, upcoming.size)
        val match = upcoming.single()
        assertFalse(match.isLive)
        assertEquals("Real Madrid", match.homeTeam)
        assertEquals("Bayern Munich", match.awayTeam)
        assertEquals("UEFA Champions League", match.competition)
    }
}
