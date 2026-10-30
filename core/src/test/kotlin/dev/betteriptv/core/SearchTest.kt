package dev.betteriptv.core

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
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

/** Search across Movies, Shows and people, seen through the Library after a Sync, with TMDB metadata from the fake Cloud. */
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
    private val godfatherPartTwo = TitleId.Tmdb(TitleKind.Movie, 240)
    private val gameOfThrones = TitleId.Tmdb(TitleKind.Show, 1399)

    private suspend fun search(query: String, contentLanguages: List<String> = english, appLanguage: String = "en") =
        library.search(query, contentLanguages, appLanguage).last()

    /** Syncs the fixture with TMDB metadata for both Godfathers, and caches it as browsing the cards would. */
    private suspend fun syncFixtureWithMetadata() {
        cloud.title(
            "en",
            tmdbTitle(
                238, "The Godfather", popularity = 120.0, cast = listOf("Marlon Brando", "Al Pacino"),
                titles = listOf(Triple("fr", "FR", "Le Parrain"), Triple("de", "DE", "Der Pate"), Triple("es", "ES", "El padrino")),
            ),
        )
        cloud.title("en", tmdbTitle(240, "The Godfather Part II", popularity = 60.0, cast = listOf("Al Pacino", "Robert De Niro")))
        library.sync()
        library.titles(0, 1000, english, "en")
        // As opening Search does: the index built after the Sync predates the metadata just cached.
        library.prepareSearch()
    }

    /** Syncs these movie Listings (name to TMDB ID), newest first, numbered from 1. */
    private suspend fun syncMovies(vararg listings: Pair<String, Long>) {
        provider.movieCategoriesOverride = """[{"category_id":"1","category_name":"MOVIES"}]"""
        provider.movieListOverride = listings.withIndex().joinToString(",", "[", "]") { (i, listing) ->
            """{"stream_id":${i + 1},"name":"${listing.first}","tmdb":"${listing.second}","category_id":"1","added":"${1_700_000_000 - i}"}"""
        }
        library.sync()
        library.titles(0, 1000, english, "en")
        // As opening Search does: the index built after the Sync predates the metadata just cached.
        library.prepareSearch()
    }

    @Test fun `a Title is found by any Listing name of its Versions and by its TMDB names`() = runBlocking {
        syncFixtureWithMetadata()

        for (query in listOf("the godfather", "le parrain", "el padrino", "il padrino", "der pate")) {
            assertEquals(query, godfather, search(query).movies.first().id)
        }
    }

    @Test fun `cards show TMDB's name in the App language`() = runBlocking {
        cloud.title("fr", tmdbTitle(238, "The Godfather", titles = listOf(Triple("fr", "FR", "Le Parrain"))))
        library.sync()

        assertEquals("Le Parrain", search("godfather", appLanguage = "fr").movies.single { it.id == godfather }.name)
    }

    @Test fun `accents and case don't matter`() = runBlocking {
        syncMovies("EN - Pokémon: Detective Pikachu (2019)" to 447404, "FR - Amélie (2001)" to 194)

        assertEquals(TitleId.Tmdb(TitleKind.Movie, 447404), search("pokemon").movies.single().id)
        assertEquals(TitleId.Tmdb(TitleKind.Movie, 447404), search("POKÉMON").movies.single().id)
        assertEquals(TitleId.Tmdb(TitleKind.Movie, 194), search("amelie").movies.single().id)
    }

    @Test fun `small typos are tolerated`() = runBlocking {
        syncFixtureWithMetadata()

        assertEquals(godfather, search("godfater").movies.first().id)
        assertEquals(godfather, search("the godfahter").movies.first().id)
        assertEquals(gameOfThrones, search("thrnoes").shows.first().id)
    }

    @Test fun `results show after two letters, and words being typed match`() = runBlocking {
        syncFixtureWithMetadata()

        assertEquals(SearchResults.NONE, search("g"))
        assertTrue(search("go").movies.any { it.id == godfather })
        assertEquals(godfather, search("the godf").movies.first().id)
    }

    @Test fun `exact matches rank before typo matches`() = runBlocking {
        syncMovies("EN - Heat (1995)" to 949, "EN - Hear Me (2009)" to 1)

        assertEquals(listOf(TitleId.Tmdb(TitleKind.Movie, 949), TitleId.Tmdb(TitleKind.Movie, 1)), search("heat").movies.map { it.id })
    }

    @Test fun `results are grouped into Movies and Shows`() = runBlocking {
        syncFixtureWithMetadata()

        val results = search("game of thrones")
        assertTrue(results.movies.none { it.id.kind == TitleKind.Show })
        assertEquals(gameOfThrones, results.shows.first().id)
        assertTrue(search("godfather").shows.isEmpty())
    }

    @Test fun `Content languages come first, then popularity, and other languages still appear after them`() = runBlocking {
        cloud.title("en", tmdbTitle(1, "Night Train", popularity = 5.0))
        cloud.title("en", tmdbTitle(2, "Night Train to Lisbon", popularity = 50.0))
        cloud.title("en", tmdbTitle(3, "Night Shift", popularity = 500.0))
        syncMovies("EN - Night Train (2010)" to 1, "EN - Night Train to Lisbon (2013)" to 2, "DE - Night Shift (2020)" to 3)

        assertEquals(listOf(2L, 1L, 3L), search("night").movies.map { (it.id as TitleId.Tmdb).tmdbId })
        assertEquals(listOf(3L, 2L, 1L), search("night", contentLanguages = listOf("de")).movies.map { (it.id as TitleId.Tmdb).tmdbId })
    }

    @Test fun `searching a person shows their Titles the Provider has`() = runBlocking {
        syncFixtureWithMetadata()

        val pacino = search("pacino").people.single()
        assertEquals("Al Pacino", pacino.name)
        assertEquals(listOf(godfather, godfatherPartTwo), pacino.titles.map { it.id })
        assertEquals(listOf("Robert De Niro"), search("de niro").people.map { it.name })
        assertEquals(listOf("Marlon Brando"), search("marlon brnado").people.map { it.name })
    }

    @Test fun `TMDB's search finds a Title by a name the device has no metadata for`() = runBlocking {
        syncMovies("EN - The Godfather (1972)" to 238, "EN - Heat (1995)" to 949)
        cloud.search("der pate", titles = listOf(godfather to 120.0, TitleId.Tmdb(TitleKind.Movie, 999_999) to 300.0))

        val (local, withTmdb) = library.search("der pate", english, "en").toList()

        assertTrue(local.movies.isEmpty())
        // The Provider has no Title 999999, so it isn't shown.
        assertEquals(listOf(godfather), withTmdb.movies.map { it.id })
        assertEquals(listOf("der pate"), cloud.searchAsked)
    }

    @Test fun `TMDB's search finds every Title of a person the Provider has`() = runBlocking {
        syncMovies("EN - The Godfather (1972)" to 238, "EN - Heat (1995)" to 949, "EN - Ronin (1998)" to 8195)
        cloud.search("de niro", people = listOf("Robert De Niro" to listOf(TitleId.Tmdb(TitleKind.Movie, 949), TitleId.Tmdb(TitleKind.Movie, 8195), TitleId.Tmdb(TitleKind.Movie, 1))))

        val deNiro = search("de niro").people.single()

        assertEquals("Robert De Niro", deNiro.name)
        assertEquals(setOf(949L, 8195L), deNiro.titles.map { (it.id as TitleId.Tmdb).tmdbId }.toSet())
    }

    @Test fun `a person found on the device and by TMDB is shown once, with the Titles of both`() = runBlocking {
        syncFixtureWithMetadata()
        cloud.search("pacino", people = listOf("Al Pacino" to listOf(godfather, gameOfThrones)))

        val pacino = search("pacino").people.single()

        assertEquals(setOf(godfather, godfatherPartTwo, gameOfThrones), pacino.titles.map { it.id }.toSet())
    }

    @Test fun `TMDB is asked from three letters, and only once typing has paused`() = runBlocking {
        syncMovies("EN - Heat (1995)" to 949)

        search("he")
        // The Viewer typed on: the results of "hea" are replaced before TMDB is asked.
        library.search("hea", english, "en").first()

        assertEquals(emptyList<String>(), cloud.searchAsked)
        search("heat")
        assertEquals(listOf("heat"), cloud.searchAsked)
    }

    @Test fun `without the cloud, the device's own matches are the results`() = runBlocking {
        syncMovies("EN - Heat (1995)" to 949)
        cloud.down = true

        assertEquals(listOf(TitleId.Tmdb(TitleKind.Movie, 949)), library.search("heat", english, "en").toList().single().movies.map { it.id })
    }

    @Test fun `a new Sync is searched at once`() = runBlocking {
        syncMovies("EN - Heat (1995)" to 949)
        assertEquals(1, search("heat").movies.size)

        syncMovies("EN - Ronin (1998)" to 8195)

        assertTrue(search("heat").movies.isEmpty())
        assertEquals(1, search("ronin").movies.size)
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

    @Test fun `search reads only names, cast and popularity from cached metadata`() {
        val json = com.squareup.moshi.Moshi.Builder().build().adapter(Any::class.java).toJson(
            tmdbTitle(
                238, "The Godfather", titles = listOf(Triple("fr", "FR", "Le Parrain"), Triple("en", "US", "The Godfather"), Triple("de", "DE", " ")),
                alternativeTitles = listOf("Mario Puzo's The Godfather"), descriptions = listOf(Triple("en", "US", "Spanning the years 1945 to 1955…")),
                genres = listOf("Drama"), cast = listOf("Marlon Brando", "Al Pacino"), popularity = 120.5, posterPath = "/p.jpg",
            ),
        )
        val store = CatalogStore(folder.root.resolve("metadata.db").path)
        store.saveMetadata("en", mapOf(godfather to json), listOf(godfatherPartTwo), java.time.Instant.now())
        val found = mutableListOf<List<Any>>()

        store.forEachSearchMetadata { id, names, cast, popularity -> found += listOf(id, names.filter(String::isNotBlank).toSet(), cast, popularity) }
        store.close()

        assertEquals(listOf(listOf(godfather, setOf("The Godfather", "Le Parrain", "Mario Puzo's The Godfather"), listOf("Marlon Brando", "Al Pacino"), 120.5)), found)
    }

    @Test fun `building the search index doesn't hold up reading a Title`() = runBlocking {
        syncFixtureWithMetadata()
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
        // Without the cloud, so the Sync doesn't ask it to Match 150,000 Listings.
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
        assertTrue("searched in $searched ms", searched < 300)
    }
}
