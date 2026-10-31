package dev.betteriptv.app

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The Title counts under Settings' Sync now. */
@RunWith(RobolectricTestRunner::class)
class CatalogCountsTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test
    fun countsMoviesAndShowsInEnglish() {
        assertEquals("12,345 movies · 3,210 shows", context.catalogCounts(12345, 3210))
        assertEquals("1 movie · 1 show", context.catalogCounts(1, 1))
        assertEquals("0 movies · 0 shows", context.catalogCounts(0, 0))
    }

    @Test
    @Config(qualifiers = "fr")
    fun countsMoviesAndShowsInFrench() {
        assertEquals("12 345 films · 3 210 séries", context.catalogCounts(12345, 3210))
        assertEquals("1 film · 1 série", context.catalogCounts(1, 1))
    }
}
