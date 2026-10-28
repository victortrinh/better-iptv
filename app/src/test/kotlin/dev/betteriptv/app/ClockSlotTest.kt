package dev.betteriptv.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClockSlotTest {
    @Test
    fun theSlotIsTheLongestClockInZeros() {
        assertEquals("0:00:00", clockSlot(5_932_000))
        assertEquals("00:00", clockSlot(1_421_000))
        assertEquals("0:00", clockSlot(59_000))
    }

    @Test
    fun everyElapsedAndRemainingTimeFitsTheSlot() {
        // A Movie of 1:38:52: elapsed grows past the hour while the time left drops under it.
        val duration = 5_932_000L
        val slot = clockSlot(duration).length
        for (position in 0..duration step 1_000) {
            assertTrue(clock(position).length <= slot)
            assertTrue(clock(duration - position).length <= slot)
        }
    }

    @Test
    fun theClockDropsItsHoursUnderAnHour() {
        assertEquals("1:18:52", clock(4_732_000))
        assertEquals("23:41", clock(1_421_000))
        assertEquals("0:09", clock(9_999))
    }
}
