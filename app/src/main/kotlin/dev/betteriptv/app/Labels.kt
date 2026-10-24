package dev.betteriptv.app

import dev.betteriptv.app.ui.components.BadgeSpec
import dev.betteriptv.app.ui.components.Certainty
import dev.betteriptv.core.Hdr
import dev.betteriptv.core.Known
import dev.betteriptv.core.Provenance
import dev.betteriptv.core.Quality
import dev.betteriptv.core.VersionAttributes
import java.util.Locale

/** A card's badges: quality, HDR, then language, as in "4K · HDR · EN". */
fun VersionAttributes.cardBadges(): List<BadgeSpec> =
    pictureBadges() + listOfNotNull(language?.badge { it.uppercase(Locale.ROOT) })

/** A Version row's badges: the picture and Dolby audio. Its languages have their own columns. */
fun VersionAttributes.versionBadges(): List<BadgeSpec> =
    pictureBadges() + listOfNotNull(if (dolbyAudio) BadgeSpec("Dolby", Certainty.Likely) else null)

private fun VersionAttributes.pictureBadges() = listOfNotNull(quality?.badge { it.label }, hdr?.badge { it.label })

private fun <T> Known<T>.badge(text: (T) -> String) = BadgeSpec(text(value), provenance.certainty)

private val Quality.label get() = when (this) {
    Quality.UHD -> "4K"
    Quality.FullHD -> "1080p"
    Quality.HD -> "720p"
    Quality.SD -> "SD"
}

private val Hdr.label get() = when (this) {
    Hdr.HDR10 -> "HDR"
    Hdr.DolbyVision -> "DV"
}

private val Provenance.certainty get() = when (this) {
    Provenance.Likely -> Certainty.Likely
    Provenance.Confirmed -> Certainty.Confirmed
}

/**
 * A language's name in that language, as Version lists show it so each household member finds
 * theirs: "English", "Deutsch", "Français (Québec)" for `fr-CA`.
 */
fun languageName(tag: String): String {
    val locale = Locale.forLanguageTag(tag)
    if (locale.language == "fr" && locale.country == "CA") return "Français (Québec)"
    return locale.getDisplayName(locale).replaceFirstChar { it.titlecase(locale) }
}
