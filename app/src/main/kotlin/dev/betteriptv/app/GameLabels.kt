package dev.betteriptv.app

import android.content.Context
import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.betteriptv.app.ui.components.CardInfo
import dev.betteriptv.core.Feed
import dev.betteriptv.core.FeedKind
import dev.betteriptv.core.Game
import dev.betteriptv.core.GameFeeds
import dev.betteriptv.core.GameSession
import dev.betteriptv.core.GameState
import dev.betteriptv.core.League
import dev.betteriptv.core.LeagueReplay
import dev.betteriptv.core.Replay
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** A League's name in the App language: the acronym the Region knows it by, or its name where it has none. */
@Composable
fun leagueName(league: League): String = stringResource(league.nameResource())

/** [leagueName], off the composition. */
fun Context.leagueName(league: League): String = getString(league.nameResource())

private fun League.nameResource(): Int = when (this) {
    League.Nhl -> R.string.league_nhl
    League.Nfl -> R.string.league_nfl
    League.Nba -> R.string.league_nba
    League.Mlb -> R.string.league_mlb
    League.Cfl -> R.string.league_cfl
    League.Mls -> R.string.league_mls
    League.PremierLeague -> R.string.league_premier_league
    League.ChampionsLeague -> R.string.league_champions_league
    League.LaLiga -> R.string.league_la_liga
    League.Bundesliga -> R.string.league_bundesliga
    League.SerieA -> R.string.league_serie_a
    League.Ligue1 -> R.string.league_ligue_1
    League.NcaaFootball -> R.string.league_ncaa_football
    League.NcaaMensBasketball -> R.string.league_ncaa_mens_basketball
    League.Wnba -> R.string.league_wnba
    League.F1 -> R.string.league_f1
    League.Nascar -> R.string.league_nascar
    League.Pga -> R.string.league_pga
    League.Lpga -> R.string.league_lpga
}

/** The name of a Game's League as the schedule keys it ("nhl"), or the key itself for one this release doesn't offer. */
fun Context.leagueName(key: String): String = League.byKey(key)?.let { leagueName(it) } ?: key

/**
 * What a Game's card says at its bottom left, and its page in its header: "Live" while the Game is
 * on, else when it starts in the device's [zone] and clock: the time alone for a Game today
 * ("7:00 PM"), the weekday before it for a later one ("Tue 7:00 PM"). A Game whose day alone is
 * known ([Game.dayOnly]: a golf round before its tee times) says "Today" or its weekday. Never a score.
 */
fun Context.gameCaption(game: Game, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): String {
    if (game.state == GameState.Live) return getString(R.string.game_live)
    if (game.dayOnly) {
        val day = game.start.atZone(zone)
        if (day.toLocalDate() == now.atZone(zone).toLocalDate()) return getString(R.string.row_today)
        return day.format(DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(resources.configuration.locales[0], "EEE"), resources.configuration.locales[0]))
    }
    return startCaption(game.start, now, zone)
}

/**
 * When something starts, in the device's [zone] and clock, as a Game's card says it: the time alone
 * for a start today ("7:00 PM"), the weekday before it otherwise ("Tue 7:00 PM"). Under a focused
 * Event channel on More live events, where the start is read from the Provider's name in the
 * Provider's zone, so it is what the name said, moved to the device's zone.
 */
fun Context.startCaption(start: Instant, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): String {
    val locale = resources.configuration.locales[0]
    val at = start.atZone(zone)
    val sameDay = at.toLocalDate() == now.atZone(zone).toLocalDate()
    val time = if (DateFormat.is24HourFormat(this)) "Hm" else "hm"
    val pattern = DateFormat.getBestDateTimePattern(locale, if (sameDay) time else "EEE$time")
    return at.format(DateTimeFormatter.ofPattern(pattern, locale))
}

/**
 * The two teams, the away team first, by their short names ("Canadiens at Maple Leafs") or their
 * full names; for an event League's Game, its event and session ("MSC Cruises São Paulo GP · Race").
 */
fun Context.matchup(game: Game, short: Boolean = true): String {
    game.event?.let { event -> return getString(R.string.game_event_session, if (short) event.shortName else event.name, sessionName(event.session)) }
    val away = game.away ?: return ""
    val home = game.home ?: return ""
    return getString(R.string.game_matchup, if (short) away.shortName else away.name, if (short) home.shortName else home.name)
}

