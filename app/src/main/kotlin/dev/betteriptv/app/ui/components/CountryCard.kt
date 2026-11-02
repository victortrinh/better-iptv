package dev.betteriptv.app.ui.components

import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.tv.material3.Text
import dev.betteriptv.app.ui.theme.Cards
import dev.betteriptv.app.ui.theme.Colors
import dev.betteriptv.app.ui.theme.Spacing
import dev.betteriptv.app.ui.theme.Type

/**
 * A country on Live's Channels row: the wide 16:9 glass card, like a Channel's without a logo, with
 * the country's [name] in the App language centred on it in title type. No flag: the interface
 * brings no colour of its own. Pressing it opens the country's Channels screen ([onClick]). The
 * info under the rail names it and tells how many Channels it has, so the card carries no slot.
 */
@Composable
fun CountryCard(name: String, onClick: () -> Unit, modifier: Modifier = Modifier, onFocus: () -> Unit = {}) {
    FocusSurface(onClick, modifier.width(Cards.wideWidth).aspectRatio(16f / 9f), container = Colors.surface, onFocus = onFocus) {
        Text(
            name,
            style = Type.title,
            color = Colors.ink,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.Center).padding(horizontal = Spacing.m),
        )
    }
}
