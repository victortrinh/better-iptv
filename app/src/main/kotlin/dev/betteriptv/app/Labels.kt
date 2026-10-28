package dev.betteriptv.app

import android.content.Context
import dev.betteriptv.app.ui.components.BadgeSpec
import dev.betteriptv.app.ui.components.Certainty
import dev.betteriptv.app.ui.components.VersionLine
import dev.betteriptv.core.Hdr
import dev.betteriptv.core.Known
import dev.betteriptv.core.Provenance
import dev.betteriptv.core.Quality
import dev.betteriptv.core.Version
import dev.betteriptv.core.VersionAttributes
import java.util.Locale

/** A card's badges: quality, HDR, then language, as in "4K · HDR · EN". */
fun VersionAttributes.cardBadges(): List<BadgeSpec> =
    pictureBadges() + listOfNotNull(language?.badge { it.uppercase(Locale.ROOT) })

/** A Version row's badges: the picture and Dolby audio. Its languages have their own columns. */
fun VersionAttributes.versionBadges(): List<BadgeSpec> =
    pictureBadges() + listOfNotNull(if (dolbyAudio) BadgeSpec("Dolby", Certainty.Likely) else null)

/**
 * Names the Version Play will start, beside the Play button: "English · 4K · HDR · Netflix".
 * [untagged] stands in for the language of an Untagged Version.
 */
fun VersionAttributes.playLabel(untagged: String): String =
    listOfNotNull(language?.value?.let(::languageName) ?: untagged, quality?.value?.label, hdr?.value?.label, source?.value).joinToString(" · ")

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

/** [playLabel] for a [version], naming an Untagged one's language as unnamed; a Channel's says how it streams. */
fun Context.playLabel(version: Version): String = when (version.liveFormat) {
    null -> version.attributes.playLabel(getString(R.string.language_untagged))
    // A Channel says nothing of its language or quality, only how it streams.
    "ts" -> getString(R.string.player_live, "MPEG-TS")
    else -> getString(R.string.player_live, "HLS")
}

/** What a [version]'s row in a Versions list shows. */
fun Context.versionLine(version: Version): VersionLine {
    val attributes = version.attributes
    val none = getString(R.string.version_none)
    return VersionLine(
        badges = attributes.versionBadges(),
        source = attributes.source?.value ?: none,
        audio = attributes.language?.value?.let(::languageName) ?: getString(R.string.language_untagged),
        subtitles = attributes.subtitles?.value?.joinToString(", ") { if (it == "mul") getString(R.string.subtitles_many) else languageName(it) } ?: none,
        bitrate = attributes.bitrate?.value?.let { getString(R.string.version_bitrate_value, it / 1e6) },
        backups = version.backups.size.takeIf { it > 0 }?.let { resources.getQuantityString(R.plurals.backups_count, it, it) },
        // Both notes are cautions.
        note = when {
            attributes.cam -> getString(R.string.version_cam)
            attributes.audioDescription -> getString(R.string.version_audio_description)
            else -> null
        },
        noteIsCaution = true,
    )
}
