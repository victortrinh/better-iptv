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
import dev.betteriptv.core.GameState
import dev.betteriptv.core.League
import java.time.Instant
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
}

/** The name of a Game's League as the schedule keys it ("nhl"), or the key itself for one this release doesn't offer. */
fun Context.leagueName(key: String): String = League.byKey(key)?.let { leagueName(it) } ?: key

/**
 * What a Game's card says at its bottom left, and its page in its header: "Live" while the Game is
 * on, else when it starts in the device's [zone] and clock: the time alone for a Game today
 * ("7:00 PM"), the weekday before it for a later one ("Tue 7:00 PM"). Never a score.
 */
fun Context.gameCaption(game: Game, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): String {
    if (game.state == GameState.Live) return getString(R.string.game_live)
    val locale = resources.configuration.locales[0]
    val start = game.start.atZone(zone)
    val sameDay = start.toLocalDate() == now.atZone(zone).toLocalDate()
    val time = if (DateFormat.is24HourFormat(this)) "Hm" else "hm"
    val pattern = DateFormat.getBestDateTimePattern(locale, if (sameDay) time else "EEE$time")
    return start.format(DateTimeFormatter.ofPattern(pattern, locale))
}

/** The two teams, visitors first, by their short names ("Canadiens at Maple Leafs") or their full names. */
fun Context.matchup(game: Game, short: Boolean = true): String =
    getString(R.string.game_matchup, if (short) game.away.shortName else game.away.name, if (short) game.home.shortName else game.home.name)

/**
 * What the info under a rail tells about a Game: its League and both teams on one line, then the
 * Broadcasters the schedule names and how many Feeds the catalog has, or that none was found.
 */
fun Context.gameInfo(entry: GameFeeds): CardInfo.Game {
    val game = entry.game
    val feeds = if (entry.feeds.isEmpty()) getString(R.string.game_no_stream) else resources.getQuantityString(R.plurals.game_feeds, entry.feeds.size, entry.feeds.size)
    return CardInfo.Game(
        "${leagueName(game.league)} · ${matchup(game)}",
        (game.broadcasters.take(3).joinToString(", ").ifEmpty { null }?.let { listOf(it) }.orEmpty() + feeds).joinToString(" · "),
    )
}

/** What a Feed is, under its name on the Game page: an Event channel, or the Broadcaster's Channel it is. */
fun Context.feedKind(feed: Feed): String = when (feed.kind) {
    FeedKind.EventChannel -> getString(R.string.feed_event_channel)
    FeedKind.Broadcaster -> getString(R.string.feed_broadcaster, feed.broadcaster.orEmpty())
}

/** The muted line under Play naming the Feed it starts: its name and its language ("Senators @ Bruins · English"). */
fun Context.feedLabel(feed: Feed): String = "${feed.name} · ${feed.language?.let(::languageName) ?: getString(R.string.language_untagged)}"
