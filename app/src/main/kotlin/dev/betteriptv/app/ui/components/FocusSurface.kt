package dev.betteriptv.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.betteriptv.app.ui.theme.Corners
import dev.betteriptv.app.ui.theme.FocusScale

/** How a surface shows focus. */
enum class FocusWeight {
    /** Artwork: grows, lifts on a deep shadow, and catches a light sheen with a bright rim. */
    Card,
    /** Buttons, tabs, keys: grow a little; their container turns white. */
    Control,
    /** Rows in a list: keep their size so their edges stay inside the screen. */
    Row,
}

/**
 * Something the remote can focus and select. All focus feedback in the app comes from here, so
 * focus reads the same on every screen.
 */
@Composable
fun FocusSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    weight: FocusWeight = FocusWeight.Card,
    corner: Dp = if (weight == FocusWeight.Card) Corners.card else Corners.control,
    /** How much a [FocusWeight.Card] grows: less for a wide one. */
    cardScale: Float = FocusScale.card,
    container: Color = Color.Transparent,
    focusedContainer: Color = container,
    onFocus: () -> Unit = {},
    interactionSource: MutableInteractionSource? = null,
    content: @Composable BoxScope.(focused: Boolean) -> Unit,
) {
    // Focus is read from the focus system rather than collected from interactions: a grid brings in
    // a row of cards at each press, and on the stick every coroutine a card starts adds up (#63).
    var focused by remember { mutableStateOf(false) }
    val target = when {
        !focused -> 1f
        weight == FocusWeight.Card -> cardScale
        weight == FocusWeight.Control -> FocusScale.control
        else -> 1f
    }
    val scale by animateFloatAsState(target, tween(160), label = "focus scale")
    val shape = RoundedCornerShape(corner)
    val lifted = focused && weight == FocusWeight.Card

    Box(
        modifier
            .onFocusChanged {
                if (it.isFocused != focused) {
                    focused = it.isFocused
                    if (focused) onFocus()
                }
            }
            // One layer scales, clips and casts the shadow: a second one per card costs a frame on the stick.
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                this.shape = shape
                clip = true
                if (lifted) {
                    shadowElevation = 28.dp.toPx()
                    ambientShadowColor = Color.Black
                    spotShadowColor = Color.Black
                }
            }
            .background(if (focused) focusedContainer else container)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick),
    ) {
        content(focused)
        if (lifted) {
            // The light catching a lifted card. The rim keeps focus visible where the shadow can't
            // show, on the dark ground.
            Box(
                Modifier.matchParentSize()
                    .background(Brush.linearGradient(0f to Color.White.copy(alpha = 0.3f), 0.45f to Color.Transparent))
                    .border(1.5.dp, Color.White.copy(alpha = 0.6f), shape),
            )
        }
    }
}
