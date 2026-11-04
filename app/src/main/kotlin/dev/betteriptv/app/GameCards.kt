package dev.betteriptv.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import dev.betteriptv.app.ui.components.EventGameCard
import dev.betteriptv.app.ui.components.GameCard
import dev.betteriptv.core.GameFeeds
import dev.betteriptv.core.GameState
import dev.betteriptv.core.League
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant

/**
 * [entry]'s card: both teams' logos, "Live" or the start time in the device's clock, and the short
 * names when a logo doesn't load; an event League's Game shows the League's logo and its event and
 * session instead ([EventGameCard]), and a fight card its main event's fighters' headshots, the
 * first-named left, or their surnames. Pressing it opens the Game's page ([onOpen]); an upcoming Game
 * with no Feed left (none, or every one marked "Not this game") is disabled, saying "No stream found", and opens nothing. The caption is read again
 * when the Game is read again (it went live) and each minute, so a Game later today drops the weekday at midnight without the rows being read. With
 * [league], in a row that mixes Leagues, a team or fight-card Game's card carries its League's logo
 * small at the top left; an event League's already shows it whole.
 */
@Composable
fun GameCard(entry: GameFeeds, onOpen: (GameFeeds) -> Unit, modifier: Modifier = Modifier, onFocus: () -> Unit = {}, league: Boolean = false) {
    val game = entry.game
    val leagueLogo = if (league && game.event == null) rememberArtwork(League.byKey(game.league)?.logo, card = true) else null
    val context = LocalContext.current
    val caption by produceState(context.gameCaption(game), game) {
        // Read again for a Game read again: one going live says so in place, its card kept (#342).
        value = context.gameCaption(game)
        if (game.state == GameState.Live) return@produceState
        while (true) {
            delay(CAPTION_REFRESH.toMillis())
            value = context.gameCaption(game, Instant.now())
        }
    }
    val disabled = if (entry.best == null) stringResource(R.string.game_no_stream) else null
    val event = game.event
    if (event != null) {
        EventGameCard(
            logo = rememberArtwork(event.logo, card = true),
            leagueName = context.leagueName(game.league),
            name = event.shortName,
            session = context.sessionName(event.session),
            caption = caption,
            onClick = { onOpen(entry) },
            modifier = modifier,
            disabled = disabled,
            onFocus = onFocus,
        )
        return
    }
    val fighters = game.fight?.fighters
    if (fighters != null) {
        GameCard(
            away = rememberArtwork(fighters.first().headshot, card = true),
            home = rememberArtwork(fighters.last().headshot, card = true),
            awayName = fighters.first().shortName,
            homeName = fighters.last().shortName,
            caption = caption,
            onClick = { onOpen(entry) },
            modifier = modifier,
            disabled = disabled,
            onFocus = onFocus,
            league = leagueLogo,
        )
        return
    }
    GameCard(
        away = rememberArtwork(game.away?.logo, card = true),
        home = rememberArtwork(game.home?.logo, card = true),
        awayName = game.away?.shortName.orEmpty(),
        homeName = game.home?.shortName.orEmpty(),
        caption = caption,
        onClick = { onOpen(entry) },
        modifier = modifier,
        disabled = disabled,
        onFocus = onFocus,
        league = leagueLogo,
    )
}

private val CAPTION_REFRESH: Duration = Duration.ofMinutes(1)
