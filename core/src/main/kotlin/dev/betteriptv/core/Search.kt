package dev.betteriptv.core

import java.text.Normalizer
import java.util.Locale

/**
 * What a search found, best first in each group: [movies] and [shows] whose names match, and
 * [people] (cast) whose names match, each with their Titles the Provider has, and the [channels]
 * whose names match.
 */
data class SearchResults(val movies: List<TitleSummary>, val shows: List<TitleSummary>, val people: List<Person>, val channels: List<Channel> = emptyList()) {
    companion object {
        val NONE = SearchResults(emptyList(), emptyList(), emptyList())
    }
}

/** Someone in TMDB's cast of Titles the Provider has, with those Titles, best first. */
data class Person(val name: String, val titles: List<TitleSummary>)

/**
 * The catalog's names in memory, built once per catalog and cached metadata: every Listing name of
 * a Title's Versions (cleaned of Prefixes), every name TMDB has for it, and its cast. Names are
 * reduced to words without accents or case, and each distinct word is kept once with the Titles
 * (or people) that have it, so a query only scans the vocabulary, not the catalog.
 */
internal class SearchIndex private constructor(
    val titles: List<Entry>,
    private val titleWords: Map<String, IntArray>,
    val people: List<String>,
    /** Each person's Titles, as indexes into [titles]. */
    val personTitles: List<IntArray>,
    private val personWords: Map<String, IntArray>,
    /** The Channels' Provider stream ids, in the Provider's order. */
    val channels: LongArray = LongArray(0),
    private val channelWords: Map<String, IntArray> = emptyMap(),
) {
    /** One Title: its primary languages of Versions, so Content languages can lead, and TMDB's popularity. */
    class Entry(val id: TitleId, val languages: Set<String>, val popularity: Double)

    /** A match and how close it was: 0 for whole words or words being typed, 1 for a tolerated typo. */
    class Hit(val index: Int, val distance: Int)

    private val positions: Map<TitleId, Int> = titles.withIndex().associate { (index, entry) -> entry.id to index }

    /** Where [id] is in [titles], or null when the catalog has no such Title. */
    fun position(id: TitleId): Int? = positions[id]

    fun findTitles(query: String): List<Hit> = find(query, titleWords)

    fun findPeople(query: String): List<Hit> = find(query, personWords)

    /** Channels whose names match [query], as indexes into [channels]. */
    fun findChannels(query: String): List<Hit> = find(query, channelWords)

    /** The entries that have a word matching every word of [query]; the worst word's distance counts. */
    private fun find(query: String, words: Map<String, IntArray>): List<Hit> {
        val wanted = words(query).ifEmpty { return emptyList() }
        var found: HashMap<Int, Int>? = null
        for (word in wanted) {
            val forWord = HashMap<Int, Int>()
            for ((known, entries) in words) {
                val distance = distance(word, known) ?: continue
                for (entry in entries) if (distance < (forWord[entry] ?: Int.MAX_VALUE)) forWord[entry] = distance
            }
            found = found?.let { before ->
                HashMap<Int, Int>().apply { before.forEach { (entry, d) -> forWord[entry]?.let { put(entry, maxOf(d, it)) } } }
            } ?: forWord
            if (found.isEmpty()) break
        }
        return found.orEmpty().map { (entry, distance) -> Hit(entry, distance) }
    }

    /** Takes the catalog's Titles, then the metadata cached for them, and the Channels, in any order. */
    class Builder {
        private val titles = mutableListOf<Entry>()
        private val positions = HashMap<TitleId, Int>()
        private val titleWords = HashMap<String, Postings>()
        private val people = mutableListOf<String>()
        private val personTitles = mutableListOf<Postings>()
        private val personWords = HashMap<String, Postings>()
        private val personIndex = HashMap<String, Int>()
        private val channels = Postings64()
        private val channelWords = HashMap<String, Postings>()

        /** Adds a Title found by [names], with Versions in [languages]. */
        fun title(id: TitleId, names: Collection<String>, languages: Set<String>) {
            val index = titles.size
            titles += Entry(id, languages, 0.0)
            positions[id] = index
            addWords(index, names)
        }

        /** Adds what TMDB says of Title [id]: more [names] it's found by, its [popularity] and [cast]. Titles not added are left out. */
        fun metadata(id: TitleId, names: Collection<String>, popularity: Double, cast: List<String>) {
            val index = positions[id] ?: return
            titles[index] = titles[index].let { Entry(it.id, it.languages, popularity) }
            addWords(index, names)
            for (name in cast.distinct()) {
                val person = personIndex.getOrPut(name) {
                    people += name
                    personTitles += Postings()
                    words(name).forEach { word -> personWords.getOrPut(word, ::Postings).add(people.size - 1) }
                    people.size - 1
                }
                personTitles[person].add(index)
            }
        }

        /** Postings skip a Title added twice in a row, so a word in several of its names is kept once. */
        private fun addWords(index: Int, names: Collection<String>) {
            for (name in names) for (word in words(name)) titleWords.getOrPut(word, ::Postings).add(index)
        }

        /** Adds Channel [id], found by its Provider [name]. */
        fun channel(id: Long, name: String) {
            val index = channels.size
            channels.add(id)
            words(name).forEach { channelWords.getOrPut(it, ::Postings).add(index) }
        }

        fun build() = SearchIndex(
            titles,
            drain(titleWords),
            people,
            personTitles.map { it.toArray() },
            drain(personWords),
            channels.toArray(),
            drain(channelWords),
        )

        /** [words]' postings as arrays, letting go of each list once copied so the heap never holds both. */
        private fun drain(words: HashMap<String, Postings>): Map<String, IntArray> {
            val arrays = HashMap<String, IntArray>(words.size * 4 / 3 + 1)
            val each = words.iterator()
            while (each.hasNext()) {
                val (word, postings) = each.next()
                arrays[word] = postings.toArray()
                each.remove()
            }
            return arrays
        }
    }

    /** A growing list of longs that doesn't box them. */
    private class Postings64 {
        private var values = LongArray(16)
        var size = 0
            private set

        fun add(value: Long) {
            if (size == values.size) values = values.copyOf(size * 2)
            values[size++] = value
        }

        fun toArray(): LongArray = values.copyOf(size)
    }

    /** A growing set of ints that doesn't box them: the full catalog has millions. Added in order, they need no sorting. */
    private class Postings {
        private var values = IntArray(2)
        private var size = 0
        private var sorted = true

        fun add(value: Int) {
            if (size > 0 && values[size - 1] >= value) {
                if (values[size - 1] == value) return
                sorted = false
            }
            if (size == values.size) values = values.copyOf(size * 2)
            values[size++] = value
        }

        /** The values in order, each once. */
        fun toArray(): IntArray {
            val array = values.copyOf(size)
            if (sorted) return array
            array.sort()
            var kept = 0
            for (value in array) if (kept == 0 || array[kept - 1] != value) array[kept++] = value
            return array.copyOf(kept)
        }
    }

    companion object {
        /** Search needs this many letters: one finds too much to be useful. */
        const val MIN_LETTERS = 2


        /** [text] as lower-case words without accents: "Pokémon: Detective" is `pokemon`, `detective`. */
        fun words(text: String): List<String> {
            // By hand rather than by regexes: the search index splits millions of names, and the
            // stick spent its time collecting their garbage (#106). Most names are plain ASCII.
            val folded = if (text.all { it < '\u0080' }) text else withoutMarks(Normalizer.normalize(text, Normalizer.Form.NFD))
            val lower = folded.lowercase(Locale.ROOT)
            val words = ArrayList<String>(4)
            var start = -1
            var i = 0
            while (i <= lower.length) {
                val point = if (i < lower.length) lower.codePointAt(i) else ' '.code
                if (isWordPart(point)) {
                    if (start < 0) start = i
                } else if (start >= 0) {
                    words += lower.substring(start, i)
                    start = -1
                }
                i += Character.charCount(point)
            }
            return words
        }

        private fun withoutMarks(text: String): String {
            val kept = StringBuilder(text.length)
            var i = 0
            while (i < text.length) {
                val point = text.codePointAt(i)
                when (Character.getType(point).toByte()) {
                    Character.NON_SPACING_MARK, Character.ENCLOSING_MARK, Character.COMBINING_SPACING_MARK -> Unit
                    else -> kept.appendCodePoint(point)
                }
                i += Character.charCount(point)
            }
            return kept.toString()
        }

        /** A letter or a number, in any script. */
        private fun isWordPart(point: Int): Boolean = when {
            point < 0x80 -> point in 'a'.code..'z'.code || point in 'A'.code..'Z'.code || point in '0'.code..'9'.code
            else -> Character.isLetter(point) || when (Character.getType(point).toByte()) {
                Character.DECIMAL_DIGIT_NUMBER, Character.LETTER_NUMBER, Character.OTHER_NUMBER -> true
                else -> false
            }
        }

        /**
         * How far [typed] is from the catalog's [word], or null when they don't match. A word being
         * typed matches the words it starts (distance 0). Longer words tolerate typos: one in four
         * letters or more, two in eight or more, the first letter always right.
         */
        fun distance(typed: String, word: String): Int? {
            if (word.startsWith(typed)) return 0
            val allowed = when {
                typed.length >= 8 -> 2
                typed.length >= 4 -> 1
                else -> return null
            }
            if (word[0] != typed[0] || word.length < typed.length - allowed) return null
            // The whole word, or its start when the Viewer is still typing it.
            val whole = if (word.length <= typed.length + allowed) editDistance(typed, word, allowed) else Int.MAX_VALUE
            val start = editDistance(typed, word.take(typed.length), allowed)
            return minOf(whole, start).takeIf { it <= allowed }
        }

        /** Optimal string alignment distance (a swap of neighbours is one typo), giving up past [limit]. */
        private fun editDistance(a: String, b: String, limit: Int): Int {
            if (kotlin.math.abs(a.length - b.length) > limit) return Int.MAX_VALUE
            var beforePrevious = IntArray(b.length + 1)
            var previous = IntArray(b.length + 1) { it }
            var current = IntArray(b.length + 1)
            for (i in 1..a.length) {
                current[0] = i
                var best = current[0]
                for (j in 1..b.length) {
                    val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                    var d = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + cost)
                    if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) d = minOf(d, beforePrevious[j - 2] + 1)
                    current[j] = d
                    best = minOf(best, d)
                }
                if (best > limit) return Int.MAX_VALUE
                val recycled = beforePrevious
                beforePrevious = previous
                previous = current
                current = recycled
            }
            return previous[b.length]
        }
    }
}

