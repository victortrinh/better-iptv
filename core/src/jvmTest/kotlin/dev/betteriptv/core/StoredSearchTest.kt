package dev.betteriptv.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The search index a Sync stores with its catalog (#584, ADR 0020): there as the app starts, landed
 * with each catalog, kept up to date as movie details are kept, and built once on a database kept
 * from before it. What Search answers from it is [SearchTest]'s to say.
 */
class StoredSearchTest {
    @get:Rule val folder = TemporaryFolder()
    private val provider = FakeProvider()
    private val credentials = InMemoryCredentials()
    private val english = listOf("en")
    private val godfather = TitleId.Numbered(TitleKind.Movie, 238)
    private val heat = TitleId.Numbered(TitleKind.Movie, 949)
    private val path by lazy { folder.root.resolve("catalog.db").path }
    private var opened: Library? = null

    private fun open(): Library = Library.open(path, credentials).also {
        it.searchInMemory = false
        opened = it
    }

    @After fun tearDown() {
        opened?.close()
        provider.close()
    }

    private suspend fun Library.find(query: String) = search(query, english, "en").last()

    private fun serveMovies(vararg movies: Pair<String, Long>) {
        provider.showListOverride = "[]"
        provider.showCategoriesOverride = "[]"
        provider.movieCategoriesOverride = """[{"category_id":"1","category_name":"MOVIES"}]"""
        provider.movieListOverride = movies.withIndex().joinToString(",", "[", "]") { (i, movie) ->
            """{"stream_id":${i + 1},"name":"${movie.first}","tmdb":"${movie.second}","category_id":"1","added":"${1_700_000_000 - i}"}"""
        }
    }

    @Test fun `Search answers straight after the app starts again, with nothing built`() = runBlocking {
        var library = open()
        library.logIn(provider.login)
        library.sync()
        library.close()

        library = open()

        assertTrue("an index was to be built at launch", library.searchIndexReady)
        assertEquals(godfather, library.find("godfather").movies.first().id)
        assertEquals(godfather, library.find("the godfahter").movies.first().id)
    }

    @Test fun `a held Sync's catalog brings its own index, searched as it lands`() = runBlocking {
        val library = open()
        library.logIn(provider.login)
        serveMovies("EN - Heat (1995)" to 949, "EN - Alien (1979)" to 348)
        library.sync()
        serveMovies("EN - Heat (1995)" to 949, "EN - Ronin (1998)" to 8195)

        assertTrue(library.sync(hold = true) is SyncStatus.Ready)
        // The catalog on screen is still the one searched.
        assertEquals(1, library.find("alien").movies.size)
        assertTrue(library.find("ronin").movies.isEmpty())

        assertTrue(library.applyPendingCatalog())
        assertEquals(1, library.find("ronin").movies.size)
        assertTrue(library.find("alien").movies.isEmpty())
        assertEquals(1, library.find("heat").movies.size)
    }

    @Test fun `a catalog that waited through a restart lands with its index`() = runBlocking {
        var library = open()
        library.logIn(provider.login)
        serveMovies("EN - Heat (1995)" to 949)
        library.sync()
        serveMovies("EN - Ronin (1998)" to 8195)
        assertTrue(library.sync(hold = true) is SyncStatus.Ready)
        library.close()

        library = open()
        assertTrue(library.applyPendingCatalog())

        assertTrue(library.searchIndexReady || library.find("ronin").movies.isNotEmpty())
        assertEquals(1, library.find("ronin").movies.size)
        assertTrue(library.find("heat").movies.isEmpty())
    }

    @Test fun `searching while a Sync runs answers from the catalog on screen, and the new one is searched the moment it lands`() = runBlocking {
        val library = open()
        library.logIn(provider.login)
        serveMovies("EN - Heat (1995)" to 949, "EN - Alien (1979)" to 348)
        library.sync()
        serveMovies("EN - Heat (1995)" to 949, "EN - Ronin (1998)" to 8195)
        // The next Sync stops in the middle of the show list until released.
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
            assertEquals(1, withTimeout(5_000) { library.find("alien") }.movies.size)
            assertTrue(withTimeout(5_000) { library.find("ronin") }.movies.isEmpty())
        } finally {
            release.countDown()
            sync.join()
        }
        provider.onRequest = null

        assertEquals(1, library.find("ronin").movies.size)
        assertTrue(library.find("alien").movies.isEmpty())
    }

    @Test fun `movie details kept while a catalog waits are in the index it lands`() = runBlocking {
        val library = open()
        library.logIn(provider.login)
        serveMovies("EN - Heat (1995)" to 949, "EN - Ronin (1998)" to 8195)
        library.sync()
        assertTrue(library.sync(hold = true) is SyncStatus.Ready)
        provider.serveMovieDetails(1, cast = "Al Pacino, Robert De Niro", genre = "Crime")

        library.title(heat, english, "en")
        assertEquals(listOf(heat), library.find("pacino").people.single().titles.map { it.id })

        assertTrue(library.applyPendingCatalog())
        assertEquals(listOf(heat), library.find("pacino").people.single().titles.map { it.id })
        assertEquals(listOf(heat), library.find("crime").genres.single().titles.map { it.id })
    }

    @Test fun `a Movie's cast and genres that its Provider changes replace the ones kept`() = runBlocking {
        val library = open()
        library.logIn(provider.login)
        serveMovies("EN - Heat (1995)" to 949)
        library.sync()
        provider.serveMovieDetails(1, cast = "Al Pacino", genre = "Crime")
        library.title(heat, english, "en")
        assertEquals(1, library.find("pacino").people.size)

        // The Provider changes the Listing, so its details are read again.
        provider.movieListOverride = provider.movieListOverride!!.replace("1700000000", "1700000500")
        provider.serveMovieDetails(1, cast = "Val Kilmer", genre = "Thriller")
        library.sync()
        library.title(heat, english, "en")

        assertEquals(listOf("Val Kilmer"), library.find("kilmer").people.map { it.name })
        assertTrue(library.find("pacino").people.isEmpty())
        assertTrue(library.find("crime").genres.isEmpty())
        assertEquals(listOf("Thriller"), library.find("").completions)
    }

    @Test fun `a database kept from before the index keeps its catalog, Favourites, Continue watching and Profiles, and is searched without a Sync`() = runBlocking {
        // A catalog the app stored before #584: Synced, a Favourite, a Movie stopped in, and a second Profile with its own.
        javaClass.getResourceAsStream("/databases/before-search-index.db")!!.use { database -> java.io.File(path).outputStream().use { database.copyTo(it) } }

        var library = open()

        assertEquals(150, library.movieListingCount())
        assertTrue(library.syncStatus.value is SyncStatus.Synced)
        assertEquals(listOf("UK: BBC ONE"), library.favourites().map { it.key })
        assertEquals(listOf(null, "Alex"), library.profiles.value.all.map { it.name })
        val resumes = { rows: List<HomeRow> -> rows.filterIsInstance<HomeRow.ContinueWatching>().single().titles.map { it.title.id } }
        assertEquals(listOf(godfather), resumes(library.homeRows(english, "en")))
        // Its index is built here, once, by the first search: no Sync, and no Provider to ask.
        assertFalse(library.searchIndexReady)
        assertEquals(godfather, library.find("godfather").movies.first().id)
        assertEquals(godfather, library.find("godfater").movies.first().id)
        assertTrue(library.find("game of thrones").shows.isNotEmpty())
        val bbc = library.find("bbc").channels.single()
        assertEquals("UK: BBC ONE", bbc.key)
        assertTrue(bbc.favourite)
        assertTrue(library.find("").completions.isNotEmpty())
        assertTrue(library.searchIndexReady)
        assertTrue("the Provider was asked", provider.requests.isEmpty())
        library.switchProfile(library.profiles.value.all.last().id)
        assertEquals(listOf(TitleId.Numbered(TitleKind.Movie, 541671)), resumes(library.homeRows(english, "en")))
        library.close()

        // And never again.
        library = open()
        assertTrue(library.searchIndexReady)
        assertEquals(godfather, library.find("godfather").movies.first().id)
    }

    @Test fun `the index built in memory can still be switched on, and answers the same`() = runBlocking {
        val library = open()
        library.logIn(provider.login)
        provider.serveChannels("US: ESPN HD" to "US| SPORTS", "US: ESPN FHD" to "US| SPORTS", "UK: BBC ONE HD" to "UK| GENERAL")
        library.sync()
        val queries = listOf("", "go", "the", "godfather", "the godfahter", "game of thrnoes", "dra", "drama", "comedy", "espn", "bbc one", "be", "st", "star", "night", "king", "love 2019")

        val stored = queries.associateWith { library.find(it) }
        library.searchInMemory = true
        val inMemory = queries.associateWith { library.find(it) }

        assertFalse(stored.getValue("godfather").isEmpty)
        for (query in queries) assertEquals(query, inMemory.getValue(query), stored.getValue(query))
    }
}
