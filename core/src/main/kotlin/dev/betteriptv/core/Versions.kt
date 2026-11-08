package dev.betteriptv.core

import java.util.Locale

/**
 * One Listing with what the Prefix table says about it: a Version before it's ranked and grouped.
 * [unknownPrefix] is the name's Prefix when neither the Base table nor the Provider table knows it.
 * [listedLanguage] is the language its Prefix says it is in (a primary subtag), the language its name
 * is written in, which stays when a read moves the Version to the language its audio is really in.
 */
internal class Candidate(
    val listing: StoredListing,
    val attributes: VersionAttributes,
    val unknownPrefix: String? = null,
    val listedLanguage: String? = attributes.language?.value?.let(::primaryLanguage),
)

/**
 * Better Versions first. CAM Versions always rank last and Audio-description ones after the rest, so
 * neither is picked by default. Then the highest quality (4K Dolby Vision, 4K HDR, 4K, then unknown),
 * then the highest bitrate where it's known, then a known Source, then the newest Listing.
 */
internal val BEST_FIRST: Comparator<Candidate> =
    compareBy<Candidate>({ it.attributes.cam }, { it.attributes.audioDescription })
        .thenByDescending { it.attributes.quality?.value }
        .thenByDescending { it.attributes.hdr?.value }
        .thenByDescending { it.attributes.bitrate?.value }
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
 * Whether a Title belongs in the browse rows for the Viewer's [contentLanguages]: it has a Version in
 * one of them, or an Untagged Version Likely in one because the Title was released in it ([originalLanguage]).
 */
internal fun inContentLanguages(candidates: List<Candidate>, contentLanguages: List<String>, originalLanguage: String?): Boolean {
    val wanted = contentLanguages.map(::primaryLanguage).toSet()
    return candidates.any { candidate ->
        candidate.primaryLanguage?.let { it in wanted } ?: (originalLanguage?.let(::primaryLanguage) in wanted)
    }
}

/** The language a Title was released in, as a primary subtag; null when Wikidata names none (#476), or for "xx" ("No Language"). */
internal fun namedOriginalLanguage(originalLanguage: String?): String? =
    originalLanguage?.takeIf { it.isNotBlank() && it != "xx" }?.let(::primaryLanguage)

/**
 * The language Original audio is sought in when Prefer original audio ([preferOriginalAudio]) acts on
 * a Title released in [originalLanguage]: null when the setting is off, when Wikidata names no language,
 * or when the original is one of the Viewer's [contentLanguages], in which case the Title plays
 * exactly as it would without the setting.
 */
internal fun originalAudioLanguage(originalLanguage: String?, contentLanguages: List<String>, preferOriginalAudio: Boolean): String? {
    if (!preferOriginalAudio) return null
    val original = namedOriginalLanguage(originalLanguage) ?: return null
    return original.takeIf { it !in contentLanguages.map(::primaryLanguage) }
}

/**
 * Whether a Version has Original audio, its audio being in the Title's [original] language (a
 * primary subtag): Confirmed when its audio languages have been seen and hold it, else Likely when
 * its main language is the original, as an Untagged Version's is ([likelyInOriginalLanguage]).
 */
internal fun VersionAttributes.hasOriginalAudio(original: String): Boolean {
    val heard = audioLanguages?.value
    if (heard != null) return original in heard
    return language?.value?.let(::primaryLanguage) == original
}

/** Whether a Version's audio is Confirmed to be in [original] (a primary subtag). */
internal fun VersionAttributes.hasConfirmedOriginalAudio(original: String): Boolean =
    audioLanguages?.let { it.provenance == Provenance.Confirmed && original in it.value } == true

/**
 * The one language a Version's Confirmed audio is in when that is neither one of the Viewer's
 * [contentLanguages] nor the Title's [original] language: what choosing it costs ("German only").
 * Null otherwise.
 */
internal fun VersionAttributes.onlyAudioLanguage(contentLanguages: List<String>, original: String?): String? {
    val heard = audioLanguages?.takeIf { it.provenance == Provenance.Confirmed }?.value?.singleOrNull() ?: return null
    return heard.takeIf { it != original && it !in contentLanguages.map(::primaryLanguage) }
}

/**
 * [candidates] with each Untagged one, whose Prefix names a Source but no language (`D+`, `NF`,
 * `TOP`) or is Multi-audio, Likely in the language the Title was released in ([originalLanguage], Wikidata's, #476): a
 * streaming service's or a Blu-ray's copy keeps the original audio. One whose Prefix names neither (`SC`, the
 * Nordic copies, which may be dubs) is left as it is, as is every one when Wikidata names no language.
 */
