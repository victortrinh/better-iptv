package dev.betteriptv.core

import java.util.Locale
import java.util.SortedMap

/**
 * Merges a Show's Episodes across its [versions] (each show Listing as a Candidate, with the
 * Episodes its show details list) by season and episode number, so the Show has every season
 * any Version has. Each Episode's Versions are grouped by language like a Title's, from what the
 * Prefix table says about its show Listing, with what the Provider's Episode details say Confirmed.
 * What Header reads and Playback found in an Episode's file ([reads], by Episode id) is Confirmed over
 * both. An Episode's Version is named after its show Listing: Providers title Episodes inconsistently
 * (` - S01E01 - Der Lohn der Sünde`, the Show left out).
 */
internal fun mergeEpisodes(
    versions: List<Pair<Candidate, List<ProviderEpisode>>>,
    contentLanguages: List<String>,
    reads: Map<Long, FileContents?> = emptyMap(),
): List<Season> {
    val byEpisode = byEpisode(versions, reads)
    val wanted = contentLanguages.map(::primaryLanguage).toSet()
    return byEpisode.entries.groupBy({ it.key.first }) { (key, versions) ->
        val (languages, otherLanguages) = groupByLanguage(versions.map { it.candidate }, contentLanguages)
        // Its name is in the language its show Listing's Prefix names, whatever its audio.
        val named = versions.sortedWith(compareBy<EpisodeVersion> { it.show.attributes.language?.value?.let(::primaryLanguage) !in wanted }.thenBy(BEST_FIRST) { it.candidate })
        Episode(key.first, key.second, named.firstNotNullOfOrNull { episodeName(it.title) }, languages, otherLanguages)
    }.map { (season, episodes) -> Season(season, episodes) }
}

/**
 * Each Episode's Versions as Candidates, by season and episode number in the order the Show lists
 * them: seasons in order, specials (season 0) last.
 */
internal fun episodeCandidates(versions: List<Pair<Candidate, List<ProviderEpisode>>>, reads: Map<Long, FileContents?> = emptyMap()): SortedMap<EpisodeNumber, List<Candidate>> =
    byEpisode(versions, reads).entries.associateTo(sortedMapOf(EPISODE_ORDER)) { (key, versions) -> EpisodeNumber(key.first, key.second) to versions.map { it.candidate } }

private val EPISODE_ORDER = compareBy<EpisodeNumber>({ it.season == 0 }, { it.season }, { it.number })

private fun byEpisode(versions: List<Pair<Candidate, List<ProviderEpisode>>>, reads: Map<Long, FileContents?>): SortedMap<Pair<Int, Int>, MutableList<EpisodeVersion>> {
    val byEpisode = sortedMapOf<Pair<Int, Int>, MutableList<EpisodeVersion>>(compareBy({ it.first == 0 }, { it.first }, { it.second }))
    versions.forEach { (show, episodes) ->
        episodes.forEach { episode ->
            // Ranked like a Listing: its show Listing's name and Category, the Episode's own date.
            val listing = StoredListing(episode.id, show.listing.name, show.listing.category, episode.added ?: show.listing.added, image = null)
            val attributes = confirmed(show.attributes, episode).let { provider -> reads[episode.id]?.let(provider::confirmedBy) ?: provider }
            byEpisode.getOrPut(episode.season to episode.number) { mutableListOf() } += EpisodeVersion(Candidate(listing, attributes), show, episode.title)
        }
    }
    return byEpisode
}

/** One Version of an Episode, from the [show] Listing whose show details give it this [title]. */
private class EpisodeVersion(val candidate: Candidate, val show: Candidate, val title: String)

/**
 * A show Listing's Likely attributes, with the Episode's resolution Confirmed where the Provider's
 * details give it, and its language when the first audio Track's agrees with the Prefix's (keeping
 * a regional one, `fr-CA`) or the Version is Untagged. When they disagree the Prefix's stays Likely:
 * the Provider names only the first Track, and `FR - Game of Thrones` reports English for files
 * that may well hold French after it, so the Version stays where a French Viewer looks for it until
 * a Header read settles it.
 */
private fun confirmed(show: VersionAttributes, episode: ProviderEpisode): VersionAttributes {
    val likely = show.language?.value
    val language = iso639(episode.audioLanguage)
        ?.takeIf { heard -> likely == null || primaryLanguage(likely) == heard }
        ?.let { heard -> Known(likely ?: heard, Provenance.Confirmed) }
    val quality = quality(episode.width, episode.height)?.let { Known(it, Provenance.Confirmed) }
    return show.copy(language = language ?: show.language, quality = quality ?: show.quality)
}

/**
 * Whether the Provider's details say the Episode's first audio Track is in another language than
 * its [show] Listing's Prefix names: only a Header read can tell whether the Prefix's is after it.
 */
internal fun firstTrackDisagrees(show: VersionAttributes, episode: ProviderEpisode): Boolean {
    val likely = show.language?.value ?: return false
    val heard = iso639(episode.audioLanguage) ?: return false
    return primaryLanguage(likely) != heard
}

/** The quality a picture's size makes: a letterboxed film (1920×800) is still Full HD. */
internal fun quality(width: Int?, height: Int?): Quality? {
    if (width == null && height == null) return null
    val w = width ?: 0
    val h = height ?: 0
    return when {
        w >= 3200 || h >= 1800 -> Quality.UHD
        w >= 1700 || h >= 1000 -> Quality.FullHD
        w >= 1200 || h >= 700 -> Quality.HD
        else -> Quality.SD
    }
}

/**
 * A language code as a primary BCP 47 subtag: `ger`, `deu` and `de` are all `de`. Null when it
 * names no language (`und`, `mul`, empty) or none Java knows.
 */
fun iso639(code: String?): String? {
    val folded = code?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() && it !in NO_LANGUAGE } ?: return null
    if (folded.length == 2) return folded.takeIf { it in TWO_LETTER }
    return THREE_LETTER[BIBLIOGRAPHIC[folded] ?: folded]
}

private val NO_LANGUAGE = setOf("und", "mul", "zxx", "mis", "qaa")
private val TWO_LETTER: Set<String> = Locale.getISOLanguages().toSet()
private val THREE_LETTER: Map<String, String> =
    Locale.getISOLanguages().mapNotNull { language -> runCatching { Locale(language).isO3Language }.getOrNull()?.let { it to language } }.toMap()

/** ISO 639-2's bibliographic codes, which files often carry, and the terminology codes Java knows them by. */
private val BIBLIOGRAPHIC = mapOf(
    "alb" to "sqi", "arm" to "hye", "baq" to "eus", "bur" to "mya", "chi" to "zho", "cze" to "ces", "dut" to "nld",
    "fre" to "fra", "geo" to "kat", "ger" to "deu", "gre" to "ell", "ice" to "isl", "mac" to "mkd", "mao" to "mri",
    "may" to "msa", "per" to "fas", "rum" to "ron", "slo" to "slk", "tib" to "bod", "wel" to "cym",
)

/**
 * An Episode's own name from the Provider's Episode title: `DE - Der Scheich (2022) - S01E01 - Die
 * Natur lügt nicht` is "Die Natur lügt nicht". Null when the title is just the Show and numbers.
 */
private fun episodeName(title: String): String? =
    EPISODE_NUMBERS.find(title)?.let { title.substring(it.range.last + 1) }?.trimStart(' ', '-', ':')?.trim()?.takeIf { it.isNotEmpty() }

private val EPISODE_NUMBERS = Regex("""\bS\d{1,3}\s?E\d{1,4}\b""", RegexOption.IGNORE_CASE)
