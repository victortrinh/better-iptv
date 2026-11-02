package dev.betteriptv.app.ui.components

import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import dev.betteriptv.app.ui.theme.Cards
import dev.betteriptv.app.ui.theme.Colors
import dev.betteriptv.app.ui.theme.Spacing
import dev.betteriptv.app.ui.theme.Type

/**
 * A Sports replay found by Search (CONTEXT.md): the wide 16:9 glass card, like a country's, with
 * the [name] the Provider gives it, less its Prefix, centred in title type on two lines at most,
 * and the [caption] (the date in its name, or "Replay") at the bottom left in caption type. It
 * never shows a poster: the Provider's is a wrong Title's. Pressing it plays the Replay ([onClick]).
 * It carries no slot: the name is on the card.
 */
@Composable
fun ReplayCard(name: String, caption: String, onClick: () -> Unit, modifier: Modifier = Modifier, onFocus: () -> Unit = {}) {
    FocusSurface(onClick, modifier.width(Cards.wideWidth).aspectRatio(16f / 9f), container = Colors.surface, onFocus = onFocus) {
        Text(
            name,
            style = Type.title,
            color = Colors.ink,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.Center).padding(horizontal = Spacing.m, vertical = Spacing.s).padding(bottom = CAPTION_ROOM),
        )
        Text(
            caption,
            style = Type.caption,
            color = Colors.inkMuted,
            maxLines = 1,
            modifier = Modifier.align(Alignment.BottomStart).padding(start = Spacing.s, bottom = CAPTION_BOTTOM),
        )
    }
}

/** The caption's line, kept clear under the name. */
private val CAPTION_ROOM = 14.dp
private val CAPTION_BOTTOM = 6.dp
