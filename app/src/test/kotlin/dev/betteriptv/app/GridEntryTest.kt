package dev.betteriptv.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GridEntryTest {
    // Two rows of five in view, the first 24 px below the top: index to its top.
    private val atTop = (0..9).associateWith { if (it < 5) 24 else 400 }

    @Test
    fun theFirstCardWhenNoneWasFocused() {
        assertEquals(0, gridEntry(focused = -1, atTop))
    }

    @Test
    fun theCardFocusedLastWhileItIsInView() {
        assertEquals(7, gridEntry(focused = 7, atTop))
    }

    @Test
    fun theFirstCardWholeInViewOnceTheGridIsScrolled() {
        // Row 40 (200 to 204) is cut off at the top; row 41 is whole.
        val scrolled = (200..214).associateWith { if (it < 205) -80 else if (it < 210) 300 else 680 }
        assertEquals(205, gridEntry(focused = -1, scrolled))
        // The card focused last scrolled out of view.
        assertEquals(205, gridEntry(focused = 3, scrolled))
    }

    @Test
    fun noCardWhenNoneIsLaidOut() {
        assertNull(gridEntry(focused = -1, emptyMap()))
    }
}
