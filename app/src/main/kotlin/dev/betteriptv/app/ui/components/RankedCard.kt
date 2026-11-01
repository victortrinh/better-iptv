package dev.betteriptv.app.ui.components

import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.Typeface
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import dev.betteriptv.app.ui.theme.Cards
import dev.betteriptv.app.ui.theme.Colors
import dev.betteriptv.app.ui.theme.Type

/**
 * A card in a Top row, as Netflix's Top 10 draws it: the [rank] as a big outlined numeral, and the
 * [card] (a poster [TitleCard]) overlapping its right edge. The numeral never takes focus; the poster
 * does, and grows over it, and widens to the right into its backdrop.
 */
@Composable
fun RankedCard(rank: Int, modifier: Modifier = Modifier, card: @Composable () -> Unit) {
    val density = LocalDensity.current
    val stroke = with(density) { NUMERAL_STROKE.toPx() }
    val room = with(density) { (Cards.numeralWidth + Cards.numeralOverlap).toPx() }
    // Inter has no condensed cut, so the numeral is narrowed to Netflix's proportions, and 10
    // further, to fit beside the poster without reaching the card before.
    var natural by remember { mutableFloatStateOf(0f) }
    // The line keeps room above and below the digits, more above than below, so the numeral is
    // centred on its drawn glyph: moved by how far the glyph's middle sits from the line's.
    var lift by remember { mutableFloatStateOf(0f) }
    // Resolved once a card, not at each composition.
    val resolver = LocalFontFamilyResolver.current
    val typeface = remember(resolver) { resolver.resolve(Type.numeral.fontFamily, Type.numeral.fontWeight ?: FontWeight.Normal) }.value as Typeface
    val size = with(density) { Type.numeral.fontSize.toPx() }
    val middle = remember(rank, typeface, size) { inkMiddle(rank.toString(), typeface, size) }
    val outline = remember(stroke) { Type.numeral.copy(drawStyle = Stroke(width = stroke * 2)) }
    // As wide as the numeral and the card: a focused poster grows to the right of the numeral, which stays put (#216).
    Box(modifier) {
        // As tall as the poster, which sets the card's height.
        Box(Modifier.matchParentSize().wrapContentWidth(Alignment.Start).width(Cards.numeralWidth + Cards.numeralOverlap)) {
            val numeral = Modifier
                .align(Alignment.CenterEnd)
                .wrapContentWidth(Alignment.End, unbounded = true)
                .wrapContentHeight(unbounded = true)
                .graphicsLayer {
                    scaleX = if (natural > 0f) minOf(CONDENSED, room / natural) else CONDENSED
                    transformOrigin = TransformOrigin(1f, 1f)
                    translationY = lift
                }
            // The outline is drawn twice as wide and its inner half covered by a fill of the ground:
            // Inter's digits are made of overlapping shapes, whose inner edges would show otherwise.
            Text(
                rank.toString(),
                style = outline,
                color = Colors.inkMuted,
                softWrap = false,
                maxLines = 1,
                onTextLayout = { layout ->
                    natural = layout.size.width.toFloat()
                    lift = layout.size.height / 2f - (layout.firstBaseline + middle)
                },
                modifier = numeral,
            )
            Text(rank.toString(), style = Type.numeral, color = Colors.ground, softWrap = false, maxLines = 1, modifier = numeral)
        }
        Box(Modifier.padding(start = Cards.numeralWidth)) { card() }
    }
}

/**
 * Where the middle of [text]'s drawn glyph sits below its baseline (negative: above it), as
 * [Type.numeral] draws it at [size] px in [typeface]. Read off the glyph's outline: at this size
 * the paint's own text bounds are a few pixels out. The walk takes thousands of steps, so each
 * numeral is measured once, not each time its card comes on screen (#63).
 */
private fun inkMiddle(text: String, typeface: Typeface, size: Float): Float =
    inkMiddles.getOrPut(InkKey(text, typeface, size)) { measureInkMiddle(text, typeface, size) }

private data class InkKey(val text: String, val typeface: Typeface, val size: Float)

/** The numerals [inkMiddle] has measured. Composition, which asks, runs on the main thread only. */
private val inkMiddles = HashMap<InkKey, Float>()

private fun measureInkMiddle(text: String, typeface: Typeface, size: Float): Float {
    val paint = Paint().apply {
        this.typeface = typeface
        textSize = size
        fontFeatureSettings = Type.numeral.fontFeatureSettings
    }
    val outline = Path().also { paint.getTextPath(text, 0, text.length, 0f, 0f, it) }
    // Walked point by point: the path's bounds would run on to its curves' control points.
    val measure = PathMeasure(outline, false)
    val point = FloatArray(2)
    var top = Float.MAX_VALUE
    var bottom = -Float.MAX_VALUE
    do {
        var distance = 0f
        while (distance <= measure.length) {
            measure.getPosTan(distance, point, null)
            top = minOf(top, point[1])
            bottom = maxOf(bottom, point[1])
            distance += 1f
        }
    } while (measure.nextContour())
    return (top + bottom) / 2f
}

/** How much narrower than Inter's own a numeral is drawn. */
private const val CONDENSED = 0.72f

/** The outline's width as seen: thin enough to read as an outline, thick enough to see from the couch. */
private val NUMERAL_STROKE = 3.5.dp
