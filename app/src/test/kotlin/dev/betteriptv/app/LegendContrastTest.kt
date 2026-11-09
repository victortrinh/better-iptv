package dev.betteriptv.app

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import dev.betteriptv.app.ui.theme.Colors
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Versions screen's Confirmed/Likely legend stays readable on the ground it sits on (ADR 0018):
 * its muted caption text and the Likely badge keep 4.5:1 (WCAG AA).
 */
class LegendContrastTest {
    private fun contrast(a: Color, b: Color): Float {
        val (light, dark) = listOf(a.luminance(), b.luminance()).sortedDescending()
        return (light + 0.05f) / (dark + 0.05f)
    }

    private val brightest = Colors.ground

    @Test fun theLegendsTextKeepsFourAndAHalfToOneOnTheGround() {
        val muted = contrast(Colors.inkMuted, brightest)
        assertTrue("muted caption at $muted:1", muted >= 4.5f)
        val likely = contrast(Colors.likely, brightest)
        assertTrue("Likely badge at $likely:1", likely >= 4.5f)
        // A Confirmed badge carries its own fill.
        val confirmed = contrast(Colors.onConfirmed, Colors.confirmed)
        assertTrue("Confirmed badge at $confirmed:1", confirmed >= 4.5f)
    }
}
