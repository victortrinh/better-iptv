package dev.betteriptv.core

import java.text.Normalizer
import java.util.Locale

/**
 * What a search found, best first in each list: the [titles] whose names match, Movies and Shows
 * together, and the same as [movies] and [shows] apart, [people] (the Provider's cast) and [genres] whose
 * names match, each with their Titles the Provider has, the [channels] whose names match, and the
 * Sports [replays] whose names match (every one, highlights shows included, since Search alone shows
 * them; Live shows only the Leagues'). [completions] are queries one press away: Title names, people
 * and genres the query starts, or genre shortcuts before anything is typed.
 */
data class SearchResults(
    val movies: List<TitleSummary>,
    val shows: List<TitleSummary>,
    val people: List<Person>,
    val channels: List<Channel> = emptyList(),
    val genres: List<Genre> = emptyList(),
    val completions: List<String> = emptyList(),
    val replays: List<Replay> = emptyList(),
    val titles: List<TitleSummary> = movies + shows,
) {
    /** Whether nothing was found; [completions] may still offer something. */
    val isEmpty: Boolean
        get() = movies.isEmpty() && shows.isEmpty() && people.isEmpty() && channels.isEmpty() && genres.isEmpty() && replays.isEmpty()

    /**
     * The cards these results show for [query], as one grid rather than rows, as on Netflix: the
     * Titles found by name, best first whatever their kind, then each genre's Titles, the Channels,
     * the Replays, then each person's Titles, a Title shown once, where it first comes. A query that
     * is a genre's whole name (a picked genre completion or shortcut, or the name typed in full) shows
     * that genre's Titles first: the Viewer chose a genre, not a name (#170).
     */
    fun cards(query: String): List<SearchCard> {
        val typed = SearchIndex.words(query)
        val picked = genres.firstOrNull { SearchIndex.words(it.name) == typed }
        val shown = HashSet<TitleId>()
        return buildList {
            fun once(titles: List<TitleSummary>) = titles.forEach { if (shown.add(it.id)) add(SearchCard.OfTitle(it)) }
            if (picked != null) once(picked.titles)
            once(titles)
            genres.forEach { if (it !== picked) once(it.titles) }
            channels.mapTo(this, SearchCard::OfChannel)
            replays.mapTo(this, SearchCard::OfReplay)
            people.forEach { once(it.titles) }
        }
    }

    companion object {
        val NONE = SearchResults(emptyList(), emptyList(), emptyList())
    }
}

/** One card of [SearchResults.cards]: a Title's poster, or a Channel's or a Replay's wide card. */
sealed interface SearchCard {
    data class OfTitle(val title: TitleSummary) : SearchCard
    data class OfChannel(val channel: Channel) : SearchCard
    data class OfReplay(val replay: Replay) : SearchCard
}

/** One of the Prefix table's genres ([name], in the App language), with its Titles the Provider has, best first. */
data class Genre(val name: String, val titles: List<TitleSummary>)

/** Someone the Provider names in the cast of its Titles, with those Titles, best first. */
data class Person(val name: String, val titles: List<TitleSummary>)

