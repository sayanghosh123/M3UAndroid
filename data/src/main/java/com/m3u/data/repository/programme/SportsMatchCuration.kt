package com.m3u.data.repository.programme

import androidx.compose.runtime.Immutable
import com.m3u.data.database.model.CuratedEventResult
import com.m3u.data.database.model.SportsChannelCandidate
import com.m3u.data.database.model.SportsProgrammeCandidate
import java.util.Locale

enum class SportPreset(
    val id: String,
    val keywords: List<String>,
    val excludeKeywords: List<String> = emptyList(),
) {
    FOOTBALL(
        id = "football",
        keywords = listOf(
            "football",
            "soccer",
            "premier league",
            "epl",
            "champions league",
            "ucl",
            "europa league",
            "conference league",
            "la liga",
            "laliga",
            "serie a",
            "bundesliga",
            "ligue 1",
            "fa cup",
            "carabao",
            "efl",
            "championship",
            "mls",
            "world cup",
            "euro 20",
            "nations league",
            "copa ",
            "libertadores",
            "eredivisie",
            "primeira liga",
            "brasileir",
            "saudi pro",
            "spfl",
            "futebol",
            "futbol",
            "calcio",
            "fußball",
            "super league",
        ),
        excludeKeywords = listOf(
            "american football",
            "nfl",
            "ncaa football",
            "college football",
            "cfl",
            "afl",
            "gaelic football",
            "rugby",
            "nba",
            "wnba",
            "euroleague",
            "basketball",
            "formula 1",
            "f1",
            "motogp",
            "nascar",
            "ufc",
            "mma",
            "boxing",
            "wwe",
            "tennis",
            "atp",
            "wta",
            "wimbledon",
            "cricket",
            "ipl",
            "nhl",
            "hockey",
            "mlb",
            "baseball",
            "golf",
        ),
    ),
    ALL_SPORTS(
        id = "all",
        keywords = listOf(
            "sport", "football", "soccer", "basketball", "nba", "nfl", "nhl", "mlb",
            "cricket", "tennis", "rugby", "mma", "ufc", "boxing", "motorsport",
            "formula 1", "f1", "motogp", "nascar", "golf", "cycling", "athletics",
        ),
    ),
    BASKETBALL(
        id = "basketball",
        keywords = listOf("basketball", "nba", "wnba", "euroleague", "ncaa basketball", "fiba", "nbl"),
    ),
    MOTORSPORT(
        id = "motorsport",
        keywords = listOf("formula 1", "f1", "motogp", "nascar", "indycar", "rally", "wrc", "motorsport", "grand prix"),
    ),
    COMBAT(
        id = "combat",
        keywords = listOf("ufc", "mma", "boxing", "fight night", "bellator", "pfl", "wwe", "aew"),
    ),
    TENNIS(
        id = "tennis",
        keywords = listOf("tennis", "atp", "wta", "wimbledon", "roland garros", "us open", "australian open", "grand slam"),
    ),
    CRICKET(
        id = "cricket",
        keywords = listOf("cricket", "ipl", "t20", "odi", "test match", "ashes", "bbl", "psl", "the hundred"),
    ),
}

@Immutable
data class MatchBroadcastSource(
    val channelId: Int,
    val channelTitle: String,
    val channelGroup: String,
    val playlistTitle: String,
    val streamUrl: String,
    val qualityLabel: String?,
    val qualityRank: Int,
    val curated: Boolean,
)

@Immutable
data class CuratedMatch(
    val id: String,
    val title: String,
    val homeTeam: String?,
    val awayTeam: String?,
    val competition: String?,
    val start: Long,
    val end: Long,
    val isLive: Boolean,
    val iconUrl: String?,
    val sources: List<MatchBroadcastSource>,
)

internal data class ParsedFixture(
    val homeTeam: String,
    val awayTeam: String,
    val competitionHint: String?,
) {
    val displayTitle: String get() = "$homeTeam vs $awayTeam"
    val normalizedKey: String get() = "${SportsMatchCuration.normalizeToken(homeTeam)}::${SportsMatchCuration.normalizeToken(awayTeam)}"
}

private data class IntermediateBroadcast(
    val key: String,
    val title: String,
    val homeTeam: String?,
    val awayTeam: String?,
    val competition: String?,
    val start: Long,
    val end: Long,
    val isLive: Boolean,
    val iconUrl: String?,
    val hasFixture: Boolean,
    val isScheduled: Boolean,
    val source: MatchBroadcastSource,
)

