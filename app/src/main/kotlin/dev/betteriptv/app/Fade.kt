package dev.betteriptv.app

import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis

/**
 * An alpha that fades in from [start] to 1 ([fadeIn]) and is shown over frames, however late they
 * come: each frame moves it on by the time since the last one, but never by more than
 * [LONGEST_FADE_STEP_MILLIS]. A fade timed by the clock alone is over by the first frame after the
 * main thread is held up, so what it brings in pops: on the stick's cold start, Home's rows and hero
 * came after half a second with no frame, at full alpha (#398). Read [value] only in drawing, so a
 * fade redraws and recomposes nothing.
 */
@Stable
class Fade(start: Float) {
    var value by mutableFloatStateOf(start)
        private set

    /** Shows it whole at once: what is already there arrives as is. */
    fun show() {
        value = 1f
    }

    /** Fades it in over [millis] of frames, from the first frame after it's called, on its [easing]. */
    suspend fun fadeIn(millis: Int, easing: Easing = FastOutSlowInEasing) {
        var last = -1L
        var shown = 0L
        while (value < 1f) withFrameMillis { now ->
            if (last >= 0) shown += (now - last).coerceAtMost(LONGEST_FADE_STEP_MILLIS)
            last = now
            value = if (shown >= millis) 1f else easing.transform(shown.toFloat() / millis)
        }
    }
}

/** The most a [Fade] moves on in one frame: about two frames at 60 fps, so a 150 ms fade shows over at least five. */
private const val LONGEST_FADE_STEP_MILLIS = 33L