/**
 * The catalog's names in memory, built once per catalog and the details kept for it: every Listing
 * name of a Title's Versions (cleaned of Prefixes), in every language the Provider carries, the cast
 * the Provider names (a Show's from its Listing, a Movie's once its details were read), and its
 * genres as the Prefix table names them (ADR 0014). Names are
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
    /** The Sports replays' Listing ids, in the Provider's order. */
    val replays: LongArray,
    /** Each Replay's day (epoch days, [NO_DAY] without a date), so the newest rank first without reading them. */
    private val replayDays: IntArray,
    private val replayWords: WordPostings,
    /** Each name of a Title as its words' hash in the high half and the Title in the low half, sorted. */
    private val titleNames: LongArray,
    /** The Titles' genres, by their English names ([Genres]): the Prefix table's, or the Provider's own word for one it doesn't know. */
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

    /** Sports replays whose names match [query], as indexes into [replays], closest first, then the newest by the date in the name, then in the Provider's order. */
    fun findReplays(query: String): List<Hit> = find(query, replayWords).sortedWith(compareBy<Hit>({ it.distance }, { -replayDays[it.index] }, { it.index }))

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

    /** Takes the catalog's Titles, in list order, and the Channels and Replays, in any order. */
    class Builder {
        private var titleKeys = LongArray(1 shl 10)
        private var titleLanguages = IntArray(1 shl 10)
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
        private val replays = Postings64()
        private var replayDays = IntArray(1 shl 8)
        private val replayWords = WordPostings.Builder()
        private val titleNames = Postings64()
        private val genres = StringTable()
        private val genreTitles = PostingsBuilder()
        private val genreWords = WordPostings.Builder()

        /**
         * Adds a Title found by [names], with Versions in [languages], the people the Provider names
         * in its [cast] and its [genres] (English names, as [Genres] has them). Titles come in the
         * Provider's order, which ranks equally close matches.
         */
        fun title(id: TitleId, names: Collection<String>, languages: Set<String>, cast: Collection<String> = emptyList(), genres: Collection<String> = emptyList()) {
            if (titles == titleKeys.size) {
                titleKeys = titleKeys.copyOf(titles * 2)
                titleLanguages = titleLanguages.copyOf(titles * 2)
            }
            val index = titles++
            titleKeys[index] = key(id)
            titleLanguages[index] = languageSetIndex.getOrPut(languages) { languageSets += languages; languageSets.size - 1 }
            positions[key(id)] = index
            addWords(index, names)
            for (name in cast.distinct()) {
                val known = people.size
                val person = people.add(name)
                if (person == known) words(name).forEach { word -> personWords.add(word, person) }
                personTitles.add(person, index)
            }
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

        /** Adds the Sports replay of Listing [id], found by its [name] (without its Prefix), dated [day] (epoch days, [NO_DAY] without a date). */
        fun replay(id: Long, name: String, day: Int) {
            val index = replays.size
            if (index == replayDays.size) replayDays = replayDays.copyOf(index * 2)
            replays.add(id)
            replayDays[index] = day
            words(name).forEach { replayWords.add(it, index) }
        }

        fun build(): SearchIndex {
            val genreTitles = genreTitles.freeze(genres.size)
            return SearchIndex(
                titleKeys.copyOf(titles),
                titleLanguages.copyOf(titles),
                languageSets.toList(),
                positions,
                titleWords.build(),
                people.freeze(),
                personTitles.freeze(people.size),
                personWords.build(),
                channels.toArray(),
                channelWords.build(),
                replays.toArray(),
                replayDays.copyOf(replays.size),
                replayWords.build(),
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

        /** A word the Viewer is still typing. */
        const val STARTED_WORD = 1

        /** A Replay whose name has no date ranks after every dated one. */
        const val NO_DAY = Int.MIN_VALUE

        private const val UNMATCHED = 1L shl 62
        private const val KIND_SHIFT = 61

        /** [id] as one long: whether it is Unmatched, its kind, and its Title number or Listing ID. */
        private fun key(id: TitleId): Long = (id.kind.ordinal.toLong() shl KIND_SHIFT) or when (id) {
            is TitleId.Numbered -> id.titleNumber
            is TitleId.Unmatched -> UNMATCHED or id.listingId
        }

        private fun id(key: Long): TitleId {
            val kind = TitleKind.entries[(key ushr KIND_SHIFT and 1).toInt()]
            val value = key and ((1L shl KIND_SHIFT) - 1)
            return if (key and UNMATCHED != 0L) TitleId.Unmatched(kind, value) else TitleId.Numbered(kind, value)
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
 * Indexes every Title of [store] by its Versions' cleaned names in every language, the cast the
 * Provider names for it and its genres (a Movie's Categories' and kept details', a Show's Listing's
 * and Categories', as the Prefix table names them), every Channel by its name, and every Sports
 * replay by its name without its Prefix. [phase] hears how long each part took, in ms.
 */
internal fun buildSearchIndex(store: CatalogStore, prefixTable: PrefixTable, phase: (name: String, ms: Long) -> Unit = { _, _ -> }): SearchIndex {
    var started = System.nanoTime()
    val lap = { name: String -> phase(name, (System.nanoTime() - started) / 1_000_000).also { started = System.nanoTime() } }
    val builder = SearchIndex.Builder()
    store.forEachChannel(builder::channel)
    lap("channels")
    store.forEachReplay { id, name, day -> builder.replay(id, ReplayNames.body(name), day?.toEpochDay()?.toInt() ?: SearchIndex.NO_DAY) }
    lap("replays")
    // A Category's genres are worked out once, not once per Listing filed under it.
    val categoryGenres = HashMap<String, List<String>>()
    for (kind in TitleKind.entries) {
        store.forEachTitle(kind) { id, listings ->
            val names = listings.flatMapTo(HashSet()) { listing ->
                val clean = prefixTable.cleanName(listing.name)
                listOfNotNull(clean.name, clean.year?.let { "${clean.name} $it" })
            }
            val languages = listings.mapNotNullTo(HashSet()) { prefixTable.language(it.name, it.category)?.let(::primaryLanguage) }
            val cast = listings.flatMapTo(LinkedHashSet()) { castNames(it.cast) }
            val genres = listings.flatMapTo(LinkedHashSet()) { categoryGenres.getOrPut(it.category) { prefixTable.titleGenres(it.category) } + prefixTable.genres(it.genre) }
            builder.title(id, names, languages, cast, genres)
        }
    }
    lap("titles")
    return builder.build().also { lap("build") }
}
