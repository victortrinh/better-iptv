package dev.betteriptv.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.paint
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.tv.material3.Text
import dev.betteriptv.app.ui.theme.Cards
import dev.betteriptv.app.ui.theme.Colors
import dev.betteriptv.app.ui.theme.FocusScale
import dev.betteriptv.app.ui.theme.Motion
import dev.betteriptv.app.ui.theme.Spacing
import dev.betteriptv.app.ui.theme.Type
import kotlinx.coroutines.launch

/**
 * One Title: its poster, or a landscape still as tall as a poster when [wide] (Continue watching). Its name and badges
 * (best quality and languages) appear under it while it has focus, so the artwork leads; not when
 * [named] is false (Home, where [RailInfo] tells them).
 * Without an [image], the card is glass with the name on it. [progress] draws how far the Viewer got.
 * [art] draws over the artwork: Home's [CardLogo].
 *
 * A poster with [grown] art grows into it once focused, at once, keeping its height and its left
 * edge, so the cards after it move right; it shrinks back the same way when focus leaves (#216).
 * Grown, it is named on its art, not under it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TitleCard(
    image: Painter?,
    name: String,
    badges: List<BadgeSpec>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    wide: Boolean = false,
    progress: Float? = null,
    named: Boolean = true,
    onFocus: () -> Unit = {},
    art: (@Composable BoxScope.() -> Unit)? = null,
    grown: (@Composable BoxScope.() -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val grows = grown != null && !wide
    // How far the card has grown, 0 to 1. Animated only when focus moves, from a scope that starts
    // nothing until then, and read in the layout: each frame of it measures the card again but
    // doesn't compose it again (#63).
    val growth = remember(grows) { if (grows) Animatable(0f) else null }
    // Whether the grown art shows: from focus until the card has shrunk back.
    var spread by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val bring = remember(grows) { if (grows) BringIntoViewRequester() else null }
    val showsGrown = spread && growth != null

    // As few nodes as the card can be: a grid brings in a whole row of them at each press (#63).
    // The bottom padding is the name area, reserved so rows don't jump when focus moves.
    Box(
        modifier
            .then(
                if (growth == null) {
                    Modifier.width(if (wide) Cards.continueWidth else Cards.posterWidth)
                } else {
                    Modifier.layout { measurable, constraints ->
                        val width = lerp(Cards.posterWidth, Cards.grownWidth, growth.value).roundToPx()
                        val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
                        layout(width, placeable.height) { placeable.place(0, 0) }
                    }
                },
            )
            .then(if (named) Modifier.padding(bottom = NAME_AREA_TOP + NAME_AREA) else Modifier),
    ) {
        FocusSurface(
            onClick,
            Modifier
                .fillMaxWidth()
                // A growing card keeps the poster's height, so its row doesn't move.
                .then(if (growth != null) Modifier.height(Cards.posterHeight) else Modifier.aspectRatio(if (wide) 16f / 9f else 2f / 3f))
                .then(if (bring != null) Modifier.bringIntoViewRequester(bring) else Modifier)
                .then(
                    if (named || growth != null) {
                        Modifier.onFocusChanged {
                            if (named) focused = it.isFocused
                            if (growth == null || it.isFocused == (growth.targetValue == 1f)) return@onFocusChanged
                            if (it.isFocused) {
                                spread = true
                                scope.launch {
                                    growth.animateTo(1f, tween(Motion.focusMillis))
                                    // Grown, it may reach past the edge of a list that scrolls only near its edges, as in Search.
                                    bring?.bringIntoView()
                                }
                            } else {
                                scope.launch {
                                    growth.animateTo(0f, tween(Motion.focusMillis))
                                    spread = false
                                }
                            }
                        }
                    } else {
                        Modifier
                    },
                ),
            container = Colors.surface,
            // Grown wide, it lifts as a still does, to stay clear of its neighbours.
            cardScale = if (wide || grows) FocusScale.wideCard else FocusScale.card,
            onFocus = onFocus,
        ) { hasFocus ->
            if (showsGrown) {
                grown!!()
            } else {
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
                if (art != null) art()
            }
            if (progress != null) {
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(PROGRESS).background(Colors.ground.copy(alpha = 0.6f)))
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(progress).height(PROGRESS).background(Colors.ink))
            }
        }
        if (focused && named) {
            // In the reserved area under the card. The gap clears the focus growth.
            Column(Modifier.align(Alignment.BottomStart).offset(y = NAME_AREA_TOP + NAME_AREA).padding(top = NAME_AREA_TOP).height(NAME_AREA)) {
                // A grown card is named on its art.
                if (!showsGrown) Text(name, style = Type.title, color = Colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                // Only the focused card shows its slot, so its badges may run on under the next card's: a Top 10 rank is wider than a poster.
                BadgeRow(badges, Modifier.padding(top = 3.dp).wrapContentWidth(Alignment.Start, unbounded = true), compact = true)
            }
        }
    }
}

private val NAME_AREA_TOP = 12.dp
private val NAME_AREA = 44.dp
/** As thick as the player's scrubber, on a still as tall as a poster. */
private val PROGRESS = 6.dp
