package dev.betteriptv.core

import java.util.Locale

/** One Listing with what the Prefix table says about it: a Version before it's ranked and grouped. */
internal class Candidate(val listing: StoredListing, val attributes: VersionAttributes)

/**
 * Better Versions first. CAM Versions always rank last and Audio-description ones after the rest, so
 * neither is picked by default. Then the highest quality (4K Dolby Vision, 4K HDR, 4K, then unknown),
 * then a known Source, then the newest Listing.
 */
internal val BEST_FIRST: Comparator<Candidate> =
    compareBy<Candidate>({ it.attributes.cam }, { it.attributes.audioDescription })
        .thenByDescending { it.attributes.quality?.value }
        .thenByDescending { it.attributes.hdr?.value }
        .thenBy { it.attributes.source == null }
        .thenByDescending { it.listing.added }
        .thenBy { it.listing.id }

/** `fr-CA` is French: a Content language matches every regional variant of it. */
internal fun primaryLanguage(tag: String): String = tag.substringBefore('-').lowercase(Locale.ROOT)

private val Candidate.primaryLanguage get() = attributes.language?.value?.let(::primaryLanguage)

/** [candidates] best first, those in the Viewer's [contentLanguages] ahead of the rest. */
internal fun preferred(candidates: List<Candidate>, contentLanguages: List<String>): List<Candidate> {
    val wanted = contentLanguages.map(::primaryLanguage).toSet()
    return candidates.sortedWith(compareBy<Candidate> { it.primaryLanguage !in wanted }.then(BEST_FIRST))
}

/**
 * Groups [candidates] by language: the Viewer's [contentLanguages] in their order, then Untagged
 * Versions (which may well be in the Viewer's language), then the other languages, most Versions
 * first. Within a group the best Version leads, and Versions equivalent to a better one (same
 * language and quality) become its Backups.
 */
internal fun groupByLanguage(candidates: List<Candidate>, contentLanguages: List<String>): Pair<List<LanguageGroup>, List<LanguageGroup>> {
    val wanted = contentLanguages.map(::primaryLanguage).distinct()
    val groups = candidates.sortedWith(BEST_FIRST)
        .groupBy { it.primaryLanguage }
        .map { (language, versions) -> LanguageGroup(language, withBackups(versions)) }
    val viewers = wanted.mapNotNull { language -> groups.find { it.language == language } } + groups.filter { it.language == null }
    val others = groups.filter { it.language != null && it.language !in wanted }
        .sortedWith(compareByDescending<LanguageGroup> { it.versionCount }.thenBy { it.language })
    return viewers to others
}

/** Keeps the first of each set of equivalent Versions in [ranked], with the others as its Backups. */
private fun withBackups(ranked: List<Candidate>): List<Version> {
    val equivalents = LinkedHashMap<Any, MutableList<Candidate>>()
    ranked.forEach { candidate ->
        val a = candidate.attributes
        equivalents.getOrPut(listOf(a.language?.value, a.quality?.value, a.hdr?.value, a.cam, a.audioDescription)) { mutableListOf() } += candidate
    }
    return equivalents.values.map { same -> same.first().toVersion(backups = same.drop(1).map { it.toVersion() }) }
}

private fun Candidate.toVersion(backups: List<Version> = emptyList()) = Version(listing.id, listing.name, attributes, backups)
