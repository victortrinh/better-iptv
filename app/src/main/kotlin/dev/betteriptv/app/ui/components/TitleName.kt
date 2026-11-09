package dev.betteriptv.app.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.tv.material3.Text
import dev.betteriptv.app.ui.theme.Colors
import dev.betteriptv.app.ui.theme.Type

/**
 * A Title's name in Display type, two lines at most: over the cover while a stream starts, and in
 * [TitleNameSlot] where no Title treatment is shown.
 */
@Composable
fun TitleName(name: String, modifier: Modifier = Modifier, textAlign: TextAlign = TextAlign.Start) {
    Text(name, style = Type.display, color = Colors.ink, textAlign = textAlign, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = modifier)
}

/**
 * A Title's name at the top of the hero and its page, in a slot the height of two Display lines and
 * as wide as the text column, so what is under it never moves: its [treatment] (the studio's
 * lettering cut from its poster, ADR 0016) fitted inside, left-aligned, standing on the line the name's
 * last baseline would sit on; else its name in Display type, at the slot's foot.
 */
@Composable
fun TitleNameSlot(name: String, treatment: Painter?, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val style = Type.display
    val density = LocalDensity.current
    // Two lines of the name, and how far under the last one's baseline they reach.
    val (slot, underBaseline) = remember(measurer, style, density) {
        val twoLines = measurer.measure("A\nA", style)
        with(density) { twoLines.size.height.toDp() to (twoLines.size.height - twoLines.lastBaseline).toDp() }
    }
    Box(modifier.fillMaxWidth().height(slot), contentAlignment = Alignment.BottomStart) {
        if (treatment != null) {
            Image(
                treatment,
                contentDescription = name,
                contentScale = ContentScale.Fit,
                alignment = Alignment.BottomStart,
                modifier = Modifier.fillMaxSize().padding(bottom = underBaseline).testTag(TITLE_TREATMENT),
            )
        } else {
            TitleName(name)
        }
    }
}

/** The Title treatment's image, for tests. */
const val TITLE_TREATMENT = "title treatment"
