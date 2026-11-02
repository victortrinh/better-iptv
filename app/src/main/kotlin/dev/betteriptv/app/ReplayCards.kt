package dev.betteriptv.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import dev.betteriptv.app.ui.components.GameCard
import dev.betteriptv.app.ui.components.ReplayCard
import dev.betteriptv.core.LeagueReplay
import dev.betteriptv.core.Replay

/**
 * A Replay of a League's game on Live's Replays row: a Game card with both teams' logos (the short
 * names when a logo doesn't load) and the date in its name as its caption ("Nov 9"). Pressing it
 * plays the Replay ([onPlay]). Never a score.
 */
@Composable
fun ReplayGameCard(entry: LeagueReplay, onPlay: (LeagueReplay) -> Unit, modifier: Modifier = Modifier, onFocus: () -> Unit = {}) {
    val context = LocalContext.current
    val caption = remember(entry) { context.replayCaption(entry.replay) }
    GameCard(
        away = rememberArtwork(entry.away.logo, card = true),
        home = rememberArtwork(entry.home.logo, card = true),
        awayName = entry.away.shortName,
        homeName = entry.home.shortName,
        caption = caption,
        onClick = { onPlay(entry) },
        modifier = modifier,
        onFocus = onFocus,
    )
}

/** A Replay found by Search: its name without its Prefix and the date in it, on a card without a poster. Pressing it plays the Replay ([onPlay]). */
@Composable
fun ReplayCard(replay: Replay, onPlay: (Replay) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val caption = remember(replay) { context.replayCaption(replay) }
    ReplayCard(replay.heading, caption, onClick = { onPlay(replay) }, modifier = modifier)
}
