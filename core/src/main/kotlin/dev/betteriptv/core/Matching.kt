package dev.betteriptv.core

import java.text.Normalizer
import java.util.Locale

/**
 * What the cloud is asked to Match a Listing (cloud/src/match.ts): its cleaned [name] and [year],
 * its Likely [language] (ISO 639) and the [tmdbId] the Provider gave it, if any.
 */
internal data class MatchQuestion(val kind: TitleKind, val name: String, val year: Int?, val language: String?, val tmdbId: Long?) {
    /** The device's cache key for the answer: the same for Listings asking the same, however their names are spelled. */
    val key: String get() = listOf(kind.name.lowercase(Locale.ROOT), tmdbId ?: "", matchName(name), year ?: "", language.orEmpty()).joinToString("|")
}

/**
 * A name with case, accents and punctuation folded away (`Lilo & Stitch` is `lilo stitch`) and an
 * acronym's letters joined (`M.B.B.S.` is `mbbs`), as the cloud folds names (cloud/src/match.ts).
 */
internal fun matchName(name: String): String =
    Normalizer.normalize(name, Normalizer.Form.NFKD)
        .replace(Regex("""\p{M}+"""), "")
        .lowercase(Locale.ROOT)
        .replace(Regex("""[^\p{L}\p{N}]+"""), " ")
        .trim()
        .replace(Regex("""(?<![\p{L}\p{N}])(\p{L}) (?=\p{L}(?![\p{L}\p{N}]))"""), "$1")
