package dev.betteriptv.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import dev.betteriptv.app.ui.theme.Colors
import dev.betteriptv.app.ui.theme.Spacing
import dev.betteriptv.app.ui.theme.Type
import dev.betteriptv.app.ui.theme.inLayoutDirection

/** What Home's [RailInfo] tells about the focused card. Not its name: the artwork and its logo carry that. */
sealed interface CardInfo {
    /**
     * A Title the Viewer is in: the Episode they're at ("S1 E6 · Pilot") or a Movie's name as its
     * [heading], then how far they are ("47 min left", "Start next episode"), null when unknown.
     */
    data class InProgress(val heading: String, val status: String?) : CardInfo

    /**
     * A Title not started: one [metadata] line, year · genre · the Provider's rating ("2024 · Drama ·
     * ★ 6.6"), from what the device holds, so focus costs no Provider call (ADR 0014). No description,
     * no runtime: those are read when the page opens.
     */
    data class About(val metadata: String) : CardInfo

    /** A Channel: its [name], since its card is only a logo, and what the menu key does ([hint]: "≡ Add to Favourites"). */
    data class Channel(val name: String, val hint: String) : CardInfo

    /**
     * A Game: its League and both [teams] ("NHL · Canadiens at Maple Leafs"), since its card is two
     * logos, then its [feeds]: the Broadcasters the schedule names and how many Feeds were found
     * ("TSN · 3 Feeds"), or that none was. Never a score.
     */
    data class Game(val teams: String, val feeds: String) : CardInfo
}

/**
 * The info under Home's focused rail, as on Netflix: the focused card's [info] and [badges], at the
 * left margin. As tall as the rail's kind of card needs ([short] for Continue watching, Favourites
 * and the Game rows, which have two lines; a Title row's is one line with the compact badges),
 * whichever card it tells about, so the rails under it stay put as focus moves along. Empty when
 * [info] is null.
 */
@Composable
fun RailInfo(info: CardInfo?, badges: List<BadgeSpec>, short: Boolean, modifier: Modifier = Modifier) {
    Column(modifier.width(INFO_WIDTH).height(if (short) SHORT_HEIGHT else HEIGHT)) {
        when (info) {
            null -> Unit
            is CardInfo.InProgress -> {
                Line(info.heading)
                if (info.status != null) Text(info.status, style = Type.railStrong, color = Colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            is CardInfo.About -> {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("rail info")) {
                    Text(info.metadata, style = Type.railMeta.inLayoutDirection(), color = Colors.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (badges.isNotEmpty()) BadgeRow(badges, Modifier.padding(start = Spacing.s), compact = true)
                }
            }
            is CardInfo.Channel -> {
                Line(info.name)
                Text(info.hint, style = Type.railMeta, color = Colors.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            is CardInfo.Game -> {
                Line(info.teams)
                Text(info.feeds, style = Type.railMeta, color = Colors.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun Line(text: String) = Text(text, style = Type.rail, color = Colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)

// As wide as Netflix's info, about 55 characters of a line.
private val INFO_WIDTH = 500.dp
// One line: the metadata line with its badges.
private val HEIGHT = 22.dp
// Two lines.
private val SHORT_HEIGHT = 50.dp
