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
    val ground = Colors.ground
    Image(image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to ground.copy(alpha = 0.85f), 0.55f to ground.copy(alpha = 0.25f), 1f to Color.Transparent)))
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to ground.copy(alpha = 0.85f), 0.2f to Color.Transparent, 0.35f to Color.Transparent, 0.75f to ground.copy(alpha = 0.85f), 1f to ground)))
}
