package dev.betteriptv.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.tv.material3.Text
import dev.betteriptv.app.ui.theme.Colors
import dev.betteriptv.app.ui.theme.Type

/**
 * A Title's name in Display type, two lines at most: in the hero, at the top of its page, and over the
 * cover while a stream starts when it has no poster. There is no logo from anywhere (ADR 0014, ADR 0018).
 */
@Composable
fun TitleName(name: String, modifier: Modifier = Modifier, textAlign: TextAlign = TextAlign.Start) {
    Text(name, style = Type.display, color = Colors.ink, textAlign = textAlign, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = modifier)
}
