package dev.betteriptv.core

import kotlin.time.Instant

/**
 * Search over the index a Sync stores ([SearchIndexStore]): what [Library.search] answers from. The
 * index finds what matches and how closely ([SearchIndex.distance]); this ranks the Titles, people,
 * genres, Channels and Sports replays it found.
 */
internal class StoredSearch(private val store: CatalogStore, private val limits: Limits) {
    private val index = store.search

    /** How many of each kind a search shows. */
    class Limits(val titles: Int, val people: Int, val genres: Int, val completions: Int, val channels: Int, val replays: Int)

    /** What a Kids profile's search keeps: the Kids catalog's Titles, as the index's rows, and the Channels of Kids Categories, by key. */
    class KidsScope(val titles: Set<Long>, val channels: Set<String>)

    /** A Title found, or one of a person's or a genre's: its row in the index, how close it matched and its language set. */
    private class Hit(val row: Long, val distance: Int, val languages: Int)

    /** The index's rows of the Titles [ids]: a Kids catalog's, for a [KidsScope]. */
    fun rows(ids: Collection<TitleId>): Set<Long> = index.titleRows(ids)

    /**
     * What [query] finds for a Viewer whose Content languages' primary subtags are [wanted], the
     * Titles' cards made by [summaries]; [completions] picks the queries to offer, from the names of
     * the Titles, people and genres found. With [kids], only what is in its scope.
     */
    fun search(
        query: String,
        wanted: Set<String>,
        appLanguage: String,
        profile: Long,
        kids: KidsScope?,
        channelsOnAfter: Instant,
        summaries: (List<StoredTitle>) -> List<TitleSummary>,
        completions: (titles: List<String>, people: List<String>, genres: List<String>) -> List<String>,
    ): SearchResults {
        val sets = index.languageSets()
        // A Title in none of the Viewer's languages ranks after every one that is.
        val unwanted = { languages: Int -> sets[languages].orEmpty().none(wanted::contains) }
        val byRank = compareBy<Hit>({ unwanted(it.languages) }, { it.distance }, { it.row })
        val inScope = { row: Long -> kids == null || row in kids.titles }

        val found = index.find(query)
        val ranked = found.titles.filterKeys(inScope).map { (row, distance) -> Hit(row, distance, found.titleLanguages.getValue(row)) }.sortedWith(byRank)

        // People: the closest names first, then those whose first Title comes first in the lists.
        val firstTitles = if (kids == null) index.firstTitles(found.people.keys) else index.castTitles(found.people.keys).mapNotNull { (person, titles) ->
            titles.firstOrNull(inScope)?.let { person to it }
        }.toMap()
        val people = firstTitles.keys.sortedWith(compareBy({ found.people.getValue(it) }, { firstTitles.getValue(it) }, { it })).take(limits.people)
        val peopleNames = index.personNames(people)
        val castTitles = index.castTitles(people).mapValues { (_, titles) -> titles.filter(inScope) }
        val castLanguages = index.titleLanguages(castTitles.values.flatten())
        val shownPeople = people.map { person ->
            peopleNames.getValue(person) to castTitles[person].orEmpty().map { Hit(it, 0, castLanguages[it] ?: -1) }.sortedWith(byRank).take(limits.titles)
        }

        // Genres: the closest names first, then those with the most Titles.
        val genreSizes = HashMap<Long, Int>()
        index.genreCounts().forEach { genreSizes[it.genre] = (genreSizes[it.genre] ?: 0) + it.titles }
        val genreNames = index.genreNames(found.genres.keys)
        val shownGenres = found.genres.keys
            .map { genre -> genre to genreTitles(genre, unwanted, inScope) }
            .filter { it.second.isNotEmpty() }
            .sortedWith(compareBy({ found.genres.getValue(it.first) }, { -(genreSizes[it.first] ?: 0) }, { it.first }))
            .take(limits.genres)
            .map { (genre, titles) -> Genres.named(genreNames.getValue(genre), appLanguage) to titles }

        val ofKind = { kind: TitleKind -> ranked.filter { SearchIndexStore.kind(it.row) == kind }.take(limits.titles) }
        val shown = (TitleKind.entries.flatMap(ofKind) + shownPeople.flatMap { it.second } + shownGenres.flatMap { it.second }).map { it.row }.distinct()
        val ids = index.titleIds(shown)
        val cards = summaries(shown.mapNotNull { ids[it]?.let(store::title) }).associateBy { it.id }
        val card = { hits: List<Hit> -> hits.mapNotNull { hit -> ids[hit.row]?.let(cards::get) } }
        // The best Titles of either kind are among those shown, so their names are at hand.
        val titleNames = card(ranked.take(limits.completions * 2)).map { it.name }
        return SearchResults(
            movies = card(ofKind(TitleKind.Movie)),
            shows = card(ofKind(TitleKind.Show)),
            // The same Titles together, best first whatever their kind: Search shows them in one grid.
            titles = card(TitleKind.entries.flatMap(ofKind).sortedWith(byRank)),
            people = shownPeople.map { (name, hits) -> Person(name, card(hits)) },
            genres = shownGenres.map { (name, hits) -> Genre(name, card(hits)) },
            completions = completions(titleNames, shownPeople.map { it.first }, shownGenres.map { it.first }),
            channels = channels(query, profile, kids?.channels, channelsOnAfter),
            replays = if (kids == null) replays(found.replays) else emptyList(),
        )
    }

