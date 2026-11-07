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
        provider.showCategoriesOverride = "[]"
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

    /**
     * Syncs these movie Listings, newest first, numbered from 1, each a Title of its own TMDB ID,
     * filed under `MOVIES` unless [filed] names another Category for that TMDB ID.
     */
    private suspend fun syncMovies(vararg listings: Pair<String, Long>, filed: Map<Long, String> = emptyMap()) {
        val categories = (listOf("MOVIES") + filed.values).distinct()
        provider.movieCategoriesOverride = categories.withIndex().joinToString(",", "[", "]") { (i, name) -> """{"category_id":"${i + 1}","category_name":"$name"}""" }
        provider.movieListOverride = listings.withIndex().joinToString(",", "[", "]") { (i, listing) ->
            val category = categories.indexOf(filed[listing.second] ?: "MOVIES") + 1
            """{"stream_id":${i + 1},"name":"${listing.first}","tmdb":"${listing.second}","category_id":"$category","added":"${1_700_000_000 - i}"}"""
        }
        library.sync()
    }

    /** Serves these show Listings at the next Sync, newest first, numbered from 1, each a Title of its own TMDB ID, with the genre field [genres] gives its TMDB ID. */
    private fun serveShows(vararg listings: Pair<String, Long>, genres: Map<Long, String> = emptyMap()) {
        provider.showCategoriesOverride = """[{"category_id":"2","category_name":"SERIES"}]"""
        provider.showListOverride = listings.withIndex().joinToString(",", "[", "]") { (i, listing) ->
            val genre = genres[listing.second]?.let { ""","genre":"$it"""" }.orEmpty()
            """{"series_id":${i + 1},"name":"${listing.first}","tmdb":"${listing.second}","category_id":"2","last_modified":"${1_700_000_000 - i}"$genre}"""
        }
    }

    /** Files each of [ids] under the Category [name], for [syncMovies]. */
    private fun under(name: String, ids: Iterable<Long>) = ids.associateWith { name }

    /** Serves TMDB's metadata of a movie [tmdbId] named [name]. */
    private fun tmdb(tmdbId: Long, name: String) = cloud.title("en", tmdbTitle(tmdbId, name))

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
    private suspend fun syncOlderMovies(vararg listings: Pair<String, Long>, filed: Map<Long, String> = emptyMap()) =
        syncMovies(*((1L..40L).map { "EN - Filler $it (2025)" to 9000 + it } + listings).toTypedArray(), filed = filed)

    private fun List<HomeRow>.genre(name: String) = filterIsInstance<HomeRow.Genre>().singleOrNull { it.name == name }?.titles?.map { it.name }

    private fun List<HomeRow>.genres() = filterIsInstance<HomeRow.Genre>().map { it.name }

    private fun numbered(name: String, ids: LongRange) = ids.mapIndexed { i, id -> "EN - $name ${i + 1} (2010)" to id }.toTypedArray()

    @Test fun `genre rows follow New, the genres with the most of the Provider's Titles first, each newest first`() = runBlocking {
        syncOlderMovies(
            *numbered("Laugh", 1L..6L), *numbered("Fright", 11L..15L), "FR - Frisson (2010)" to 16, *numbered("Tear", 21L..24L),
            filed = under("EN - COMEDY", 1L..6L) + under("EN - HORROR", 11L..15L) + under("FR - HORREUR", listOf(16L)) + under("EN - DRAMA", 21L..24L),
        )

        val rows = rows()
        // Horror has 6 Titles, one in French; Comedy 6 too, and the table lists it first; Drama's 4 make no row.
        assertEquals(listOf("New Movie", "Comedy", "Horror"), rows.names())
        assertEquals((1..5).map { "Fright $it" }, rows.genre("Horror"))
        assertEquals((1..6).map { "Laugh $it" }, rows.genre("Comedy"))
    }

    @Test fun `genre rows skip the Titles shown above them, and a genre left with fewer than 5 is left out`() = runBlocking {
        syncOlderMovies(
            *numbered("Laugh", 1L..10L), *numbered("Fright", 11L..14L), *numbered("Other", 21L..22L),
            // Filler 1 is on New movies; Laugh 5 to 7 are Comedy and Horror, Comedy the bigger genre, so Horror is left with 4.
            filed = under("EN - COMEDY", (1L..4L) + (8L..10L) + 9001L) + under("EN - COMEDY HORROR", 5L..7L) + under("EN - HORROR", 11L..14L),
        )
        cloud.charts("CA", movies = listOf(1, 2, 3))
        cloud.trending(movie(4), movie(21), movie(22))

        val rows = rows()
        assertTrue("Filler 1" in rows.newNames())
        assertEquals(listOf("TopMovies", "TrendingMovies", "New Movie", "Comedy"), rows.names())
        assertEquals((5..10).map { "Laugh $it" }, rows.genre("Comedy"))
    }

    @Test fun `an Untagged Title is in a genre row when its original language is a Content language`() = runBlocking {
        // Wikidata says which language each Untagged Title was released in; the device learns it at the Sync (#476).
        cloud.titleFacts(movie(5), originalLanguage = "en")
        cloud.titleFacts(movie(6), originalLanguage = "fr")
        syncOlderMovies("4K - Untagged French (2010)" to 6, "4K - Untagged English (2010)" to 5, *numbered("Laugh", 1L..4L), filed = under("EN - COMEDY", 1L..6L))

        assertEquals(listOf("Untagged English", "Laugh 1", "Laugh 2", "Laugh 3", "Laugh 4"), rows().genre("Comedy"))
    }

    @Test fun `the Shows tab has the show genres, from their Listings' genre field, the Movies tab the movie ones`() = runBlocking {
        serveShows(*((1L..40L).map { "EN - Show filler $it (2025)" to 9000 + it } + numbered("Saga", 1L..5L)).toTypedArray(), genres = (1L..5L).associateWith { "Drama" })
        syncMovies(*((1L..40L).map { "EN - Filler $it (2025)" to 9000 + it } + numbered("Laugh", 1L..5L)).toTypedArray(), filed = under("EN - COMEDY", 1L..5L))

        assertEquals(listOf("Comedy"), rows(TitleKind.Movie).genres())
        assertEquals(listOf("Drama"), rows(TitleKind.Show).genres())
        assertEquals((1..5).map { "Saga $it" }, rows(TitleKind.Show).genre("Drama"))
    }

    @Test fun `genre rows need no cloud`() = runBlocking {
        cloud.down = true
        syncOlderMovies(*numbered("Laugh", 1L..5L), filed = under("EN - COMEDY", 1L..5L))

        assertEquals((1..5).map { "Laugh $it" }, rows().genre("Comedy"))
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

    @Test fun `New movies lists the newest movies in a Content language first, two added the same second by the Provider's rating`() = runBlocking {
        provider.movieCategoriesOverride = """[{"category_id":"1","category_name":"MOVIES"}]"""
        provider.movieListOverride = """[
            {"stream_id":1,"name":"EN - Old (2020)","tmdb":"1","category_id":"1","added":"1600000000","rating":"9.9"},
            {"stream_id":2,"name":"EN - Tie low (2025)","tmdb":"2","category_id":"1","added":"1700000100","rating":"6.1"},
            {"stream_id":3,"name":"EN - Tie high (2025)","tmdb":"3","category_id":"1","added":"1700000100","rating":"8.5"},
            {"stream_id":4,"name":"FR - Français (2025)","tmdb":"4","category_id":"1","added":"1700000300"},
            {"stream_id":5,"name":"EN - Newest (2025)","tmdb":"5","category_id":"1","added":"1700000200"}
        ]"""
        library.sync()

        assertEquals(listOf("Newest", "Tie high", "Tie low", "Old"), rows().newNames())
    }

    @Test fun `a tab's Top row keeps the Provider's Titles in a Content language, ranked in order, in the Region`() = runBlocking {
        syncMovies("EN - Fourth (2025)" to 4, "EN - First (2025)" to 1, "FR - Second (2025)" to 2, "EN - Fifth (2025)" to 5)
        tmdb(1, "First")
        tmdb(2, "Second")
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
        // An answer from before textless backdrops is asked again on every read, so this one saves metadata each time.
        cloud.title("en", tmdbTitle(4, "Fourth") - "textlessBackdropPath")
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
