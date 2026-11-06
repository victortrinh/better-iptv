package dev.betteriptv.app

import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis

/**
 * An alpha that fades in from [start] to 1 ([fadeIn]), or out to 0 ([fadeOut]), and is shown over frames, however late they
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
    suspend fun fadeIn(millis: Int, easing: Easing = FastOutSlowInEasing) = fade(millis, easing) { it }

    /**
     * Fades it out over [millis] of frames, from the first frame after it's called, on its [easing]: the
     * starting cover's way to the picture, which on the stick comes a frame every 50–120 ms as a trailer
     * starts (#407).
     */
    suspend fun fadeOut(millis: Int, easing: Easing = FastOutSlowInEasing) = fade(millis, easing) { 1f - it }

    private suspend fun fade(millis: Int, easing: Easing, at: (Float) -> Float) {
        // Already where it's going (a screen shown as is): it stays there.
        if (value == at(1f)) return
        var last = -1L
        var shown = 0L
        var done = false
        while (!done) withFrameMillis { now ->
            if (last >= 0) shown += (now - last).coerceAtMost(LONGEST_FADE_STEP_MILLIS)
            last = now
            done = shown >= millis
            value = at(if (done) 1f else easing.transform(shown.toFloat() / millis))
        }
    }
}

/** The most a [Fade] moves on in one frame: about two frames at 60 fps, so a 150 ms fade shows over at least five. */
private const val LONGEST_FADE_STEP_MILLIS = 33L