object SportsMatchCuration {
    private val SPORTS_CHANNEL_OR_EPG_KEYWORDS = listOf(
        "sport", "football", "soccer", "premier league", "epl", "champions league", "ucl",
        "la liga", "laliga", "serie a", "bundesliga", "ligue 1", "mls", "fa cup", "carabao",
        "nba", "nfl", "nhl", "mlb", "ufc", "mma", "boxing", "tennis", "cricket",
        "formula 1", "f1", "motogp", "nascar", "rugby", "golf", "bein", "dazn", "espn", "sky sports",
    )

    private val BRACKET_TAG_REGEX = Regex("""(\[[^\]]*]|\([^)]*\))""")
    private val LEADING_LIVE_REGEX = Regex("""^(?:live|replay|hd|fhd|uhd|4k)\s*[:|\-]\s*""", RegexOption.IGNORE_CASE)
    private val LEADING_SLOT_REGEX = Regex("""^(?:[A-Z]{2,4}\s+)?(?:EPL|UCL|PPV|VIP|SPORT|CH|LIVE)?\s*\d{1,3}\s*[:|\-]\s*""", RegexOption.IGNORE_CASE)
    private val LEADING_TIME_REGEX = Regex("""^(?:\d{1,2}[:.]\d{2}\s*(?:am|pm|gmt|utc|cet|est|bst)?\s*[:|\-]?\s*)""", RegexOption.IGNORE_CASE)
    private val QUALITY_SUFFIX_REGEX = Regex("""\b(?:4K|UHD|2160p?|FHD|1080p?|HD|720p?|SD|480p?|HEVC|H\.?265|50FPS|60FPS|RAW|BACKUP)\b""", RegexOption.IGNORE_CASE)
    private val PRIMARY_MATCHUP_REGEX = Regex("""^(.+?)\s+(?:vs\.?|v\.?|x|@)\s+(.+)$""", RegexOption.IGNORE_CASE)
    private val DASH_MATCHUP_REGEX = Regex("""^(.+?)\s+[-–—]\s+(.+)$""")
    private val NON_MATCH_DASH_TERMS = listOf(
        "live", "highlights", "replay", "pre-show", "post-match", "analysis",
        "studio", "show", "news", "magazine", "review", "preview", "qualifying",
        "practice", "race", "main event", "press conference", "special",
    )
    private val SLOT_PREFIX_NOISE_REGEX = Regex("""^(?:[A-Z]{2,4}(?:\s+[A-Z]{2,4})?|\w+\s+\d{1,3}|LIVE|PPV|VIP|CH\s*\d+)$""", RegexOption.IGNORE_CASE)

