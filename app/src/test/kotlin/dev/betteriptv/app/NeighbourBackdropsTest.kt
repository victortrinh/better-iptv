package dev.betteriptv.app

import dev.betteriptv.core.HomeRow
import dev.betteriptv.core.TitleAbout
import dev.betteriptv.core.TitleId
import dev.betteriptv.core.TitleKind
import dev.betteriptv.core.TitleSummary
import dev.betteriptv.core.VersionAttributes
import org.junit.Assert.assertEquals
import org.junit.Test

/** Which backdrops the rows load ahead once focus rests on a card (#343): those its Left and Right would grow into. */
class NeighbourBackdropsTest {
    private fun backdrop(i: Int) = "https://image.tmdb.org/t/p/w780/backdrop$i.jpg"

    private fun title(i: Int, textless: Boolean = true) =
        TitleSummary(TitleId.Unmatched(TitleKind.Movie, i.toLong()), "Film$i", 2024, null, VersionAttributes(), TitleAbout(textlessBackdrop = backdrop(i).takeIf { textless }))

    private val row = HomeRow.Genre("Action", (0 until 10).map { title(it) })
    private fun card(i: Int) = RowCard("genre/Action", title(i).id.toString())

    @Test fun theCardsEitherSideOfTheFocusedOne() {
        assertEquals(listOf(backdrop(3), backdrop(5)), neighbourBackdrops(listOf(row), card(4)) { false })
    }

    @Test fun onALoopingRailTheEndsAreNeighbours() {
        assertEquals(listOf(backdrop(9), backdrop(1)), neighbourBackdrops(listOf(row), card(0)) { true })
        assertEquals(listOf(backdrop(8), backdrop(0)), neighbourBackdrops(listOf(row), card(9)) { true })
    }

    @Test fun onARailThatEndsTheEndsHaveOneNeighbour() {
        assertEquals(listOf(backdrop(1)), neighbourBackdrops(listOf(row), card(0)) { false })
        assertEquals(listOf(backdrop(8)), neighbourBackdrops(listOf(row), card(9)) { false })
    }

    @Test fun aNeighbourWithNoTextlessBackdropLoadsNothing() {
        val row = HomeRow.Genre("Action", listOf(title(0, textless = false), title(1), title(2, textless = false)))
        assertEquals(emptyList<String>(), neighbourBackdrops(listOf(row), card(1)) { false })
    }

    @Test fun fromTheHeroTheFirstRowsFirstCardIsLoaded() {
        // The first Down from the hero lands on the first row's first card, which grows into its backdrop (#398).
        val below = HomeRow.Genre("Drama", (10 until 20).map { title(it) })
        assertEquals(listOf(backdrop(0)), neighbourBackdrops(listOf(row, below), RowCard(HERO_ROW, HERO_PLAY)) { true })
        assertEquals(listOf(backdrop(0)), neighbourBackdrops(listOf(row, below), RowCard(HERO_ROW, HERO_DETAILS)) { false })
    }

    @Test fun fromTheHeroAFirstRowOfStillsLoadsNothing() {
        val resumes = HomeRow.ContinueWatching(emptyList())
        assertEquals(emptyList<String>(), neighbourBackdrops(listOf(resumes, row), RowCard(HERO_ROW, HERO_PLAY)) { true })
    }

    @Test fun aCardInNoTitleRowLoadsNothing() {
        assertEquals(emptyList<String>(), neighbourBackdrops(listOf(row), RowCard("live-now", "game/nhl/1")) { true })
        assertEquals(emptyList<String>(), neighbourBackdrops(listOf(row), RowCard("genre/Other", title(1).id.toString())) { true })
    }
}
