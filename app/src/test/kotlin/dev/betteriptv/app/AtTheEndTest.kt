package dev.betteriptv.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A file that fails at its very end, as an MKV does when the Viewer scrubs to its last frame, has ended. */
class AtTheEndTest {
    /** American Horror Story S1 E2: 42:44. */
    private val episode = 2_564_000L

    @Test
    fun aScrubToTheLastFrameIsTheEnd() {
        assertTrue(atTheEnd(position = episode, duration = episode))
    }

    @Test
    fun theLastSecondsAreTheEnd() {
        assertTrue(atTheEnd(position = episode - 4_000, duration = episode))
    }

    @Test
    fun aFailureBeforeThenIsAFailure() {
        assertFalse(atTheEnd(position = episode - 60_000, duration = episode))
        assertFalse(atTheEnd(position = 0, duration = episode))
    }

    @Test
    fun aStreamWithoutALengthNeverEnds() {
        assertFalse(atTheEnd(position = episode, duration = null))
    }
}
