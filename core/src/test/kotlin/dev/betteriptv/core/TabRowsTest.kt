package dev.betteriptv.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The Movies and Shows tabs' rows, seen through the Library after a Sync, with metadata and Charts from the fake Cloud. */
class TabRowsTest {
    @get:Rule val folder = TemporaryFolder()
    private val provider = FakeProvider()
    private val cloud = FakeCloud()
    private lateinit var library: Library

    @Before fun logIn() = runBlocking {
        library = Library.open(folder.root.resolve("catalog.db").path, InMemoryCredentials(), cloud.url)
        library.logIn(provider.login)
        provider.showListOverride = "[]"
        Unit
    }

    @After fun tearDown() {
        library.close()
        provider.close()
        cloud.close()
    }

    private val english = listOf("en")

    private fun movie(tmdbId: Long) = TitleId.Tmdb(TitleKind.Movie, tmdbId)

    private fun show(tmdbId: Long) = TitleId.Tmdb(TitleKind.Show, tmdbId)

    /** Syncs these movie Listings, newest first, numbered from 1, each a Title of its own TMDB ID. */
    private suspend fun syncMovies(vararg listings: Pair<String, Long>) {
        provider.movieCategoriesOverride = """[{"category_id":"1","category_name":"MOVIES"}]"""
        provider.movieListOverride = listings.withIndex().joinToString(",", "[", "]") { (i, listing) ->
            """{"stream_id":${i + 1},"name":"${listing.first}","tmdb":"${listing.second}","category_id":"1","added":"${1_700_000_000 - i}"}"""
        }
        library.sync()
    }

    /** Serves these show Listings at the next Sync, newest first, numbered from 1, each a Title of its own TMDB ID. */
    private fun serveShows(vararg listings: Pair<String, Long>) {
        provider.showCategoriesOverride = """[{"category_id":"2","category_name":"SERIES"}]"""
        provider.showListOverride = listings.withIndex().joinToString(",", "[", "]") { (i, listing) ->
            """{"series_id":${i + 1},"name":"${listing.first}","tmdb":"${listing.second}","category_id":"2","last_modified":"${1_700_000_000 - i}"}"""
        }
    }

    /** Serves a movie [tmdbId] named [name], released in [originalLanguage]. */
    private fun tmdb(tmdbId: Long, name: String, popularity: Double = 10.0, originalLanguage: String = "en") =
        cloud.title("en", tmdbTitle(tmdbId, name, originalLanguage = originalLanguage, popularity = popularity))

    private suspend fun rows(kind: TitleKind = TitleKind.Movie, contentLanguages: List<String> = english, region: String = "CA") =
        library.tabRows(kind, contentLanguages, appLanguage = "en", region = region, now = Instant.now())

    private fun List<HomeRow>.chart(list: ChartList) = filterIsInstance<HomeRow.Chart>().singleOrNull { it.list == list }

    private fun List<HomeRow>.chartNames(list: ChartList) = chart(list)?.titles?.map { it.name }

    private fun List<HomeRow>.new() = filterIsInstance<HomeRow.NewOnYourService>().single()

    private fun List<HomeRow>.newNames() = new().titles.map { it.name }

    private fun List<HomeRow>.names() = map {
        when (it) {
            is HomeRow.Chart -> it.list.toString()
            is HomeRow.NewOnYourService -> "New ${it.kind}"
            is HomeRow.Genre -> it.name
            else -> it.toString()
        }
    }

    /** Syncs these movie Listings after 40 newer ones, which New movies picks from, so these are left to the genre rows. */
    private suspend fun syncOlderMovies(vararg listings: Pair<String, Long>) =
        syncMovies(*((1L..40L).map { "EN - Filler $it (2025)" to 9000 + it } + listings).toTypedArray())

    private fun List<HomeRow>.genre(name: String) = filterIsInstance<HomeRow.Genre>().singleOrNull { it.name == name }?.titles?.map { it.name }

    private fun List<HomeRow>.genres() = filterIsInstance<HomeRow.Genre>().map { it.name }

    private fun numbered(name: String, ids: LongRange) = ids.mapIndexed { i, id -> "EN - $name ${i + 1} (2010)" to id }.toTypedArray()

