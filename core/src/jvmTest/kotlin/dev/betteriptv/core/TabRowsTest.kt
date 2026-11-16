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

/** The Movies and Shows tabs' rows, seen through the Library after a Sync, with metadata and Trending from the fake Cloud. */
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

    private fun movie(titleNumber: Long) = TitleId.Numbered(TitleKind.Movie, titleNumber)

    private fun show(titleNumber: Long) = TitleId.Numbered(TitleKind.Show, titleNumber)

    /**
     * Syncs these movie Listings, newest first, numbered from 1, each a Title of its own Title number,
     * filed under `MOVIES` unless [filed] names another Category for that Title number.
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

    /** Serves these show Listings at the next Sync, newest first, numbered from 1, each a Title of its own Title number, with the genre field [genres] gives its Title number. */
    private fun serveShows(vararg listings: Pair<String, Long>, genres: Map<Long, String> = emptyMap()) {
        provider.showCategoriesOverride = """[{"category_id":"2","category_name":"SERIES"}]"""
        provider.showListOverride = listings.withIndex().joinToString(",", "[", "]") { (i, listing) ->
            val genre = genres[listing.second]?.let { ""","genre":"$it"""" }.orEmpty()
            """{"series_id":${i + 1},"name":"${listing.first}","tmdb":"${listing.second}","category_id":"2","last_modified":"${1_700_000_000 - i}"$genre}"""
        }
    }

    /** Files each of [ids] under the Category [name], for [syncMovies]. */
    private fun under(name: String, ids: Iterable<Long>) = ids.associateWith { name }

    private suspend fun rows(kind: TitleKind = TitleKind.Movie, contentLanguages: List<String> = english) =
        library.tabRows(kind, contentLanguages, appLanguage = "en", now = Instant.now())

    /**
     * Puts [ids] in Trending, most viewed first: each one's English Wikipedia article in its Title
     * facts, and the article in the cloud's feed. Called before the Sync, which asks for the facts.
     */
    private fun trending(vararg ids: TitleId.Numbered) {
        ids.forEach { cloud.titleFacts(it, originalLanguage = "en", enwiki = article(it)) }
        cloud.trending("en", "2026-11-06", *ids.mapIndexed { i, id -> article(id) to 1_000L - i }.toTypedArray())
    }

    private fun article(id: TitleId.Numbered) = "Article ${id.kind} ${id.titleNumber}"

    private fun List<HomeRow>.trending() = filterIsInstance<HomeRow.Trending>().singleOrNull()

    private fun List<HomeRow>.trendingNames() = trending()?.titles?.map { it.name }

    private fun List<HomeRow>.new() = filterIsInstance<HomeRow.NewOnYourService>().single()

    private fun List<HomeRow>.newNames() = new().titles.map { it.name }

    private fun List<HomeRow>.names() = map {
        when (it) {
            is HomeRow.Trending -> "Trending ${it.kind}"
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
        trending(movie(1), movie(2), movie(3), movie(4))
        syncOlderMovies(
            *numbered("Laugh", 1L..10L), *numbered("Fright", 11L..14L), *numbered("Other", 21L..22L),
            // Filler 1 is on New movies; Laugh 5 to 7 are Comedy and Horror, Comedy the bigger genre, so Horror is left with 4.
            filed = under("EN - COMEDY", (1L..4L) + (8L..10L) + 9001L) + under("EN - COMEDY HORROR", 5L..7L) + under("EN - HORROR", 11L..14L),
        )

        val rows = rows()
        assertTrue("Filler 1" in rows.newNames())
        assertEquals(listOf("Trending Movie", "New Movie", "Comedy"), rows.names())
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

    @Test fun `the Movies tab shows Trending movies, then New movies, then genres`() = runBlocking {
        serveShows("EN - Show 1 (2025)" to 501, "EN - Show 2 (2025)" to 502, "EN - Show 3 (2025)" to 503)
        trending(show(501), movie(4), show(502), movie(5), movie(6), show(503))
        syncOlderMovies(*(1L..9L).map { "EN - Film $it (2025)" to it }.toTypedArray(), filed = under("EN - COMEDY", 1L..9L))

        val rows = rows(TitleKind.Movie)
        assertEquals(listOf("Trending Movie", "New Movie", "Comedy"), rows.names())
        // Trending movies only: the shows trending with them go to the Shows tab.
        assertEquals(listOf("Film 4", "Film 5", "Film 6"), rows.trendingNames())
        assertEquals(TitleKind.Movie, rows.trending()!!.kind)
    }

    @Test fun `the Shows tab mirrors it with shows`() = runBlocking {
        serveShows(*(1L..9L).map { "EN - Show $it (2025)" to 500 + it }.toTypedArray())
        trending(movie(1), show(504), movie(2), show(505), show(506), movie(3))
        syncMovies("EN - Film 1 (2025)" to 1, "EN - Film 2 (2025)" to 2, "EN - Film 3 (2025)" to 3)

        val rows = rows(TitleKind.Show)
        assertEquals(listOf("Trending Show", "New Show"), rows.names())
        assertEquals(listOf("Show 4", "Show 5", "Show 6"), rows.trendingNames())
        assertEquals((1..9).filter { it !in 4..6 }.map { "Show $it" }.toSet(), rows.newNames().toSet())
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

    @Test fun `a tab's Trending of fewer than 3 cards is left out, and its Titles go to New`() = runBlocking {
        trending(movie(1), movie(2))
        syncMovies("EN - First (2025)" to 1, "EN - Second (2025)" to 2)

        val rows = rows()
        assertEquals(listOf("New Movie"), rows.names())
        assertEquals(setOf("First", "Second"), rows.newNames().toSet())
    }

    @Test fun `a tab shows each Title once, New skipping what Trending shows`() = runBlocking {
        trending(movie(4), movie(5), movie(6))
        syncMovies(*(1L..6L).map { "EN - Film $it (2025)" to it }.toTypedArray())

        val rows = rows()
        assertEquals(listOf("Film 4", "Film 5", "Film 6"), rows.trendingNames())
        assertEquals(setOf("Film 1", "Film 2", "Film 3"), rows.newNames().toSet())
    }

    @Test fun `a Title in Home's Trending also shows in the Movies tab's, each screen showing a Title once on its own`() = runBlocking {
        trending(movie(1), movie(2), movie(3))
        syncMovies(*(1L..4L).map { "EN - Film $it (2025)" to it }.toTypedArray())

        val home = library.homeRows(english, "en", "CA")
        val movies = rows()
        assertEquals(listOf("Film 1", "Film 2", "Film 3"), home.filterIsInstance<HomeRow.Trending>().single().titles.map { it.name })
        assertEquals(listOf("Film 1", "Film 2", "Film 3"), movies.trendingNames())
        assertEquals(listOf("Film 4"), movies.newNames())
    }

    @Test fun `without the cloud and no feed kept, a tab still has New`() = runBlocking {
        trending(movie(1), movie(2), movie(3))
        syncMovies("EN - First (2025)" to 1, "EN - Second (2025)" to 2, "EN - Third (2025)" to 3)
        cloud.down = true

        val rows = rows()
        assertEquals(listOf("New Movie"), rows.names())
    }

    @Test fun `a tab reads what's stored while a Sync downloads, though its Trending feed is asked again`() = runBlocking {
        trending(movie(1), movie(2), movie(3))
        syncMovies("EN - First (2025)" to 1, "EN - Second (2025)" to 2, "EN - Third (2025)" to 3, "EN - Fourth (2025)" to 4)
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
            // Over an hour later, so the kept feed is asked again.
            val rows = withTimeout(5_000) { library.tabRows(TitleKind.Movie, english, "en", Instant.now().plusSeconds(2 * 3600)) }
            assertEquals(listOf("en", "en"), cloud.trendingAsked)
            assertEquals(listOf("First", "Second", "Third"), rows.trendingNames())
            assertEquals(listOf("Fourth"), rows.newNames())
        } finally {
            release.countDown()
            sync.join()
        }
    }
}
