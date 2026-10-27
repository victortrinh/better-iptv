package dev.betteriptv.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RowMoveTest {
    @Test
    fun downKeepsTheColumn() {
        assertEquals(9, rowMove(focused = 2, columns = 7, count = 100, down = true))
        assertEquals(1052, rowMove(focused = 1045, columns = 7, count = 58_311, down = true))
    }

    @Test
    fun upKeepsTheColumn() {
        assertEquals(2, rowMove(focused = 9, columns = 7, count = 100, down = false))
    }

    @Test
    fun upFromTheFirstRowLeavesTheGrid() {
        assertNull(rowMove(focused = 4, columns = 7, count = 100, down = false))
    }

    @Test
    fun downFromTheLastRowLeavesItToTheFocusSearch() {
        // 100 cards in 7 columns: the last row is 98 and 99.
        assertNull(rowMove(focused = 99, columns = 7, count = 100, down = true))
        assertNull(rowMove(focused = 98, columns = 7, count = 100, down = true))
    }

    @Test
    fun downOverAShorterLastRowGoesToItsLastCard() {
        assertEquals(99, rowMove(focused = 95, columns = 7, count = 100, down = true))
        assertEquals(98, rowMove(focused = 91, columns = 7, count = 100, down = true))
    }
}
