package dev.betteriptv.app.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.tv.material3.Text
import dev.betteriptv.app.ui.theme.Colors
import dev.betteriptv.app.ui.theme.Spacing
import dev.betteriptv.app.ui.theme.Type

/**
 * A Home card's name, at its bottom left as on Netflix: the Title's [logo] art when it has one
 * ([hasLogo]), fitted in [LOGO_WIDTH] by [LOGO_HEIGHT] of the card, over a short scrim that keeps a
 * light logo readable on bright artwork; without a logo, the [name] in title type. Only over art
 * with no text on it: a [wide] still, or a textless poster, which comes only with a logo.
 */
@Composable
fun BoxScope.CardLogo(logo: Painter?, hasLogo: Boolean, name: String, wide: Boolean) {
    Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().fillMaxHeight(SCRIM_HEIGHT).background(scrim))
    val inset = Modifier.align(Alignment.BottomStart).padding(Spacing.s)
    when {
        logo != null -> Image(
            logo,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            alignment = Alignment.BottomStart,
            modifier = inset.fillMaxWidth(if (wide) WIDE_LOGO_WIDTH else LOGO_WIDTH).fillMaxHeight(if (wide) WIDE_LOGO_HEIGHT else LOGO_HEIGHT),
        )
        // Not while the logo loads, so the name doesn't flash first.
        !hasLogo -> Text(name, style = Type.title, color = Colors.ink, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = inset)
    }
}

private val scrim = Brush.verticalGradient(0f to Colors.ground.copy(alpha = 0f), 1f to Colors.ground.copy(alpha = 0.7f))
private const val SCRIM_HEIGHT = 0.45f
private const val LOGO_WIDTH = 0.8f
private const val LOGO_HEIGHT = 0.25f
// A still is wide: its logo takes less of its width, and about a poster's logo's height, as Netflix's do.
private const val WIDE_LOGO_WIDTH = 0.4f
private const val WIDE_LOGO_HEIGHT = 0.3f
