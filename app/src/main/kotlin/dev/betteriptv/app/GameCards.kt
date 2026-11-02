package dev.betteriptv.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import dev.betteriptv.app.ui.components.GameCard
import dev.betteriptv.core.GameFeeds
import dev.betteriptv.core.GameState
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant

/**
 * [entry]'s card: both teams' logos, "Live" or the start time in the device's clock, and the short
 * names when a logo doesn't load. Pressing it opens the Game's page ([onOpen]); an upcoming Game
 * with no Feed is disabled, saying "No stream found", and opens nothing. The caption is read again
 * each minute, so a Game later today drops the weekday at midnight without the rows being read.
 */
@Composable
fun GameCard(entry: GameFeeds, onOpen: (GameFeeds) -> Unit, modifier: Modifier = Modifier, onFocus: () -> Unit = {}) {
    val game = entry.game
    val context = LocalContext.current
    val caption by produceState(context.gameCaption(game), game) {
        if (game.state == GameState.Live) return@produceState
        while (true) {
            delay(CAPTION_REFRESH.toMillis())
            value = context.gameCaption(game, Instant.now())
        }
    }
    GameCard(
        away = rememberArtwork(game.away.logo, card = true),
        home = rememberArtwork(game.home.logo, card = true),
        awayName = game.away.shortName,
        homeName = game.home.shortName,
        caption = caption,
        onClick = { onOpen(entry) },
        modifier = modifier,
        disabled = if (entry.feeds.isEmpty()) stringResource(R.string.game_no_stream) else null,
        onFocus = onFocus,
    )
}

private val CAPTION_REFRESH: Duration = Duration.ofMinutes(1)
