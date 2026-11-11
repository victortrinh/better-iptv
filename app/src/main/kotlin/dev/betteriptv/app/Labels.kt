package dev.betteriptv.app

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import dev.betteriptv.app.ui.components.BadgeSpec
import dev.betteriptv.app.ui.components.Certainty
import dev.betteriptv.app.ui.components.VersionLine
import dev.betteriptv.core.Hdr
import dev.betteriptv.core.Known
import dev.betteriptv.core.Provenance
import dev.betteriptv.core.Quality
import dev.betteriptv.core.Version
import dev.betteriptv.core.VersionAttributes
import dev.betteriptv.core.VersionTableRow
import java.util.Locale

/**
 * The Provider's [rating] on a 10 scale as the screens show it, "★ 6.6", one decimal in the device's
 * locale ("★ 6,6" in French). Nothing is shown for a Title without one: callers pass none.
 */
fun ratingLabel(rating: Double): String = "★ " + String.format(Locale.getDefault(), "%.1f", rating)

/**
 * The one line under a focused Home card (DESIGN.md, Cards): [year] · the first of its [genres] · the
 * Provider's [rating] ("2024 · Drama · ★ 6.6"), leaving out what the Provider doesn't give, so a
 * Title with none of them reads as nothing. From stored data alone: no Provider call on focus (ADR 0014).
 */
fun aboutLine(year: Int?, genres: List<String>, rating: Double?): String =
    metaLine(year?.toString(), genres.firstOrNull()?.let(::isolated), rating?.let(::ratingLabel))

/**
 * A metadata line from its [parts] ("2024 · 2h 50m · Drama, Comedy · ★ 6.6"), leaving out the
 * missing ones. A part holding right-to-left text (a Hebrew or Arabic genre) is isolated
 * ([isolated]), so it stays in its slot and the rating after it keeps its order (#514).
 */
fun metaLine(vararg parts: String?): String = parts.filterNotNull().filter { it.isNotEmpty() }.joinToString(" · ") { isolated(it) }

/** The first two of [genres], each [isolated], as the metadata lines show them: "Drama, Comedy". Empty for none. */
fun genresPart(genres: List<String>): String = genres.take(2).joinToString(", ") { isolated(it) }

/**
 * [text] wrapped in first-strong isolate marks (U+2068 … U+2069) when it holds a right-to-left
 * letter, so the bidi algorithm lays it out on its own and the line around it keeps its direction;
 * left-to-right text is returned as it is.
 */
fun isolated(text: String): String =
    if (text.none { Character.getDirectionality(it).let { d -> d == Character.DIRECTIONALITY_RIGHT_TO_LEFT || d == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC } }) text
    else if (text.startsWith(FSI) && text.endsWith(PDI)) text
    else "$FSI$text$PDI"

private const val FSI = '\u2068'
private const val PDI = '\u2069'


/**
 * A card's badges (Home rows, Movies and Shows, the hero): quality, HDR, then language, as in
 * "4K · HDR · EN", the picture's only once Confirmed. A Prefix's quality is a guess a Header read
 * often corrects (a `4K-EN` Listing whose file is 1080p), so a card waits for Background reading or
 * the Title's page to Confirm it (#546).
 */
fun VersionAttributes.cardBadges(): List<BadgeSpec> =
    pictureBadges().filter { it.certainty == Certainty.Confirmed } + listOfNotNull(language?.badge { it.uppercase(Locale.ROOT) })

/** The badges of a Title page's header and the player: quality, HDR, then language, Likely ones outlined. */
fun VersionAttributes.pageBadges(): List<BadgeSpec> =
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

/**
 * What a [version]'s row in a Versions list shows, noted as a [backup] when it is one; [playing] is
 * the Version playing when it is this one. A Channel variant's row names it as the Provider lists it ("VIP: TNT
 * SPORT 1 ᴴᴰ") instead of filling the columns, with how it streams once it plays ("Playing · Live · HLS").
 */
fun Context.versionLine(version: Version, playing: Version? = null, backup: Boolean = false): VersionLine {
    val playingNote = playing?.let { getString(R.string.player_playing) }
    if (version.liveFormat != null) {
        val note = playing?.let { "$playingNote · ${playLabel(it)}" }
        return VersionLine(emptyList(), "", "", "", bitrate = null, backup = null, note = note, live = version.name)
    }
    val attributes = version.attributes
    val none = getString(R.string.version_none)
    return VersionLine(
        badges = attributes.versionBadges(),
        source = attributes.source?.value ?: none,
        audio = attributes.language?.value?.let(::languageName) ?: getString(R.string.language_untagged),
        subtitles = attributes.subtitles?.value?.joinToString(", ") { if (it == "mul") getString(R.string.subtitles_many) else languageName(it) } ?: none,
        bitrate = attributes.bitrate?.value?.let { getString(R.string.version_bitrate_value, it / 1e6) },
        backup = if (backup) getString(R.string.version_backup) else null,
        // Both other notes are cautions.
        note = when {
            playingNote != null -> playingNote
            attributes.cam -> getString(R.string.version_cam)
            attributes.audioDescription -> getString(R.string.version_audio_description)
            else -> null
        },
        noteIsCaution = playingNote == null,
    )
}

/**
 * What a row of the Versions screen's table shows ([versionTable]): unknown cells empty, the Audio
 * cell only when it says more than the language heading, or what choosing the Version costs
 * ("German only", [audioOnly]), and a Backup's cells in muted type, noted "Backup" where the bitrate goes.
 */
fun Context.versionLine(row: VersionTableRow): VersionLine {
    val attributes = row.version.attributes
    return VersionLine(
        badges = attributes.versionBadges(),
        source = attributes.source?.value.orEmpty(),
        audio = row.only?.let(::audioOnly) ?: row.audio?.let(::languageName).orEmpty(),
        subtitles = attributes.subtitles?.value?.joinToString(", ") { if (it == "mul") getString(R.string.subtitles_many) else languageName(it) }.orEmpty(),
        bitrate = attributes.bitrate?.value?.let { getString(R.string.version_bitrate_value, it / 1e6) },
        backup = if (row.backup) getString(R.string.version_backup) else null,
        note = when {
            attributes.cam -> getString(R.string.version_cam)
            attributes.audioDescription -> getString(R.string.version_audio_description)
            else -> null
        },
        noteIsCaution = true,
        muted = row.backup,
    )
}

/**
 * A Version whose only audio is in [language] (a primary subtag), neither one of the Viewer's nor the
 * Title's original: "German only", the language named in the App language so the cost reads plainly.
 */
fun Context.audioOnly(language: String): String {
    val app = resources.configuration.locales[0]
    val name = Locale.forLanguageTag(language).getDisplayLanguage(app).replaceFirstChar { it.titlecase(app) }
    return getString(R.string.version_audio_only, name)
}

/**
 * The Versions action's value: what Play starts and how many Versions there are to choose from,
 * "English · 1080p · 9 Versions"; the count alone when nothing plays, or the plan's Version says
 * neither. An Untagged Version's language isn't named: the count says enough.
 */
fun Context.versionsValue(playing: Version?, count: Int): String {
    val attributes = playing?.attributes
    return listOfNotNull(
        attributes?.language?.value?.let(::languageName),
        attributes?.quality?.value?.label,
        resources.getQuantityString(R.plurals.versions_count, count, count),
    ).joinToString(" · ")
}