internal fun likelyInOriginalLanguage(candidates: List<Candidate>, originalLanguage: String?): List<Candidate> {
    // "xx" once meant "No Language"; Wikidata's codes are ISO 639-1, but the guard costs nothing.
    val language = originalLanguage?.takeIf { it.isNotBlank() && it != "xx" } ?: return candidates
    return candidates.map { candidate ->
        val attributes = candidate.attributes
        if (attributes.language != null || !attributes.untaggedCopy) candidate
        else Candidate(candidate.listing, attributes.copy(language = Known(language, Provenance.Likely)), candidate.unknownPrefix, candidate.listedLanguage)
    }
}

/**
 * Groups [candidates] by language: the Viewer's [contentLanguages] in their order, then the Title's
 * [original] language's when Prefer original audio acts on it ([originalAudioLanguage]), then
 * Untagged Versions (which may well be in the Viewer's language), then the other languages, most
 * Versions first. Within a group the best Version leads, and Versions equivalent to a better one
 * (same language and quality) become its Backups.
 */
internal fun groupByLanguage(candidates: List<Candidate>, contentLanguages: List<String>, original: String? = null): Pair<List<LanguageGroup>, List<LanguageGroup>> {
    val wanted = (contentLanguages.map(::primaryLanguage) + listOfNotNull(original)).distinct()
    val groups = candidates.sortedWith(BEST_FIRST)
        .groupBy { it.primaryLanguage }
        .map { (language, versions) -> LanguageGroup(language, leadersWithBackups(versions)) }
    val viewers = wanted.mapNotNull { language -> groups.find { it.language == language } } + groups.filter { it.language == null }
    val others = groups.filter { it.language != null && it.language !in wanted }
        .sortedWith(compareByDescending<LanguageGroup> { it.versionCount }.thenBy { it.language })
    return viewers to others
}

/**
 * The playable Versions of a Title for Play, best first: those in the language the Viewer last
 * picked ([remembered]), then, when Prefer original audio acts on the Title ([original], from
 * [originalAudioLanguage]), those with Original audio ([hasOriginalAudio]), then those in the
 * Viewer's [contentLanguages] (in their order), then the rest, each [BEST_FIRST]. Among the
 * Versions with Original audio, one whose file is Confirmed to carry subtitles in a Content language
 * beats one that isn't at the same picture, so its own subtitles show rather than downloaded ones.
 * CAM Versions rank after every other language, and Audio-description ones and those the [device]
 * can't display are left out. Versions above the [qualityCap] rank after every other language too,
 * the lowest quality first, so they play only when the Title has nothing within it.
 */
internal fun playable(
    candidates: List<Candidate>,
    contentLanguages: List<String>,
    device: DeviceProfile,
    qualityCap: QualityCap = QualityCap.UHD,
    remembered: String? = null,
    original: String? = null,
): List<Candidate> {
    val wanted = contentLanguages.map(::primaryLanguage).distinct()
    val picked = remembered?.let(::primaryLanguage)
    val overCap = { candidate: Candidate -> candidate.attributes.quality?.value?.let { it > qualityCap.quality } == true }
    val isOriginal = { candidate: Candidate -> original != null && candidate.attributes.hasOriginalAudio(original) }
    fun tier(candidate: Candidate): Int = when {
        picked != null && candidate.primaryLanguage == picked -> 0
        isOriginal(candidate) -> 1
        else -> 2 + (wanted.indexOf(candidate.primaryLanguage).takeIf { i -> i >= 0 } ?: wanted.size)
    }
    val subtitled = { candidate: Candidate -> isOriginal(candidate) && candidate.attributes.hasConfirmedSubtitlesIn(wanted) }
    return candidates.filter { !it.attributes.audioDescription && device.canDisplay(it.attributes) }
        .sortedWith(
            compareBy<Candidate>(
                { it.attributes.cam },
                overCap,
                { if (overCap(it)) it.attributes.quality?.value else null },
                ::tier,
            ).thenByDescending { it.attributes.quality?.value }
                .thenByDescending { it.attributes.hdr?.value }
                .thenBy { !subtitled(it) }
                .then(BEST_FIRST),
        )
}

private fun VersionAttributes.hasConfirmedSubtitlesIn(languages: List<String>): Boolean =
    subtitles?.let { it.provenance == Provenance.Confirmed && it.value.any { language -> primaryLanguage(language) in languages } } == true

/**
 * The languages whose markets subtitle rather than dub, so a copy filed under one of them (`NL`,
 * the Nordic `SC`) is the likeliest to carry a Title's Original audio.
 */
