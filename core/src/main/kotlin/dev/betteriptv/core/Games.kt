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
 * nickname, no two alike), and its [logo] URL when the schedule has one.
 */
class GameTeam(val name: String, val shortName: String, val abbreviation: String, val aliases: List<String>, val logo: String?)

/** Which of a Grand Prix weekend's sessions an event League's Game is. Practice and the sprint's qualifying are no Games. */
enum class GameSession { Qualifying, Sprint, Race }

/**
 * What an event League's Game ([League.events]) is part of, in place of two teams: the event's
 * [name] (Pirelli British Grand Prix) and [shortName] as the schedule gives them, sponsor and all,
 * its [session], the names an Event channel may call it by in [aliases] (São Paulo, Brazil), and the
 * League's [logo].
 */
class GameEvent(val name: String, val shortName: String, val session: GameSession, val aliases: List<String>, val logo: String?)

/**
 * A scheduled contest of a [league] (CONTEXT.md: Game), as the cloud's schedule gives it
 * (cloud/src/schedule.ts): its [id] at the schedule source, when it [start]s, its [state], both
 * teams (a team League's) or its [event] (an event League's, whose [home] and [away] are null), and
 * the names of the national Broadcasters the schedule says carry it. Its Feeds are found among the
 * catalog's Channels by the teams' or the event's aliases and the Broadcasters' names.
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
) {
    /** Both teams, the away team first, or none for an event League's Game. */
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

    /** The Games in [league]'s kept [json], as the cloud listed them. A Game missing a part (two teams, or its event) is left out. */
    fun parse(league: String, json: String): List<Game> = (read(json) as? List<*>).orEmpty().filterIsInstance<Map<*, *>>().mapNotNull { game ->
        val event = event(game["event"])
        if (game["event"] != null && event == null) return@mapNotNull null
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
            home = if (event != null) null else team(game["home"]) ?: return@mapNotNull null,
            away = if (event != null) null else team(game["away"]) ?: return@mapNotNull null,
            broadcasters = (game["broadcasters"] as? List<*>).orEmpty().filterIsInstance<String>(),
            event = event,
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
        )
    }

    private fun read(json: String): Any? = JsonReader.of(Buffer().writeUtf8(json)).use { it.readJsonValue() }

    private val adapter = Moshi.Builder().build().adapter(Any::class.java).serializeNulls()
}
