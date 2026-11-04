package dev.betteriptv.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.semantics

/**
 * A team's colour as its half of a Game card or page shows it (#349): its [color], or its
 * [alternate] when the colour is too dark to tell from the ground at [FIELD_ALPHA] (a black or
 * near-black one, the Raiders' or the Kings'), so its logo still stands off the glass. A dark colour
 * with no lighter alternate stays. Null when the schedule gives the team no colour.
 */
fun teamTint(color: Int?, alternate: Int?): Color? {
    val main = color?.let(::Color) ?: return null
    val other = alternate?.let(::Color)
    return if (main.luminance() < TOO_DARK && other != null && other.luminance() > main.luminance()) other else main
}

/**
 * The two teams' colours side by side at [FIELD_ALPHA] over whatever is behind (the ground, through
 * a card's glass): the [away] team's on the left half, the [home] team's on the right, as their logos
 * sit. A side with no colour stays clear; the teams' colours are their artwork, so they are the only
 * hue the Live tab adds (DESIGN.md: The Artwork Carries the Colour Rule). Draws nothing when neither
 * team has a colour, so such a Game looks as it did.
 */
@Composable
fun TeamField(away: Color?, home: Color?, modifier: Modifier = Modifier) {
    if (away == null && home == null) return
    Row(modifier) {
        Half(away, "away-colour")
        Half(home, "home-colour")
    }
}

@Composable
private fun RowScope.Half(tint: Color?, tag: String) {
    val base = Modifier.weight(1f).fillMaxHeight()
    Box(if (tint == null) base else base.background(tint.copy(alpha = FIELD_ALPHA)).testTag(tag).semantics { teamTint = tint })
}

/** The colour a [TeamField]'s half is tinted with, for tests. */
val TeamTint = SemanticsPropertyKey<Color>("TeamTint")
private var SemanticsPropertyReceiver.teamTint by TeamTint

/** How strongly a team's colour lies over the ground. */
const val FIELD_ALPHA = 0.35f

/** Relative luminance under which a team's colour is too dark to separate a logo from the ground: black, and colours a hair off it. */
private const val TOO_DARK = 0.015f
