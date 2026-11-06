package dev.betteriptv.app

import androidx.compose.animation.core.LinearEasing
import androidx.compose.runtime.MonotonicFrameClock
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A fade in ([Fade]) shows over frames, however late they come (#398): on the stick's cold start the
 * main thread drew no frame for half a second as Home's rows and hero arrived, and a fade timed by
 * the clock alone was over before the next frame, so they popped in.
 */
class FadeTest {
    /** Frames at these times, in ms; the value the fade had after each. */
    private fun fadeOver(millis: Int, vararg frames: Long, out: Boolean = false): List<Float> {
        val fade = Fade(if (out) 1f else 0f)
        val seen = ArrayList<Float>()
        val times = frames.iterator()
        val clock = object : MonotonicFrameClock {
            override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
                val at = times.next()
                val result = onFrame(at * 1_000_000)
                seen += fade.value
                return result
            }
        }
        runBlocking { withContext(clock) { if (out) fade.fadeOut(millis, LinearEasing) else fade.fadeIn(millis, LinearEasing) } }
        return seen
    }

    @Test fun framesOnTimeFadeByTheClock() {
        val seen = fadeOver(160, 0, 16, 32, 48, 64, 80, 96, 112, 128, 144, 160)
        assertEquals(0f, seen.first())
        assertEquals(0.5f, seen[5], 0.01f)
        assertEquals(1f, seen.last())
    }

    @Test fun aLateFrameDoesNotSkipTheFade() {
        // The rows' first frame, then half a second with none, then frames on time again.
        val seen = fadeOver(150, 0, 500, 516, 532, 548, 564, 580, 596, 612, 628, 644)
        assertEquals(0f, seen[0])
        assertTrue("after the late frame it is at ${seen[1]}", seen[1] > 0f && seen[1] < 0.3f)
        assertTrue("it keeps fading after it: $seen", seen[2] in seen[1]..0.6f)
        assertEquals(1f, seen.last())
        // Shown over several frames, not one.
        assertTrue(seen.count { it > 0f && it < 1f } >= 4)
    }

    @Test fun aFadeOutShowsOverFramesHoweverSlowTheyCome() {
        // The stick while a trailer starts: a frame every 120 ms (#407). Timed by the clock, a 150 ms fade
        // was at full strength on one frame and gone on the next.
        val seen = fadeOver(150, 0, 120, 240, 360, 480, 600, 720, 840, out = true)
        assertEquals(1f, seen[0])
        assertTrue("it never jumps more than a fifth at a time: $seen", seen.zipWithNext().all { (a, b) -> b <= a && a - b <= 0.25f })
        assertEquals(0f, seen.last())
        assertTrue(seen.count { it > 0f && it < 1f } >= 4)
    }

    @Test fun aFadeAlreadyThereStaysThere() {
        val shown = Fade(1f)
        runBlocking { shown.fadeIn(150) }
        assertEquals(1f, shown.value)
        val gone = Fade(0f)
        runBlocking { gone.fadeOut(150) }
        assertEquals(0f, gone.value)
    }

    @Test fun showSkipsTheFade() {
        val fade = Fade(0f)
        fade.show()
        assertEquals(1f, fade.value)
    }
}
