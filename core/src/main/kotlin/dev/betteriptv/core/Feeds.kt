package dev.betteriptv.core

import java.time.Duration
import java.time.Instant

/** What kind of Channel a Feed is, in the order Feeds of one language rank (CONTEXT.md: Feed). */
enum class FeedKind {
    /** An Event channel named after the Game: dedicated to it, so it never shows another game. */
    EventChannel,

    /** The Channel of a Broadcaster the schedule names for the Game: a network that may show another game by market. */
    Broadcaster,
}

/**
 * One way of watching a Game (CONTEXT.md: Feed): a [channel] of some [kind], in a [language] (a
 * primary language subtag, from the Channel's country tag or the Event channel's Category; null when
 * neither says) and, for a Broadcaster's Channel, the [broadcaster] the schedule named. Watching it
 * plays the Channel's variants best first, as watching any Channel does ([Library.channelPlayPlan]).
 */
data class Feed(val channel: Channel, val kind: FeedKind, val language: String?, val broadcaster: String? = null) {
    /** The Feed's name on the Game page: the Channel's, as the Provider lists it without its tags. */
    val name: String get() = channel.name
}

/** A [game] with its [feeds], best first ([rankedFeeds]); none for a Game nothing in the catalog carries. */
data class GameFeeds(val game: Game, val feeds: List<Feed>) {
    /** The Feed Play starts, or null when the Game has none. */
    val best: Feed? get() = feeds.firstOrNull()

    /** What tells this Game from every other on a screen: its League and the schedule's id. */
    val key: String get() = "${game.league}/${game.id}"
}

/** An Event channel as the catalog has it: the merged [channel], when its name says its Game [start]s (null when marked `Live |` or `Next |`), and its Category. */
internal class EventChannelListing(val channel: Channel, val start: Instant?, val category: LiveCategory?)

/**
 * Each of [games] with the Feeds the catalog has for it (docs/roadmap.md, M3, "The cloud fetches,
 * the device matches"), the Feeds ranked for the Viewer's [contentLanguages]: among [events], the
 * Event channels whose Provider name holds an alias of both teams, or of one team and one of the
 * League's names ([League.names]), and whose start is the Game's ([sameEvent]); then, among
 * [broadcasters] (Channels by their lower-cased name), the Channels named exactly like one of the
 * Game's Broadcasters ("ESPN 2" is not ESPN). Names are compared as Search compares them: lower
 * case, accents dropped, an alias found only as whole words in order, so "Jets" is not "Jetsons".
 *
 * Two guards keep a city's other team's Game out: an Event channel naming another offered League
 * ("NBA: Toronto vs Boston" for the Leafs and the Bruins) is never this Game's, and a match resting
 * on the teams' locations alone ([TeamAliases.weak]: no nickname, short name or abbreviation of
 * either) needs the Game's League named too.
 */
internal fun matchFeeds(
    games: List<Game>,
    events: List<EventChannelListing>,
    broadcasters: Map<String, List<Channel>>,
    contentLanguages: List<String>,
    now: Instant,
): List<GameFeeds> {
    val names = events.map { SearchIndex.words(it.channel.variants.first().name) }
    // Each word to the Event channels whose names hold it, so an alias is looked for only where its first word is.
    val index = HashMap<String, MutableList<Int>>()
    names.forEachIndexed { i, words -> words.distinct().forEach { index.getOrPut(it) { ArrayList(2) } += i } }
    fun holding(phrase: String): Set<Int> {
        val words = SearchIndex.words(phrase)
        if (words.isEmpty()) return emptySet()
        return index[words.first()].orEmpty().filterTo(HashSet()) { containsPhrase(names[it], words) }
    }
    fun holdingAny(phrases: Collection<String>): Set<Int> = phrases.flatMapTo(HashSet(), ::holding)
    val byLeague = League.entries.associateWith { holdingAny(it.names) }
    return games.map { game ->
        val thisLeague = League.byKey(game.league)
        val league = thisLeague?.let { byLeague[it] }.orEmpty()
        val otherLeague = byLeague.filterKeys { it != thisLeague }.values.flatten().toSet()
        val home = TeamAliases(game.home)
        val away = TeamAliases(game.away)
        val homeStrong = holdingAny(home.strong)
        val awayStrong = holdingAny(away.strong)
        val homeAny = homeStrong + holdingAny(home.weak)
        val awayAny = awayStrong + holdingAny(away.weak)
        val both = (homeAny intersect awayAny) intersect (homeStrong + awayStrong + league)
        val named = (both + ((homeAny + awayAny) intersect league)) - otherLeague
        val eventFeeds = named.sorted().map { events[it] }.filter { sameEvent(game, it.start, now) }.map { listing ->
            Feed(listing.channel, FeedKind.EventChannel, languageOf(listing.channel.country ?: listing.category?.country))
        }
        val broadcasterFeeds = game.broadcasters.flatMap { broadcaster ->
            broadcasters[broadcaster.lowercase()].orEmpty().map { Feed(it, FeedKind.Broadcaster, languageOf(it.country), broadcaster) }
        }
        GameFeeds(game, rankedFeeds((eventFeeds + broadcasterFeeds).distinctBy { it.channel.key }, contentLanguages))
    }
}

/**
 * A team's aliases sorted by how surely they name it: [strong] ones (its name, short name,
 * abbreviation and nickname, as the schedule gives them, plus the last word of a nickname of more
 * than one word, "Leafs" from "Maple Leafs", when it has [NICKNAME_WORD_LETTERS] letters or more
 * and is no League's word, since that is how Providers write Event channels) and [weak] ones, its
 * location ("Toronto"), which another team shares.
 */
