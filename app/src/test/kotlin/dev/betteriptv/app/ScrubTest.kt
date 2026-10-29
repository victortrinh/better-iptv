package dev.betteriptv.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrubTest {
    /** A 2 h Movie, scrubbed from 1:00:00. */
    private val movie = 7_200_000L
    private val hour = 3_600_000L

    @Test
    fun aTapMovesTheTarget10Seconds() {
        val scrub = Scrub(from = hour, duration = movie)
        scrub.move(forward = true, at = 0, downAt = 0)
        assertEquals(hour + 10_000, scrub.target)
        scrub.move(forward = false, at = 300, downAt = 300)
        scrub.move(forward = false, at = 600, downAt = 600)
        assertEquals(hour - 10_000, scrub.target)
    }

    @Test
    fun holdingCrossesA2hMovieInUnder5Seconds() {
        // Android's key repeat: 400 ms before the first, then every 50 ms; a slower remote's every 100 ms.
        for (interval in listOf(50L, 100L)) {
            val scrub = Scrub(from = 0, duration = movie)
            val reached = hold(scrub, forward = true, interval = interval, until = { scrub.target == movie })
            assertTrue("$interval ms repeats took $reached ms", reached < 5_000)
        }
    }

    @Test
    fun aHoldGoesAsFarHoweverFewRepeatsArrive() {
        // A busy app can miss most of a held key's repeats; only how long it was held counts.
        val steady = Scrub(from = 0, duration = movie)
        hold(steady, forward = true, interval = 50, until = { it >= 3_000 })
        val sparse = Scrub(from = 0, duration = movie)
        hold(sparse, forward = true, interval = 650, until = { it >= 3_000 })
        assertEquals(steady.target, sparse.target)
    }

    @Test
    fun aRepeatArrivingAfterTheKeyUpNeverMovesBack() {
        // A busy app can get the key's up, stamped now, before repeats stamped earlier.
        val scrub = Scrub(from = 0, duration = movie)
        hold(scrub, forward = true, interval = 50, until = { it >= 2_000 })
        val reached = scrub.target
        scrub.release(at = 2_300)
        scrub.move(forward = true, at = 2_100, downAt = 0)
        assertTrue(scrub.target >= reached)
    }

    @Test
    fun theTargetStaysInTheFile() {
        val scrub = Scrub(from = hour, duration = movie)
        hold(scrub, forward = true, interval = 50, until = { it > 10_000 })
        assertEquals(movie, scrub.target)
        hold(scrub, forward = false, interval = 50, until = { it > 10_000 })
        assertEquals(0, scrub.target)
    }

    @Test
    fun aHeldKeyNeverCommitsBySelfOnlyASecondAfterItIsLetGo() {
        val scrub = Scrub(from = hour, duration = movie)
        scrub.move(forward = true, at = 1_000, downAt = 1_000)
        scrub.move(forward = true, at = 1_400, downAt = 1_000)
        assertNull(scrub.commitsAt())
        scrub.release(at = 6_000)
        assertEquals(7_000L, scrub.commitsAt())
    }

    @Test
    fun committingSeeksFromWherePlaybackIsToTheTarget() {
        // Playback crept on 300 ms while the engine paused.
        val scrub = Scrub(from = hour, duration = movie)
        hold(scrub, forward = true, interval = 50, until = { scrub.target >= hour + 600_000 })
        assertEquals(scrub.target - hour - 300, scrub.commit(position = hour + 300))
    }

    @Test
    fun aScrubBackToWhereItStartedDoesNotSeek() {
        val scrub = Scrub(from = hour, duration = movie)
        scrub.move(forward = true, at = 0, downAt = 0)
        scrub.move(forward = false, at = 300, downAt = 300)
        assertNull(scrub.commit(position = hour + 300))
    }

    /** Holds the key down, repeating every [interval] after the first 400 ms, until [until] says so after a repeat; returns how long it was held. */
    private fun hold(scrub: Scrub, forward: Boolean, interval: Long, until: (Long) -> Boolean): Long {
        val downAt = clock
        var at = downAt
        scrub.move(forward, at, downAt)
        at += 400
        while (true) {
            scrub.move(forward, at, downAt)
            if (until(at - downAt) || at - downAt >= 60_000) break
            at += interval
        }
        clock = at + 1_000
        return at - downAt
    }

    private var clock = 0L
}
