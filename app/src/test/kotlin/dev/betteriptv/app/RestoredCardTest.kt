package dev.betteriptv.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RestoredCardTest {
    private val home = mapOf(
        "continue" to listOf("moana", "verity"),
        "new" to listOf("lupin", "moana", "tenzing"),
        "genre/Drama" to listOf("moana", "verity"),
    )

    @Test
    fun aCardStillThereKeepsFocus() {
        assertEquals(HomeCard("new", "moana"), restoredCard(HomeCard("new", "moana"), home, home))
    }

    @Test
    fun theRowKeepsItsCardWhenContinueWatchingGoes() {
        val after = home - "continue"

        assertEquals(HomeCard("new", "moana"), restoredCard(HomeCard("new", "moana"), home, after))
    }

    @Test
    fun theRowKeepsItsCardWhenContinueWatchingComes() {
        val before = home - "continue"

        assertEquals(HomeCard("new", "moana"), restoredCard(HomeCard("new", "moana"), before, home))
    }

    @Test
    fun aRemovedCardGivesFocusToTheOneNowInItsPlace() {
        val after = home + ("continue" to listOf("verity"))

        assertEquals(HomeCard("continue", "verity"), restoredCard(HomeCard("continue", "moana"), home, after))
    }

    @Test
    fun theLastCardRemovedGivesFocusToTheOneBeforeIt() {
        val after = home + ("new" to listOf("lupin", "moana"))

        assertEquals(HomeCard("new", "moana"), restoredCard(HomeCard("new", "tenzing"), home, after))
    }

    @Test
    fun aRowGoneGivesFocusToTheRowNowInItsPlace() {
        val before = home + ("continue" to listOf("moana"))
        val after = home - "continue"

        assertEquals(HomeCard("new", "lupin"), restoredCard(HomeCard("continue", "moana"), before, after))
    }

    @Test
    fun theLastRowGoneGivesFocusToTheRowBeforeIt() {
        val after = home - "genre/Drama"

        assertEquals(HomeCard("new", "lupin"), restoredCard(HomeCard("genre/Drama", "verity"), home, after))
    }

    @Test
    fun noCardsLeaveNothingToFocus() {
        assertNull(restoredCard(HomeCard("continue", "moana"), home, emptyMap()))
    }
}