private val SUBTITLE_CULTURES = setOf("nl", "da", "sv", "no", "nb", "fi", "is")

/**
 * The Versions a Title's details page Header reads, in order: the best Version not yet [read] (by
 * Listing id) in each language, the Viewer's [contentLanguages] first, then Untagged Versions, then
 * the other languages, most Versions first.
 *
 * When Prefer original audio acts on the Title ([original], from [originalAudioLanguage]), the
 * order is that of how likely each is to carry Original audio: Untagged Versions (a Source but no
 * language); the subtitle cultures' copies (`SC`, which names no language or Source, then `NL` and
 * the Nordic languages); the highest-quality Version whose language is neither a Content language
 * nor the original, so the app learns whether the best picture carries the original audio; the
 * Content languages in their order; then the rest.
 *
 * Either way, the Versions whose Prefix no table knows go first ([unknownPrefixFirst]), the best
 * one not yet read of each such Prefix: they are the ones the app knows least about.
 */
internal fun readOrder(candidates: List<Candidate>, read: Set<Long>, contentLanguages: List<String>, original: String? = null): List<Candidate> =
    unknownPrefixFirst(candidates, read) + knownPrefixOrder(candidates.filter { it.unknownPrefix == null }, read, contentLanguages, original)

/** The best Version not yet [read] of each Prefix no table knows, the best of them first. */
internal fun unknownPrefixFirst(candidates: List<Candidate>, read: Set<Long>): List<Candidate> =
    candidates.filter { it.unknownPrefix != null }.sortedWith(BEST_FIRST).groupBy { it.unknownPrefix }.values
        .mapNotNull { group -> group.firstOrNull { it.listing.id !in read } }

private fun knownPrefixOrder(candidates: List<Candidate>, read: Set<Long>, contentLanguages: List<String>, original: String?): List<Candidate> {
    val wanted = contentLanguages.map(::primaryLanguage).distinct()
    val byLanguage = candidates.sortedWith(BEST_FIRST).groupBy { it.primaryLanguage }
    fun firstUnread(group: List<Candidate>?) = group?.firstOrNull { it.listing.id !in read }
    val others = byLanguage.keys.filterNotNull().filter { it !in wanted && it != original }
        .sortedWith(compareByDescending<String> { byLanguage.getValue(it).size }.thenBy { it })
    if (original == null) {
        return (wanted + listOf(null) + others).distinct().mapNotNull { firstUnread(byLanguage[it]) }
    }
    val unnamed = byLanguage[null].orEmpty()
    val untagged = firstUnread(unnamed.filter { it.attributes.untaggedCopy })
    val subtitleCultures = listOfNotNull(firstUnread(unnamed.filter { !it.attributes.untaggedCopy })) +
        others.filter { it in SUBTITLE_CULTURES }.mapNotNull { firstUnread(byLanguage[it]) }
    val outside = others.filter { it !in SUBTITLE_CULTURES }.mapNotNull { firstUnread(byLanguage[it]) }
    val bestOutside = outside.minWithOrNull(BEST_FIRST)
    val content = wanted.mapNotNull { firstUnread(byLanguage[it]) }
    val rest = outside.filter { it !== bestOutside } + listOfNotNull(firstUnread(byLanguage[original]))
    return listOfNotNull(untagged) + subtitleCultures + listOfNotNull(bestOutside) + content + rest
}

/** [chosen] with the Versions equivalent to it among [candidates] as its Backups, best first. */
internal fun withBackups(chosen: Candidate, candidates: List<Candidate>): Version =
    chosen.toVersion(backups = candidates.filter { it !== chosen && it.equivalence == chosen.equivalence }.sortedWith(BEST_FIRST).map { it.toVersion() })

/**
 * What to suggest when [playing] keeps buffering: the best of [versions] and their Backups in its
 * language with a lower quality (unknown counts as lowest), CAM and Audio-description ones aside.
 * Backups count because they were grouped before Play: one whose quality wasn't known then may not
 * match what the player has since learned of [playing]. Null when [playing]'s own quality isn't known.
 */
internal fun lowerVersion(playing: Version, versions: List<Version>): Version? {
    val quality = playing.attributes.qualityRank ?: return null
    val language = playing.attributes.language?.value?.let(::primaryLanguage)
    return versions.flatMap { listOf(it) + it.backups }.firstOrNull { other ->
        val attributes = other.attributes
        other.listingId != playing.listingId && !attributes.cam && !attributes.audioDescription &&
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
