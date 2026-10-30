package dev.betteriptv.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import dev.betteriptv.app.ui.theme.Colors
import dev.betteriptv.app.ui.theme.Spacing
import dev.betteriptv.app.ui.theme.Type

/** What Home's [InfoPanel] tells about the focused card, under its [name]. */
sealed interface CardInfo {
    val name: String

    /** A Title the Viewer is in: the [episode] they're at ("S1 E6 · Pilot") for a Show, and the time [left] ("47 min left"). */
    data class InProgress(override val name: String, val episode: String?, val left: String) : CardInfo

    /** A Title not started: its [metadata] line (year · runtime · genres) and its [description]. */
    data class About(override val name: String, val metadata: String, val description: String?) : CardInfo

    /** A Channel: what the menu key does on it ([hint]: "≡ Add to Favourites"). */
    data class Channel(override val name: String, val hint: String) : CardInfo
}

/**
 * Home's info panel: the focused card's [info] and [badges], in one place at the left above the
 * rows, doing the hero's job. Always the same size, so the rows under it never move; empty when
 * [info] is null.
 */
@Composable
fun InfoPanel(info: CardInfo?, badges: List<BadgeSpec>, modifier: Modifier = Modifier) {
    Column(modifier.width(INFO_WIDTH).height(INFO_HEIGHT)) {
        if (info == null) return@Column
        Text(info.name, style = Type.headline, color = Colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        when (info) {
            is CardInfo.InProgress -> {
                if (info.episode != null) InfoLine(info.episode, Colors.ink)
                InfoLine(info.left, Colors.inkMuted, badges)
            }
            is CardInfo.About -> {
                InfoLine(info.metadata, Colors.inkMuted, badges)
                if (info.description != null) {
                    Text(
                        info.description,
                        style = Type.body,
                        color = Colors.ink,
                        maxLines = DESCRIPTION_LINES,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = INFO_GAP),
                    )
                }
            }
            is CardInfo.Channel -> InfoLine(info.hint, Colors.inkMuted)
        }
    }
}

/** One line of the panel, in body type, its [badges] after it. */
@Composable
private fun InfoLine(text: String, color: Color, badges: List<BadgeSpec> = emptyList()) {
    Row(Modifier.padding(top = INFO_GAP), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = Type.body, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        if (badges.isNotEmpty()) BadgeRow(badges, Modifier.padding(start = Spacing.s), compact = true)
    }
}

private const val DESCRIPTION_LINES = 3
// About 70 characters a line in English, room for French's longer words.
private val INFO_WIDTH = 620.dp
private val INFO_GAP = 4.dp
// The name, the metadata line and three lines of description.
private val INFO_HEIGHT = 128.dp
