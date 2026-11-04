package dev.betteriptv.core

import com.squareup.moshi.JsonReader
import com.squareup.moshi.Moshi
import okio.Buffer
import java.time.Instant

/** Where a Game stands: not started, in progress, or finished. Never how far along, and never a score. */
enum class GameState { Upcoming, Live, Over }

/**
 * A team as a Game names it: its [name] (Toronto Maple Leafs), [shortName] (Maple Leafs) and
 * [abbreviation] (TOR), every name it goes by in [aliases] (those three, its location and its
 * nickname, no two alike), and its [logo] URL when the schedule has one. Its [color] and
 * [alternateColor] are the schedule's, as opaque ARGB (0xFF00205B), each null when it has none (#349).
 */
class GameTeam(
    val name: String,
    val shortName: String,
    val abbreviation: String,
    val aliases: List<String>,
    val logo: String?,
    val color: Int? = null,
    val alternateColor: Int? = null,
)

/**
 * Which part of its event an event League's Game is: a Grand Prix weekend's session (practice and
 * the sprint's qualifying are no Games), or a golf tournament's [round], the last one its final round.
 */
enum class GameSession(val round: Boolean = false) {
    Qualifying, Sprint, Race,
    Round1(round = true), Round2(round = true), Round3(round = true), Round4(round = true), FinalRound(round = true),
}

/**
 * What an event League's Game ([League.events]) is part of, in place of two teams: the event's
 * [name] (Pirelli British Grand Prix) and [shortName] as the schedule gives them, sponsor and all,
 * its [session], the names an Event channel may call it by in [aliases] (São Paulo, Brazil), and the
 * League's [logo].
 */
class GameEvent(val name: String, val shortName: String, val session: GameSession, val aliases: List<String>, val logo: String?)

/**
 * One of a fight card's main-event fighters: their [name] (Waldo Cortes Acosta), their [shortName]
 * (the surname the card's name gives, Cortes Acosta), the names an Event channel may call them by in
 * [aliases], and their [headshot] URL when the schedule has one (it may not exist).
 */
class GameFighter(val name: String, val shortName: String, val aliases: List<String>, val headshot: String?)

/**
 * A fight-card League's Game ([League.fights]) in place of two teams: the card's [name] (UFC 334:
 * Gane vs. Hokit) and [shortName] (UFC 334) as the schedule gives them, when its [mainCard] starts
 * (the Game's start is the prelims'), and its main event's two [fighters], in the name's order.
 * Never another bout, a result or a record.
 */
class GameFight(val name: String, val shortName: String, val mainCard: Instant?, val fighters: List<GameFighter>)

/**
 * A scheduled contest of a [league] (CONTEXT.md: Game), as the cloud's schedule gives it
 * (cloud/src/schedule.ts): its [id] at the schedule source, when it [start]s, its [state], both
 * teams (a team League's), its [event] (an event League's) or its [fight] (a fight-card League's;
 * for these two, [home] and [away] are null), and the names of the national Broadcasters the
 * schedule says carry it. A Game [dayOnly] has only its day known yet (a golf round before its tee
 * times are out): [start] is noon US Eastern that day. Its Feeds are found among the catalog's
 * Channels by the teams', the event's or the fighters' aliases and the Broadcasters' names.
 */
class Game(
    val league: String,
    val id: String,
    val start: Instant,
    val state: GameState,
    val home: GameTeam?,
    val away: GameTeam?,
    val broadcasters: List<String>,
    val event: GameEvent? = null,
    val fight: GameFight? = null,
    val dayOnly: Boolean = false,
) {
    /** Both teams, the away team first, or none for an event or fight-card League's Game. */
    val teams: List<GameTeam> get() = listOfNotNull(away, home)
}

/**
 * The cloud's Games answer (cloud/src/games.ts), as the device keeps it: each League's Games as
 * their own JSON, so a League's last copy serves while the cloud fails whatever else was asked with it.
 */
internal object Games {
    /** Each League in the cloud's answer [json], with its Games as JSON. A League the cloud has no copy of is absent. */
    fun leagues(json: String): Map<String, String> {
        val tree = read(json) as? Map<*, *> ?: return emptyMap()
        return (tree["leagues"] as? List<*>).orEmpty().filterIsInstance<Map<*, *>>().mapNotNull { entry ->
            val league = entry["league"] as? String ?: return@mapNotNull null
            league to adapter.toJson(entry["games"] as? List<*> ?: emptyList<Any>())
        }.toMap()
    }

