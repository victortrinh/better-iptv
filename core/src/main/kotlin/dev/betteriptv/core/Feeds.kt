package dev.betteriptv.core

import java.time.Duration
import java.time.Instant

/** What kind of Channel a Feed is, in the order Feeds of one language rank (CONTEXT.md: Feed). */
enum class FeedKind {
    /** An Event channel named after the Game: dedicated to it, so it never shows another game. */
    EventChannel,

    /** The Channel of a Broadcaster the schedule names for the Game: a network that may show another game by market. */
    Broadcaster,

    /** The League network ([League.network]: NHL Network, NBA TV), a Feed only when the Game has no other left: it shows one game of the night. */
    LeagueNetwork,
}

/**
 * One way of watching a Game (CONTEXT.md: Feed): a [channel] of some [kind], in a [language] (a
 * primary language subtag, from the Channel's country tag or the Event channel's Category; null when
 * neither says) and, for a Broadcaster's Channel, the [broadcaster] the schedule named. Watching it
 * plays the Channel's variants best first, as watching any Channel does ([Library.channelPlayPlan]).
 * A Feed the Viewer marked "Not this game" on this device is [dropped]: kept in the list, last and
 * marked, never started by Play ([Library.notThisGame]).
 */
data class Feed(val channel: Channel, val kind: FeedKind, val language: String?, val broadcaster: String? = null, val dropped: Boolean = false) {
    /** The Feed's name on the Game page: the Channel's, as the Provider lists it without its tags. */
    val name: String get() = channel.name
}

/** A [game] with its [feeds], best first ([rankedFeeds]), the dropped ones last; none for a Game nothing in the catalog carries. */
data class GameFeeds(val game: Game, val feeds: List<Feed>) {
    /** The Feed Play starts: the best not marked "Not this game", or null when the Game has none left. */
    val best: Feed? get() = feeds.firstOrNull { !it.dropped }

    /** What tells this Game from every other on a screen: its League and the schedule's id. */
    val key: String get() = "${game.league}/${game.id}"

    /** The Feed after [feed] to play when it is dropped: the best one left, [feed] itself apart. */
    fun next(feed: Feed): Feed? = feeds.firstOrNull { !it.dropped && it.channel.key != feed.channel.key }

    /** What a "Not this game" mark is kept by: this Game and the Feed's Channel ([CatalogStore.dropFeed]). */
    fun droppedKey(feed: Feed): String = droppedKey(key, feed.channel.key)

    companion object {
        internal fun droppedKey(gameKey: String, channelKey: String) = "$gameKey|$channelKey"
    }
}

/** An Event channel as the catalog has it: the merged [channel], when its name says its Game [start]s (null when marked `Live |` or `Next |`), and its Category. */
internal class EventChannelListing(val channel: Channel, val start: Instant?, val category: LiveCategory?)

