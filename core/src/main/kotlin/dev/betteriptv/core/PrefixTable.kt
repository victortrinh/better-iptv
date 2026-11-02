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
    private val liveClutterNames: List<Regex>,
    private val emptyEventSlots: List<Regex>,
    /** The Tier tags, as uppercase words without their colon. */
    private val tierTags: Set<String>,
    /** Each Channel quality word as a whole-word pattern, with what it says of the variant. */
    private val qualityWords: List<Pair<Regex, Rule>>,
) {
    /** Why a Listing is left out of the movie list. */
    enum class Hidden { Clutter, SportsReplay }

    /**
     * What the table makes of one Listing. [unknownPrefix] is the name's Prefix when the table
     * doesn't know it; what that Prefix would say is then left unknown.
     */
    class Description(val unknownPrefix: String?, val attributes: VersionAttributes, val hidden: Hidden?)

    /** What the table makes of one live Listing: a Channel, an empty Event slot (Clutter), or other Clutter. */
    enum class LiveListing { Channel, EmptyEventSlot, Clutter }

    /**
     * What a live Listing's name says: which Channel it is a variant of, and what the variant is. The
     * Channel's [key] is its identity, the country tag and the normalised [name] (`UK: BBC ONE NI`),
     * with case folded so a Provider's spelling can't split a Channel; [name] keeps the Provider's
     * casing, without the Tier tag, the country tag or the quality words. [country] is the country
     * tag (`UK`, `CA FR`), null when the name has none. [quality] is the variant's Likely quality
     * (UHD for 4K, HD for HD, FHD and RAW, or SD), null when the name says none; [hevc] whether the
     * name says HEVC.
     */
    class ChannelName(val key: String, val name: String, val country: String?, val quality: Quality?, val hevc: Boolean)

    private val categoryRules = ConcurrentHashMap<String, List<Rule>>()
    /** [language]'s answers by Prefix and Category, with "" for none. */
    private val languages = ConcurrentHashMap<String, String>()

    /**
     * The main audio's language [describe] tells for a Listing, without the rest: it depends only
     * on the Prefix and the Category, so it's worked out once for each. The search index asks for
     * every Listing's, and describing each one was most of its garbage on the stick (#106).
     */
    fun language(name: String, category: String): String? {
        val prefix = PREFIX.find(name)?.groupValues?.get(1)
        return languages.getOrPut("${prefix.orEmpty()}\u0000$category") { describe(name, category).attributes.language?.value.orEmpty() }.ifEmpty { null }
    }

    fun describe(name: String, category: String): Description {
        val prefix = PREFIX.find(name)?.groupValues?.get(1)?.replace('=', '-')
        val prefixRule = prefix?.let(prefixes::get)
        val rules = listOfNotNull(prefixRule) + categoryRules(category)
        val draft = rules.fold(Draft()) { draft, rule -> draft.apply(rule) }
        val hidden = when {
            draft.clutter || clutterNames.any { it.containsMatchIn(name) } -> Hidden.Clutter
            draft.sportsReplay -> Hidden.SportsReplay
            else -> null
        }
        return Description(prefix?.takeIf { prefixRule == null }, draft.attributes(), hidden)
    }

    /**
     * What a live Listing is, by its [name] and its [category]: an empty Event slot (`- NO EVENT
     * STREAMING -`, `RFEF EVENTO 3 | No event`), which is Clutter and tells an Event Category; other
     * Clutter (a separator row, a finished-marked Event, radio); or a Channel. Event channels finished
     * by the clock are told apart by [eventStart]. Sports replay hints mean nothing for live Listings.
     */
    fun describeLive(name: String, category: String): LiveListing = when {
        emptyEventSlots.any { it.containsMatchIn(name) } -> LiveListing.EmptyEventSlot
        describe(name, category).hidden == Hidden.Clutter || liveClutterNames.any { it.containsMatchIn(name) } -> LiveListing.Clutter
        else -> LiveListing.Channel
    }

    /** Whether [category]'s name says its Listings are Clutter (radio, karaoke, audiobooks). */
    fun isClutterCategory(category: String): Boolean = categoryRules(category).any { it.clutter }

    /**
     * The Channel a live Listing named [name] is a variant of, and what the variant is ([ChannelName]).
     * Unicode superscripts are normalised (`ᴴᴰ` reads HD), then the tags before the colons at the
     * start are read: Tier tags are dropped (`VIP: `, `TR VIP: `, `8K: `) and a two-letter country tag
     * is kept apart (`UK: `, `CA FR: `); then the quality words are taken out wherever they stand
     * (`HD`, `ʰᵉᵛᶜ`, `HD/RAW`, `(SAT)`), and spaces are collapsed. `VIP: TNT SPORT 1 ᴴᴰ ʰᵉᵛᶜ` is the
     * Channel "TNT SPORT 1" with no country, an HD HEVC variant; `8K: beIN SP⚽RTS 1 SD` keeps its
     * emoji. Two Listings under the identical name are variants of one Channel too.
     */
    fun channelName(name: String): ChannelName {
        var rest = Normalizer.normalize(name, Normalizer.Form.NFKC)
        var country: String? = null
        // The tags before the colons at the start: Tier tags, and a country tag, in either order.
        while (true) {
            val head = HEAD.find(rest) ?: break
            val words = head.groupValues[1].split(' ')
            val tiers = words.filter { it.uppercase(Locale.ROOT) in tierTags }
            val codes = words - tiers.toSet()
            // Anything else before the colon is the name's own (`Arena: Jets vs Oilers`, `NHL: ...`).
            if (codes.any { !COUNTRY_CODE.matches(it) } || codes.size > 2 || (codes.isNotEmpty() && country != null)) break
            if (codes.isNotEmpty()) country = codes.joinToString(" ")
            rest = rest.substring(head.range.last + 1)
        }
        var quality: Quality? = null
        var hevc = false
        for ((word, rule) in qualityWords) {
            if (!word.containsMatchIn(rest)) continue
            rest = rest.replace(word, " ")
            // A name saying SD is SD whatever else it says (`SD ᴿᴬᵂ`).
            quality = listOfNotNull(quality, rule.quality).minOrNull()
            hevc = hevc || rule.hevc
        }
        // `ᴴᴰ/ᴿᴬᵂ` leaves its slash behind.
        val cleaned = rest.replace(LONE_SLASH, " ").replace(SPACES, " ").trim().ifEmpty { name.trim() }
        val key = listOfNotNull(country, cleaned).joinToString(": ").uppercase(Locale.ROOT)
        return ChannelName(key, cleaned, country, quality, hevc)
    }

    private fun categoryRules(category: String): List<Rule> = categoryRules.getOrPut(category) {
        val folded = fold(category)
        categoryHints.filter { (hint, _) -> hint.containsMatchIn(folded) }.map { it.second }
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
        val title = (if (year != null) rest.substring(0, year.range.first) else rest).replace(COUNTRY, "").replace(SPACES, " ").trim()
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
        val hevc: Boolean = false,
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

        private val SPACES = Regex("""\s+""")

        private val MARKS = Regex("""\p{Mn}+""")

        /** The words before a colon at the start of a live Listing's name: `VIP:`, `CA FR:`, `TR VIP:`, up to three short codes. */
        private val HEAD = Regex("""^\s*([A-Za-z0-9+]{1,5}(?: [A-Za-z0-9+]{1,5}){0,2}):\s*""")

        /** A country tag's word: two capitals. */
        private val COUNTRY_CODE = Regex("""[A-Z]{2}""")

        /** A `/` standing alone, once the quality words around it are gone. */
        private val LONE_SLASH = Regex("""(?<!\S)/(?!\S)""")

        /** The surveyed Provider's table, bundled with the app. */
        fun bundled(): PrefixTable = parse(
            PrefixTable::class.java.getResourceAsStream("prefix-table.txt")!!.bufferedReader().use { it.readText() },
        )

        fun parse(text: String): PrefixTable {
            val prefixes = LinkedHashMap<String, Rule>()
            val hints = mutableListOf<Pair<Regex, Rule>>()
            val clutterNames = mutableListOf<Regex>()
            val liveClutterNames = mutableListOf<Regex>()
            val emptyEventSlots = mutableListOf<Regex>()
            val tierTags = LinkedHashSet<String>()
            val qualityWords = mutableListOf<Pair<Regex, Rule>>()
            var section: String? = null
            text.lineSequence().forEachIndexed { index, raw ->
                fun fail(why: String): Nothing = throw IllegalArgumentException("prefix-table.txt line ${index + 1}: $why")
                if (raw.isBlank() || raw.trimStart().startsWith("#")) return@forEachIndexed
                if (raw.startsWith("[")) {
                    section = raw.trim().removeSurrounding("[", "]")
                    return@forEachIndexed
                }
                val names = when (section) {
                    "clutter names" -> clutterNames
                    "live clutter names" -> liveClutterNames
                    "empty event slots" -> emptyEventSlots
                    else -> null
                }
                if (names != null) {
                    names += Regex(raw.trim())
                    return@forEachIndexed
                }
                val line = raw.replace(Regex("""\s+#.*$"""), "")
                val key = line.substringBefore('|').trim()
                val rule = parseRule(line.substringAfter('|', "")) { fail(it) }
                when (section) {
                    "prefixes" -> if (prefixes.put(key, rule) != null) fail("$key is listed twice")
                    "category hints" -> hints += Regex("""(?<![\p{L}\p{N}-])${Regex.escape(fold(key))}(?![\p{L}\p{N}-])""") to rule
                    // The colon is where the tag ends, not part of it.
                    "tier tags" -> tierTags += key.removeSuffix(":").uppercase(Locale.ROOT)
                    // A whole word, however it's cased; `+` counts as part of a word (`LIGUE1+`), and parentheses may enclose it.
                    "channel quality words" -> qualityWords += Regex("""(?<![\p{L}\p{N}+])\(?${Regex.escape(key)}\)?(?![\p{L}\p{N}+])""", RegexOption.IGNORE_CASE) to rule
                    else -> fail("outside a [section]")
                }
            }
            return PrefixTable(prefixes, hints, clutterNames, liveClutterNames, emptyEventSlots, tierTags, qualityWords)
        }

        private fun parseRule(text: String, fail: (String) -> Nothing): Rule =
            text.split(';').map { it.trim() }.filter { it.isNotEmpty() }.fold(Rule()) { rule, attribute ->
                val name = attribute.substringBefore('=').trim()
                val value = attribute.substringAfter('=', "").trim()
                when (name) {
                    "language" -> rule.copy(language = value)
                    "subtitles" -> rule.copy(subtitles = rule.subtitles + value.split(',').map { it.trim() })
                    "subtitled" -> rule.copy(subtitled = true)
                    "quality" -> rule.copy(quality = when (value) { "4K" -> Quality.UHD; "HD" -> Quality.HD; "SD" -> Quality.SD; else -> fail("unknown quality $value") })
                    "hdr" -> rule.copy(hdr = when (value) { "HDR10" -> Hdr.HDR10; "DV" -> Hdr.DolbyVision; else -> fail("unknown hdr $value") })
                    "source" -> rule.copy(source = value)
                    "dolby-audio" -> rule.copy(dolbyAudio = true)
                    "cam" -> rule.copy(cam = true)
                    "audio-description" -> rule.copy(audioDescription = true)
                    "sports-replay" -> rule.copy(sportsReplay = true)
                    "clutter" -> rule.copy(clutter = true)
                    "hevc" -> rule.copy(hevc = true)
                    else -> fail("unknown attribute $attribute")
                }
            }

        /** Folds superscripts (`ᴰᴼᴸᴮʸ`), accents and case, so Category hints match however they're styled. */
        private fun fold(text: String): String {
            val compatible = Normalizer.normalize(text, Normalizer.Form.NFKC)
            val unaccented = Normalizer.normalize(compatible, Normalizer.Form.NFD).replace(MARKS, "")
            // ᶦ folds to a small capital I, which has no uppercase of its own.
            return unaccented.replace('ɪ', 'I').uppercase(Locale.ROOT)
        }
    }
}