    /** The Games in [league]'s kept [json], as the cloud listed them. A Game missing a part (two teams, its event, or its card's two fighters) is left out. */
    fun parse(league: String, json: String): List<Game> = (read(json) as? List<*>).orEmpty().filterIsInstance<Map<*, *>>().mapNotNull { game ->
        val event = event(game["event"])
        if (game["event"] != null && event == null) return@mapNotNull null
        val fight = fight(game["fight"])
        if (game["fight"] != null && fight == null) return@mapNotNull null
        val teamless = event != null || fight != null
        Game(
            league = league,
            id = game["id"] as? String ?: return@mapNotNull null,
            start = (game["start"] as? String)?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return@mapNotNull null,
            state = when (game["state"]) {
                "upcoming" -> GameState.Upcoming
                "live" -> GameState.Live
                "over" -> GameState.Over
                else -> return@mapNotNull null
            },
            home = if (teamless) null else team(game["home"]) ?: return@mapNotNull null,
            away = if (teamless) null else team(game["away"]) ?: return@mapNotNull null,
            broadcasters = (game["broadcasters"] as? List<*>).orEmpty().filterIsInstance<String>(),
            event = event,
            dayOnly = game["dayOnly"] == true,
            fight = fight,
        )
    }

    /** A fight-card League's Game's card, or null when there is none or it doesn't name two fighters. */
    private fun fight(json: Any?): GameFight? {
        val fight = json as? Map<*, *> ?: return null
        val name = fight["name"] as? String ?: return null
        val fighters = (fight["fighters"] as? List<*>).orEmpty().filterIsInstance<Map<*, *>>().mapNotNull { fighter ->
            val full = fighter["name"] as? String ?: return@mapNotNull null
            GameFighter(
                name = full,
                shortName = fighter["shortName"] as? String ?: full,
                aliases = (fighter["aliases"] as? List<*>).orEmpty().filterIsInstance<String>().ifEmpty { listOf(full) },
                headshot = fighter["headshot"] as? String,
            )
        }
        if (fighters.size != 2) return null
        return GameFight(
            name = name,
            shortName = fight["shortName"] as? String ?: name,
            mainCard = (fight["mainCard"] as? String)?.let { runCatching { Instant.parse(it) }.getOrNull() },
            fighters = fighters,
        )
    }

    /** An event League's Game's event, or null when there is none or its session is one this release doesn't know. */
    private fun event(json: Any?): GameEvent? {
        val event = json as? Map<*, *> ?: return null
        val name = event["name"] as? String ?: return null
        return GameEvent(
            name = name,
            shortName = event["shortName"] as? String ?: name,
            session = when (event["session"]) {
                "qualifying" -> GameSession.Qualifying
                "sprint" -> GameSession.Sprint
                "race" -> GameSession.Race
                "round-1" -> GameSession.Round1
                "round-2" -> GameSession.Round2
                "round-3" -> GameSession.Round3
                "round-4" -> GameSession.Round4
                "final-round" -> GameSession.FinalRound
                else -> return null
            },
            aliases = (event["aliases"] as? List<*>).orEmpty().filterIsInstance<String>(),
            logo = event["logo"] as? String,
        )
    }

    private fun team(json: Any?): GameTeam? {
        val team = json as? Map<*, *> ?: return null
        val name = team["name"] as? String ?: return null
        return GameTeam(
            name = name,
            shortName = team["shortName"] as? String ?: name,
            abbreviation = team["abbreviation"] as? String ?: "",
            aliases = (team["aliases"] as? List<*>).orEmpty().filterIsInstance<String>().ifEmpty { listOf(name) },
            logo = team["logo"] as? String,
            color = colour(team["color"]),
            alternateColor = colour(team["alternateColor"]),
        )
    }

    /** [json], six hex digits as the cloud writes a colour (`00205b`), as opaque ARGB; null when it is none. */
    private fun colour(json: Any?): Int? {
        val hex = (json as? String)?.takeIf { it.length == 6 } ?: return null
        return hex.toIntOrNull(16)?.let { it or 0xFF000000.toInt() }
    }

    private fun read(json: String): Any? = JsonReader.of(Buffer().writeUtf8(json)).use { it.readJsonValue() }

    private val adapter = Moshi.Builder().build().adapter(Any::class.java).serializeNulls()
}