/**
 * Each of [games] with the Feeds the catalog has for it (docs/roadmap.md, M3, "The cloud fetches,
 * the device matches"), the Feeds ranked for the Viewer's [contentLanguages]: among [events], the
 * Event channels whose Provider name holds an alias of both teams, or of one team and one of the
 * League's names ([League.names]), and whose start is the Game's ([sameEvent]); then, among
 * [broadcasters] (Channels by their lower-cased name), the Channels named exactly like one of the
 * Game's Broadcasters, or like one of its other spellings ([broadcasterNames]: "NHL Net" is NHL
 * Network); "ESPN 2" is not ESPN, and no Broadcaster is guessed from the Region. A Game with no
 * Feed that way gets the League network ([League.network], among [broadcasters] too) as a
 * last resort: it shows one game of the night, so it never outranks a named Feed. Names are
 * compared as Search compares them: lower case, accents dropped, an alias found only as whole
 * words in order, so "Jets" is not "Jetsons". A Broadcaster's Channel from another country is a
 * namesake, not the Broadcaster ([broadcasterCountryFits], for the [region]). A Feed in [dropped]
 * (by [GameFeeds.droppedKey]) was marked "Not this game" on this device: it stays listed, last,
 * and once every named Feed is dropped the League network is offered as it is when none exists.
 *
 * Two guards keep a city's other team's Game out: an Event channel naming another offered League
 * ("NBA: Toronto vs Boston" for the Leafs and the Bruins) is never this Game's, and a match resting
 * on the teams' locations alone ([TeamAliases.weak]: no nickname, short name or abbreviation of
 * either) needs the Game's League named too.
 *
 * An event League's Game ([GameEvent], F1's, a NASCAR race or a golf round) has no teams: its
 * Event channels name the League and one of the event's aliases ("NEXT | SÃO PAULO: RACE | … | US:
 * APPLE TV F1 PPV 2", "Golf: World Wide Technology Championship Round 1 @ …"), and no session but
 * the Game's ([SESSION_WORDS]), so Saturday's qualifying never takes Sunday's race's channel, nor
 * round 1 round 2's; one naming a session that is no Game ([NO_GAME_SESSIONS]: practice, the
 * sprint's qualifying) is none's, nor one naming another series or tour of the sport
 * ([League.otherSeries]: NASCAR's Truck Series races at the Cup's track the same weekend; the LPGA,
 * the Korn Ferry Tour). A name two Leagues share ("Golf") keeps neither out.
 *
 * A fight-card League's Game ([GameFight], UFC's) has no teams either: its Event channels name the
 * League and an alias of both main-event fighters ("UFC event: 1 name: UFC FIGHT NIGHT: MOICANO VS
 * NOLAN PRELIMS start:…"), or the League and the card's number ([numberedCard]: "UFC 334"). A
 * card of another promotion in the League's slots ("UFC 00 : ANTHONY PETTIS FC 26") names neither.
 */
internal fun matchFeeds(
    games: List<Game>,
    events: List<EventChannelListing>,
    broadcasters: Map<String, List<Channel>>,
    contentLanguages: List<String>,
    now: Instant,
    dropped: Set<String> = emptySet(),
    region: String = "CA",
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
    // Another League's names, those it shares with this one ("Golf") apart.
    val notThis = HashMap<League?, Set<Int>>()
    fun otherLeagues(league: League?): Set<Int> = notThis.getOrPut(league) {
        val own = league?.names.orEmpty().map { it.lowercase() }
        holdingAny(League.entries.filter { it != league }.flatMap { it.names }.filter { it.lowercase() !in own })
    }
    return games.map { game ->
        val thisLeague = League.byKey(game.league)
        val league = thisLeague?.let { byLeague[it] }.orEmpty()
        val otherLeague = otherLeagues(thisLeague)
        val event = game.event
        val fight = game.fight
        val named = if (fight != null) {
            // A fight card's: the League and both main-event fighters, or the League and the card's number.
            val both = fight.fighters.map { holdingAny(it.aliases) }.reduce { a, b -> a intersect b }
            ((both + numberedCard(fight)?.let(::holding).orEmpty()) intersect league) - otherLeague
        } else if (event != null) {
            // An event League's: the League and a name of the event, and no session of the same kind but its own.
            val own = holdingAny(SESSION_WORDS.getValue(event.session))
            val others = holdingAny(SESSION_WORDS.filterKeys { it != event.session && it.round == event.session.round }.values.flatten()) - own
            val otherSeries = holdingAny(thisLeague?.otherSeries.orEmpty())
            (holdingAny(event.aliases) intersect league) - otherLeague - others - holdingAny(NO_GAME_SESSIONS) - otherSeries
        } else {
            val home = TeamAliases(game.home ?: return@map GameFeeds(game, emptyList()))
            val away = TeamAliases(game.away ?: return@map GameFeeds(game, emptyList()))
            val homeStrong = holdingAny(home.strong)
            val awayStrong = holdingAny(away.strong)
            val homeAny = homeStrong + holdingAny(home.weak)
            val awayAny = awayStrong + holdingAny(away.weak)
            val both = (homeAny intersect awayAny) intersect (homeStrong + awayStrong + league)
            (both + ((homeAny + awayAny) intersect league)) - otherLeague
        }
        val eventFeeds = named.sorted().map { events[it] }.filter { sameEvent(game, it.start, now) }.map { listing ->
            Feed(listing.channel, FeedKind.EventChannel, languageOf(listing.channel.country ?: listing.category?.country))
        }
        val gameKey = "${game.league}/${game.id}"
        fun named(broadcaster: String, kind: FeedKind) = broadcasterNames(broadcaster).flatMap { name -> broadcasters[name].orEmpty() }
            .filter { broadcasterCountryFits(it.country, region, contentLanguages) }
            .map { Feed(it, kind, languageOf(it.country), broadcaster) }
        val broadcasterFeeds = game.broadcasters.flatMap { named(it, FeedKind.Broadcaster) }
        val namedFeeds = (eventFeeds + broadcasterFeeds).distinctBy { it.channel.key }.map { it.copy(dropped = GameFeeds.droppedKey(gameKey, it.channel.key) in dropped) }
        val feeds = if (namedFeeds.any { !it.dropped }) namedFeeds else {
            val network = thisLeague?.network?.let { named(it, FeedKind.LeagueNetwork) }.orEmpty().filter { feed -> namedFeeds.none { it.channel.key == feed.channel.key } }
            namedFeeds + network.map { it.copy(dropped = GameFeeds.droppedKey(gameKey, it.channel.key) in dropped) }
        }
        GameFeeds(game, rankedFeeds(feeds, contentLanguages))
    }
}

