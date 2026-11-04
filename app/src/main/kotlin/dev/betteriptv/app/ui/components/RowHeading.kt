package dev.betteriptv.app.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import dev.betteriptv.app.ui.theme.Colors
import dev.betteriptv.app.ui.theme.Spacing
import dev.betteriptv.app.ui.theme.Type

/**
 * A row's heading, in headline type on one line. A League's row on Live carries the League's [logo]
 * before its name (#350), whole and as tall as the headline's line, so the row is no taller; with none, or
 * one that can't be loaded, the heading is the name alone, where it always was.
 */
@Composable
fun RowHeading(text: String, modifier: Modifier = Modifier, logo: Painter? = null) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (logo != null) {
            Image(logo, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.size(HEADING_LOGO).testTag("heading-logo"))
            Spacer(Modifier.width(Spacing.s))
        }
        Text(text, style = Type.headline, color = Colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A League's logo before its row's name: the headline's 20 sp line. */
private val HEADING_LOGO = 20.dp
