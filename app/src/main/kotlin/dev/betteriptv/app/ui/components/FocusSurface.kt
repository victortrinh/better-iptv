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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.betteriptv.app.ui.theme.FocusKind
import dev.betteriptv.app.ui.theme.Nuage

/** How much a surface reacts to focus: cards get the direction's full treatment, controls a quieter one. */
enum class FocusWeight { Card, Control }

/**
 * Something the remote can focus and select. Draws the direction's focus treatment around [content],
 * scaled together with it, so focus reads the same on every screen.
 */
@Composable
fun FocusSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    weight: FocusWeight = FocusWeight.Card,
    /** Rows in a list keep their size on focus so their edges stay inside the screen. */
    grow: Boolean = true,
    corner: Dp = if (weight == FocusWeight.Card) Nuage.shape.card else Nuage.shape.control,
    container: Color = Color.Transparent,
    focusedContainer: Color = container,
    onFocus: () -> Unit = {},
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    content: @Composable BoxScope.(focused: Boolean) -> Unit,
) {
    val direction = Nuage.direction
    val colors = direction.colors
    val focused by interactionSource.collectIsFocusedAsState()
    LaunchedEffect(focused) { if (focused) onFocus() }
    val targetScale = when {
        !focused || !grow -> 1f
        weight == FocusWeight.Card -> direction.focusedScale
        else -> 1f + (direction.focusedScale - 1f) / 2
    }
    val scale by animateFloatAsState(targetScale, tween(140), label = "focus scale")
    val shape = RoundedCornerShape(corner)
    val decorated = if (weight == FocusWeight.Card) {
        Modifier.cardFocus(focused, direction.focus, shape, corner, colors.accent, colors.ink)
    } else Modifier

    Box(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .then(decorated)
            .clip(shape)
            .background(if (focused) focusedContainer else container)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick),
    ) {
        content(focused)
        if (focused && weight == FocusWeight.Card && direction.focus == FocusKind.Parallax) {
            // The light catching a lifted card.
            Box(
                Modifier.matchParentSize()
                    .background(Brush.linearGradient(0f to Color.White.copy(alpha = 0.3f), 0.45f to Color.Transparent))
                    .border(1.5.dp, Color.White.copy(alpha = 0.6f), shape),
            )
        }
    }
}

private fun Modifier.cardFocus(focused: Boolean, kind: FocusKind, shape: Shape, corner: Dp, accent: Color, ink: Color): Modifier =
    if (!focused) this else when (kind) {
        FocusKind.OffsetFrame -> drawBehind {
            val gap = 5.dp.toPx()
            val width = 3.dp.toPx()
            drawRect(accent, Offset(-gap, -gap), Size(size.width + 2 * gap, size.height + 2 * gap), style = Stroke(width))
        }
        FocusKind.Underline -> drawBehind {
            val ring = 2.dp.toPx()
            val r = corner.toPx()
            drawRoundRect(ink, Offset(-ring, -ring), Size(size.width + 2 * ring, size.height + 2 * ring), CornerRadius(r + ring), style = Stroke(ring))
            val barTop = size.height + 7.dp.toPx()
            drawRoundRect(accent, Offset(0f, barTop), Size(size.width, 4.dp.toPx()), CornerRadius(2.dp.toPx()))
        }
        FocusKind.Mat -> drawBehind {
            val mat = 6.dp.toPx()
            drawRect(accent, Offset(-mat, -mat), Size(size.width + 2 * mat, size.height + 2 * mat))
        }
        FocusKind.Lift -> shadow(18.dp, shape, ambientColor = Color.Black, spotColor = Color.Black).drawBehind {
            val ring = 2.dp.toPx()
            val r = corner.toPx()
            drawRoundRect(accent, Offset(-ring, -ring), Size(size.width + 2 * ring, size.height + 2 * ring), CornerRadius(r + ring), style = Stroke(ring))
        }
        FocusKind.Parallax -> shadow(28.dp, shape, ambientColor = Color.Black, spotColor = Color.Black)
        FocusKind.Ring -> drawBehind {
            val ring = 3.dp.toPx()
            val r = corner.toPx()
            drawRoundRect(ink, Offset(-ring * 1.5f, -ring * 1.5f), Size(size.width + 3 * ring, size.height + 3 * ring), CornerRadius(r + ring), style = Stroke(ring))
        }
    }
