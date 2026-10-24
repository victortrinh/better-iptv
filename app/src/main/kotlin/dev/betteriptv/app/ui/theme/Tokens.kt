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

/** The type scale, in Inter. Sizes are for the 960×540 dp TV canvas, read from about 3 m. */
object Type {
    /** A Title's name in the hero and on its details page. */
    val display = TextStyle(fontFamily = inter, fontWeight = FontWeight.Bold, fontSize = 50.sp, lineHeight = 52.sp, letterSpacing = (-0.03).em)
    /** Row headings, screen headings, the wordmark. */
    val headline = TextStyle(fontFamily = inter, fontWeight = FontWeight.Bold, fontSize = 20.sp, lineHeight = 26.sp, letterSpacing = (-0.01).em)
    /** A Title's name on a card, a language heading. */
    val title = TextStyle(fontFamily = inter, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp)
    val body = TextStyle(fontFamily = inter, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 22.sp)
    /** Buttons, tabs, settings rows. */
    val label = TextStyle(fontFamily = inter, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp)
    /** Badges, metadata, explanations under a setting. */
    val caption = TextStyle(fontFamily = inter, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.01.em)
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
    val posterWidth = 104.dp
    /** A landscape (16:9) card: Continue watching. */
    val wideWidth = 196.dp
    /** Wide enough that a focused card's growth doesn't touch its neighbours. */
    val gap = 20.dp
}

/** How much things grow on focus. */
object FocusScale {
    const val card = 1.12f
    const val control = 1.06f
}
