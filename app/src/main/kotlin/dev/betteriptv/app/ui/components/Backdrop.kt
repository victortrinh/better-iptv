package dev.betteriptv.app.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import dev.betteriptv.app.ui.theme.Colors

/**
 * Artwork filling the screen behind everything. Scrims keep the tabs at the top, the text on the
 * left and the rows at the bottom readable.
 */
@Composable
fun Backdrop(image: Painter) {
    Image(image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    backdropScrims(Colors.ground).forEach { Box(Modifier.fillMaxSize().background(it)) }
}

/**
 * The scrims over a full-screen backdrop, in drawing order, fading to [ground]: 85% at the left, 25%
 * at 55% of the width, transparent at the right; and down the screen, 85% under the tabs, clear in
 * the middle, the ground at the bottom.
 */
fun backdropScrims(ground: Color): List<Brush> = listOf(
    Brush.horizontalGradient(0f to ground.copy(alpha = 0.85f), 0.55f to ground.copy(alpha = 0.25f), 1f to Color.Transparent),
    Brush.verticalGradient(0f to ground.copy(alpha = 0.85f), 0.2f to Color.Transparent, 0.35f to Color.Transparent, 0.75f to ground.copy(alpha = 0.85f), 1f to ground),
)