/**
 * The names a Provider may list a Broadcaster's Channel under, as [channelKey] folds them (lower
 * case, no spaces, so "ESPN2" meets "ESPN 2"), the schedule's own first: ESPN abbreviates ("NHL
 * Net" for NHL Network, "FS1" for Fox Sports 1) and Providers spell a streaming service's name out
 * ("ESPN Plus", "Amazon Prime Video"). Only spellings of the same network: nothing here turns
 * "ESPN" into "ESPN 2" or TSN into a regional feed.
 */
internal fun broadcasterNames(broadcaster: String): List<String> {
    val name = channelKey(broadcaster)
    return (listOf(name) + BROADCASTER_SPELLINGS[name].orEmpty()).distinct()
}

/** A Channel's or Broadcaster's name as the two are compared: lower case, spaces dropped ([CatalogStore.channelsNamed] keys its answer by it). */
internal fun channelKey(name: String): String = name.lowercase().replace(" ", "")

/** The Channel names of League networks and Broadcasters, every spelling, so one catalog read finds them all ([CatalogStore.channelsNamed]). */
internal fun broadcasterChannelNames(games: List<Game>): List<String> =
    (games.flatMap { game -> game.broadcasters.flatMap(::broadcasterNames) } + games.flatMap { League.byKey(it.league)?.network?.let(::broadcasterNames).orEmpty() }).distinct()

/**
 * Whether a Channel tagged [country] may be a Broadcaster's: the schedule's Broadcasters are the
 * ones airing in the United States, so a US Channel always may, as may one of the Viewer's
 * [region] or of a country broadcasting in one of their [contentLanguages] ([languageOf]), and
 * one with no tag; `TR: FOX`, `AU: ABC` and `JP: TBS` are namesakes, not Fox, ABC and TBS. This
 * keeps a Feed out; it never adds one the schedule didn't name.
 */
internal fun broadcasterCountryFits(country: String?, region: String, contentLanguages: List<String>): Boolean {
    val code = country?.trim()?.uppercase()?.split(Regex("""\s+"""))?.firstOrNull() ?: return true
    val home = region.uppercase().let { if (it == "GB") "UK" else it }
    return code in US_CODES || code == home || (code == "GB" && home == "UK") || languageOf(country) in contentLanguages.map(::primaryLanguage)
}

private val US_CODES = setOf("US", "USA")