    @Test fun `genre rows follow New, the genres with the most of the Provider's Titles in a Content language first`() = runBlocking {
        syncOlderMovies(*numbered("Laugh", 1L..6L), *numbered("Fright", 11L..15L), "FR - Frisson (2010)" to 16, *numbered("Tear", 21L..25L))
        // Horror lists 8, but the Provider has 6 of them and one is in French: 5, as many as Drama, listed after it.
        cloud.genre(TitleKind.Movie, "Horror", 16, 15, 14, 13, 12, 11, 99, 98)
        cloud.genre(TitleKind.Movie, "Comedy", 1, 2, 3, 4, 5, 6)
        cloud.genre(TitleKind.Movie, "Drama", 21, 22, 23, 24, 25)
        cloud.genre(TitleKind.Show, "Sitcom", 1, 2, 3, 4, 5, 6, 7)

        val rows = rows()
        assertEquals(listOf("New Movie", "Comedy", "Horror", "Drama"), rows.names())
        assertEquals(listOf("Fright 5", "Fright 4", "Fright 3", "Fright 2", "Fright 1"), rows.genre("Horror"))
    }

    @Test fun `a genre row puts TMDB's most popular across the whole catalog first`() = runBlocking {
        syncOlderMovies(*numbered("Laugh", 1L..5L))
        tmdb(1, "Laugh 1", popularity = 99.0)
        tmdb(3, "Laugh 3", popularity = 1.0)
        cloud.genre(TitleKind.Movie, "Comedy", 3, 5, 1, 2, 4)

        assertEquals(listOf("Laugh 3", "Laugh 5", "Laugh 1", "Laugh 2", "Laugh 4"), rows().genre("Comedy"))
    }

    @Test fun `genre rows skip the Titles shown above them, and a genre left with fewer than 5 is left out`() = runBlocking {
        syncOlderMovies(*numbered("Laugh", 1L..10L), *numbered("Fright", 11L..14L), *numbered("Other", 21L..22L))
        cloud.charts("CA", movies = listOf(1, 2, 3))
        cloud.trending(movie(4), movie(21), movie(22))
        // Filler 1 is on New movies.
        cloud.genre(TitleKind.Movie, "Comedy", 9001, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10)
        // Laugh 5 to 7 go to Comedy, the bigger genre, so Horror is left with 4.
        cloud.genre(TitleKind.Movie, "Horror", 5, 6, 7, 11, 12, 13, 14)

        val rows = rows()
        assertTrue("Filler 1" in rows.newNames())
        assertEquals(listOf("TopMovies", "TrendingMovies", "New Movie", "Comedy"), rows.names())
        assertEquals((5..10).map { "Laugh $it" }, rows.genre("Comedy"))
    }

    @Test fun `an Untagged Title is in a genre row when its original language is a Content language`() = runBlocking {
        syncOlderMovies(*numbered("Laugh", 1L..4L), "4K - Untagged English (2010)" to 5, "4K - Untagged French (2010)" to 6)
        tmdb(5, "Untagged English")
        tmdb(6, "Untagged French", originalLanguage = "fr")
        cloud.genre(TitleKind.Movie, "Comedy", 6, 5, 1, 2, 3, 4)

        assertEquals(listOf("Untagged English", "Laugh 1", "Laugh 2", "Laugh 3", "Laugh 4"), rows().genre("Comedy"))
    }

    @Test fun `the Shows tab has the show genres, the Movies tab the movie ones`() = runBlocking {
        serveShows(*((1L..40L).map { "EN - Show filler $it (2025)" to 9000 + it } + numbered("Saga", 1L..5L)).toTypedArray())
        syncMovies(*((1L..40L).map { "EN - Filler $it (2025)" to 9000 + it } + numbered("Laugh", 1L..5L)).toTypedArray())
        cloud.genre(TitleKind.Movie, "Comedy", 1, 2, 3, 4, 5)
        cloud.genre(TitleKind.Show, "Drama", 1, 2, 3, 4, 5)

        assertEquals(listOf("Comedy"), rows(TitleKind.Movie).genres())
        assertEquals(listOf("Drama"), rows(TitleKind.Show).genres())
        assertEquals((1..5).map { "Saga $it" }, rows(TitleKind.Show).genre("Drama"))
    }

