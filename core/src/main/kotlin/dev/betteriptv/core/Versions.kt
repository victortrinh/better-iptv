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
        .map { (language, versions) -> LanguageGroup(language, leadersWithBackups(versions)) }
    val viewers = wanted.mapNotNull { language -> groups.find { it.language == language } } + groups.filter { it.language == null }
    val others = groups.filter { it.language != null && it.language !in wanted }
        .sortedWith(compareByDescending<LanguageGroup> { it.versionCount }.thenBy { it.language })
    return viewers to others
}

/**
 * The playable Versions of a Title for Play, best first: those in the Viewer's [contentLanguages]
 * (in their order) ahead of the rest, then [BEST_FIRST]. CAM Versions rank after every other
 * language, and Audio-description ones and those the [device] can't display are left out.
 */
internal fun playable(candidates: List<Candidate>, contentLanguages: List<String>, device: DeviceProfile): List<Candidate> {
    val wanted = contentLanguages.map(::primaryLanguage).distinct()
    return candidates.filter { !it.attributes.audioDescription && device.canDisplay(it.attributes) }
        .sortedWith(compareBy<Candidate>({ it.attributes.cam }, { wanted.indexOf(it.primaryLanguage).takeIf { i -> i >= 0 } ?: wanted.size }).then(BEST_FIRST))
}

/** [chosen] with the Versions equivalent to it among [candidates] as its Backups, best first. */
internal fun withBackups(chosen: Candidate, candidates: List<Candidate>): Version =
    chosen.toVersion(backups = candidates.filter { it !== chosen && it.equivalence == chosen.equivalence }.sortedWith(BEST_FIRST).map { it.toVersion() })

/**
 * What to suggest when [playing] keeps buffering: the best of [versions] in its language with a
 * lower quality (unknown counts as lowest), CAM and Audio-description ones aside. Null when
 * [playing]'s own quality isn't known.
 */
internal fun lowerVersion(playing: Version, versions: List<Version>): Version? {
    val quality = playing.attributes.qualityRank ?: return null
    val language = playing.attributes.language?.value?.let(::primaryLanguage)
    return versions.firstOrNull { other ->
        val attributes = other.attributes
        !attributes.cam && !attributes.audioDescription &&
            attributes.language?.value?.let(::primaryLanguage) == language &&
            (attributes.qualityRank ?: -1) < quality
    }
}

/** Where a Version's quality ranks, higher being better; null when unknown. */
private val VersionAttributes.qualityRank get() = quality?.value?.ordinal

/** Keeps the first of each set of equivalent Versions in [ranked], with the others as its Backups. */
internal fun leadersWithBackups(ranked: List<Candidate>): List<Version> =
    ranked.groupBy { it.equivalence }.values.map { same -> same.first().toVersion(backups = same.drop(1).map { it.toVersion() }) }

/** Versions are equivalent with the same language and picture (quality and HDR), and both CAM or Audio-description or neither. */
internal val Candidate.equivalence: Any
    get() = attributes.let { listOf(it.language?.value, it.quality?.value, it.hdr?.value, it.cam, it.audioDescription) }

private fun Candidate.toVersion(backups: List<Version> = emptyList()) = Version(listing.id, listing.name, attributes, backups)
