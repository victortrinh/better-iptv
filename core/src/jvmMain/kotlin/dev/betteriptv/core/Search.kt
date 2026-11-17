package dev.betteriptv.core


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

/**
 * Whether [query] has too few letters for Search to look anything up ([SearchIndex.MIN_LETTERS], in
 * its words, so spaces and punctuation don't count): it answers nothing then, which says nothing of
 * the catalog, and the screen keeps asking for more letters.
 */
fun tooShortToSearch(query: String): Boolean = SearchIndex.words(query).sumOf { it.length } < SearchIndex.MIN_LETTERS

/** One of the Prefix table's genres ([name], in the App language), with its Titles the Provider has, best first. */
data class Genre(val name: String, val titles: List<TitleSummary>)

/** Someone the Provider names in the cast of its Titles, with those Titles, best first. */
data class Person(val name: String, val titles: List<TitleSummary>)

/**
 * How Search reads names, the rules its index ([SearchIndexStore]) is built and queried with: a
 * name's words ([words]), how close a typed word is to one of the catalog's ([distance]), and what
 * a match's distance means.
 */
internal object SearchIndex {
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

    /** [text] as lower-case words without accents: "Pokémon: Detective" is `pokemon`, `detective`. */
    fun words(text: String): List<String> {
        // By hand rather than by regexes: the search index splits millions of names, and the
        // stick spent its time collecting their garbage (#106). Most names are plain ASCII.
        val folded = if (text.all { it < '\u0080' }) text else withoutMarks(canonicallyDecomposed(text))
        val lower = folded.lowercase()
        val words = ArrayList<String>(4)
        var start = -1
        var i = 0
        while (i <= lower.length) {
            val point = if (i < lower.length) codePointAt(lower, i) else ' '.code
            if (isWordPart(point)) {
                if (start < 0) start = i
            } else if (start >= 0) {
                words += lower.substring(start, i)
                start = -1
            }
            i += charCount(point)
        }
        return words
    }

    private fun withoutMarks(text: String): String {
        val kept = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val point = codePointAt(text, i)
            val next = i + charCount(point)
            if (!isMark(point)) kept.appendRange(text, i, next)
            i = next
        }
        return kept.toString()
    }

    /** A letter or a number, in any script. */
    private fun isWordPart(point: Int): Boolean = when {
        point < 0x80 -> point in 'a'.code..'z'.code || point in 'A'.code..'Z'.code || point in '0'.code..'9'.code
        else -> isLetter(point) || isNumber(point)
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
