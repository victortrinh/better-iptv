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
import dev.betteriptv.app.ui.theme.Cards
import dev.betteriptv.app.ui.theme.Colors
import dev.betteriptv.app.ui.theme.Type

/**
 * One Title: its poster, or a landscape still when [wide] (Continue watching). Its name and badges
 * (best quality and languages) appear under it while it has focus, so the artwork leads.
 * [progress] draws how far the Viewer got.
 */
@Composable
fun TitleCard(
    image: Painter,
    name: String,
    badges: List<BadgeSpec>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    wide: Boolean = false,
    progress: Float? = null,
    onFocus: () -> Unit = {},
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()

    Column(modifier.width(if (wide) Cards.wideWidth else Cards.posterWidth)) {
        FocusSurface(
            onClick,
            Modifier.fillMaxWidth().aspectRatio(if (wide) 16f / 9f else 2f / 3f),
            onFocus = onFocus,
            interactionSource = interaction,
        ) {
            Image(image, contentDescription = name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            if (progress != null) {
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Colors.ground.copy(alpha = 0.6f)))
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(progress).height(4.dp).background(Colors.ink))
            }
        }
        // Space is always reserved so rows don't jump when focus moves. The gap clears the focus growth.
        Spacer(Modifier.height(12.dp))
        Box(Modifier.height(44.dp)) {
            if (focused) {
                Column {
                    Text(name, style = Type.title, color = Colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    BadgeRow(badges, Modifier.padding(top = 3.dp), compact = true)
                }
            }
        }
    }
}
