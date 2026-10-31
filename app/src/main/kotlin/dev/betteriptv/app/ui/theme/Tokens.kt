package dev.betteriptv.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.betteriptv.app.R

/*
 * The design tokens of the Verre direction (#3, written up in DESIGN.md): tvOS-style Liquid Glass.
 * Artwork carries the colour; the interface is black, white and translucent white. Screens read
 * these, never raw values.
 */

/** Colour roles. */
object Colors {
    /** Behind everything, and what scrims fade artwork into. */
    val ground = Color(0xFF07080A)
    /** Glass: rows, fields, unfocused buttons and tabs. Translucent, so artwork shows through. */
    val surface = Color(0x1FFFFFFF)
    /** Stronger glass: unfocused buttons, the selected tab. */
    val surfaceHigh = Color(0x33FFFFFF)
    /** Laid under glass that carries text over a busy picture: the ground at 75%. */
    val veil = Color(0xBF07080A)
    val ink = Color(0xFFFFFFFF)
    /** Secondary text. 8.6:1 on the ground. */
    val inkMuted = Color(0xFFA9A9AF)
    /** What has focus. Reserved for the one thing the remote will act on. */
    val focus = Color(0xFFFFFFFF)
    val onFocus = Color(0xFF000000)
    /** Fill of a Confirmed badge. */
    val confirmed = Color(0xFFE8E8ED)
    val onConfirmed = Color(0xFF000000)
    /** Outline and text of a Likely badge. */
    val likely = Color(0xFFC7C7CC)
    /** Cautions that aren't errors: a CAM copy, an Audio-description Version. */
    val caution = Color(0xFFFFB35C)
    val error = Color(0xFFFF6B60)
}

private val inter = FontFamily(
    Font(R.font.inter_400, FontWeight.Normal),
    Font(R.font.inter_600, FontWeight.SemiBold),
    Font(R.font.inter_700, FontWeight.Bold),
)

/** The type scale, in Inter. Sizes are for the 960×540 dp TV canvas, read from about 3 m, as checked on the Fire TV Stick (#186). */
object Type {
    /** A Title's name in the hero and on its details page. */
    val display = TextStyle(fontFamily = inter, fontWeight = FontWeight.Bold, fontSize = 42.sp, lineHeight = 44.sp, letterSpacing = (-0.03).em)
    /** Row headings, screen headings, the wordmark. Kept small so the artwork under it leads (#147). */
    val headline = TextStyle(fontFamily = inter, fontWeight = FontWeight.Bold, fontSize = 15.sp, lineHeight = 20.sp, letterSpacing = (-0.01).em)
    /** A Title's name on a card, a language heading. */
    val title = TextStyle(fontFamily = inter, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 19.sp)
    /** Descriptions, metadata lines, messages, field text: what's read from the couch. */
    val body = TextStyle(fontFamily = inter, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp)
    /** The info under Home's focused rail: the metadata line, muted, as Netflix sets it (#182). */
    val railMeta = TextStyle(fontFamily = inter, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, lineHeight = 18.sp)
    /** The info under Home's focused rail: the description, an Episode, a Channel's name. A step above body, as on Netflix. */
    val rail = TextStyle(fontFamily = inter, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp)
    /** The info under Home's focused rail: the time left in Continue watching, its strongest line. */
    val railStrong = TextStyle(fontFamily = inter, fontWeight = FontWeight.Bold, fontSize = 16.sp, lineHeight = 22.sp)
    /** Body beside label type in a table or row: Version cells, a setting's value. Sized so French fits the Version columns. */
    val detail = TextStyle(fontFamily = inter, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp)
    /** Buttons, tabs, settings rows. */
    val label = TextStyle(fontFamily = inter, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, lineHeight = 18.sp)
    /** A Top row's rank beside its poster, drawn as an outline (`RankedCard`): about as tall as the poster, as on Netflix. */
    val numeral = TextStyle(fontFamily = inter, fontWeight = FontWeight.Bold, fontSize = 200.sp, lineHeight = 200.sp, fontFeatureSettings = "pnum")
    /** Badges, metadata, explanations under a setting. */
    val caption = TextStyle(fontFamily = inter, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, lineHeight = 15.sp, letterSpacing = 0.01.em)
}

/** Corner radii. Controls are capsules at their 40 dp height. */
object Corners {
    val card = 12.dp
    val control = 22.dp
    val badge = 4.dp
}

/** Space. Sized for a 960×540 dp TV canvas. */
object Spacing {
    /** Horizontal overscan-safe margin. */
    val screenX = 48.dp
    /** Vertical overscan-safe margin. */
    val screenY = 27.dp
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
}

/** Card sizes on the home rows. */
object Cards {
    /** About 5.5 posters across between the margins, `gap` apart: (960 − 2 × 48) / (136 + 20) ≈ 5.5. */
    val posterWidth = 136.dp
    /** A landscape (16:9) card: Continue watching. */
    val wideWidth = 196.dp
    /** Wide enough that a focused card's growth doesn't touch its neighbours. */
    val gap = 20.dp
    /** A Top row's numeral shows this wide to the left of its poster (`RankedCard`)... */
    val numeralWidth = 72.dp
    /** ...and runs on this far under the poster, which overlaps its right edge. */
    val numeralOverlap = 12.dp
    /** A Top row's card: the numeral, then the poster. */
    val rankedWidth = numeralWidth + posterWidth
}

/** How much things grow on focus. */
object FocusScale {
    const val card = 1.12f
    const val control = 1.06f
}
