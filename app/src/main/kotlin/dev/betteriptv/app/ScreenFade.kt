package dev.betteriptv.app

import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import dev.betteriptv.app.ui.theme.Colors
import dev.betteriptv.app.ui.theme.Motion

/**
 * Fades the screen under it in over the `ground` each time [screen] changes (DESIGN.md, Motion: screen fade-in).
 * Call it after the screens, in the Box that holds them, with what names the screen shown.
 *
 * It's a `ground` veil over the Box, opaque on the new screen's first frame and thinning to nothing over
 * [Motion.screenFadeMillis]: on the `ground` that looks the same as the screen's alpha rising, but the screen is
 * never drawn offscreen, and only the veil, in a layer of its own, redraws each frame. The old screen is gone at
 * once, since the navigation composes one screen at a time: nothing cross-dissolves. The veil takes no focus and
 * no keys, so focus lands where the new screen puts it on the fade's first frame. A change to or from a screen
 * [asIs] picks (the player) cuts, as before: no fade over the picture, and none as it closes. With [firstFades]
 * false the first screen shows as is, for a fade inside a screen that has just faded in itself (Settings' lists).
 */
@Composable
fun BoxScope.ScreenFade(screen: Any?, asIs: (Any?) -> Boolean = { false }, firstFades: Boolean = true) {
    val last = remember { LastScreen() }
    key(screen) {
        // Read on the new screen's first composition, while [last] still names the screen it replaces.
        val cut = asIs(screen) || asIs(last.screen) || (!firstFades && !last.shown)
        // Over frames, not by the clock: closing the Trailer screen can hold the main thread, and a veil
        // timed by the clock went from opaque to gone in one frame, so the page popped in (#407).
        val shown = remember { Fade(if (cut) 1f else 0f) }
        SideEffect {
            last.screen = screen
            last.shown = true
        }
        LaunchedEffect(Unit) { shown.fadeIn(Motion.screenFadeMillis, LinearOutSlowInEasing) }
        Box(Modifier.matchParentSize().graphicsLayer { }.drawBehind {
            val veil = 1f - shown.value
            if (veil > 0f) drawRect(Colors.ground, alpha = veil)
        })
    }
}

/** The screen shown last, not state: it's only read when the next one first composes. */
private class LastScreen(var screen: Any? = null, var shown: Boolean = false)
