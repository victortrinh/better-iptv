package dev.betteriptv.app

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The Title counts under Settings' Sync now, with the Mismatches the year rule kept apart. */
@RunWith(RobolectricTestRunner::class)
class CatalogCountsTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test
    fun countsMoviesAndShowsInEnglish() {
        assertEquals("12,345 movies · 3,210 shows", context.catalogCounts(12345, 3210))
        assertEquals("1 movie · 1 show", context.catalogCounts(1, 1))
        assertEquals("0 movies · 0 shows", context.catalogCounts(0, 0))
        assertEquals("54,658 movies · 14,818 shows · 181 kept apart by year", context.catalogCounts(54658, 14818, 181))
        assertEquals("1 movie · 1 show · 1 kept apart by year", context.catalogCounts(1, 1, 1))
    }

    @Test
    @Config(qualifiers = "fr")
    fun countsMoviesAndShowsInFrench() {
        assertEquals("12 345 films · 3 210 séries", context.catalogCounts(12345, 3210))
        assertEquals("1 film · 1 série", context.catalogCounts(1, 1))
        assertEquals("54 658 films · 14 818 séries · 181 séparés par l'année", context.catalogCounts(54658, 14818, 181))
    }
}