    @Test fun `Home's genre rows don't come from the tabs' genres`() = runBlocking {
        syncOlderMovies(*numbered("Laugh", 1L..5L))
        cloud.genre(TitleKind.Movie, "Comedy", 1, 2, 3, 4, 5)

        assertEquals(listOf("Comedy"), rows().genres())
        assertTrue(library.homeRows(english, "en", "CA").none { it is HomeRow.Genre })
    }

    @Test fun `the genre rows kept on the device stand while the cloud can't answer`() = runBlocking {
        syncOlderMovies(*numbered("Laugh", 1L..5L))
        cloud.genre(TitleKind.Movie, "Comedy", 1, 2, 3, 4, 5)
        assertEquals(listOf("Comedy"), rows().genres())

        cloud.down = true
        val later = library.tabRows(TitleKind.Movie, english, "en", "CA", Instant.now().plusSeconds(2 * 3600))
        assertEquals((1..5).map { "Laugh $it" }, later.genre("Comedy"))
    }

    @Test fun `the Movies tab shows Top movies, Trending movies, then New movies`() = runBlocking {
        serveShows("EN - Show 1 (2025)" to 501, "EN - Show 2 (2025)" to 502, "EN - Show 3 (2025)" to 503)
        syncMovies(*(1L..9L).map { "EN - Film $it (2025)" to it }.toTypedArray())
        cloud.charts("CA", movies = listOf(1, 2, 3), shows = listOf(501, 502, 503))
        cloud.trending(show(501), movie(4), show(502), movie(5), movie(6), show(503))

        val rows = rows(TitleKind.Movie)
        assertEquals(listOf("TopMovies", "TrendingMovies", "New Movie"), rows.names())
        assertEquals(listOf("Film 1", "Film 2", "Film 3"), rows.chartNames(ChartList.TopMovies))
        // Trending movies only: the shows trending with them go to the Shows tab.
        assertEquals(listOf("Film 4", "Film 5", "Film 6"), rows.chartNames(ChartList.TrendingMovies))
        assertEquals(setOf("Film 7", "Film 8", "Film 9"), rows.newNames().toSet())
    }

    @Test fun `the Shows tab mirrors it with shows`() = runBlocking {
        serveShows(*(1L..9L).map { "EN - Show $it (2025)" to 500 + it }.toTypedArray())
        syncMovies("EN - Film 1 (2025)" to 1, "EN - Film 2 (2025)" to 2, "EN - Film 3 (2025)" to 3)
        cloud.charts("CA", movies = listOf(1, 2, 3), shows = listOf(501, 502, 503))
        cloud.trending(movie(1), show(504), movie(2), show(505), show(506))

        val rows = rows(TitleKind.Show)
        assertEquals(listOf("TopShows", "TrendingShows", "New Show"), rows.names())
        assertEquals(listOf("Show 1", "Show 2", "Show 3"), rows.chartNames(ChartList.TopShows))
        assertEquals(listOf("Show 4", "Show 5", "Show 6"), rows.chartNames(ChartList.TrendingShows))
        assertEquals(setOf("Show 7", "Show 8", "Show 9"), rows.newNames().toSet())
    }

    @Test fun `New movies puts the most popular of the newest movies in a Content language first`() = runBlocking {
        syncMovies("EN - Quiet (2025)" to 1, "EN - Hit (2025)" to 2, "FR - Français (2025)" to 3, "EN - Middling (2025)" to 4)
        tmdb(1, "Quiet", popularity = 2.0)
        tmdb(2, "Hit", popularity = 90.0)
        tmdb(3, "Français", popularity = 99.0, originalLanguage = "fr")
        tmdb(4, "Middling", popularity = 30.0)

        assertEquals(listOf("Hit", "Middling", "Quiet"), rows().newNames())
    }

    @Test fun `a tab's Top row keeps the Provider's Titles in a Content language, ranked in order, in the Region`() = runBlocking {
        syncMovies("EN - Fourth (2025)" to 4, "EN - First (2025)" to 1, "FR - Second (2025)" to 2, "EN - Fifth (2025)" to 5)
        tmdb(1, "First")
        tmdb(2, "Second", originalLanguage = "fr")
        tmdb(4, "Fourth")
        tmdb(5, "Fifth")
        cloud.charts("CA", movies = listOf(1, 99, 2, 4, 5))
        cloud.charts("FR", movies = listOf(5, 4, 1), worldwide = true)

        val top = rows(region = "CA").chart(ChartList.TopMovies)!!
        assertEquals(listOf("First", "Fourth", "Fifth"), top.titles.map { it.name })
        assertEquals("CA", top.region)
        // The world's list says so.
        assertEquals(null, rows(region = "FR").chart(ChartList.TopMovies)!!.region)
    }