/** Spellings of one network, each listed under each other, as [channelKey] folds them. */
private val BROADCASTER_SPELLINGS: Map<String, List<String>> = listOf(
    listOf("NHL Net", "NHL Network"),
    listOf("NBA TV", "NBATV"),
    listOf("NFL Net", "NFL Network"),
    listOf("MLB Net", "MLB Network"),
    listOf("ESPN+", "ESPN Plus"),
    listOf("ESPN2", "ESPN 2"),
    listOf("ESPNU", "ESPN U"),
    listOf("ESPNEWS", "ESPN News"),
    listOf("FS1", "Fox Sports 1"),
    listOf("FS2", "Fox Sports 2"),
    listOf("CBSSN", "CBS Sports Network", "CBS Sports"),
    listOf("NBCSN", "NBC Sports Network", "NBC Sports"),
    listOf("USA Net", "USA Network"),
    listOf("BTN", "Big Ten Network"),
    listOf("SECN", "SEC Network"),
    listOf("ACCN", "ACC Network"),
    listOf("Apple TV", "Apple TV+", "Apple TV Plus"),
    listOf("Prime Video", "Amazon Prime Video", "Amazon Prime"),
    listOf("Peacock", "Peacock TV"),
    listOf("Paramount+", "Paramount Plus"),
    listOf("Golf Chnl", "Golf Channel"),
).flatMap { spellings -> spellings.map { channelKey(it) to spellings.map(::channelKey) } }.toMap()

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

/** The words an Event channel names each session of an event League's Game by, in English and French. */
private val SESSION_WORDS: Map<GameSession, List<String>> = mapOf(
    GameSession.Qualifying to listOf("Qualifying", "Quali", "Qualification", "Qualifications"),
    GameSession.Sprint to listOf("Sprint"),
    GameSession.Race to listOf("Race", "Course"),
    GameSession.Round1 to listOf("Round 1", "R1", "First Round", "1st Round", "Round One", "1er tour", "Premier tour"),
    GameSession.Round2 to listOf("Round 2", "R2", "Second Round", "2nd Round", "Round Two", "2e tour", "Deuxième tour"),
    GameSession.Round3 to listOf("Round 3", "R3", "Third Round", "3rd Round", "Round Three", "3e tour", "Troisième tour"),
    GameSession.Round4 to listOf("Round 4", "R4", "Fourth Round", "4th Round", "Round Four", "4e tour", "Quatrième tour"),
    GameSession.FinalRound to listOf("Final Round", "Round 4", "R4", "Fourth Round", "4th Round", "Round Four", "Dernier tour", "4e tour"),
)

/** The sessions of a Grand Prix weekend that are no Game: an Event channel naming one carries none. */
private val NO_GAME_SESSIONS: List<String> = listOf("Sprint Qualifying", "Sprint Shootout", "Shootout", "Practice", "FP1", "FP2", "FP3", "Essais libres")

/** A numbered fight card's short name ("UFC 334"), which an Event channel may name it by alone, or null for one with no number ("UFC Fight Night"). */
private fun numberedCard(fight: GameFight): String? = fight.shortName.takeIf { name -> name.any(Char::isDigit) }

/** Whether [words] hold [phrase]'s words in order, one after another. */
internal fun containsPhrase(words: List<String>, phrase: List<String>): Boolean {
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
 * channel before a Broadcaster's Channel before the League's network; then by the best quality
 * among the Channel's variants; then as the catalog lists them. The Feeds marked "Not this game"
 * come after every other, in the same order among themselves.
 */
internal fun rankedFeeds(feeds: List<Feed>, contentLanguages: List<String>): List<Feed> {
    val wanted = contentLanguages.map(::primaryLanguage).distinct()
    fun languageRank(language: String?): Int = when (language) {
        null -> wanted.size
        else -> wanted.indexOf(language).let { if (it < 0) wanted.size + 1 else it }
    }
    return feeds.sortedWith(
        compareBy<Feed> { it.dropped }
            .thenBy { languageRank(it.language) }
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
