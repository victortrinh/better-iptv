package dev.betteriptv.app.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import dev.betteriptv.app.ui.theme.Colors
import dev.betteriptv.app.ui.theme.Type

/**
 * A Title's name at the top of the hero and its details: its [logo] art when it has one, fitted in a
 * 320 × 104 dp box and sitting on its bottom edge, else the name in Display type. The logo keeps the
 * name as its content description. While the logo loads the box stays empty, so the name doesn't
 * flash first; if it can't be loaded the caller passes null and the name shows.
 */
@Composable
fun TitleName(name: String, logo: Painter?, modifier: Modifier = Modifier) {
    if (logo != null) {
        Image(logo, contentDescription = name, contentScale = ContentScale.Fit, alignment = Alignment.BottomStart, modifier = modifier.size(LogoWidth, LogoHeight))
    } else {
        Text(name, style = Type.display, color = Colors.ink, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = modifier)
    }
}

/** About Netflix's logo width on the stick. */
private val LogoWidth = 320.dp

/** Two lines of Display type, so a logo takes the room the name would. */
private val LogoHeight = 104.dp
