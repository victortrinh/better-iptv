package dev.betteriptv.app.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp
import dev.betteriptv.app.R
import dev.betteriptv.app.ui.theme.Cards

/**
 * A card in a Top row, as Netflix's Top 10 draws it: the [rank] as a big outlined numeral, and the
 * [card] (a poster [TitleCard]) overlapping its right edge. The numeral never takes focus; the poster
 * does, and grows from its left edge, so it never covers more of the numeral than it does at rest,
 * and casts its shadow down and to the right, away from it (#379). It widens to the right into its backdrop.
 */
@Composable
fun RankedCard(rank: Int, modifier: Modifier = Modifier, style: NumeralStyle = Numerals.style, card: @Composable () -> Unit) {
    val numeral = painterResource(Numerals.of(rank, style))
    val room = Cards.numeralWidth + Cards.numeralOverlap
    // Drawn at its own size: its digits Cards.numeralHeight tall, with room around them for the stroke.
    val size = with(LocalDensity.current) { numeral.intrinsicSize.toDpSize() }
    val width = size.width
    // As wide as the numeral and the card: a focused poster grows to the right of the numeral, which stays put (#216).
    Box(modifier) {
        // As tall as the poster, which sets the card's height.
        Box(Modifier.matchParentSize().wrapContentWidth(Alignment.Start).width(room)) {
            Image(
                numeral,
                contentDescription = null,
                Modifier
                    .align(Alignment.CenterStart)
                    // Ending where the room does; a 10, wider, starts where it does and runs on under the poster.
                    .wrapContentWidth(Alignment.Start, unbounded = true)
                    .offset(x = (room - width).coerceAtLeast(0.dp))
                    .requiredSize(size)
                    .testTag(NUMERAL_TAG),
            )
        }
        Box(Modifier.padding(start = Cards.numeralWidth)) {
            CompositionLocalProvider(LocalFocusFromLeft provides true) { card() }
        }
    }
}

/**
 * How a Top row's numerals are drawn (#379): which open-licence condensed face they are cut from
 * (scripts/numerals), each a style the Viewer could have picked.
 */
enum class NumeralStyle {
    /** Barlow Condensed ExtraBold: a grotesque like Inter, heavy as Netflix's. */
    Barlow,
    /** Bebas Neue: the classic Top 10 look, slimmer and very even. */
    Bebas,
    /** Big Shoulders Display Black: squarer, with more character. */
    BigShoulders,
}

/** Top rows' numerals, in one style. */
object Numerals {
    /** The style the Top rows show: the one switch to change it. */
    val style = NumeralStyle.Barlow

    /** Rank [rank]'s numeral, 1 to 10 (0 is cut too, for 10), in [style]. */
    @DrawableRes
    fun of(rank: Int, style: NumeralStyle = this.style): Int {
        require(rank in 0..10) { "A Top row ranks 1 to 10, not $rank" }
        return when (style) {
            NumeralStyle.Barlow -> barlow
            NumeralStyle.Bebas -> bebas
            NumeralStyle.BigShoulders -> bigShoulders
        }[rank]
    }

    // Cut by scripts/numerals/numerals.py.
    private val barlow = intArrayOf(
        R.drawable.numeral_barlow_0, R.drawable.numeral_barlow_1, R.drawable.numeral_barlow_2, R.drawable.numeral_barlow_3,
        R.drawable.numeral_barlow_4, R.drawable.numeral_barlow_5, R.drawable.numeral_barlow_6, R.drawable.numeral_barlow_7,
        R.drawable.numeral_barlow_8, R.drawable.numeral_barlow_9, R.drawable.numeral_barlow_10,
    )
    private val bebas = intArrayOf(
        R.drawable.numeral_bebas_0, R.drawable.numeral_bebas_1, R.drawable.numeral_bebas_2, R.drawable.numeral_bebas_3,
        R.drawable.numeral_bebas_4, R.drawable.numeral_bebas_5, R.drawable.numeral_bebas_6, R.drawable.numeral_bebas_7,
        R.drawable.numeral_bebas_8, R.drawable.numeral_bebas_9, R.drawable.numeral_bebas_10,
    )
    private val bigShoulders = intArrayOf(
        R.drawable.numeral_bigshoulders_0, R.drawable.numeral_bigshoulders_1, R.drawable.numeral_bigshoulders_2, R.drawable.numeral_bigshoulders_3,
        R.drawable.numeral_bigshoulders_4, R.drawable.numeral_bigshoulders_5, R.drawable.numeral_bigshoulders_6, R.drawable.numeral_bigshoulders_7,
        R.drawable.numeral_bigshoulders_8, R.drawable.numeral_bigshoulders_9, R.drawable.numeral_bigshoulders_10,
    )
}

/**
 * Whether a focused card grows from its left edge and casts its shadow down and to the right,
 * rather than growing from its centre over a shadow all round: true for a Top row's poster, beside
 * its numeral (#379).
 */
val LocalFocusFromLeft = staticCompositionLocalOf { false }

/** A Top row card's numeral, for tests. */
const val NUMERAL_TAG = "numeral"
