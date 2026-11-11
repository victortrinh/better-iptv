package dev.betteriptv.app

import dev.betteriptv.app.ui.components.Certainty
import dev.betteriptv.core.Hdr
import dev.betteriptv.core.Known
import dev.betteriptv.core.Provenance
import dev.betteriptv.core.Quality
import dev.betteriptv.core.VersionAttributes
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A card's badges (Home rows, Movies and Shows, the hero) name no quality the Prefix only guessed
 * (#546): a `4K-EN` Listing's file may be 1080p, so its card waits for Background reading or its page
 * to Confirm the Version. The Title page and the player still show what's Likely, outlined.
 */
class CardBadgesTest {
    private fun <T> likely(value: T) = Known(value, Provenance.Likely)
    private fun <T> confirmed(value: T) = Known(value, Provenance.Confirmed)

    private fun VersionAttributes.texts() = cardBadges().map { it.text to it.certainty }

    @Test fun aGuessedQualityIsNotOnTheCard() {
        val guessed = VersionAttributes(language = likely("en"), quality = likely(Quality.UHD), hdr = likely(Hdr.HDR10))

        assertEquals(listOf("EN" to Certainty.Likely), guessed.texts())
    }

    @Test fun aConfirmedQualityIsOnTheCard() {
        val read = VersionAttributes(language = confirmed("en"), quality = confirmed(Quality.FullHD), hdr = confirmed(Hdr.DolbyVision))

        assertEquals(listOf("1080p" to Certainty.Confirmed, "DV" to Certainty.Confirmed, "EN" to Certainty.Confirmed), read.texts())
    }

    @Test fun theTitlePageStillShowsWhatsLikely() {
        val guessed = VersionAttributes(language = likely("en"), quality = likely(Quality.UHD))

        assertEquals(listOf("4K" to Certainty.Likely, "EN" to Certainty.Likely), guessed.pageBadges().map { it.text to it.certainty })
    }
}
