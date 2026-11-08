package dev.betteriptv.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Search across Movies, Shows, people and genres, seen through the Library after a Sync: all of it
 * from the device's index of what the Provider says (ADR 0014), the cloud never asked.
 */
class SearchTest {
    @get:Rule val folder = TemporaryFolder()
    private val provider = FakeProvider()
    private val cloud = FakeCloud()
    private val credentials = InMemoryCredentials()
    private lateinit var library: Library

    @Before fun logIn() = runBlocking {
        library = Library.open(folder.root.resolve("catalog.db").path, credentials, cloud.url)
        library.logIn(provider.login)
        Unit
    }

    @After fun tearDown() {
        library.close()
        provider.close()
        cloud.close()
    }

    private val english = listOf("en")
    private val godfather = TitleId.Tmdb(TitleKind.Movie, 238)
    private val gameOfThrones = TitleId.Tmdb(TitleKind.Show, 1399)

    private suspend fun search(query: String, contentLanguages: List<String> = english, appLanguage: String = "en") =
        library.search(query, contentLanguages, appLanguage).last()

    private fun ids(titles: List<TitleSummary>) = titles.map { (it.id as TitleId.Tmdb).tmdbId }

    /** A movie Listing to Sync: its name, its Title number, and the Category it is filed under. */
    private class Movie(val name: String, val number: Long, val category: String = "MOVIES")

    /** Syncs these movie Listings, newest first, numbered from 1, and the shows served (none, unless some were), and builds the search index. */
    private suspend fun syncMovies(vararg movies: Movie) {
        if (provider.showListOverride == null) {
            provider.showListOverride = "[]"
            provider.showCategoriesOverride = "[]"
        }
        val categories = movies.map { it.category }.distinct()
        provider.movieCategoriesOverride = categories.withIndex().joinToString(",", "[", "]") { (i, name) -> """{"category_id":"${i + 1}","category_name":"$name"}""" }
        provider.movieListOverride = movies.withIndex().joinToString(",", "[", "]") { (i, movie) ->
            """{"stream_id":${i + 1},"name":"${movie.name}","tmdb":"${movie.number}","category_id":"${categories.indexOf(movie.category) + 1}","added":"${1_700_000_000 - i}"}"""
        }
        library.sync()
        library.prepareSearch()
    }

    private suspend fun syncMovies(vararg listings: Pair<String, Long>) = syncMovies(*listings.map { Movie(it.first, it.second) }.toTypedArray())

    /** Syncs these show Listings ([FakeProvider.serveShows]) and no movies, and builds the search index. */
    private suspend fun syncShows(vararg shows: FakeProvider.ShowListing) {
        provider.serveShows(*shows)
        provider.movieListOverride = "[]"
        provider.movieCategoriesOverride = "[]"
        library.sync()
        library.prepareSearch()
    }

    /** Syncs the fixture's catalog and builds the search index. */
    private suspend fun syncFixture() {
        library.sync()
        library.prepareSearch()
    }