internal class TeamAliases(team: GameTeam) {
    val weak: List<String> = team.aliases.filter { it != team.name && it != team.shortName && it != team.abbreviation && team.name.startsWith(it) && !team.shortName.startsWith(it) }
    val strong: List<String> = (team.aliases - weak.toSet() + listOfNotNull(nicknameWord(team.shortName), nicknameWord(team.name))).distinct()

    private fun nicknameWord(name: String): String? {
        val words = SearchIndex.words(name)
        val last = words.lastOrNull() ?: return null
        return last.takeIf { words.size > 1 && it.length >= NICKNAME_WORD_LETTERS && it !in LEAGUE_WORDS }
    }

    private companion object {
        const val NICKNAME_WORD_LETTERS = 4
        val LEAGUE_WORDS: Set<String> = League.entries.flatMap { league -> league.names.flatMap(SearchIndex::words) }.toSet()
    }
}

/** Whether [words] hold [phrase]'s words in order, one after another. */
private fun containsPhrase(words: List<String>, phrase: List<String>): Boolean {
    if (phrase.size > words.size) return false
    outer@ for (from in 0..words.size - phrase.size) {
        for (i in phrase.indices) if (words[from + i] != phrase[i]) continue@outer
        return true
    }
    return false
}

/**
 * Whether an Event channel whose name says its Game starts at [start] carries [game]. [eventStart]
 * reads the name in the Provider's zone, or the one it states, but a Provider's zone is a guess
 * and names are sometimes stale, so the start counts as the Game's when it is within
 * [SAME_EVENT_WITHIN] of the schedule's, which holds for the same wall-clock day read in any zone
 * on Earth (a 7 PM Toronto start named "19:00", "00:00" or "16:00" all pass) and fails for the same
 * teams' Game the day before or after. One marked `Live |` or `Next |` gives no start: it carries a
 * Game that is on, or starts within [EVENT_FINISHED_AFTER].
 */
internal fun sameEvent(game: Game, start: Instant?, now: Instant): Boolean {
    if (start == null) return game.state == GameState.Live || Duration.between(now, game.start).abs() <= EVENT_FINISHED_AFTER
    return Duration.between(start, game.start).abs() <= SAME_EVENT_WITHIN
}

/**
 * [feeds] best first (CONTEXT.md: the best Feed): in the Viewer's [contentLanguages] first, in their
 * order, then Feeds whose language isn't known, then the other languages; within a language an Event
 * channel before a Broadcaster's Channel; then by the best quality among the Channel's variants;
 * then as the catalog lists them.
 */
internal fun rankedFeeds(feeds: List<Feed>, contentLanguages: List<String>): List<Feed> {
    val wanted = contentLanguages.map(::primaryLanguage).distinct()
    fun languageRank(language: String?): Int = when (language) {
        null -> wanted.size
        else -> wanted.indexOf(language).let { if (it < 0) wanted.size + 1 else it }
    }
    return feeds.sortedWith(
        compareBy<Feed> { languageRank(it.language) }
            .thenBy { it.kind.ordinal }
            .thenByDescending { feed -> feed.channel.variants.maxOfOrNull { it.quality?.ordinal ?: -1 } ?: -1 },
    )
}

/**
 * The language a Channel's country tag (`UK`, `CA FR`, `DE`) or an Event channel's Category's country
 * code says it broadcasts in, as a primary language subtag, or null when the tag says nothing or is
 * unknown. A language after the country (`CA FR`, `BE NL`) wins over the country's usual one.
 */
internal fun languageOf(tag: String?): String? {
    if (tag == null) return null
    val parts = tag.trim().uppercase().split(Regex("""\s+""")).filter { it.isNotEmpty() }
    if (parts.isEmpty()) return null
    if (parts.size > 1) LANGUAGE_WORDS[parts.last()]?.let { return it }
    return COUNTRY_LANGUAGES[parts.first()]
}

/** How far a start read in the wrong zone can be from the true one: zones run from UTC−12 to UTC+14. */
private val SAME_EVENT_WITHIN: Duration = Duration.ofHours(14)

private val LANGUAGE_WORDS = mapOf("EN" to "en", "FR" to "fr", "ES" to "es", "PT" to "pt", "DE" to "de", "IT" to "it", "NL" to "nl", "AR" to "ar")

private val COUNTRY_LANGUAGES = mapOf(
    "UK" to "en", "GB" to "en", "US" to "en", "USA" to "en", "CA" to "en", "AU" to "en", "NZ" to "en", "IE" to "en", "ZA" to "en", "IN" to "en", "PH" to "en", "JM" to "en",
    "FR" to "fr", "BE" to "fr", "LU" to "fr", "SN" to "fr", "CI" to "fr", "MA" to "ar", "DZ" to "ar", "TN" to "ar", "EG" to "ar", "SA" to "ar", "AE" to "ar", "QA" to "ar",
    "ES" to "es", "MX" to "es", "AR" to "es", "CO" to "es", "CL" to "es", "PE" to "es", "VE" to "es", "UY" to "es", "LAT" to "es", "LATINO" to "es",
    "PT" to "pt", "BR" to "pt", "DE" to "de", "AT" to "de", "CH" to "de", "IT" to "it", "NL" to "nl", "TR" to "tr", "PL" to "pl", "GR" to "el",
    "RU" to "ru", "UA" to "uk", "RO" to "ro", "HU" to "hu", "CZ" to "cs", "SK" to "sk", "SE" to "sv", "NO" to "no", "DK" to "da", "FI" to "fi",
    "RS" to "sr", "HR" to "hr", "BG" to "bg", "AL" to "sq", "IL" to "he", "PK" to "ur", "BD" to "bn", "ID" to "id", "MY" to "ms", "TH" to "th",
    "VN" to "vi", "KR" to "ko", "JP" to "ja", "CN" to "zh", "TW" to "zh", "HK" to "zh",
)