    @Test fun `Charts with fewer than 3 cards are left out, and their Titles go to New`() = runBlocking {
        syncMovies("EN - First (2025)" to 1, "EN - Second (2025)" to 2)
        cloud.charts("CA", movies = listOf(1, 2))
        cloud.trending(movie(1), movie(2))

        val rows = rows()
        assertEquals(listOf("New Movie"), rows.names())
        assertEquals(setOf("First", "Second"), rows.newNames().toSet())
    }

    @Test fun `a tab shows each Title once, the Top row keeping all of its own`() = runBlocking {
        syncMovies(*(1L..6L).map { "EN - Film $it (2025)" to it }.toTypedArray())
        cloud.charts("CA", movies = listOf(1, 2, 3))
        // Film 1 is in Top movies, so Trending skips it; Trending's then go missing from New.
        cloud.trending(movie(1), movie(4), movie(5), movie(6))

        val rows = rows()
        assertEquals(listOf("Film 1", "Film 2", "Film 3"), rows.chartNames(ChartList.TopMovies))
        assertEquals(listOf("Film 4", "Film 5", "Film 6"), rows.chartNames(ChartList.TrendingMovies))
        assertTrue(rows.none { it is HomeRow.NewOnYourService })
    }

    @Test fun `a Title on Home's Top row also shows on the Movies tab's, each screen showing a Title once on its own`() = runBlocking {
        syncMovies(*(1L..4L).map { "EN - Film $it (2025)" to it }.toTypedArray())
        cloud.charts("CA", movies = listOf(1, 2, 3))

        val home = library.homeRows(english, "en", "CA")
        val movies = rows()
        assertEquals(listOf("Film 1", "Film 2", "Film 3"), home.chartNames(ChartList.TopMovies))
        assertEquals(listOf("Film 1", "Film 2", "Film 3"), movies.chartNames(ChartList.TopMovies))
        assertEquals(listOf("Film 4"), movies.newNames())
    }

    @Test fun `without the cloud and no genres kept, a tab has no genre rows`() = runBlocking {
        syncOlderMovies(*numbered("Laugh", 1L..5L))
        cloud.genre(TitleKind.Movie, "Comedy", 1, 2, 3, 4, 5)
        cloud.down = true

        assertEquals(listOf("New Movie"), rows().names())
    }

    @Test fun `without the cloud and no Charts kept, a tab still has New`() = runBlocking {
        syncMovies("EN - First (2025)" to 1, "EN - Second (2025)" to 2, "EN - Third (2025)" to 3)
        cloud.charts("CA", movies = listOf(1, 2, 3))
        cloud.down = true

        val rows = rows()
        assertEquals(listOf("New Movie"), rows.names())
    }

    @Test fun `a tab reads what's stored while a Sync downloads, though its Charts and metadata are asked again`() = runBlocking {
        syncMovies("EN - First (2025)" to 1, "EN - Second (2025)" to 2, "EN - Third (2025)" to 3, "EN - Fourth (2025)" to 4)
        cloud.charts("CA", movies = listOf(1, 2, 3))
        // An answer from before textless posters is asked again on every read, so this one saves metadata each time.
        cloud.title("en", tmdbTitle(4, "Fourth") - "textlessPosterPath")
        rows()
        // The Sync stops in the middle of the show list, holding the catalog's writer, until released.
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
            // Over an hour later, so the kept Charts are asked again.
            val rows = withTimeout(5_000) { library.tabRows(TitleKind.Movie, english, "en", "CA", Instant.now().plusSeconds(2 * 3600)) }
            assertEquals(listOf("First", "Second", "Third"), rows.chartNames(ChartList.TopMovies))
            assertEquals(listOf("Fourth"), rows.newNames())
        } finally {
            release.countDown()
            sync.join()
        }
    }
}
