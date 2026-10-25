package dev.betteriptv.core

import java.text.Normalizer
import java.time.Year
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * A Provider's mapping from Prefixes and Category hints to Version attributes, read from a data
 * asset (`prefix-table.txt`, whose header documents the format) so that a new Prefix needs no code.
 */
internal class PrefixTable private constructor(
    private val prefixes: Map<String, Rule>,
    private val categoryHints: List<Pair<Regex, Rule>>,
    private val clutterNames: List<Regex>,
) {
    /** Why a Listing is left out of the movie list. */
    enum class Hidden { Clutter, SportsReplay }

    /**
     * What the table makes of one Listing. [unknownPrefix] is the name's Prefix when the table
     * doesn't know it; what that Prefix would say is then left unknown.
     */
    class Description(val unknownPrefix: String?, val attributes: VersionAttributes, val hidden: Hidden?)

    private val categoryRules = ConcurrentHashMap<String, List<Rule>>()

    fun describe(name: String, category: String): Description {
        val prefix = PREFIX.find(name)?.groupValues?.get(1)?.replace('=', '-')
        val prefixRule = prefix?.let(prefixes::get)
        val rules = listOfNotNull(prefixRule) + categoryRules.getOrPut(category) {
            val folded = fold(category)
            categoryHints.filter { (hint, _) -> hint.containsMatchIn(folded) }.map { it.second }
        }
        val draft = rules.fold(Draft()) { draft, rule -> draft.apply(rule) }
        val hidden = when {
            draft.clutter || clutterNames.any { it.containsMatchIn(name) } -> Hidden.Clutter
            draft.sportsReplay -> Hidden.SportsReplay
            else -> null
        }
        return Description(prefix?.takeIf { prefixRule == null }, draft.attributes(), hidden)
    }

    /**
     * A Listing's name without its Prefix and year, for showing until cloud metadata arrives:
     * `EN - The Godfather  (1972)` is "The Godfather" from 1972. Anything after the year (often the
     * name again in another script) is dropped too, and so is a trailing country code (`Reacher (US)`).
     */
    fun cleanName(name: String): CleanName {
        var rest = name
        // Some names repeat their Prefix: `NL - NL - The Godfather: Part II`.
        while (true) rest = PREFIX.find(rest)?.let { rest.substring(it.range.last + 1) } ?: break
        // A release-style name, `P-A.Méthot.Le.Party.Du.Millénaire.2022`, ends with a bare year;
        // one still to come is part of the name (`Blade.Runner.2049`).
        val dotted = DOTTED.matchEntire(rest.trim())?.takeIf { it.groupValues[2].toInt() <= Year.now().value + 1 }
        if (dotted != null) return CleanName(dotted.groupValues[1].replace('.', ' '), dotted.groupValues[2].toInt())
        val year = YEAR.find(rest)
        val title = (if (year != null) rest.substring(0, year.range.first) else rest).replace(COUNTRY, "").replace(Regex("""\s+"""), " ").trim()
        return CleanName(title.ifEmpty { name.trim() }, year?.groupValues?.get(1)?.toInt())
    }

    class CleanName(val name: String, val year: Int?)

    /** One line of the table: what a Prefix or Category hint says. */
    private data class Rule(
        val language: String? = null,
        val subtitles: Set<String> = emptySet(),
        val subtitled: Boolean = false,
        val quality: Quality? = null,
        val hdr: Hdr? = null,
        val source: String? = null,
        val dolbyAudio: Boolean = false,
        val cam: Boolean = false,
        val audioDescription: Boolean = false,
        val sportsReplay: Boolean = false,
        val clutter: Boolean = false,
    )

    private data class Draft(
        val language: String? = null,
        val subtitles: Set<String> = emptySet(),
        val quality: Quality? = null,
        val hdr: Hdr? = null,
        val source: String? = null,
        val dolbyAudio: Boolean = false,
        val cam: Boolean = false,
        val audioDescription: Boolean = false,
        val sportsReplay: Boolean = false,
        val clutter: Boolean = false,
    ) {
        fun apply(rule: Rule): Draft {
            val moved = if (rule.subtitled) copy(language = null, subtitles = subtitles + listOfNotNull(language)) else this
            return moved.copy(
                language = rule.language ?: moved.language,
                subtitles = moved.subtitles + rule.subtitles,
                quality = listOfNotNull(moved.quality, rule.quality).maxOrNull(),
                hdr = listOfNotNull(moved.hdr, rule.hdr).maxOrNull(),
                source = moved.source ?: rule.source,
                dolbyAudio = moved.dolbyAudio || rule.dolbyAudio,
                cam = moved.cam || rule.cam,
                audioDescription = moved.audioDescription || rule.audioDescription,
                sportsReplay = moved.sportsReplay || rule.sportsReplay,
                clutter = moved.clutter || rule.clutter,
            )
        }

        fun attributes() = VersionAttributes(
            language = language?.let(::likely),
            subtitles = subtitles.takeIf { it.isNotEmpty() }?.let(::likely),
            quality = quality?.let(::likely),
            hdr = hdr?.let(::likely),
            source = source?.let(::likely),
            dolbyAudio = dolbyAudio,
            cam = cam,
            audioDescription = audioDescription,
        )

        private fun <T> likely(value: T) = Known(value, Provenance.Likely)
    }

    companion object {
        /**
         * `PREFIX - Title`, also with the space missing on one side of the dash (`GR- Title`) or a
         * spaced dot (`4K-FR . Title`). A Prefix is capitals, digits and `+` joined by `-` (or the
         * odd `=`), with at least one letter, so `007 - Octopussy` and `Spider-Man: ...` have none.
         */
        private val PREFIX = Regex("""^\s*((?=[A-Z0-9+=-]*[A-Z])[A-Z0-9+]+(?:[-=][A-Z0-9+]+)*)(?:\s+-\s*|-\s+|\s+\.\s+)""")

        private val YEAR = Regex("""\((19\d\d|20\d\d)\)""")

        private val DOTTED = Regex("""(\S+?)\.(19\d\d|20\d\d)""")

        private val COUNTRY = Regex("""\s*\([A-Z]{2}\)\s*$""")

        /** The surveyed Provider's table, bundled with the app. */
        fun bundled(): PrefixTable = parse(
            PrefixTable::class.java.getResourceAsStream("prefix-table.txt")!!.bufferedReader().use { it.readText() },
        )

        fun parse(text: String): PrefixTable {
            val prefixes = LinkedHashMap<String, Rule>()
            val hints = mutableListOf<Pair<Regex, Rule>>()
            val clutterNames = mutableListOf<Regex>()
            var section: String? = null
            text.lineSequence().forEachIndexed { index, raw ->
                fun fail(why: String): Nothing = throw IllegalArgumentException("prefix-table.txt line ${index + 1}: $why")
                if (raw.isBlank() || raw.trimStart().startsWith("#")) return@forEachIndexed
                if (raw.startsWith("[")) {
                    section = raw.trim().removeSurrounding("[", "]")
                    return@forEachIndexed
                }
                if (section == "clutter names") {
                    clutterNames += Regex(raw.trim())
                    return@forEachIndexed
                }
                val line = raw.replace(Regex("""\s+#.*$"""), "")
                val key = line.substringBefore('|').trim()
                val rule = parseRule(line.substringAfter('|', "")) { fail(it) }
                when (section) {
                    "prefixes" -> if (prefixes.put(key, rule) != null) fail("$key is listed twice")
                    "category hints" -> hints += Regex("""(?<![\p{L}\p{N}-])${Regex.escape(fold(key))}(?![\p{L}\p{N}-])""") to rule
                    else -> fail("outside a [section]")
                }
            }
            return PrefixTable(prefixes, hints, clutterNames)
        }

        private fun parseRule(text: String, fail: (String) -> Nothing): Rule =
            text.split(';').map { it.trim() }.filter { it.isNotEmpty() }.fold(Rule()) { rule, attribute ->
                val name = attribute.substringBefore('=').trim()
                val value = attribute.substringAfter('=', "").trim()
                when (name) {
                    "language" -> rule.copy(language = value)
                    "subtitles" -> rule.copy(subtitles = rule.subtitles + value.split(',').map { it.trim() })
                    "subtitled" -> rule.copy(subtitled = true)
                    "quality" -> rule.copy(quality = if (value == "4K") Quality.UHD else fail("unknown quality $value"))
                    "hdr" -> rule.copy(hdr = when (value) { "HDR10" -> Hdr.HDR10; "DV" -> Hdr.DolbyVision; else -> fail("unknown hdr $value") })
                    "source" -> rule.copy(source = value)
                    "dolby-audio" -> rule.copy(dolbyAudio = true)
                    "cam" -> rule.copy(cam = true)
                    "audio-description" -> rule.copy(audioDescription = true)
                    "sports-replay" -> rule.copy(sportsReplay = true)
                    "clutter" -> rule.copy(clutter = true)
                    else -> fail("unknown attribute $attribute")
                }
            }

        /** Folds superscripts (`ᴰᴼᴸᴮʸ`), accents and case, so Category hints match however they're styled. */
        private fun fold(text: String): String {
            val compatible = Normalizer.normalize(text, Normalizer.Form.NFKC)
            val unaccented = Normalizer.normalize(compatible, Normalizer.Form.NFD).replace(Regex("""\p{Mn}+"""), "")
            // ᶦ folds to a small capital I, which has no uppercase of its own.
            return unaccented.replace('ɪ', 'I').uppercase(Locale.ROOT)
        }
    }
}
