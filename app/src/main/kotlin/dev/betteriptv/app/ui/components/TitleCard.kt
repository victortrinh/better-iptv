package dev.betteriptv.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.paint
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import dev.betteriptv.app.ui.theme.Cards
import dev.betteriptv.app.ui.theme.Colors
import dev.betteriptv.app.ui.theme.Spacing
import dev.betteriptv.app.ui.theme.Type
import kotlin.math.min
import kotlin.math.roundToInt

/** What the info area under a focused Home card tells, after the Title's name. */
sealed interface CardInfo {
    /** A Title the Viewer is in: the [episode] they're at ("S1 E6 · Pilot") for a Show, and the time [left] ("47 min left"). */
    data class InProgress(val episode: String?, val left: String) : CardInfo

    /** A Title not started: its [metadata] line (year · runtime · genres) and its [description]. */
    data class About(val metadata: String, val description: String?) : CardInfo
}

/**
 * One Title: its poster, or a landscape still when [wide] (Continue watching). Its name and badges
 * (best quality and languages) appear under it while it has focus, so the artwork leads. With
 * [info] (Home), that area is taller and wider than the card and also tells the [CardInfo], doing
 * the hero's job once it has scrolled away.
 * Without an [image], the card is glass with the name on it. [progress] draws how far the Viewer got.
 */
@Composable
fun TitleCard(
    image: Painter?,
    name: String,
    badges: List<BadgeSpec>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    wide: Boolean = false,
    progress: Float? = null,
    info: CardInfo? = null,
    onFocus: () -> Unit = {},
) {
    var focused by remember { mutableStateOf(false) }
    val area = if (info != null) INFO_AREA else NAME_AREA
    // Where the card starts on screen, while the info area needs it to stay inside the margin.
    var cardX by remember { mutableFloatStateOf(0f) }

    // As few nodes as the card can be: a grid brings in a whole row of them at each press (#63).
    // The bottom padding is the name area, reserved so rows don't jump when focus moves.
    Box(
        modifier
            .width(if (wide) Cards.wideWidth else Cards.posterWidth)
            .then(if (info != null && focused) Modifier.onPlaced { cardX = it.positionInRoot().x } else Modifier)
            .padding(bottom = NAME_AREA_TOP + area),
    ) {
        FocusSurface(
            onClick,
            Modifier.fillMaxWidth().aspectRatio(if (wide) 16f / 9f else 2f / 3f).onFocusChanged { focused = it.isFocused },
            container = Colors.surface,
            onFocus = onFocus,
        ) {
            if (image != null) {
                Box(Modifier.matchParentSize().paint(image, contentScale = ContentScale.Crop).semantics { contentDescription = name })
            } else {
                Text(
                    name,
                    // A poster is narrow: long words break with a hyphen rather than anywhere.
                    style = Type.label.copy(hyphens = Hyphens.Auto),
                    color = Colors.ink,
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.Center).padding(horizontal = Spacing.s),
                )
            }
            if (progress != null) {
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Colors.ground.copy(alpha = 0.6f)))
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(progress).height(4.dp).background(Colors.ink))
            }
        }
        if (focused) {
            // In the reserved area under the card. The gap clears the focus growth.
            val placement = Modifier.align(Alignment.BottomStart).offset(y = NAME_AREA_TOP + area).padding(top = NAME_AREA_TOP)
            if (info == null) {
                Column(placement.height(NAME_AREA)) {
                    Text(name, style = Type.title, color = Colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    BadgeRow(badges, Modifier.padding(top = 3.dp), compact = true)
                }
            } else {
                val screenWidth = LocalWindowInfo.current.containerSize.width
                Column(placement.infoPlacement { screenWidth - Spacing.screenX.roundToPx() - cardX }) {
                    Text(name, style = Type.title, color = Colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(top = INFO_GAP),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** One line of the info area, in body type, its [badges] after it. */
@Composable
private fun InfoLine(text: String, color: Color, badges: List<BadgeSpec> = emptyList()) {
    Row(Modifier.padding(top = INFO_GAP), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = Type.body, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        if (badges.isNotEmpty()) BadgeRow(badges, Modifier.padding(start = Spacing.s), compact = true)
    }
}

/**
 * Lays the info area out [INFO_WIDTH] wide from the card's left edge, overflowing the card, and
 * moves it left by as much as it would pass [room] (px from the card's left edge to the margin).
 */
private fun Modifier.infoPlacement(room: Density.() -> Float) = layout { measurable, constraints ->
    val width = INFO_WIDTH.roundToPx()
    val placeable = measurable.measure(Constraints.fixed(width, INFO_AREA.roundToPx()))
    val density: Density = this
    layout(constraints.maxWidth, placeable.height) {
        // Read while placing, so a card moving as its row scrolls only places the area again.
        placeable.place(min(0f, density.room() - width).roundToInt(), 0)
    }
}

private val NAME_AREA_TOP = 12.dp
private val NAME_AREA = 44.dp
// Room for the name, two lines and two of description.
private val INFO_AREA = 92.dp
private val INFO_WIDTH = 360.dp
private val INFO_GAP = 2.dp
