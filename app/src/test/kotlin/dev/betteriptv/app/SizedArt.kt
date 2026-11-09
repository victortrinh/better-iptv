package dev.betteriptv.app

import android.graphics.drawable.ColorDrawable
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.TextUnit
import dev.betteriptv.app.ui.theme.TextSize
import dev.betteriptv.app.ui.theme.TypeScale

/**
 * Artwork of one [color] that says it is [width] × [height], as a decoded picture does: a 1280 × 720
 * picture by default, or a 600 × 900 poster.
 */
open class SizedArt(color: Int = 0xFFFF0000.toInt(), private val width: Int = 1280, private val height: Int = 720) : ColorDrawable(color) {
    override fun getIntrinsicWidth() = width
    override fun getIntrinsicHeight() = height
}

/** The size the text of this node is laid out at. */
internal fun SemanticsNodeInteraction.fontSize(): TextUnit {
    val layouts = mutableListOf<TextLayoutResult>()
    fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
    return layouts.single().layoutInput.style.fontSize
}

/** Display type's size at the default Text size: a Title's name in the hero, on its page and over the starting cover. */
internal val displaySize: TextUnit get() = TypeScale.of(TextSize.Medium).display.fontSize
