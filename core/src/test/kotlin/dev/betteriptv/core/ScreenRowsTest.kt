package dev.betteriptv.core

import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenRowsTest {
    private fun movie(tmdbId: Long) = StoredTitle(TitleId.Tmdb(TitleKind.Movie, tmdbId), emptyList())

    private fun ids(titles: List<StoredTitle>) = titles.map { (it.id as TitleId.Tmdb).tmdbId }

    // A rail keys its cards by Title: the same Title twice in one row crashed the app (#378).
    @Test
    fun aRowShowsATitleOnceWhenItsListHasItTwice() {
        val row = ScreenRows().row(listOf(movie(1), movie(2), movie(1), movie(3)), size = 10)
        assertEquals(listOf(1L, 2L, 3L), ids(row))
    }

    @Test
    fun aRowCountsATitleOnceTowardsItsSizeAndItsMinimum() {
        val screen = ScreenRows()
        assertEquals(emptyList<Long>(), ids(screen.row(listOf(movie(1), movie(1), movie(1)), size = 10, min = 2)))
        assertEquals(listOf(4L, 5L), ids(screen.row(listOf(movie(4), movie(4), movie(5), movie(6)), size = 2)))
    }
}
