package dev.betteriptv.core

import java.text.Normalizer
import java.util.Locale

/**
 * What a search found, best first in each group: [movies] and [shows] whose names match, and
 * [people] (cast) and TMDB [genres] whose names match, each with their Titles the Provider has, and
 * the [channels] whose names match. [completions] are queries one press away: Title names, people
 * and genres the query starts, or genre shortcuts before anything is typed.
 */
data class SearchResults(
    val movies: List<TitleSummary>,
    val shows: List<TitleSummary>,
    val people: List<Person>,
    val channels: List<Channel> = emptyList(),
    val genres: List<Genre> = emptyList(),
    val completions: List<String> = emptyList(),
) {
    /** Whether nothing was found; [completions] may still offer something. */
    val isEmpty: Boolean
        get() = movies.isEmpty() && shows.isEmpty() && people.isEmpty() && channels.isEmpty() && genres.isEmpty()

    /**
     * The rows these results show for [query]: Movies, Shows, genres, Channels, then people. A query
     * that is a genre's whole name (a picked genre completion or shortcut, or the name typed in
     * full) shows that genre first, as Netflix does: the Viewer chose a genre, not a name (#170).
     */
    fun groups(query: String): List<SearchGroup> = buildList {
        val typed = SearchIndex.words(query)
        val picked = genres.firstOrNull { SearchIndex.words(it.name) == typed }
        if (picked != null) add(SearchGroup.OfGenre(picked))
        if (movies.isNotEmpty()) add(SearchGroup.Movies(movies))
        if (shows.isNotEmpty()) add(SearchGroup.Shows(shows))
        genres.filter { it !== picked }.mapTo(this, SearchGroup::OfGenre)
        if (channels.isNotEmpty()) add(SearchGroup.Channels(channels))
        people.mapTo(this, SearchGroup::OfPerson)
    }

    companion object {
        val NONE = SearchResults(emptyList(), emptyList(), emptyList())
    }
}

/** One row of [SearchResults], in the order [SearchResults.groups] shows them. */
sealed interface SearchGroup {
    data class Movies(val titles: List<TitleSummary>) : SearchGroup
    data class Shows(val titles: List<TitleSummary>) : SearchGroup
    data class OfGenre(val genre: Genre) : SearchGroup
    data class Channels(val channels: List<Channel>) : SearchGroup
    data class OfPerson(val person: Person) : SearchGroup
}

/** One of TMDB's genres ([name], in the App language), with its Titles the Provider has, best first. */
data class Genre(val name: String, val titles: List<TitleSummary>)

/** Someone in TMDB's cast of Titles the Provider has, with those Titles, best first. */
data class Person(val name: String, val titles: List<TitleSummary>)

/**
 * The catalog's names in memory, built once per catalog and cached metadata: every Listing name of
 * a Title's Versions (cleaned of Prefixes), every name TMDB has for it, and its cast. Names are
 * reduced to words without accents or case, and each distinct word is kept once with the Titles
 * (or people) that have it, so a query only scans the vocabulary, not the catalog. A Listing name
 * is also kept with its year, so "chase 1966" tells The Chase (1966) from The Chase (1994).
 *
 * All of it is kept in a few large primitive arrays rather than millions of objects, so holding it
 * costs the garbage collector next to nothing (#63).
 */