/**
 * Indexes every Title of [store] by its Versions' cleaned names and the metadata cached for it, in
 * any language, and every Channel by its name. [phase] hears how long each part took, in ms.
 */
internal fun buildSearchIndex(store: CatalogStore, prefixTable: PrefixTable, phase: (name: String, ms: Long) -> Unit = { _, _ -> }): SearchIndex {
    var started = System.nanoTime()
    val lap = { name: String -> phase(name, (System.nanoTime() - started) / 1_000_000).also { started = System.nanoTime() } }
    val builder = SearchIndex.Builder()
    store.forEachChannel(builder::channel)
    lap("channels")
    for (kind in TitleKind.entries) {
        store.forEachTitle(kind) { id, listings ->
            val names = listings.mapTo(HashSet()) { prefixTable.cleanName(it.first).name }
            val languages = listings.mapNotNullTo(HashSet()) { (name, category) -> prefixTable.language(name, category)?.let(::primaryLanguage) }
            builder.title(id, names, languages)
        }
    }
    lap("titles")
    // Straight into the index: holding every Title's names until its Listings come by doesn't fit the stick's heap.
    store.forEachSearchMetadata { id, names, cast, popularity -> builder.metadata(id, names, popularity, cast) }
    lap("metadata")
    return builder.build().also { lap("build") }
}
