package dev.betteriptv.app.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import dev.betteriptv.app.ui.theme.CardCaption
import dev.betteriptv.app.ui.theme.FocusKind
import dev.betteriptv.app.ui.theme.Nuage

/**
 * One Title: its poster (or, when [wide], a landscape still), and under it what the direction shows
 * (name, and the best quality and languages as badges). [progress] draws a Continue watching bar.
 */
@Composable
fun TitleCard(
    poster: Painter,
    name: String,
    badges: List<BadgeSpec>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    progress: Float? = null,
    wide: Boolean = false,
    onFocus: () -> Unit = {},
) {
    val direction = Nuage.direction
    val colors = Nuage.colors
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val width = if (wide) direction.wideWidth else direction.posterWidth

    Column(modifier.width(width)) {
        FocusSurface(onClick, Modifier.fillMaxWidth().aspectRatio(if (wide) 16f / 9f else 2f / 3f), onFocus = onFocus, interactionSource = interaction) {
            Image(poster, contentDescription = name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            if (progress != null) {
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(colors.ground.copy(alpha = 0.6f)))
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(progress).height(4.dp).background(colors.ink))
            }
        }
        // The focus bar of Lampadaire and the mat of Galerie sit just under the poster.
        val gap = when (direction.focus) {
            FocusKind.Underline -> 16.dp
            FocusKind.Mat -> 14.dp
            else -> 10.dp
        }
        Spacer(Modifier.height(gap))
        // Space is always reserved so rows don't jump when focus moves.
        Box(Modifier.height(44.dp)) {
            val showName = direction.cardCaption == CardCaption.Always || focused
            val showBadges = direction.cardCaption != CardCaption.OnFocus || focused
            Column {
                if (showName) {
                    Text(name, style = Nuage.type.title, color = colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (showBadges) BadgeRow(badges, Modifier.padding(top = 3.dp), compact = true)
            }
        }
    }
}
