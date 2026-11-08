package dev.betteriptv.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.paint
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import dev.betteriptv.app.ui.theme.Cards
import dev.betteriptv.app.ui.theme.Colors
import dev.betteriptv.app.ui.theme.Spacing
import dev.betteriptv.app.ui.theme.Type

/**
 * One Title: its poster, the one card shape everywhere, Continue watching included. Its name and
 * badges (best quality and languages) appear under it while it has focus, so the artwork leads; not
 * when [named] is false (Home, Movies and Shows, where the poster's printed title names it and
 * [RailInfo] tells the rest). Without an [image], the card is glass with the name on it. [progress]
 * draws how far the Viewer got, along the poster's bottom edge.
 *
 * A poster stays a poster (#474): focused, it lifts 1.12x and its row never widens.
 */
@Composable
fun TitleCard(
    image: Painter?,
    name: String,
    badges: List<BadgeSpec>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    progress: Float? = null,
    named: Boolean = true,
    onFocus: () -> Unit = {},
) {
    var focused by remember { mutableStateOf(false) }

    // As few nodes as the card can be: a grid brings in a whole row of them at each press (#63).
    // The bottom padding is the name area, reserved so rows don't jump when focus moves.
    Box(
        modifier
            .width(Cards.posterWidth)
            .then(if (named) Modifier.padding(bottom = NAME_AREA_TOP + NAME_AREA) else Modifier),
    ) {
        FocusSurface(
            onClick,
            Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .then(if (named) Modifier.onFocusChanged { focused = it.isFocused } else Modifier),
            container = Colors.surface,
            onFocus = onFocus,
        ) { _ ->
            if (image != null) {
                Box(Modifier.matchParentSize().paint(image, contentScale = ContentScale.Crop).semantics { contentDescription = name })
            } else {
                val label = Type.label
                Text(
                    name,
                    // A poster is narrow: long words break with a hyphen rather than anywhere.
                    style = remember(label) { label.copy(hyphens = Hyphens.Auto) },
                    color = Colors.ink,
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.Center).padding(horizontal = Spacing.s),
                )
            }
            if (progress != null) {
                // Inside the surface's clip, so the bar follows the poster's rounded corners.
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(PROGRESS).background(Colors.ground.copy(alpha = 0.6f)))
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(progress).height(PROGRESS).background(Colors.ink).testTag(TITLE_CARD_PROGRESS))
            }
        }
        if (focused && named) {
            // In the reserved area under the card. The gap clears the focus growth.
            Column(Modifier.align(Alignment.BottomStart).offset(y = NAME_AREA_TOP + NAME_AREA).padding(top = NAME_AREA_TOP).height(NAME_AREA)) {
                Text(name, style = Type.title, color = Colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                // Only the focused card shows its slot, so its badges may run on under the next card's.
                BadgeRow(badges, Modifier.padding(top = 3.dp).wrapContentWidth(Alignment.Start, unbounded = true), compact = true)
            }
        }
    }
}

/** The part of the progress bar that is watched, for tests. */
internal const val TITLE_CARD_PROGRESS = "title-card-progress"

private val NAME_AREA_TOP = 12.dp
private val NAME_AREA = 44.dp
/** As thick as the player's scrubber. */
private val PROGRESS = 6.dp
