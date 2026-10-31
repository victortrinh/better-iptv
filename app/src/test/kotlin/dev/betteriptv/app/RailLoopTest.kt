package dev.betteriptv.app

import dev.betteriptv.app.ui.theme.Cards
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RailLoopTest {
    // Home's posters: 136 dp, 20 dp apart, the first at the 48 dp margin of a 960 dp screen.
    private val posters = RailLoop.seen(width = 136f, gap = 20f, screen = 960f, margin = 48f)

    // Favourites' Channel cards: 196 dp.
    private val channels = RailLoop.seen(width = 196f, gap = 20f, screen = 960f, margin = 48f)

    // Continue watching's stills, as tall as a poster: 363 dp.
    private val stills = RailLoop.seen(width = Cards.continueWidth.value, gap = 20f, screen = 960f, margin = 48f)

    @Test
    fun aContinueWatchingStillIsAsTallAsAPoster() {
        assertEquals(Cards.posterWidth.value * 3 / 2, Cards.continueWidth.value * 9 / 16, 0.5f)
    }

    @Test
    fun theScreenShowsSevenPostersSixChannelsAndFourStillsCountingTheOneInTheMargin() {
        assertEquals(7, posters)
        assertEquals(6, channels)
        assertEquals(4, stills)
    }

    @Test
    fun onlyARailLongerThanTheScreenLoops() {
        assertFalse(RailLoop(7, posters).loops)
        assertTrue(RailLoop(8, posters).loops)
        assertTrue(RailLoop(7, channels).loops)
        // A short Continue watching stops at its ends.
        assertFalse(RailLoop(3, stills).loops)
        assertFalse(RailLoop(4, stills).loops)
        assertTrue(RailLoop(5, stills).loops)
        assertEquals(7, RailLoop(7, posters).count)
        assertEquals(0, RailLoop(7, posters).start)
    }

    @Test
    fun aRankedRailNeverLoopsWhateverItsLength() {
        val top = RailLoop(10, posters, ranked = true)
        assertFalse(top.loops)
        assertEquals(10, top.count)
        assertEquals(0, top.start)
        assertEquals(9, top.place(9, near = 3))
    }

    @Test
    fun aLoopingRailOpensOnItsFirstCardWithCardsEitherSide() {
        val loop = RailLoop(20, posters)
        assertEquals(0, loop.card(loop.start))
        // Left from the first card is the last.
        assertEquals(19, loop.card(loop.start - 1))
        // Right from the last card is the first.
        assertEquals(0, loop.card(loop.start + 20))
        assertTrue(loop.start > 1000 && loop.count - loop.start > 1000)
    }

    @Test
    fun aCardFocusedAgainIsTheCopyNearestWhereTheRailIs() {
        val loop = RailLoop(10, posters)
        val at = loop.start + 23 // card 3
        assertEquals(at, loop.place(3, near = at))
        // The card before the rail's first visible place, which peeks at the left.
        assertEquals(at + 1, loop.place(4, near = at))
        assertEquals(at - 1, loop.place(2, near = at))
        // Card 9 is one to the left of card 0, not nine to the right.
        assertEquals(loop.start - 1, loop.place(9, near = loop.start))
    }

    @Test
    fun aShortRailHasEachCardOnce() {
        val loop = RailLoop(4, posters)
        assertEquals(2, loop.place(2, near = 0))
        assertEquals(2, loop.card(2))
    }
}