    /** The best of [genre]'s Titles in scope: those in the Viewer's languages first, each group in list order. */
    private fun genreTitles(genre: Long, unwanted: (Int) -> Boolean, inScope: (Long) -> Boolean): List<Hit> {
        val first = ArrayList<Hit>()
        val rest = ArrayList<Hit>()
        index.genreTitles(genre) { row, languages ->
            if (inScope(row)) {
                val hit = Hit(row, 0, languages)
                if (!unwanted(languages)) first += hit else if (rest.size < limits.titles) rest += hit
            }
            first.size < limits.titles
        }
        return (first + rest).take(limits.titles)
    }

    /**
     * The genres with the most Titles in the Viewer's [wanted] languages, named in the [appLanguage], to
     * search before anything is typed; with [kids], counting the Kids catalog's Titles alone.
     */
    fun genreShortcuts(wanted: Set<String>, appLanguage: String, kids: KidsScope?): List<String> {
        val sets = index.languageSets()
        val counts = LinkedHashMap<String, Int>()
        (if (kids == null) index.genreCounts() else index.genresOf(kids.titles)).forEach {
            if (sets[it.languages].orEmpty().any(wanted::contains)) counts[it.name] = (counts[it.name] ?: 0) + it.titles
        }
        return counts.map { (genre, titles) -> Genres.named(genre, appLanguage) to titles }
            .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })
            .take(limits.completions)
            .map { it.first }
    }

    /** The Channels on now whose names match [query], closest first, then Favourites, then in the Provider's order; only those keyed in [only] when it is given. */
    private fun channels(query: String, profile: Long, only: Set<String>?, onAfter: Instant): List<Channel> {
        val hits = index.findChannels(query).toList().sortedWith(compareBy({ it.second }, { it.first })).let { if (only == null) it.take(limits.channels * 4) else it }
        val leads = index.channelIds(hits.map { it.first })
        // Finished Event channels drop out, so a few more are read than are shown.
        val on = store.channels(profile, leads.values, onAfter)
        return hits.mapNotNull { hit -> leads[hit.first]?.let(on::get)?.takeIf { only == null || it.key in only }?.let { hit to it } }
            .sortedWith(compareBy({ it.first.second }, { !it.second.favourite }, { it.first.first }))
            .map { it.second }
            .take(limits.channels)
    }

    /** The Sports replays [found], closest first, then the newest by the date in the name, then in the Provider's order. */
    private fun replays(found: Map<Long, Int>): List<Replay> {
        val days = index.replayDays(found.keys)
        val best = days.keys.sortedWith(compareBy({ found.getValue(it) }, { -days.getValue(it).second }, { it })).take(limits.replays).map { days.getValue(it).first }
        val replays = store.replays(best)
        return best.mapNotNull(replays::get)
    }
}