    fun curateMatches(
        programmes: List<SportsProgrammeCandidate>,
        sportsChannels: List<SportsChannelCandidate>,
        curatedEvents: List<CuratedEventResult>,
        preset: SportPreset,
        now: Long,
        includeLiveUnscheduledChannels: Boolean,
        upcomingOnly: Boolean,
    ): List<CuratedMatch> {
        val scheduledEntries = mutableListOf<IntermediateBroadcast>()
        val coveredChannelIds = HashSet<Int>()

        programmes.forEach { candidate ->
            if (upcomingOnly && candidate.programme.start < now) return@forEach
            val fixture = parseFixture(candidate.programme.title, candidate.programme.categories.firstOrNull())
            val searchable = buildSearchable(
                candidate.programme.title,
                candidate.programme.description,
                candidate.programme.categories.joinToString(" "),
                candidate.channelTitle,
                candidate.channelGroup,
                candidate.playlistTitle,
            )
            val isGenericSports = containsAnyKeyword(searchable, SPORTS_CHANNEL_OR_EPG_KEYWORDS) || fixture != null
            if (!isGenericSports) return@forEach
            if (!matchesPreset(searchable, hasFixture = fixture != null, preset = preset)) return@forEach

            val isLive = candidate.programme.start <= now && candidate.programme.end >= now
            coveredChannelIds.add(candidate.channelId)
            val (qualityLabel, qualityRank) = extractStreamQuality(candidate.channelTitle, candidate.channelUrl)
            val displayTitle = fixture?.displayTitle ?: cleanDisplayTitle(candidate.programme.title)
            val competition = fixture?.competitionHint
                ?: candidate.programme.categories.firstOrNull()?.takeIf { it.isNotBlank() }
                ?: candidate.channelGroup.takeIf { it.isNotBlank() }
            val startBucket = candidate.programme.start / (30 * 60 * 1000L)
            val key = if (fixture != null) {
                "fx:${fixture.normalizedKey}"
            } else {
                "pg:${normalizeToken(displayTitle)}::$startBucket"
            }

            scheduledEntries += IntermediateBroadcast(
                key = key,
                title = displayTitle,
                homeTeam = fixture?.homeTeam,
                awayTeam = fixture?.awayTeam,
                competition = competition,
                start = candidate.programme.start,
                end = candidate.programme.end,
                isLive = isLive,
                iconUrl = candidate.programme.icon,
                hasFixture = fixture != null,
                isScheduled = true,
                source = MatchBroadcastSource(
                    channelId = candidate.channelId,
                    channelTitle = candidate.channelTitle,
                    channelGroup = candidate.channelGroup,
                    playlistTitle = candidate.playlistTitle,
                    streamUrl = candidate.channelUrl,
                    qualityLabel = qualityLabel,
                    qualityRank = qualityRank,
                    curated = false,
                ),
            )
        }

        curatedEvents.forEach { curated ->
            if (upcomingOnly && curated.event.start < now) return@forEach
            val fixture = parseFixture(curated.event.title, curated.event.competition ?: curated.event.category)
            val searchable = buildSearchable(
                curated.event.title,
                curated.event.competition.orEmpty(),
                curated.event.category.orEmpty(),
                curated.channelTitle,
            )
            if (!matchesPreset(searchable, hasFixture = fixture != null, preset = preset)) return@forEach

            coveredChannelIds.add(curated.event.channelId)
            val (qualityLabel, qualityRank) = extractStreamQuality(curated.channelTitle, "")
            val displayTitle = fixture?.displayTitle ?: cleanDisplayTitle(curated.event.title)
            val competition = curated.event.competition
                ?: curated.event.category
                ?: fixture?.competitionHint
            val startBucket = curated.event.start / (30 * 60 * 1000L)
            val key = if (fixture != null) {
                "fx:${fixture.normalizedKey}"
            } else {
                "pg:${normalizeToken(displayTitle)}::$startBucket"
            }
            scheduledEntries += IntermediateBroadcast(
                key = key,
                title = displayTitle,
                homeTeam = fixture?.homeTeam,
                awayTeam = fixture?.awayTeam,
                competition = competition,
                start = curated.event.start,
                end = curated.event.end,
                isLive = curated.event.start <= now && curated.event.end >= now,
                iconUrl = null,
                hasFixture = fixture != null,
                isScheduled = true,
                source = MatchBroadcastSource(
                    channelId = curated.event.channelId,
                    channelTitle = curated.channelTitle,
                    channelGroup = competition.orEmpty(),
                    playlistTitle = curated.playlistTitle,
                    streamUrl = "",
                    qualityLabel = qualityLabel,
                    qualityRank = qualityRank,
                    curated = true,
                ),
            )
        }

        val liveChannelEntries = mutableListOf<IntermediateBroadcast>()
        if (includeLiveUnscheduledChannels && !upcomingOnly) {
            sportsChannels.forEach { ch ->
                if (ch.channelId in coveredChannelIds) return@forEach
                val fixture = parseFixture(ch.channelTitle, ch.channelGroup)
                val searchable = buildSearchable(ch.channelTitle, ch.channelGroup, ch.playlistTitle)
                val hasPresetKeyword = if (preset == SportPreset.ALL_SPORTS) {
                    containsAnyKeyword(searchable, SPORTS_CHANNEL_OR_EPG_KEYWORDS) || fixture != null
                } else {
                    matchesPreset(searchable, hasFixture = fixture != null, preset = preset) &&
                        (fixture != null || containsAnyKeyword("${ch.channelTitle} ${ch.channelGroup}", preset.keywords))
                }
                if (!hasPresetKeyword) return@forEach

                val (qualityLabel, qualityRank) = extractStreamQuality(ch.channelTitle, ch.channelUrl)
                val displayTitle = fixture?.displayTitle ?: cleanDisplayTitle(ch.channelTitle)
                val competition = fixture?.competitionHint ?: ch.channelGroup.takeIf { it.isNotBlank() }
                val key = if (fixture != null) {
                    "fx:${fixture.normalizedKey}"
                } else {
                    "ch:${ch.channelId}"
                }

                liveChannelEntries += IntermediateBroadcast(
                    key = key,
                    title = displayTitle,
                    homeTeam = fixture?.homeTeam,
                    awayTeam = fixture?.awayTeam,
                    competition = competition,
                    start = 0L,
                    end = 0L,
                    isLive = true,
                    iconUrl = ch.channelCover,
                    hasFixture = fixture != null,
                    isScheduled = false,
                    source = MatchBroadcastSource(
                        channelId = ch.channelId,
                        channelTitle = ch.channelTitle,
                        channelGroup = ch.channelGroup,
                        playlistTitle = ch.playlistTitle,
                        streamUrl = ch.channelUrl,
                        qualityLabel = qualityLabel,
                        qualityRank = qualityRank,
                        curated = false,
                    ),
                )
            }
        }

        val grouped = linkedMapOf<String, MutableList<IntermediateBroadcast>>()
        // Add scheduled broadcasts first; fixture-keyed groups split if kickoffs are > 3 hours apart.
        scheduledEntries.sortedBy { it.start }.forEach { entry ->
            val groupKey = resolveTimeAwareGroupKey(grouped, entry)
            grouped.getOrPut(groupKey) { mutableListOf() }.add(entry)
        }
        // Attach unscheduled live channels (e.g. PPV/event streams without XMLTV) to matching live/same-day fixture cards or new cards.
        liveChannelEntries.forEach { entry ->
            val existingMatchingKey = if (entry.hasFixture) {
                grouped.keys.firstOrNull { key ->
                    key == entry.key || key.startsWith("${entry.key}@")
                }
            } else {
                null
            }
            val groupKey = existingMatchingKey ?: entry.key
            grouped.getOrPut(groupKey) { mutableListOf() }.add(entry)
        }

        return grouped.entries.map { (groupKey, items) ->
            val primary = items.firstOrNull { it.isScheduled } ?: items.first()
            val dedupSources = items
                .map { it.source }
                .distinctBy { it.channelId }
                .sortedWith(
                    compareByDescending<MatchBroadcastSource> { it.curated }
                        .thenByDescending { it.qualityRank }
                        .thenBy { it.channelTitle.lowercase(Locale.ROOT) }
                )
            val scheduledItems = items.filter { it.isScheduled }
            val start = scheduledItems.minOfOrNull { it.start } ?: 0L
            val end = scheduledItems.maxOfOrNull { it.end } ?: 0L
            val isLive = items.any { it.isLive }
            CuratedMatch(
                id = groupKey,
                title = primary.title,
                homeTeam = primary.homeTeam ?: items.firstNotNullOfOrNull { it.homeTeam },
                awayTeam = primary.awayTeam ?: items.firstNotNullOfOrNull { it.awayTeam },
                competition = items.firstNotNullOfOrNull { it.competition?.takeIf(String::isNotBlank) },
                start = start,
                end = end,
                isLive = isLive,
                iconUrl = items.firstNotNullOfOrNull { it.iconUrl?.takeIf(String::isNotBlank) },
                sources = dedupSources,
            )
        }.sortedWith(
            compareByDescending<CuratedMatch> { it.isLive }
                .thenByDescending { it.homeTeam != null && it.awayTeam != null }
                .thenBy { if (it.start > 0L) 0 else 1 }
                .thenBy { it.start }
                .thenByDescending { it.sources.size }
                .thenBy { it.title.lowercase(Locale.ROOT) }
        )
    }

