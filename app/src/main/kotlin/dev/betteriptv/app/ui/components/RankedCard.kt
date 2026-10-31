package dev.betteriptv.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import dev.betteriptv.app.ui.theme.Cards
import dev.betteriptv.app.ui.theme.Colors
import dev.betteriptv.app.ui.theme.Type

/**
 * A card in a Top row, as Netflix's Top 10 draws it: the [rank] as a big outlined numeral, and the
 * [card] (a poster [TitleCard]) overlapping its right edge. The numeral never takes focus; the poster
 * does, and grows over it.
 */
@Composable
fun RankedCard(rank: Int, modifier: Modifier = Modifier, card: @Composable () -> Unit) {
    val density = LocalDensity.current
    val stroke = with(density) { NUMERAL_STROKE.toPx() }
    val room = with(density) { (Cards.numeralWidth + Cards.numeralOverlap).toPx() }
    // Inter has no condensed cut, so the numeral is narrowed to Netflix's proportions, and 10
    // further, to fit beside the poster without reaching the card before.
    var natural by remember { mutableFloatStateOf(0f) }
    Box(modifier.width(Cards.rankedWidth)) {
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .width(Cards.numeralWidth + Cards.numeralOverlap)
                .offset(y = NUMERAL_DROP),
        ) {
            val numeral = Modifier
                .align(Alignment.BottomEnd)
                .wrapContentWidth(Alignment.End, unbounded = true)
                .graphicsLayer {
                    scaleX = if (natural > 0f) minOf(CONDENSED, room / natural) else CONDENSED
                    transformOrigin = TransformOrigin(1f, 1f)
                }
            // The outline is drawn twice as wide and its inner half covered by a fill of the ground:
            // Inter's digits are made of overlapping shapes, whose inner edges would show otherwise.
            Text(
                rank.toString(),
                style = Type.numeral.copy(drawStyle = Stroke(width = stroke * 2)),
                color = Colors.inkMuted,
                softWrap = false,
                maxLines = 1,
                onTextLayout = { natural = it.size.width.toFloat() },
                modifier = numeral,
            )
            Text(rank.toString(), style = Type.numeral, color = Colors.ground, softWrap = false, maxLines = 1, modifier = numeral)
        }
        Box(Modifier.align(Alignment.BottomEnd)) { card() }
    }
}

/** How much narrower than Inter's own a numeral is drawn. */
private const val CONDENSED = 0.72f

/** The outline's width as seen: thin enough to read as an outline, thick enough to see from the couch. */
private val NUMERAL_STROKE = 3.5.dp

/** Moves the numeral down by the space its line keeps under the digits, so they stand on the poster's foot. */
private val NUMERAL_DROP = 36.dp
