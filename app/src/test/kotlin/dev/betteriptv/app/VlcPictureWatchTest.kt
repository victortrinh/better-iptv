package dev.betteriptv.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * VLC's `Vout` comes 1.5–2.4 s before its first frame on the stick (#409), and its first half-second
 * of picture can show a green block or a smear (#408): its picture counts as shown once it has played
 * a moment.
 */
class VlcPictureWatchTest {
    private val watch = VlcPictureWatch()

    @Test
    fun theVideoOutputAndTheFirstTimeReportedAreNotAPictureYet() {
        watch.vout()

        assertFalse(watch.time(255_440))
    }

    @Test
    fun aPictureOnceTheTimeHasMovedHalfASecondPastItsFirstReport() {
        watch.vout()
        watch.time(255_440)

        // As on the stick: VLC drops its late pictures between these two reports.
        assertFalse(watch.time(255_720))
        assertTrue(watch.time(256_054))
    }

    @Test
    fun timesBeforeTheVideoOutputDoNotCount() {
        watch.time(0)
        watch.time(1_000)
        watch.vout()

        assertFalse(watch.time(1_100))
        assertTrue(watch.time(1_600))
    }

    @Test
    fun aSoundOnlyStreamHasNoPicture() {
        assertFalse(watch.time(0))
        assertFalse(watch.time(10_000))
    }
}
