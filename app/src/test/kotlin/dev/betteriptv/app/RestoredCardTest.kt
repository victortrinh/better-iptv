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
        assertEquals(RowCard("new", "moana"), restoredCard(RowCard("new", "moana"), home, home))
    }

    @Test
    fun theRowKeepsItsCardWhenContinueWatchingGoes() {
        val after = home - "continue"

        assertEquals(RowCard("new", "moana"), restoredCard(RowCard("new", "moana"), home, after))
    }

    @Test
    fun theRowKeepsItsCardWhenContinueWatchingComes() {
        val before = home - "continue"

        assertEquals(RowCard("new", "moana"), restoredCard(RowCard("new", "moana"), before, home))
    }

    @Test
    fun aRemovedCardGivesFocusToTheOneNowInItsPlace() {
        val after = home + ("continue" to listOf("verity"))

        assertEquals(RowCard("continue", "verity"), restoredCard(RowCard("continue", "moana"), home, after))
    }

    @Test
    fun theLastCardRemovedGivesFocusToTheOneBeforeIt() {
        val after = home + ("new" to listOf("lupin", "moana"))

        assertEquals(RowCard("new", "moana"), restoredCard(RowCard("new", "tenzing"), home, after))
    }

    @Test
    fun aRowGoneGivesFocusToTheRowNowInItsPlace() {
        val before = home + ("continue" to listOf("moana"))
        val after = home - "continue"

        assertEquals(RowCard("new", "lupin"), restoredCard(RowCard("continue", "moana"), before, after))
    }

    @Test
    fun aGameEndedWhileItsPageWasOpenGivesFocusToTheGameNowInItsPlace() {
        // Home's Live now row, between Continue watching and the rows below (#256).
        val before = mapOf("continue" to listOf("moana"), "live-now" to listOf("game/nhl/1", "game/nhl/2")) + (home - "continue")
        val after = before + ("live-now" to listOf("game/nhl/2"))

        assertEquals(RowCard("live-now", "game/nhl/2"), restoredCard(RowCard("live-now", "game/nhl/1"), before, after))
    }

    @Test
    fun theLastGameEndedTakesLiveNowWithItAndFocusGoesToTheRowInItsPlace() {
        val before = mapOf("continue" to listOf("moana"), "live-now" to listOf("game/nhl/1")) + (home - "continue")
        val after = before - "live-now"

        assertEquals(RowCard("new", "lupin"), restoredCard(RowCard("live-now", "game/nhl/1"), before, after))
    }

    @Test
    fun theLastRowGoneGivesFocusToTheRowBeforeIt() {
        val after = home - "genre/Drama"

        assertEquals(RowCard("new", "lupin"), restoredCard(RowCard("genre/Drama", "verity"), home, after))
    }

    @Test
    fun noCardsLeaveNothingToFocus() {
        assertNull(restoredCard(RowCard("continue", "moana"), home, emptyMap()))
    }

    /** The Live tab's last Favourite removed: null, which `RowsScreen` turns into focus on the tab. */
    @Test
    fun theLastFavouriteOnLiveLeavesNothingToFocus() {
        val live = mapOf("favourites" to listOf("channel/7"))

        assertNull(restoredCard(RowCard("favourites", "channel/7"), live, emptyMap()))
    }
}