/** An event League's session in the App language ("Race", "Qualifying", "Round 1"). */
fun Context.sessionName(session: GameSession): String = getString(
    when (session) {
        GameSession.Qualifying -> R.string.game_session_qualifying
        GameSession.Sprint -> R.string.game_session_sprint
        GameSession.Race -> R.string.game_session_race
        GameSession.Round1 -> R.string.game_session_round_1
        GameSession.Round2 -> R.string.game_session_round_2
        GameSession.Round3 -> R.string.game_session_round_3
        GameSession.Round4 -> R.string.game_session_round_4
        GameSession.FinalRound -> R.string.game_session_final_round
    },
)

/**
 * What the info under a rail tells about a Game: its League and both teams on one line, then the
 * Broadcasters whose Channels were found among its Feeds (not the schedule's whole list: a Canadian
 * Viewer has no Fox) and how many Feeds are left, the dropped ones not counted, or that none was found.
 */
fun Context.gameInfo(entry: GameFeeds): CardInfo.Game {
    val game = entry.game
    val feeds = feedCount(entry)
    val found = entry.feeds.filter { !it.dropped }.mapNotNull { it.broadcaster }.distinct().take(3).joinToString(", ")
    return CardInfo.Game("${leagueName(game.league)} · ${matchup(game)}", listOfNotNull(found.ifEmpty { null }, feeds).joinToString(" · "))
}

/** The two teams of a Replay, the away team first, by their short names, as a Game's [matchup]. */
fun Context.matchup(entry: LeagueReplay): String = getString(R.string.game_matchup, entry.away.shortName, entry.home.shortName)

/**
 * What a Replay's card says at its bottom left: the date in its name, short ("Nov 9", with the year
 * when it isn't this year's, [today]'s), the year alone when that is all the name gives, or "Replay"
 * without either.
 */
fun Context.replayCaption(replay: Replay, today: LocalDate = LocalDate.now()): String =
    replayDate(replay, long = false, today) ?: getString(R.string.replay)

/**
 * What the info under a rail tells about a Replay: its League and both teams on one line, as a
 * Game's, then the date in its name in full ("November 9, 2025"), or "Replay" without one.
 */
fun Context.replayInfo(entry: LeagueReplay): CardInfo.Game =
    CardInfo.Game("${leagueName(entry.league.key)} · ${matchup(entry)}", replayDate(entry.replay, long = true) ?: getString(R.string.replay))

/** The date in [replay]'s name, in the App language: the day and month, the year too when [long] or not [today]'s, or the year alone; null without one. */
fun Context.replayDate(replay: Replay, long: Boolean, today: LocalDate = LocalDate.now()): String? {
    val locale = resources.configuration.locales[0]
    val date = replay.date ?: return replay.year?.toString()
    val skeleton = when {
        long -> "yMMMMd"
        date.year == today.year -> "MMMd"
        else -> "yMMMd"
    }
    return date.format(DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale))
}

/** How many Feeds a Game has left, the ones marked "Not this game" not counted ("3 Feeds"), or "No stream found". */
fun Context.feedCount(entry: GameFeeds): String {
    val left = entry.feeds.count { !it.dropped }
    return if (left == 0) getString(R.string.game_no_stream) else resources.getQuantityString(R.plurals.game_feeds, left, left)
}

/**
 * What a Feed is, under its name on the Game page: an Event channel, the Broadcaster's Channel it
 * is, or the League's network standing in; a Feed marked "Not this game" says so after it.
 */
fun Context.feedKind(feed: Feed): String {
    val kind = when (feed.kind) {
        FeedKind.EventChannel -> getString(R.string.feed_event_channel)
        FeedKind.Broadcaster -> getString(R.string.feed_broadcaster, feed.broadcaster.orEmpty())
        FeedKind.LeagueNetwork -> getString(R.string.feed_league_network, feed.broadcaster.orEmpty())
    }
    return if (feed.dropped) "$kind · ${getString(R.string.feed_not_this_game)}" else kind
}

/** A Feed's row in the player's Feed list: what it is and its language ("Event channel · English"), after "Playing" for the one on. */
fun Context.feedNote(feed: Feed, playing: Boolean): String {
    val note = "${feedKind(feed)} · ${feed.language?.let(::languageName) ?: getString(R.string.language_untagged)}"
    return if (playing) "${getString(R.string.player_playing)} · $note" else note
}

/** The muted line under Play naming the Feed it starts: its name and its language ("Senators @ Bruins · English"). */
fun Context.feedLabel(feed: Feed): String = "${feed.name} · ${feed.language?.let(::languageName) ?: getString(R.string.language_untagged)}"
