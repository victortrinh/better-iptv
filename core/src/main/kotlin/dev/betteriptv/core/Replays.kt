package dev.betteriptv.core

import java.time.Instant
import java.time.LocalDate

/**
 * A Sports replay (CONTEXT.md): a Listing of a past game in the Provider's VOD catalog, kept out of
 * the Movies and read from its [name] alone, since its Title number and poster are wrong. [away] and
 * [home] are the two teams the name gives (`SOC - Boston Bruins at Toronto Maple Leafs 09.11.25`:
 * the Bruins at the Leafs; with `vs`, `v` or a dash the first team is at home), null for a name with
 * no two teams (a highlights show, a season review). [date] is the date in the name, read as
 * `dd.mm.yyyy`, `dd.mm.yy` or `yyyy.mm.dd`, or null; a name with only a year in brackets keeps
 * it as [year]. [added] is when the Provider added the Listing, and [category] the Category it is
 * filed under, which with the name says what the Version is (the Prefix table).
 */
data class Replay(
    val listingId: Long,
    val name: String,
    val category: String,
    val added: Instant?,
    val away: String?,
    val home: String?,
    val date: LocalDate?,
    val year: Int?,
) {
    /** The name without its Prefix, for a card: "Boston Bruins at Toronto Maple Leafs 09.11.25". Not a Title's name: a Replay is no Title. */
    val heading: String get() = ReplayNames.body(name)

    /** When the game was, for sorting newest first: the date, else the first day of the year, else null. */
    internal val on: LocalDate? get() = date ?: year?.let { LocalDate.of(it, 1, 1) }
}

/** A [replay] of a [league]'s game, its two teams found among the League's ([away] at [home]), with their logos. */
data class LeagueReplay(val replay: Replay, val league: League, val away: GameTeam, val home: GameTeam)

/** What a Sports replay's name says: the two teams and the date, as [ReplayNames.parse] reads them. */
internal class ParsedReplay(val away: String?, val home: String?, val date: LocalDate?, val year: Int?)

/**
 * Reads Sports replays' names as the surveyed Provider writes them (docs/roadmap.md, M3): a Prefix,
 * two teams joined by `at`, `vs`, `vs.`, `v` or a dash, and a date, `dd.mm.yyyy` for most,
 * `dd.mm.yy` for the North American leagues, `yyyy.mm.dd` for wrestling and `(yyyy)` for fights.
 */
internal object ReplayNames {
    fun parse(name: String): ParsedReplay {
        var body = body(name)
        var date: LocalDate? = null
        var year: Int? = null
        val dated = DATES.firstNotNullOfOrNull { (regex, read) -> regex.find(body)?.let { it to read(it) } }
        if (dated != null) {
            date = dated.second
            body = body.substring(0, dated.first.range.first)
        } else {
            val bracketed = YEAR.find(body)
            if (bracketed != null) {
                year = bracketed.groupValues[1].toInt()
                body = body.substring(0, bracketed.range.first)
            }
        }
        // A date cut short ("Brentford vs. Liverpool 25.10") names no day; it is not a team's name either.
        body = body.replace(PARTIAL_DATE, "").trim()
        val split = WORD_SEPARATORS.find(body) ?: DASH.find(body)
        val teams = split?.let { body.substring(0, it.range.first).trim() to body.substring(it.range.last + 1).trim() }
            ?.takeIf { (first, second) -> first.any(Char::isLetter) && second.any(Char::isLetter) }
        val visiting = split != null && split.groupValues.getOrNull(1).equals("at", ignoreCase = true)
        return ParsedReplay(
            away = teams?.let { if (visiting) it.first else it.second },
            home = teams?.let { if (visiting) it.second else it.first },
            date = date,
            year = year ?: date?.year,
        )
    }

    /** [name] without its Prefix (`SOC - `, `SC - `, `Wresling: `), trimmed. One Prefix only: in `SOC - LAFC - LA Galaxy`, LAFC is a team. */
    fun body(name: String): String = (PREFIX.find(name)?.let { name.substring(it.range.last + 1) } ?: name).trim()

    private fun date(year: Int, month: Int, day: Int): LocalDate? = runCatching { LocalDate.of(year, month, day) }.getOrNull()

    /** `PREFIX - `, as the Prefix table reads it, or a word and a colon (`Wresling: `, `F1: `). */
    private val PREFIX = Regex("""^\s*(?:(?=[A-Z0-9+=-]*[A-Z])[A-Z0-9+]+(?:[-=][A-Z0-9+]+)*(?:\s+-\s*|-\s+|\s+\.\s+)|[A-Za-z0-9]{1,12}:\s+)""")

    /** Each date shape to how it is read; the first found wins, the four-digit year's first so `2024.04.23` isn't `24.04.23`. */
    private val DATES: List<Pair<Regex, (MatchResult) -> LocalDate?>> = listOf(
        Regex("""(\d{4})\.(\d{1,2})\.(\d{1,2})(?!\d)""") to { m -> date(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()) },
        Regex("""(\d{1,2})\.(\d{1,2})\.(\d{4})(?!\d)""") to { m -> date(m.groupValues[3].toInt(), m.groupValues[2].toInt(), m.groupValues[1].toInt()) },
        Regex("""(\d{1,2})\.(\d{1,2})\.(\d{2})(?!\d)""") to { m -> date(2000 + m.groupValues[3].toInt(), m.groupValues[2].toInt(), m.groupValues[1].toInt()) },
    )

