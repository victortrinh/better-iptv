package dev.betteriptv.app

import dev.betteriptv.core.TitleAbout
import org.junit.Assert.assertEquals
import org.junit.Test

class CardArtTest {
    private val poster = "https://image.tmdb.org/t/p/w342/poster.jpg"
    private val plainPoster = "https://image.tmdb.org/t/p/w342/plain.jpg"
    private val backdrop = "https://image.tmdb.org/t/p/w780/wide.jpg"
    private val logo = "https://image.tmdb.org/t/p/w300/logo.png"

    private fun about(logo: String? = null, textlessPoster: String? = null, textlessBackdrop: String? = null) =
        TitleAbout("A film.", listOf("Drama"), 95, logo, textlessPoster, textlessBackdrop)

    @Test
    fun aTitleWithATextlessBackdropAndALogoGrowsIntoTheBackdropNamedByItsLogo() {
        val art = cardArt(poster, about(logo = logo, textlessPoster = plainPoster, textlessBackdrop = backdrop), textlessPosters = true)

        assertEquals(CardArt(plainPoster, logo, GrownArt.WithLogo(backdrop, logo)), art)
    }

    @Test
    fun withATextlessBackdropButNoLogoItGrowsIntoTheBackdropNamedInType() {
        val art = cardArt(poster, about(textlessBackdrop = backdrop), textlessPosters = true)

        assertEquals(CardArt(poster, null, GrownArt.WithName(backdrop)), art)
    }

    @Test
    fun withoutATextlessBackdropItStaysTheRegularPosterAndDoesNotGrow() {
        assertEquals(CardArt(poster, null, null), cardArt(poster, about(logo = logo), textlessPosters = true))
        // No TMDB metadata at all: the Provider's poster.
        assertEquals(CardArt(poster, null, null), cardArt(poster, null, textlessPosters = true))
        assertEquals(CardArt(null, null, null), cardArt(null, null, textlessPosters = true))
    }

    @Test
    fun aTextlessPosterRestsUnderTheLogoOnlyWhereTheRowsUseThem() {
        val about = about(logo = logo, textlessPoster = plainPoster)

        assertEquals(CardArt(plainPoster, logo, null), cardArt(poster, about, textlessPosters = true))
        // Search keeps the regular poster, with its name under the focused card.
        assertEquals(CardArt(poster, null, null), cardArt(poster, about, textlessPosters = false))
    }

    @Test
    fun aTextlessPosterWithoutALogoIsNotUsedSoTheNameStillShows() {
        assertEquals(CardArt(poster, null, null), cardArt(poster, about(textlessPoster = plainPoster), textlessPosters = true))
    }
}