    private fun resolveTimeAwareGroupKey(
        grouped: Map<String, List<IntermediateBroadcast>>,
        entry: IntermediateBroadcast,
    ): String {
        if (!entry.key.startsWith("fx:") || !entry.isScheduled) return entry.key
        val threeHoursMs = 3L * 60L * 60L * 1000L
        val matchingKey = grouped.entries.firstOrNull { (key, list) ->
            (key == entry.key || key.startsWith("${entry.key}@")) &&
                list.any { existing ->
                    existing.isScheduled && kotlin.math.abs(existing.start - entry.start) <= threeHoursMs
                }
        }?.key
        if (matchingKey != null) return matchingKey
        val variant = grouped.keys.count { it == entry.key || it.startsWith("${entry.key}@") }
        return if (variant == 0) entry.key else "${entry.key}@$variant"
    }

    internal fun parseFixture(
        rawTitle: String,
        fallbackCompetition: String? = null,
    ): ParsedFixture? {
        var working = rawTitle
            .replace(BRACKET_TAG_REGEX, " ")
            .replace(QUALITY_SUFFIX_REGEX, " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
        var competitionHint: String? = fallbackCompetition?.takeIf { it.isNotBlank() }

        working = LEADING_LIVE_REGEX.replace(working, "").trim()
        working = LEADING_SLOT_REGEX.replace(working, "").trim()
        working = LEADING_TIME_REGEX.replace(working, "").trim()

        // Split prefix such as "Premier League: Arsenal vs Chelsea" or "UCL | Real Madrid vs Bayern"
        val separatorIndex = listOf(':', '|')
            .map { working.indexOf(it) }
            .filter { it > 0 }
            .minOrNull()
        if (separatorIndex != null) {
            val prefix = working.substring(0, separatorIndex).trim()
            val remainder = working.substring(separatorIndex + 1).trim()
            if (remainder.isNotBlank()) {
                if (!SLOT_PREFIX_NOISE_REGEX.matches(prefix) && prefix.length in 3..40) {
                    competitionHint = prefix
                }
                working = remainder
            }
        }

        val matchResult = PRIMARY_MATCHUP_REGEX.find(working)
            ?: DASH_MATCHUP_REGEX.find(working)?.takeIf { result ->
                val lowerLeft = result.groupValues[1].lowercase(Locale.ROOT)
                val lowerRight = result.groupValues[2].lowercase(Locale.ROOT)
                NON_MATCH_DASH_TERMS.none { term ->
                    lowerLeft.contains(term) || lowerRight.contains(term)
                }
            }
            ?: return null

        val home = cleanTeamName(matchResult.groupValues[1])
        val away = cleanTeamName(matchResult.groupValues[2])
        if (home.length < 2 || away.length < 2) return null
        if (home.equals(away, ignoreCase = true)) return null
        return ParsedFixture(
            homeTeam = home,
            awayTeam = away,
            competitionHint = competitionHint,
        )
    }

    internal fun extractStreamQuality(
        channelTitle: String,
        streamUrl: String,
    ): Pair<String?, Int> {
        val text = "$channelTitle $streamUrl".uppercase(Locale.ROOT)
        var baseLabel: String? = null
        var rank = 0
        when {
            Regex("""\b(4K|UHD|2160P?)\b""").containsMatchIn(text) -> {
                baseLabel = "4K"
                rank = 40
            }
            Regex("""\b(FHD|1080P?|FULL\s*HD)\b""").containsMatchIn(text) -> {
                baseLabel = "FHD"
                rank = 30
            }
            Regex("""\b(HD|720P?)\b""").containsMatchIn(text) -> {
                baseLabel = "HD"
                rank = 20
            }
            Regex("""\b(SD|480P?)\b""").containsMatchIn(text) -> {
                baseLabel = "SD"
                rank = 10
            }
        }
        val has60Fps = Regex("""\b60\s*FPS\b""").containsMatchIn(text)
        val has50Fps = Regex("""\b50\s*FPS\b""").containsMatchIn(text)
        if (has60Fps) {
            rank += 3
            baseLabel = listOfNotNull(baseLabel, "60fps").joinToString(" ")
        } else if (has50Fps) {
            rank += 2
            baseLabel = listOfNotNull(baseLabel, "50fps").joinToString(" ")
        }
        return baseLabel to rank
    }

    internal fun matchesPreset(
        searchableText: String,
        hasFixture: Boolean,
        preset: SportPreset,
    ): Boolean {
        if (preset == SportPreset.ALL_SPORTS) return true
        val lower = searchableText.lowercase(Locale.ROOT)
        val excluded = preset.excludeKeywords.any { lower.contains(it) }
        val explicitPositive = preset.keywords.any { lower.contains(it) }
        if (excluded) {
            // Allow if an explicit soccer/competition keyword is present unless it's unmistakably American football/NBA/etc.
            val explicitSoccer = listOf("soccer", "premier league", "champions league", "la liga", "serie a", "bundesliga", "ligue 1")
                .any { lower.contains(it) }
            return preset == SportPreset.FOOTBALL && explicitSoccer
        }
        if (explicitPositive) return true
        return preset == SportPreset.FOOTBALL && hasFixture
    }

    private fun cleanDisplayTitle(raw: String): String = raw
        .replace(Regex("""\s+"""), " ")
        .trim()

    private fun cleanTeamName(raw: String): String = raw
        .replace(LEADING_TIME_REGEX, "")
        .replace(QUALITY_SUFFIX_REGEX, " ")
        .replace(Regex("""\b\d+\s*[-:]\s*\d+\b"""), " ")
        .replace(Regex("""^[\-:|•\s]+|[\-:|•\s]+$"""), "")
        .replace(Regex("""\s+"""), " ")
        .trim()

    private fun containsAnyKeyword(text: String, keywords: List<String>): Boolean {
        val lower = text.lowercase(Locale.ROOT)
        return keywords.any { lower.contains(it) }
    }

    private fun buildSearchable(vararg parts: String): String =
        parts.filter { it.isNotBlank() }.joinToString(" ")

    internal fun normalizeToken(value: String): String = value
        .lowercase(Locale.ROOT)
        .replace(Regex("""^(?:fc|cf|sc|ac|as|cd|ud|fk|nk|sk|bk|afc|ssc)\s+"""), "")
        .replace(Regex("""\s+(?:fc|cf|sc|afc|ssc)$"""), "")
        .replace(Regex("""[^a-z0-9]"""), "")
}