    private val YEAR = Regex("""\((19\d\d|20\d\d)\)""")

    private val PARTIAL_DATE = Regex("""\s*\d{1,2}\.\d{1,2}\.?\s*$""")

    /** `at`, `vs`, `vs.` or `v` between the teams, in any case. */
    private val WORD_SEPARATORS = Regex("""\s+(at|vs\.?|v)\s+""", RegexOption.IGNORE_CASE)

    /** A dash with a space on at least one side: `Villarreal - Rayo Vallecano`, never `Saint-Gilloise`. */
    private val DASH = Regex("""\s+-\s*|\s*-\s+""")
}

/**
 * The Replays of [leagues]' teams among [replays] (the Replays row): each Replay whose two teams are
 * both teams of one League, as the League's Games ([teams]: each League's teams, home and away, as
 * the schedule names them) know them, newest first by the date in the name, then by when the
 * Provider added it. A team is found as a Game's Event channel is ([matchFeeds]): a team's name,
 * short name, abbreviation or nickname found as whole words in order in the Replay's team, folded as
 * Search folds names ("STL Blues" is the St. Louis Blues, "17 Washington Huskies" the Huskies). A
 * location alone names no team ("Toronto - Boston" could be two Leagues' games), as it names no
 * Event channel without the League; and a match resting on one word alone ("United", "City", "TOR")
 * holds only when every word of the Replay's team is a word of that team's aliases, so "Leeds
 * United" is not Manchester United when Leeds has no Game this week. When several teams fit, the
 * one whose longest alias fits wins. A Replay naming one team twice, or teams of two Leagues, is left out.
 */
internal fun matchReplays(replays: List<Replay>, teams: Map<League, List<GameTeam>>): List<LeagueReplay> {
    val finder = TeamFinder(teams)
    return replays.mapNotNull { replay ->
        val away = replay.away ?: return@mapNotNull null
        val home = replay.home ?: return@mapNotNull null
        finder.pair(away, home)?.let { (league, found) -> LeagueReplay(replay, league, found.first, found.second) }
    }.sortedWith(compareByDescending<LeagueReplay, LocalDate?>(nullsFirst()) { it.replay.on }.thenByDescending { it.replay.added }.thenBy { it.replay.listingId })
}

/** Whether [replay] is of [game]: its two teams are the Game's, either way round. The date is the caller's to check. */
internal fun replayOf(replay: Replay, game: Game): Boolean {
    val away = replay.away ?: return false
    val home = replay.home ?: return false
    // The League only groups the teams here: one this release doesn't offer does as well as any.
    val league = League.byKey(game.league) ?: League.entries.first()
    val teams = game.teams.takeIf { it.size == 2 } ?: return false
    return TeamFinder(mapOf(league to teams)).pair(away, home) != null
}

/** Finds which of some Leagues' [teams] a Replay's team names, by their aliases' first words. */
private class TeamFinder(teams: Map<League, List<GameTeam>>) {
    /** One alias of a team: its [words], and [teamWords], every word of every alias of the team, for a one-word match to be checked against. */
    private class Known(val league: League, val team: GameTeam, val words: List<String>, val teamWords: Set<String>)

    private val byFirstWord = HashMap<String, MutableList<Known>>()

    init {
        teams.forEach { (league, members) ->
            members.distinctBy { it.name }.forEach { team ->
                val aliases = TeamAliases(team)
                val teamWords = (aliases.strong + aliases.weak).flatMapTo(HashSet(), SearchIndex::words)
                aliases.strong.forEach { add(league, team, it, teamWords) }
            }
        }
    }

    private fun add(league: League, team: GameTeam, alias: String, teamWords: Set<String>) {
        val words = SearchIndex.words(alias)
        if (words.isEmpty()) return
        byFirstWord.getOrPut(words.first()) { ArrayList(2) } += Known(league, team, words, teamWords)
    }

    /** The teams [name] may be, each with its League, the surest first: by the longest alias found, then the longest name. */
    private fun candidates(name: String): List<Known> {
        val words = SearchIndex.words(name)
        val found = ArrayList<Known>()
        words.forEachIndexed { at, word ->
            byFirstWord[word]?.forEach { known ->
                val fits = at + known.words.size <= words.size && containsPhrase(words.subList(at, words.size), known.words) &&
                    (known.words.size > 1 || words.all { it in known.teamWords })
                if (fits && found.none { it.team === known.team }) found += known
            }
        }
        return found.sortedWith(compareByDescending<Known> { it.words.size }.thenByDescending { it.words.sumOf(String::length) })
    }

    /** The League whose teams [away] and [home] both are, with those teams, or null. */
    fun pair(away: String, home: String): Pair<League, Pair<GameTeam, GameTeam>>? {
        val visitors = candidates(away)
        val hosts = candidates(home)
        for (visitor in visitors) {
            val host = hosts.firstOrNull { it.league == visitor.league && it.team !== visitor.team } ?: continue
            return visitor.league to (visitor.team to host.team)
        }
        return null
    }
}