internal class SearchIndex private constructor(
    /** Each Title's [TitleId], as [key] makes it. */
    private val titleKeys: LongArray,
    private val titleLanguages: IntArray,
    private val titlePopularity: DoubleArray,
    /** The distinct sets of primary languages Titles have, which [titleLanguages] points into. */
    private val languageSets: List<Set<String>>,
    private val positions: LongIntMap,
    private val titleWords: WordPostings,
    private val people: StringTable,
    /** Each person's Titles, as positions. */
    private val personTitles: PackedPostings,
    private val personWords: WordPostings,
    /** The Channels' Provider stream ids, in the Provider's order. */
    val channels: LongArray,
    private val channelWords: WordPostings,
    /** Each name of a Title as its words' hash in the high half and the Title in the low half, sorted. */
    private val titleNames: LongArray,
    /** TMDB's genres of the Titles with metadata, by their English names ([Genres]). */
    val genres: List<String>,
    /** Each genre's Titles, as positions. */
    val genreTitles: List<IntArray>,
    private val genreWords: WordPostings,
) {
    /**
     * A match and how close it was: [WHOLE_WORD], [STARTED_WORD] or a tolerated typo after them. A
     * Title matches by its [WHOLE_NAME] first, and its words a step after.
     */
    class Hit(val index: Int, val distance: Int)

    /** How many Titles there are; a Title's position is from 0 to this. */
    val titleCount: Int get() = titleKeys.size

    fun titleId(position: Int): TitleId = id(titleKeys[position])

    /** The primary languages of the Title's Versions, so Content languages can lead. */
    fun languages(position: Int): Set<String> = languageSets[titleLanguages[position]]

    /** TMDB's popularity of the Title, 0 without metadata. */
    fun popularity(position: Int): Double = titlePopularity[position]

    val peopleCount: Int get() = people.size

    fun person(index: Int): String = people[index]

    /** The person's Titles, as positions. */
    fun personTitles(index: Int): IntArray = personTitles[index]

    /** Where [id] is among the Titles, or null when the catalog has no such Title. */
    fun position(id: TitleId): Int? = positions[key(id)].takeIf { it >= 0 }

    /** Titles with a word matching every word of [query], those with a name of just those words first (#137). */
    fun findTitles(query: String): List<Hit> {
        val named = nameKey(words(query))
        var at = titleNames.binarySearch(named.toLong() shl 32).let { if (it < 0) -it - 1 else it }
        val whole = HashSet<Int>()
        while (at < titleNames.size && (titleNames[at] shr 32).toInt() == named) whole += titleNames[at++].toInt()
        return find(query, titleWords).map { if (it.index in whole) Hit(it.index, WHOLE_NAME) else Hit(it.index, it.distance + 1) }
    }

    fun findPeople(query: String): List<Hit> = find(query, personWords)

    /** Genres whose names in any App language match [query], as indexes into [genres]. */
    fun findGenres(query: String): List<Hit> = find(query, genreWords)

    /** Channels whose names match [query], as indexes into [channels]. */
    fun findChannels(query: String): List<Hit> = find(query, channelWords)

    /** The entries that have a word matching every word of [query]; the worst word's distance counts. */
    private fun find(query: String, words: WordPostings): List<Hit> {
        val wanted = words(query).ifEmpty { return emptyList() }
        val known = words.words.view()
        val entries = words.entries
        var found: HashMap<Int, Int>? = null
        for (word in wanted) {
            val forWord = HashMap<Int, Int>()
            for (number in 0 until words.words.size) {
                val distance = distance(word, known.of(number)) ?: continue
                for (at in entries.start(number) until entries.end(number)) {
                    val entry = entries.value(at)
                    if (distance < (forWord[entry] ?: Int.MAX_VALUE)) forWord[entry] = distance
                }
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
        private var titleKeys = LongArray(1 shl 10)
        private var titleLanguages = IntArray(1 shl 10)
        private var titlePopularity = DoubleArray(1 shl 10)
        private var titles = 0
        private val languageSets = mutableListOf<Set<String>>()
        private val languageSetIndex = HashMap<Set<String>, Int>()
        private val positions = LongIntMap()
        private val titleWords = WordPostings.Builder()
        private val people = StringTable()
        private val personTitles = PostingsBuilder()
        private val personWords = WordPostings.Builder()
        private val channels = Postings64()
        private val channelWords = WordPostings.Builder()
        private val titleNames = Postings64()
        private val genres = StringTable()
        private val genreTitles = PostingsBuilder()
        private val genreWords = WordPostings.Builder()

        /** Adds a Title found by [names], with Versions in [languages]. */
        fun title(id: TitleId, names: Collection<String>, languages: Set<String>) {
            if (titles == titleKeys.size) {
                titleKeys = titleKeys.copyOf(titles * 2)
                titleLanguages = titleLanguages.copyOf(titles * 2)
                titlePopularity = titlePopularity.copyOf(titles * 2)
            }
            val index = titles++
            titleKeys[index] = key(id)
            titleLanguages[index] = languageSetIndex.getOrPut(languages) { languageSets += languages; languageSets.size - 1 }
            positions[key(id)] = index
            addWords(index, names)
        }

        /**
         * Adds what TMDB says of Title [id]: more [names] it's found by, its [popularity], [cast] and
         * [genres]. Titles not added are left out.
         */
        fun metadata(id: TitleId, names: Collection<String>, popularity: Double, cast: List<String>, genres: List<String>) {
            val index = positions[key(id)].takeIf { it >= 0 } ?: return
            titlePopularity[index] = popularity
            addWords(index, names)
            for (name in cast.distinct()) {
                val known = people.size
                val person = people.add(name)
                if (person == known) words(name).forEach { word -> personWords.add(word, person) }
                personTitles.add(person, index)
            }
            // One genre, whichever language its metadata was cached in.
            for (name in genres.map(Genres::english).distinct()) {
                val known = this.genres.size
                val genre = this.genres.add(name)
                if (genre == known) Genres.every(name).flatMap(::words).distinct().forEach { word -> genreWords.add(word, genre) }
                genreTitles.add(genre, index)
            }
        }

        /** A word in several of a Title's names is kept once. */
        private fun addWords(index: Int, names: Collection<String>) {
            for (name in names) {
                val words = words(name)
                for (word in words) titleWords.add(word, index)
                titleNames.add(nameKey(words).toLong() shl 32 or index.toLong())
            }
        }

        /** Adds Channel [id], found by its Provider [name]. */
        fun channel(id: Long, name: String) {
            val index = channels.size
            channels.add(id)
            words(name).forEach { channelWords.add(it, index) }
        }

        fun build(): SearchIndex {
            val genreTitles = genreTitles.freeze(genres.size)
            return SearchIndex(
                titleKeys.copyOf(titles),
                titleLanguages.copyOf(titles),
                titlePopularity.copyOf(titles),
                languageSets.toList(),
                positions,
                titleWords.build(),
                people.freeze(),
                personTitles.freeze(people.size),
                personWords.build(),
                channels.toArray(),
                channelWords.build(),
                titleNames.toArray().apply { sort() },
                List(genres.size) { genres[it] },
                List(genres.size) { genreTitles[it] },
                genreWords.build(),
            )
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

    companion object {
        /** Search needs this many letters: one finds too much to be useful. */
        const val MIN_LETTERS = 2

        /** A Title whose name is just the query's words: "the chase" is The Chase, not The Desperate Chase. */
        const val WHOLE_NAME = 0

        /** A word typed in full, or every word of a Title's. */
        const val WHOLE_WORD = 0

        /** A word the Viewer is still typing, or a Title found by TMDB's search alone. */
        const val STARTED_WORD = 1

        private const val UNMATCHED = 1L shl 62
        private const val KIND_SHIFT = 61

        /** [id] as one long: whether it is Unmatched, its kind, and its TMDB or Listing ID. */
        private fun key(id: TitleId): Long = (id.kind.ordinal.toLong() shl KIND_SHIFT) or when (id) {
            is TitleId.Tmdb -> id.tmdbId
            is TitleId.Unmatched -> UNMATCHED or id.listingId
        }

        private fun id(key: Long): TitleId {
            val kind = TitleKind.entries[(key ushr KIND_SHIFT and 1).toInt()]
            val value = key and ((1L shl KIND_SHIFT) - 1)
            return if (key and UNMATCHED != 0L) TitleId.Unmatched(kind, value) else TitleId.Tmdb(kind, value)
        }

        /** A name's words, hashed: two names of the same words have the same key. */
        private fun nameKey(words: List<String>): Int = words.joinToString(" ").hashCode()

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
         * How far [typed] is from the catalog's [word], or null when they don't match: [WHOLE_WORD]
         * for the same word, [STARTED_WORD] for a word it starts (the Viewer may still be typing it).
         * Longer words tolerate typos, each a step further: one in four letters or more, two in
         * eight or more, the first letter always right.
         */
        fun distance(typed: String, word: CharSequence): Int? {
            if (word.length >= typed.length && word.regionMatches(0, typed, 0, typed.length)) {
                return if (word.length == typed.length) WHOLE_WORD else STARTED_WORD
            }
            val allowed = when {
                typed.length >= 8 -> 2
                typed.length >= 4 -> 1
                else -> return null
            }
            if (word[0] != typed[0] || word.length < typed.length - allowed) return null
            // The whole word, or its start when the Viewer is still typing it.
            val whole = if (word.length <= typed.length + allowed) editDistance(typed, word, word.length, allowed) else Int.MAX_VALUE
            val start = editDistance(typed, word, minOf(word.length, typed.length), allowed)
            return minOf(whole, start).takeIf { it <= allowed }?.plus(STARTED_WORD)
        }

        /** Optimal string alignment distance (a swap of neighbours is one typo) to [b]'s first [length] letters, giving up past [limit]. */
        private fun editDistance(a: String, b: CharSequence, length: Int, limit: Int): Int {
            if (kotlin.math.abs(a.length - length) > limit) return Int.MAX_VALUE
            var beforePrevious = IntArray(length + 1)
            var previous = IntArray(length + 1) { it }
            var current = IntArray(length + 1)
            for (i in 1..a.length) {
                current[0] = i
                var best = current[0]
                for (j in 1..length) {
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
            return previous[length]
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
            val names = listings.flatMapTo(HashSet()) { (name) ->
                val clean = prefixTable.cleanName(name)
                listOfNotNull(clean.name, clean.year?.let { "${clean.name} $it" })
            }
            val languages = listings.mapNotNullTo(HashSet()) { (name, category) -> prefixTable.language(name, category)?.let(::primaryLanguage) }
            builder.title(id, names, languages)
        }
    }
    lap("titles")
    // Straight into the index: holding every Title's names until its Listings come by doesn't fit the stick's heap.
    store.forEachSearchMetadata { id, names, cast, genres, popularity -> builder.metadata(id, names, popularity, cast, genres) }
    lap("metadata")
    return builder.build().also { lap("build") }
}
