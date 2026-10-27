package dev.betteriptv.core

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
        library.search(query, contentLanguages, appLanguage)

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
    }

    /** Syncs these movie Listings (name to TMDB ID), newest first, numbered from 1. */
    private suspend fun syncMovies(vararg listings: Pair<String, Long>) {
        provider.movieCategoriesOverride = """[{"category_id":"1","category_name":"MOVIES"}]"""
        provider.movieListOverride = listings.withIndex().joinToString(",", "[", "]") { (i, listing) ->
            """{"stream_id":${i + 1},"name":"${listing.first}","tmdb":"${listing.second}","category_id":"1","added":"${1_700_000_000 - i}"}"""
        }
        library.sync()
        library.titles(0, 1000, english, "en")
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

    @Test fun `a new Sync is searched at once`() = runBlocking {
        syncMovies("EN - Heat (1995)" to 949)
        assertEquals(1, search("heat").movies.size)

        syncMovies("EN - Ronin (1998)" to 8195)

        assertTrue(search("heat").movies.isEmpty())
        assertEquals(1, search("ronin").movies.size)
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
        val results = library.search("generated movie 12345", english, "en")
        // Best of 3, timing only the search: a shared CI runner can stall on any one of them,
        // which says nothing about Search itself.
        val searched = (1..3).minOf {
            started = System.nanoTime()
            library.search("generated movie 12345", english, "en")
            (System.nanoTime() - started) / 1_000_000
        }
        library.close()
        println("Search index of 150,000 movie Listings on this machine: built in $built ms, searched in $searched ms")

        assertTrue(results.movies.isNotEmpty())
        // The stick is several times slower than a laptop: a keystroke must stay well under a second there.
        assertTrue("searched in $searched ms", searched < 300)
    }
}
