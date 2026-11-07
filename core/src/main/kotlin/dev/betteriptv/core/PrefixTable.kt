package dev.betteriptv.core

import java.text.Normalizer
import java.time.Year
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * A Provider's mapping from Prefixes and Category hints to Version attributes: the Base table
 * (`prefix-table.txt`, whose header documents the format and the merge) with the Provider's own
 * Provider table merged into it, read from text so that a new Prefix needs no code.
 */
internal class PrefixTable private constructor(
    private val prefixes: Map<String, Rule>,
    private val categoryHints: List<Pair<Regex, Rule>>,
    private val clutterNames: List<Regex>,
    private val liveClutterNames: List<Regex>,
    private val emptyEventSlots: List<Regex>,
    /** The Tier tags, as uppercase words without their colon. */
    private val tierTags: Set<String>,
    /** Each Channel quality word, uppercase, with what it says of the variant. */
    private val qualityWords: Map<String, Rule>,
    /** The live Category country codes that aren't countries (`AR`, `EX YU`), uppercase, to the name the browser shows for them. */
    private val countryAliases: Map<String, String>,
    /** Each genre Category word, as a whole-word pattern over the folded name, with its genre's place in the genre list. */
    private val genreWords: List<Pair<Regex, Int>>,
    /** The zone the Provider writes Event channel times in when the name states none ([eventStart]); UTC when the table names none. */
    val eventTimeZone: ZoneId,
) {
    /** What the Channel browser calls a live Category's country [code] that isn't a country ("Arabic" for `AR`), or null for one not listed. */
    fun countryAlias(code: String): String? = countryAliases[code.uppercase(Locale.ROOT)]

    /** The languages the table names for any Prefix or Category hint, as primary subtags: `fr-CA` counts as French. */
    private val knownLanguages: Set<String> by lazy {
        (prefixes.values + categoryHints.map { it.second }).mapNotNull { it.language?.let(::primaryLanguage) }.filter { it.isNotEmpty() && it != "xx" }.toSet()
    }

    /** Whether the table names [language] (a primary subtag) for some Prefix or Category: a Header read may move a Version to it. */
    fun knowsLanguage(language: String): Boolean = language in knownLanguages

    /**
     * Where a browsed Category named [name] (`SPORTS · FR`, `ACTUALITÉS`) stands in the table's genre
     * list, 0 for the first genre, or null when it names no genre: a service bucket (`AT&T`, `PRIME`,
     * `NETFLIX ON AIR`). A name with words of two genres takes the earlier one.
     */
    fun genreRank(name: String): Int? {
        val folded = fold(name)
        return genreWords.filter { (word, _) -> word.containsMatchIn(folded) }.minOfOrNull { (_, rank) -> rank }
    }

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
     * The quality words are taken off the end of the name, one after another (`ᴴᴰ ʰᵉᵛᶜ`, `HD (SAT)`,
     * `ᴴᴰ/ᴿᴬᵂ ⁶⁰ᶠᵖˢ`, `HD ◉`), each read with its Unicode superscripts normalised (`ᴴᴰ` reads HD); a
     * word the table marks `superscript` counts only as the Provider's superscript tag (`ᴿᴬᵂ`), so
     * "WWE RAW" keeps its name. The rest is normalised the same way, and the tags before the colons
     * at its start are read: Tier tags are dropped (`VIP: `, `TR VIP: `, `8K: `) and a two-letter
     * country tag is kept apart (`UK: `, `CA FR: `); spaces are collapsed. `VIP: TNT SPORT 1 ᴴᴰ ʰᵉᵛᶜ`
     * is the Channel "TNT SPORT 1" with no country, an HD HEVC variant; `8K: beIN SP⚽RTS 1 SD` keeps
     * its emoji. Two Listings under the identical name are variants of one Channel too.
     */
    fun channelName(name: String): ChannelName {
        var tail = name
        var quality: Quality? = null
        var hevc = false
        while (true) {
            val token = TAIL_TOKEN.find(tail) ?: break
            val raw = token.groupValues[1]
            val plain = Normalizer.normalize(raw, Normalizer.Form.NFKC).uppercase(Locale.ROOT)
            // `(HD)` is HD; `(SAT)` is only ever in its parentheses.
            val rule = qualityWords[plain] ?: qualityWords[plain.removeSurrounding("(", ")")]?.takeIf { !it.superscript || raw != plain }
            if (rule == null || (rule.superscript && raw == plain)) break
            tail = tail.substring(0, token.range.first)
            // A name saying SD is SD whatever else it says (`SD ᴿᴬᵂ`).
            quality = listOfNotNull(quality, rule.quality).minOrNull()
            hevc = hevc || rule.hevc
        }
        var rest = withoutSmallCapitals(Normalizer.normalize(tail, Normalizer.Form.NFKC))
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
        val cleaned = rest.replace(SPACES, " ").trim().ifEmpty { name.trim() }
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
        /** A Channel quality word that counts only in the Provider's superscript form. */
        val superscript: Boolean = false,
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

        /** The last word of a live Listing's name, with the spaces or `/` before it: `ᴴᴰ`, `(SAT)`, `◉`, `HD+`, the `ᴿᴬᵂ` of `ᴴᴰ/ᴿᴬᵂ`. */
        private val TAIL_TOKEN = Regex("""[\s/]*(\(?[^\s/()]+\)?)\s*$""")

        /** The Base table's text, shipped with the app. */
        private val baseText: String by lazy { resource("prefix-table.txt")!! }

        /** The Base table alone: what the app knows of a Provider that has no Provider table. */
        fun base(): PrefixTable = parse(baseText)

        /** The Base table with [providerTable] (a Provider table's text, or null for none) merged into it. */
        fun withProviderTable(providerTable: String?): PrefixTable = parse(baseText, providerTable)

        /**
         * The hand-written Provider table shipped with the app for the Provider whose host hashes to
         * [providerKey] ([dev.betteriptv.core.providerKey]), or null for a Provider with none. The build
         * copies them from cloud/provider-tables/ until the cloud serves them (#431).
         */
        fun bundledProviderTable(providerKey: String): String? =
            if (PROVIDER_KEY.matches(providerKey)) resource("provider-tables/$providerKey.txt") else null

        private val PROVIDER_KEY = Regex("[0-9a-f]{64}")

        private fun resource(path: String): String? =
            PrefixTable::class.java.getResourceAsStream(path)?.bufferedReader()?.use { it.readText() }

        /**
         * Reads the Base table's [base] text and merges a Provider table's [provider] text into it, by
         * the rules the Base table's header documents: a Provider line for a key Base has replaces
         * Base's, an empty rule keeps the key known and saying nothing, the event time zone replaces
         * Base's, and every other line adds.
         */
        fun parse(base: String, provider: String? = null): PrefixTable {
            val sections = Sections()
            sections.read(base, "prefix-table.txt", ofProvider = false)
            provider?.let { sections.read(it, "the Provider table", ofProvider = true) }
            return sections.table()
        }

        /** What the table's sections hold so far, as [parse] reads its sources one after the other. */
        private class Sections {
            val prefixes = LinkedHashMap<String, Rule>()
            /** Each Category hint's phrase, as written, to its rule. */
            val hints = LinkedHashMap<String, Rule>()
            val clutterNames = mutableListOf<Regex>()
            val liveClutterNames = mutableListOf<Regex>()
            val emptyEventSlots = mutableListOf<Regex>()
            val tierTags = LinkedHashSet<String>()
            val qualityWords = LinkedHashMap<String, Rule>()
            val countryAliases = LinkedHashMap<String, String>()
            /** Each genre word, as written, to its genre. */
            val genreWords = LinkedHashMap<String, String>()
            var eventTimeZone: ZoneId? = null

            fun read(text: String, source: String, ofProvider: Boolean) {
                // A key is listed once per source; a later source may list it again to replace it.
                val seen = HashSet<String>()
                var zoneSeen = false
                var section: String? = null
                text.lineSequence().forEachIndexed { index, raw ->
                    fun fail(why: String): Nothing = throw IllegalArgumentException("$source line ${index + 1}: $why")
                    fun once(key: String) { if (!seen.add("$section\u0000$key")) fail("$key is listed twice") }
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
                    when (section) {
                        "event time zone" -> {
                            if (zoneSeen) fail("a second event time zone")
                            zoneSeen = true
                            eventTimeZone = runCatching { ZoneId.of(key) }.getOrElse { fail("$key is not a zone") }
                        }
                        "country aliases" -> {
                            val code = key.uppercase(Locale.ROOT)
                            once(code)
                            val alias = line.substringAfter('|', "").trim()
                            // A Provider table drops a Base alias with an empty name.
                            if (alias.isEmpty()) {
                                if (!ofProvider) fail("$key has no name")
                                countryAliases.remove(code)
                            } else {
                                countryAliases[code] = alias
                            }
                        }
                        "genre categories" -> {
                            once(key)
                            val genre = line.substringAfter('|', "").trim()
                            // A Provider table drops a Base genre word with an empty genre.
                            if (genre.isEmpty()) {
                                if (!ofProvider) fail("$key has no genre")
                                genreWords.remove(key)
                            } else {
                                genreWords[key] = genre
                            }
                        }
                        else -> {
                            val rule = parseRule(line.substringAfter('|', "")) { fail(it) }
                            when (section) {
                                "prefixes" -> { once(key); prefixes[key] = rule }
                                "category hints" -> { once(key); hints[key] = rule }
                                // The colon is where the tag ends, not part of it.
                                "tier tags" -> tierTags += key.removeSuffix(":").uppercase(Locale.ROOT)
                                "channel quality words" -> key.uppercase(Locale.ROOT).let { once(it); qualityWords[it] = rule }
                                else -> fail("outside a [section]")
                            }
                        }
                    }
                }
            }

            fun table(): PrefixTable {
                val genres = LinkedHashMap<String, Int>()
                val ranked = genreWords.map { (word, genre) -> wholeWord(word) to genres.getOrPut(genre) { genres.size } }
                return PrefixTable(
                    prefixes, hints.map { (phrase, rule) -> wholeWord(phrase) to rule }, clutterNames, liveClutterNames, emptyEventSlots,
                    tierTags, qualityWords, countryAliases, ranked, eventTimeZone ?: ZoneOffset.UTC,
                )
            }
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
                    "superscript" -> rule.copy(superscript = true)
                    else -> fail("unknown attribute $attribute")
                }
            }

        /** [phrase] as whole words of a [fold]ed Category name; a hyphen joins words, so SUBS doesn't match inside MULTI-SUBS. */
        private fun wholeWord(phrase: String) = Regex("""(?<![\p{L}\p{N}-])${Regex.escape(fold(phrase))}(?![\p{L}\p{N}-])""")

        /** Folds superscripts (`ᴰᴼᴸᴮʸ`), small capitals (`ɴᴇᴡꜱ`, and the small capital I that NFKC makes of `ᶦ`), accents and case, so Category hints match however they're styled. */
        private fun fold(text: String): String {
            val compatible = withoutSmallCapitals(Normalizer.normalize(text, Normalizer.Form.NFKC))
            val unaccented = Normalizer.normalize(compatible, Normalizer.Form.NFD).replace(MARKS, "")
            return unaccented.uppercase(Locale.ROOT)
        }
    }
}
