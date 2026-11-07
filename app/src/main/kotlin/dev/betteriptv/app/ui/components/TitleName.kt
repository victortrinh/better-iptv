package dev.betteriptv.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.tv.material3.Text
import dev.betteriptv.app.ui.theme.Colors
import dev.betteriptv.app.ui.theme.Type

/**
 * A Title's name at the top of the hero and its details, and over the cover while a stream starts:
 * the name in Display type, two lines at most. A Title is named in type: no logo exists (ADR 0014).
 */
@Composable
fun TitleName(name: String, modifier: Modifier = Modifier, textAlign: TextAlign = TextAlign.Start) {
    Text(name, style = Type.display, color = Colors.ink, textAlign = textAlign, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = modifier)
}