    /** Waits up to 10 s for the search index of the current catalog to be built, without searching. */
    private fun awaitSearchIndex(library: Library = this.library): Boolean {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!library.searchIndexReady) {
            if (System.nanoTime() > until) return false
            Thread.sleep(20)
        }
        return true
    }

    // Names

    @Test fun `a Title is found by any of its Listings' names, in any language the Provider carries`() = runBlocking {
        syncMovies("EN - The Godfather (1972)" to 238, "FR - Le Parrain (1972)" to 238, "EN - Heat (1995)" to 949)

        for (query in listOf("le parrain", "the godfather")) {
            assertEquals(query, listOf(godfather), search(query).movies.map { it.id })
        }
    }

    @Test fun `cards show the Listing's name in the App language`() = runBlocking {
        syncMovies("EN - The Godfather (1972)" to 238, "FR - Le Parrain (1972)" to 238)

        assertEquals("Le Parrain", search("godfather", appLanguage = "fr").movies.single().name)
        assertEquals("The Godfather", search("parrain").movies.single().name)
    }

    @Test fun `accents and case don't matter`() = runBlocking {
        syncMovies("EN - Pokémon: Detective Pikachu (2019)" to 447404, "FR - Amélie (2001)" to 194)

        assertEquals(TitleId.Tmdb(TitleKind.Movie, 447404), search("pokemon").movies.single().id)
        assertEquals(TitleId.Tmdb(TitleKind.Movie, 447404), search("POKÉMON").movies.single().id)
        assertEquals(TitleId.Tmdb(TitleKind.Movie, 194), search("amelie").movies.single().id)
    }

    @Test fun `small typos are tolerated`() = runBlocking {
        syncFixture()

        assertEquals(godfather, search("godfater").movies.first().id)
        assertEquals(godfather, search("the godfahter").movies.first().id)
        assertEquals(gameOfThrones, search("thrnoes").shows.first().id)
    }

    @Test fun `results show after two letters, and words being typed match`() = runBlocking {
        syncFixture()

        assertEquals(SearchResults.NONE, search("g"))
        assertTrue(search("go").movies.any { it.id == godfather })
        assertEquals(godfather, search("the godf").movies.first().id)
    }

    @Test fun `exact matches rank before typo matches`() = runBlocking {
        syncMovies("EN - Hear Me (2009)" to 1, "EN - Heat (1995)" to 949)

        assertEquals(listOf(949L, 1L), ids(search("heat").movies))
    }

    @Test fun `a whole name ranks before whole words, then words being typed`() = runBlocking {
        // #137: The Chaser and The Desperate Chase come first in the Provider's order, and still rank after The Chase.
        syncMovies("EN - The Chaser (2008)" to 3, "EN - The Desperate Chase (2024)" to 2, "EN - The Chase (1994)" to 4, "EN - The Chase (1966)" to 31602)

        assertEquals(listOf(4L, 31602L, 2L, 3L), ids(search("the chase").movies))
    }

    @Test fun `two equally close matches come in the Provider's order`() = runBlocking {
        syncMovies("EN - Night Train (2010)" to 1, "EN - Night Shift (2020)" to 2)
        assertEquals(listOf(1L, 2L), ids(search("night").movies))

        syncMovies("EN - Night Shift (2020)" to 2, "EN - Night Train (2010)" to 1)
        assertEquals(listOf(2L, 1L), ids(search("night").movies))
    }

    @Test fun `a year tells same-name Titles apart`() = runBlocking {
        syncMovies("EN - The Chase (1994)" to 4, "EN - The Chase (1966)" to 31602)

        assertEquals(listOf(31602L), ids(search("chase 1966").movies))
        assertEquals(31602L, ids(search("the chase 1966").movies).first())
    }

    @Test fun `results are grouped into Movies and Shows`() = runBlocking {
        syncFixture()

        val results = search("game of thrones")
        assertTrue(results.movies.none { it.id.kind == TitleKind.Show })
        assertEquals(gameOfThrones, results.shows.first().id)
        assertTrue(search("godfather").shows.isEmpty())
    }

    @Test fun `Content languages come first, then the Provider's order, and other languages still appear after them`() = runBlocking {
        syncMovies("DE - Night Shift (2020)" to 3, "EN - Night Train (2010)" to 1, "EN - Night Train to Lisbon (2013)" to 2)

        assertEquals(listOf(1L, 2L, 3L), ids(search("night").movies))
        assertEquals(listOf(3L, 1L, 2L), ids(search("night", contentLanguages = listOf("de")).movies))
    }

    @Test fun `typing a query answers once, and the cloud is never asked`() = runBlocking {
        syncFixture()

        val answers = library.search("the godfather", english, "en").toList()

        assertEquals(1, answers.size)
        assertEquals(godfather, answers.single().movies.first().id)
        assertTrue(cloud.received.none { it.path == "/v1/search" })
    }

    @Test fun `without the cloud, Search answers the same`() = runBlocking {
        syncMovies("EN - Heat (1995)" to 949)
        cloud.down = true

        assertEquals(listOf(TitleId.Tmdb(TitleKind.Movie, 949)), library.search("heat", english, "en").toList().single().movies.map { it.id })
    }

    // People

    @Test fun `a Show's cast is found from its Listing once Synced`() = runBlocking {
        syncShows(
            FakeProvider.ShowListing("EN - Succession (2018)", "EN - SERIES", 76331, cast = "Brian Cox, Jeremy Strong, Sarah Snook"),
            FakeProvider.ShowListing("EN - Mare of Easttown (2021)", "EN - SERIES", 115004, cast = "Kate Winslet, Jean Smart"),
            FakeProvider.ShowListing("EN - Hacks (2021)", "EN - SERIES", 105248, cast = "Jean Smart, Hannah Einbinder"),
        )

        val smart = search("jean smart").people.single()
        assertEquals("Jean Smart", smart.name)
        assertEquals(listOf(115004L, 105248L), ids(smart.titles))
        assertEquals(listOf("Sarah Snook"), search("sarah snok").people.map { it.name })
        // Without a single details call.
        assertTrue(provider.requests.none { it.startsWith("get_series_info") || it.startsWith("get_vod_info") })
    }

    @Test fun `a Movie's cast is found once its page was opened, and not before`() = runBlocking {
        syncMovies("EN - Heat (1995)" to 949, "EN - Ronin (1998)" to 8195)
        provider.serveMovieDetails(1, cast = "Al Pacino, Robert De Niro, Val Kilmer")
        provider.serveMovieDetails(2, cast = "Robert De Niro, Jean Reno")

        assertTrue(search("pacino").people.isEmpty())
        assertTrue("Search read details", provider.movieDetailsRequests.isEmpty())

        library.title(TitleId.Tmdb(TitleKind.Movie, 949), english, "en")
        assertTrue("no search index with the details", awaitSearchIndexAfterSearching("pacino"))

        assertEquals(listOf(949L), ids(search("pacino").people.single().titles))
        // Ronin's page was never opened.
        assertEquals(listOf(949L), ids(search("de niro").people.single().titles))
        assertTrue(search("jean reno").people.isEmpty())
    }

    /** Searches [query] once, which starts building the index with what's been kept since, and waits for it. */
    private suspend fun awaitSearchIndexAfterSearching(query: String): Boolean {
        search(query)
        return awaitSearchIndex()
    }

    // Genres

    @Test fun `searching a genre shows its Titles the Provider has, from the Prefix table's genres`() = runBlocking {
        syncMovies(Movie("EN - Bridget Jones's Diary (2001)", 1, "EN - ROMANCE"), Movie("EN - Brooklyn (2015)", 2, "EN - ROMANCE"), Movie("EN - Heat (1995)", 3, "EN - ACTION"))

        val romance = search("romance").genres.single()

        assertEquals("Romance", romance.name)
        assertEquals(listOf(1L, 2L), ids(romance.titles))
        assertTrue(search("romance").movies.isEmpty())
    }

    @Test fun `a genre named in another language is found, and named in the App language`() = runBlocking {
        syncMovies(Movie("EN - The Shining (1980)", 1, "EN - HORROR"), Movie("EN - Alien (1979)", 2, "EN - HORROR"), Movie("EN - Heat (1995)", 3, "EN - ACTION"))

        val horreur = search("horreur", appLanguage = "fr").genres.single()

        assertEquals("Horreur", horreur.name)
        assertEquals(listOf(1L, 2L), ids(horreur.titles))
        assertEquals("Horror", search("horreur").genres.single().name)
    }

    @Test fun `a Show's genre field and a Movie's details name its genres`() = runBlocking {
        provider.serveShows(FakeProvider.ShowListing("EN - Mare of Easttown (2021)", "EN - SERIES", 115004, genre = "Crime / Drama"))
        syncMovies(Movie("EN - Heat (1995)", 949, "EN - ACTION"))
        provider.serveMovieDetails(1, genre = "Crime, Thriller")
        assertEquals(listOf(115004L), ids(search("crime").genres.single().titles))

        library.title(TitleId.Tmdb(TitleKind.Movie, 949), english, "en")
        assertTrue(awaitSearchIndexAfterSearching("crime"))

        assertEquals(listOf(949L, 115004L), search("crime").genres.single().titles.map { (it.id as TitleId.Tmdb).tmdbId })
    }

    @Test fun `a query that is a genre's whole name shows that genre first (#170)`() = runBlocking {
        syncMovies(Movie("EN - The Drama (2024)", 1, "EN - COMEDY"), Movie("EN - Brooklyn (2015)", 2, "EN - DRAMA"), Movie("EN - Melodrama (2020)", 3, "EN - MOVIES"))

        val drama = search("Drama")
        assertEquals(listOf("The Drama"), drama.titles.map { it.name })
        // The genre's Titles first, then the one found by name.
        assertEquals(listOf("Brooklyn", "The Drama"), drama.cards("Drama").map { (it as SearchCard.OfTitle).title.name })
        // Typed in full, in any case, with a space after it.
        assertEquals("Brooklyn", (search("drama ").cards("drama ").first() as SearchCard.OfTitle).title.name)
        // Still being typed: the usual order.
        assertEquals("The Drama", (search("dra").cards("dra").first() as SearchCard.OfTitle).title.name)
    }

    // Completions

    @Test fun `completions offer Titles, then people, then genres, the query starts`() = runBlocking {
        syncShows(
            FakeProvider.ShowListing("EN - Bridget Jones's Diary (2001)", "EN - SERIES", 1, genre = "Comedy"),
            FakeProvider.ShowListing("EN - Brooklyn (2015)", "EN - SERIES", 2, genre = "Drama"),
            FakeProvider.ShowListing("EN - Fight Club (1999)", "EN - SERIES", 3, genre = "Drama", cast = "Brad Pitt, Edward Norton"),
            FakeProvider.ShowListing("EN - City of God (2002)", "EN - SERIES", 4, genre = "Brazilian Telenovela"),
        )

        assertEquals(listOf("Bridget Jones's Diary", "Brooklyn", "Brad Pitt", "Brazilian Telenovela"), search("br").completions)
    }

    @Test fun `completions lead with Titles in Content languages, and never offer the query itself`() = runBlocking {
        syncMovies("DE - Night Shift (2020)" to 2, "EN - Night Train (2010)" to 1, "EN - Night (2019)" to 3)

        assertEquals(listOf("Night Train", "Night Shift"), search("night").completions)
    }

    @Test fun `before anything is typed, completions are the canonical genres with the most Titles in Content languages`() = runBlocking {
        syncMovies(
            Movie("EN - Heat (1995)", 1, "EN - ACTION CRIME"),
            Movie("EN - Ronin (1998)", 2, "EN - ACTION"),
            Movie("DE - Das Boot (1981)", 3, "DE - KRIEG WAR"),
            Movie("DE - Der Untergang (2004)", 4, "DE - KRIEG WAR"),
        )

        val empty = search("")

        assertTrue(empty.isEmpty)
        assertEquals(listOf("Action", "Crime"), empty.completions)
        assertEquals(SearchResults.NONE, search("h"))
    }

    @Test fun `genre shortcuts are named in the App language and find the same Titles in either (#242)`() = runBlocking {
        syncMovies(
            Movie("EN - Heat (1995)", 1, "EN - DRAMA THRILLER"),
            Movie("EN - Brooklyn (2015)", 2, "EN - DRAMA ROMANCE"),
            Movie("EN - Superbad (2007)", 3, "EN - COMEDY"),
            Movie("EN - Intouchables (2011)", 4, "FR - DRAME COMÉDIE"),
        )

        assertEquals(listOf("Drama", "Comedy", "Romance", "Thriller"), search("").completions)
        assertEquals(listOf("Drame", "Comédie", "Romance", "Thriller"), search("", appLanguage = "fr").completions)
        val drama = search("Drama").genres.first()
        val drame = search("Drame", appLanguage = "fr").genres.first()
        assertEquals("Drame", drame.name)
        assertEquals(setOf(1L, 2L, 4L), ids(drama.titles).toSet())
        assertEquals(ids(drama.titles).toSet(), ids(drame.titles).toSet())
        assertEquals("Comédie", search("comedie", appLanguage = "fr").genres.first().name)
    }

    // The grid

    private val dramaTv = Channel("Drama TV", "Drama TV", null, null, false, listOf(ChannelVariant(7, "Drama TV", null, false)))

    @Test fun `results are one grid of cards, Titles by name first, then genres', Channels, Replays and people's, each Title once`() {
        val movie = TitleSummary(TitleId.Tmdb(TitleKind.Movie, 1), "The Drama", null, null, VersionAttributes())
        val show = TitleSummary(TitleId.Tmdb(TitleKind.Show, 2), "Drama Club", null, null, VersionAttributes())
        val brooklyn = TitleSummary(TitleId.Tmdb(TitleKind.Movie, 3), "Brooklyn", null, null, VersionAttributes())
        val replay = Replay(9, "SOC - Drama FC - Comedy United 01.11.2025", "SOCCER FOOTBALL", null, "Comedy United", "Drama FC", java.time.LocalDate.of(2025, 11, 1), 2025)
        val results = SearchResults(
            movies = listOf(movie),
            shows = listOf(show),
            people = listOf(Person("Dramane Diallo", listOf(movie, brooklyn))),
            channels = listOf(dramaTv),
            genres = listOf(Genre("Drama", listOf(show, brooklyn))),
            replays = listOf(replay),
            // The show matched better than the movie.
            titles = listOf(show, movie),
        )

        assertEquals(
            listOf(
                SearchCard.OfTitle(show),
                SearchCard.OfTitle(movie),
                SearchCard.OfTitle(brooklyn),
                SearchCard.OfChannel(dramaTv),
                SearchCard.OfReplay(replay),
            ),
            results.cards("dra"),
        )
        // Without the Titles together, Movies come before Shows.
        assertEquals(listOf(movie, show), SearchResults(movies = listOf(movie), shows = listOf(show), people = emptyList()).titles)
    }

    @Test fun `a Sports replay is found by its name, and its Title number never makes it a Title (#258)`() = runBlocking {
        // The Replay carries The Godfather's number, as the surveyed Provider's do (wrongly).
        syncMovies("SOC - Boston Bruins at Toronto Maple Leafs 09.11.25" to 238, "EN - The Godfather (1972)" to 238)

        val bruins = search("bruins")
        assertEquals(listOf("SOC - Boston Bruins at Toronto Maple Leafs 09.11.25"), bruins.replays.map { it.name })
        assertTrue(bruins.movies.isEmpty())
        assertEquals(bruins.replays.map(SearchCard::OfReplay), bruins.cards("bruins"))
        assertEquals(listOf(godfather), search("godfather").movies.map { it.id })
        assertTrue(search("godfather").replays.isEmpty())
    }

    @Test fun `a Title without a number is still found by its name, now that the tabs are rows (#200)`() = runBlocking {
        provider.movieCategoriesOverride = """[{"category_id":"1","category_name":"MOVIES"}]"""
        provider.movieListOverride = """[{"stream_id":1,"name":"IN - Mohgiben MBBS (2003)","tmdb":"","category_id":"1","added":"1700000000"}]"""
        library.sync()
        library.prepareSearch()

        val found = search("mohgiben", contentLanguages = listOf("hi")).movies.single()
        assertTrue(found.id is TitleId.Unmatched)
        assertEquals("Mohgiben MBBS", found.name)
    }

    // The index

    @Test fun `a new Sync is searched at once`() = runBlocking {
        syncMovies("EN - Heat (1995)" to 949)
        assertEquals(1, search("heat").movies.size)

        syncMovies("EN - Ronin (1998)" to 8195)

        assertTrue(search("heat").movies.isEmpty())
        assertEquals(1, search("ronin").movies.size)
    }

    @Test fun `searching while a Sync runs and right after it answers from the index built before it (#241)`() = runBlocking {
        syncMovies("EN - Heat (1995)" to 949, "EN - Alien (1979)" to 348)
        // Every build of the index from here waits until released: on the stick one takes 70 to 90 s (#106).
        val building = CountDownLatch(1)
        val built = CountDownLatch(1)
        library.onSearchIndexBuild = {
            building.countDown()
            built.await(30, TimeUnit.SECONDS)
        }
        // The next Sync drops Alien for Ronin, and stops in the middle of the show list until released.
        provider.movieListOverride = """[{"stream_id":1,"name":"EN - Heat (1995)","tmdb":"949","category_id":"1","added":"1700000000"},""" +
            """{"stream_id":3,"name":"EN - Ronin (1998)","tmdb":"8195","category_id":"1","added":"1699999999"}]"""
        val downloading = CountDownLatch(1)
        val release = CountDownLatch(1)
        provider.onRequest = { action ->
            if (action == "get_series") {
                downloading.countDown()
                release.await(30, TimeUnit.SECONDS)
            }
        }
        val sync = launch(Dispatchers.IO) { library.sync() }
        try {
            assertTrue(downloading.await(10, TimeUnit.SECONDS))
            assertEquals(1, withTimeout(5_000) { search("heat") }.movies.size)

            release.countDown()
            sync.join()
            assertTrue("no new index being built", building.await(10, TimeUnit.SECONDS))
            // The old index answers meanwhile: Alien, gone from the catalog, drops out, and Ronin isn't found yet.
            assertEquals(1, withTimeout(5_000) { search("heat") }.movies.size)
            assertTrue(withTimeout(5_000) { search("alien") }.movies.isEmpty())
            assertTrue(withTimeout(5_000) { search("ronin") }.movies.isEmpty())
        } finally {
            release.countDown()
            built.countDown()
            sync.join()
        }
        provider.onRequest = null
        // Swapped in once built.
        assertTrue("no search index after the Sync", awaitSearchIndex())
        assertEquals(1, search("ronin").movies.size)
        assertTrue(search("alien").movies.isEmpty())
    }

    @Test fun `a Sync builds the search index in the background, before the first search`() = runBlocking {
        library.sync()

        assertTrue("no search index after the Sync", awaitSearchIndex())
    }

    @Test fun `the search index can be built in the background when the app starts`() = runBlocking {
        library.sync()
        library.close()
        library = Library.open(folder.root.resolve("catalog.db").path, credentials, cloud.url)
        assertFalse(library.searchIndexReady)

        library.prepareSearchInBackground()

        assertTrue("no search index after starting", awaitSearchIndex())
        assertEquals(godfather, search("godfather").movies.first().id)
    }

    @Test fun `names fold to the same words in any script`() {
        // What words() did before its fast path for plain ASCII names.
        val reference = { text: String ->
            java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase(java.util.Locale.ROOT)
                .split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
        }
        val names = listOf(
            "The Godfather: Part II", "  WALL·E  ", "Pokémon: Detective Pikachu", "Amélie", "ÉTÉ 85", "Ocean's Eleven", "Se7en",
            "Москва слезам не верит", "東京物語", "Ça (It)", "Dr. Strangelove", "naïve-ŒUVRE", "İstanbul", "ß", "", "---", "4K-EN Movie_Title",
        )
        for (name in names) assertEquals(name, reference(name), SearchIndex.words(name))
    }

    @Test fun `building the search index doesn't hold up reading a Title`() = runBlocking {
        syncFixture()
        val store = CatalogStore(folder.root.resolve("catalog.db").path)
        val indexing = CountDownLatch(1)
        val opened = CountDownLatch(1)
        // Cleaning names takes about a minute on the stick: a details page opened meanwhile mustn't wait for it.
        val index = thread {
            store.forEachTitle(TitleKind.Movie) { _, _ ->
                indexing.countDown()
                opened.await(5, TimeUnit.SECONDS)
            }
        }
        indexing.await()

        val reading = thread { store.title(godfather) }
        reading.join(2_000)
        val held = reading.isAlive
        opened.countDown()
        index.join()
        reading.join()
        store.close()

        assertFalse("a Title waited for the search index", held)
    }

    @Test fun `search stays fast on a full-size catalog`() = runBlocking {
        provider.generatedMovies = 150_000
        val library = Library.open(folder.root.resolve("full.db").path, InMemoryCredentials())
        library.logIn(provider.login)
        library.sync()

        var started = System.nanoTime()
        library.prepareSearch()
        val built = (System.nanoTime() - started) / 1_000_000
        val results = library.search("generated movie 12345", english, "en").last()
        // Best of 3, timing only the search: a shared CI runner can stall on any one of them,
        // which says nothing about Search itself.
        val searched = (1..3).minOf {
            started = System.nanoTime()
            library.search("generated movie 12345", english, "en").last()
            (System.nanoTime() - started) / 1_000_000
        }
        library.close()
        println("Search index of 150,000 movie Listings on this machine: built in $built ms, searched in $searched ms")

        assertTrue(results.movies.isNotEmpty())
        // The stick is several times slower than a laptop: a keystroke must stay well under a second there.
        // A laptop searches in about 80 ms. GitHub's runners usually take 130 to 160 ms, but a slow one
        // reached 320 ms even at best of 3, so CI gets about 5 times its usual time: still a real slowdown.
        val limit = if (System.getenv("CI") != null) 750 else 300
        assertTrue("searched in $searched ms, over $limit ms", searched < limit)
    }
}
