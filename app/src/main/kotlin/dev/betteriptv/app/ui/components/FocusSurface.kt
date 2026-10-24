package dev.betteriptv.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
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
    container: Color = Color.Transparent,
    focusedContainer: Color = container,
    onFocus: () -> Unit = {},
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    content: @Composable BoxScope.(focused: Boolean) -> Unit,
) {
    val focused by interactionSource.collectIsFocusedAsState()
    LaunchedEffect(focused) { if (focused) onFocus() }
    val target = when {
        !focused -> 1f
        weight == FocusWeight.Card -> FocusScale.card
        weight == FocusWeight.Control -> FocusScale.control
        else -> 1f
    }
    val scale by animateFloatAsState(target, tween(160), label = "focus scale")
    val shape = RoundedCornerShape(corner)

    Box(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .then(if (focused && weight == FocusWeight.Card) Modifier.shadow(28.dp, shape, ambientColor = Color.Black, spotColor = Color.Black) else Modifier)
            .clip(shape)
            .background(if (focused) focusedContainer else container)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick),
    ) {
        content(focused)
        if (focused && weight == FocusWeight.Card) {
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
