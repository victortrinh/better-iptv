package dev.betteriptv.core

import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Duration
import java.time.Instant

/** The home screen's rows, seen through the Library after a Sync, with metadata from the fake Cloud. */
class HomeRowsTest {
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

    /** Syncs these movie Listings, newest first, numbered from 1, each a Title of its own TMDB ID. */
    private suspend fun syncMovies(vararg listings: Pair<String, Long>) {
        provider.movieCategoriesOverride = """[{"category_id":"1","category_name":"MOVIES"}]"""
        provider.movieListOverride = listings.withIndex().joinToString(",", "[", "]") { (i, listing) ->
            """{"stream_id":${i + 1},"name":"${listing.first}","tmdb":"${listing.second}","category_id":"1","added":"${1_700_000_000 - i}"}"""
        }
        library.sync()
    }

    /** Serves a Title [tmdbId] named [name], released in [originalLanguage]. */
    private fun tmdb(tmdbId: Long, name: String, popularity: Double = 10.0, originalLanguage: String = "en", genres: List<String> = emptyList()) =
        cloud.title("en", tmdbTitle(tmdbId, name, originalLanguage = originalLanguage, genres = genres, popularity = popularity, backdropPath = "/still-$tmdbId.jpg"))

    private suspend fun rows(contentLanguages: List<String> = english, region: String = "CA", now: Instant = Instant.now()) =
        library.homeRows(contentLanguages, appLanguage = "en", region = region, now = now)

    /** Serves these show Listings at the next Sync, numbered from 1, each a Title of its own TMDB ID. */
    private fun serveShows(vararg listings: Pair<String, Long>) {
        provider.showCategoriesOverride = """[{"category_id":"2","category_name":"SERIES"}]"""
        provider.showListOverride = listings.withIndex().joinToString(",", "[", "]") { (i, listing) ->
            """{"series_id":${i + 1},"name":"${listing.first}","tmdb":"${listing.second}","category_id":"2","last_modified":"${1_700_000_000 - i}"}"""
        }
    }

    private fun List<HomeRow>.top(list: ChartList) = filterIsInstance<HomeRow.Chart>().singleOrNull { it.list == list }

    private fun List<HomeRow>.topNames(list: ChartList) = top(list)?.titles?.map { it.name }

    private fun List<HomeRow>.new() = filterIsInstance<HomeRow.NewOnYourService>().single().titles.map { it.name }

    private fun List<HomeRow>.genre(name: String) = filterIsInstance<HomeRow.Genre>().single { it.name == name }.titles.map { it.name }

    @Test fun `New on your service puts the most popular new Titles first`() = runBlocking {
        syncMovies("EN - Quiet (2025)" to 1, "EN - Hit (2025)" to 2, "EN - Middling (2025)" to 3)
        tmdb(1, "Quiet", popularity = 2.0)
        tmdb(2, "Hit", popularity = 90.0)
        tmdb(3, "Middling", popularity = 30.0)

        assertEquals(listOf("Hit", "Middling", "Quiet"), rows().new())
    }

    @Test fun `browse rows show only Titles in a Content language, and Untagged ones Likely in one`() = runBlocking {
        syncMovies(
            "EN - English (2025)" to 1,
            "FR - Français (2025)" to 2,
            "4K - Untagged English (2025)" to 3,
            "4K - Untagged French (2025)" to 4,
            "FR - Both (2025)" to 5,
            "EN - Both (2025)" to 5,
        )
        tmdb(1, "English")
        tmdb(2, "Français", originalLanguage = "fr")
        tmdb(3, "Untagged English")
        tmdb(4, "Untagged French", originalLanguage = "fr")
        tmdb(5, "Both", originalLanguage = "fr")

        assertEquals(setOf("English", "Untagged English", "Both"), rows(english).new().toSet())
        assertEquals(setOf("Français", "Untagged French", "Both"), rows(listOf("fr-CA")).new().toSet())
    }

    /** The 40 newest movies New on your service is picked from, so the Titles synced after them are left to the genre rows. */
    private fun newest() = (1L..40L).map { "EN - New $it (2025)" to 1000 + it }

    @Test fun `genre rows hold the Titles of each TMDB genre, most popular first`() = runBlocking {
        syncMovies(*(newest() + (1L..6L).map { "EN - Film $it (2025)" to it }).toTypedArray())
        (1L..6L).forEach { tmdb(it, "Film $it", popularity = it.toDouble(), genres = if (it == 6L) listOf("Drama", "Comedy") else listOf("Drama")) }

        val rows = rows()
        assertEquals((6 downTo 1).map { "Film $it" }, rows.genre("Drama"))
        // One comedy isn't a row.
        assertTrue(rows.none { it is HomeRow.Genre && it.name == "Comedy" })
    }

    @Test fun `Continue watching shows what the Viewer stopped in, last played first, until they finish it`() = runBlocking {
        syncMovies("EN - First (2025)" to 1, "EN - Second (2025)" to 2, "FR - Foreign (2025)" to 3)
        tmdb(1, "First")
        tmdb(2, "Second")
        tmdb(3, "Foreign", originalLanguage = "fr")
        library.saveProgress(movie(1), position = 30 * 60_000L, duration = 120 * 60_000L)
        Thread.sleep(5)
        library.saveProgress(movie(3), position = 10 * 60_000L, duration = null)
        Thread.sleep(5)
        library.saveProgress(movie(2), position = 60 * 60_000L, duration = 120 * 60_000L)

        var resumes = rows().filterIsInstance<HomeRow.ContinueWatching>().single().titles
        // What the Viewer started shows whatever its language.
        assertEquals(listOf("Second", "Foreign", "First"), resumes.map { it.title.name })
        assertEquals(listOf(0.5f, null, 0.25f), resumes.map { it.progress })
        assertEquals(listOf(Duration.ofMinutes(60), null, Duration.ofMinutes(90)), resumes.map { it.left })
        assertEquals("https://image.tmdb.org/t/p/w1280/still-2.jpg", resumes.first().still)
        assertTrue(rows().first() is HomeRow.ContinueWatching)

        library.finished(movie(2))
        resumes = rows().filterIsInstance<HomeRow.ContinueWatching>().single().titles
        assertEquals(listOf("Foreign", "First"), resumes.map { it.title.name })
    }

    @Test fun `a card carries what the info area under it shows`() = runBlocking {
        syncMovies("EN - Described (2025)" to 1, "EN - Unknown (2025)" to 2)
        cloud.title(
            "en",
            tmdbTitle(1, "Described", descriptions = listOf(Triple("en", "", "A film.")), genres = listOf("Drama"), runtimeMinutes = 95),
        )

        val cards = rows().filterIsInstance<HomeRow.NewOnYourService>().single().titles.associateBy { it.name }
        assertEquals(TitleAbout("A film.", listOf("Drama"), 95), cards.getValue("Described").about)
        // Without TMDB metadata there is nothing to tell.
        assertEquals(null, cards.getValue("Unknown").about)
    }

    @Test fun `without anything started there's no Continue watching row`() = runBlocking {
        syncMovies("EN - First (2025)" to 1)
        tmdb(1, "First")

        assertTrue(rows().none { it is HomeRow.ContinueWatching })
    }

    @Test fun `a Title in New on your service doesn't show again in a genre row`() = runBlocking {
        syncMovies(*(newest() + (1L..5L).map { "EN - Old $it (2025)" to it }).toTypedArray())
        tmdb(1001, "New 1", genres = listOf("Drama"))
        (1L..5L).forEach { tmdb(it, "Old $it", popularity = it.toDouble(), genres = listOf("Drama")) }

        val rows = rows()
        assertTrue("New 1" in rows.new())
        assertEquals((5 downTo 1).map { "Old $it" }, rows.genre("Drama"))
    }

    @Test fun `a Title in several genres shows only in the highest genre row`() = runBlocking {
        // Drama has 7 Titles and Comedy 6, Film 1 in both: Drama is the higher row.
        syncMovies(*(newest() + (1L..12L).map { "EN - Film $it (2025)" to it }).toTypedArray())
        (1L..12L).forEach {
            val genres = when {
                it == 1L -> listOf("Comedy", "Drama")
                it <= 7L -> listOf("Drama")
                else -> listOf("Comedy")
            }
            tmdb(it, "Film $it", popularity = it.toDouble(), genres = genres)
        }

        val rows = rows()
        assertEquals(listOf("Drama", "Comedy"), rows.filterIsInstance<HomeRow.Genre>().map { it.name })
        assertTrue("Film 1" in rows.genre("Drama"))
        assertEquals((12 downTo 8).map { "Film $it" }, rows.genre("Comedy"))
    }

    @Test fun `a Title in Continue watching still shows in the rows below it`() = runBlocking {
        syncMovies(*(newest() + (1L..5L).map { "EN - Old $it (2025)" to it }).toTypedArray())
        tmdb(1001, "New 1")
        (1L..5L).forEach { tmdb(it, "Old $it", genres = listOf("Drama")) }
        library.saveProgress(movie(1001), position = 30 * 60_000L, duration = 120 * 60_000L)
        library.saveProgress(movie(1), position = 30 * 60_000L, duration = 120 * 60_000L)

        val rows = rows()
        assertEquals(setOf("New 1", "Old 1"), rows.filterIsInstance<HomeRow.ContinueWatching>().single().titles.map { it.title.name }.toSet())
        assertTrue("New 1" in rows.new())
        assertTrue("Old 1" in rows.genre("Drama"))
    }

    @Test fun `a genre row left under 5 Titles is left out, and the next genre takes its place`() = runBlocking {
        // Drama has 7 Titles; Thriller 5, one of them a Drama; then 7 genres of 5. Eight genre rows fit.
        val others = (1..7).map { "Genre $it" }
        val films = (1L..7L).map { it to if (it == 1L) listOf("Drama", "Thriller") else listOf("Drama") } +
            (8L..11L).map { it to listOf("Thriller") } +
            others.withIndex().flatMap { (i, genre) -> (1..5).map { n -> 12L + i * 5 + n - 1 to listOf(genre) } }
        syncMovies(*(newest() + films.map { (id, _) -> "EN - Film $id (2025)" to id }).toTypedArray())
        films.forEach { (id, genres) -> tmdb(id, "Film $id", genres = genres) }

        assertEquals(listOf("Drama") + others, rows().filterIsInstance<HomeRow.Genre>().map { it.name })
    }

    // Charts

    @Test fun `Home's rows go Continue watching, Top movies, Top shows, New, Trending, Favourites, then genres`() = runBlocking {
        provider.serveChannels("US: ESPN HD" to "US| SPORT")
        serveShows("EN - Show 1 (2025)" to 501, "EN - Show 2 (2025)" to 502, "EN - Show 3 (2025)" to 503)
        syncMovies(*(newest() + (1L..8L).map { "EN - Film $it (2025)" to it }).toTypedArray())
        (1L..8L).forEach { tmdb(it, "Film $it", genres = listOf("Drama")) }
        cloud.charts("CA", movies = listOf(1001, 1002, 1003), shows = listOf(501, 502, 503))
        cloud.trending(movie(6), movie(7), movie(8))
        library.setFavourite(library.search("ESPN", english, "en").last().channels.single().id, true)
        library.saveProgress(movie(1), position = 30 * 60_000L, duration = 120 * 60_000L)

        val rows = rows()
        assertEquals(
            listOf("continue", "top movies", "top shows", "new", "trending", "favourites", "genre"),
            rows.map {
                when (it) {
                    is HomeRow.ContinueWatching -> "continue"
                    is HomeRow.Chart -> when (it.list) {
                        ChartList.TopMovies -> "top movies"
                        ChartList.TopShows -> "top shows"
                        ChartList.Trending -> "trending"
                    }
                    is HomeRow.NewOnYourService -> "new"
                    is HomeRow.Favourites -> "favourites"
                    is HomeRow.Genre -> "genre"
                }
            },
        )
        assertEquals(listOf("Show 1", "Show 2", "Show 3"), rows.topNames(ChartList.TopShows))
    }

    @Test fun `a Top row keeps the Provider's Titles in a Content language, in rank order without gaps`() = runBlocking {
        syncMovies("EN - Fourth (2025)" to 4, "EN - First (2025)" to 1, "FR - Second (2025)" to 2, "EN - Fifth (2025)" to 5)
        tmdb(1, "First")
        tmdb(2, "Second", originalLanguage = "fr")
        tmdb(4, "Fourth")
        tmdb(5, "Fifth")
        // Netflix's #2 isn't on the Provider; #3 has no Version in English.
        cloud.charts("CA", movies = listOf(1, 99, 2, 4, 5))

        val top = rows().top(ChartList.TopMovies)!!
        assertEquals(listOf("First", "Fourth", "Fifth"), top.titles.map { it.name })
        assertEquals("CA", top.region)
    }

    @Test fun `a Top row of fewer than 3 cards is left out`() = runBlocking {
        syncMovies("EN - First (2025)" to 1, "EN - Second (2025)" to 2)
        cloud.charts("CA", movies = listOf(1, 2, 3))

        val rows = rows()
        assertEquals(null, rows.top(ChartList.TopMovies))
        // Its Titles stay in the rows below.
        assertEquals(setOf("First", "Second"), rows.new().toSet())
    }

    @Test fun `the worldwide Top 10 says it is the world's`() = runBlocking {
        syncMovies("EN - First (2025)" to 1, "EN - Second (2025)" to 2, "EN - Third (2025)" to 3)
        cloud.charts("CA", movies = listOf(1, 2, 3), worldwide = true)

        assertEquals(null, rows().top(ChartList.TopMovies)!!.region)
    }

    @Test fun `the Charts are the Region's`() = runBlocking {
        syncMovies("EN - First (2025)" to 1, "EN - Second (2025)" to 2, "EN - Third (2025)" to 3)
        cloud.charts("CA", movies = listOf(1, 2, 3))
        cloud.charts("FR", movies = listOf(3, 2, 1))

        assertEquals(listOf("First", "Second", "Third"), rows(region = "CA").topNames(ChartList.TopMovies))
        assertEquals(listOf("Third", "Second", "First"), rows(region = "FR").topNames(ChartList.TopMovies))
        assertEquals("FR", rows(region = "FR").top(ChartList.TopMovies)!!.region)
    }

    @Test fun `a Chart keeps all its Titles, the rows below skip them, and Continue watching keeps its own`() = runBlocking {
        syncMovies(*newest().toTypedArray())
        cloud.charts("CA", movies = listOf(1001, 1002, 1003))
        library.saveProgress(movie(1001), position = 30 * 60_000L, duration = 120 * 60_000L)

        val rows = rows()
        assertEquals(listOf("New 1", "New 2", "New 3"), rows.topNames(ChartList.TopMovies))
        assertTrue(rows.new().none { it in setOf("New 1", "New 2", "New 3") })
        assertEquals(listOf("New 1"), rows.filterIsInstance<HomeRow.ContinueWatching>().single().titles.map { it.title.name })
    }

    @Test fun `the Charts are kept, asked for again after an hour, and the kept ones used while the cloud fails`() = runBlocking {
        syncMovies("EN - First (2025)" to 1, "EN - Second (2025)" to 2, "EN - Third (2025)" to 3, "EN - Fourth (2025)" to 4)
        cloud.charts("CA", movies = listOf(1, 2, 3))
        rows()
        rows()
        assertEquals(listOf("CA"), cloud.chartsAsked)

        cloud.down = true
        val later = rows(now = Instant.now() + Duration.ofHours(2))
        assertEquals(listOf("CA", "CA"), cloud.chartsAsked)
        assertEquals(listOf("First", "Second", "Third"), later.topNames(ChartList.TopMovies))
        assertEquals(listOf("Fourth"), later.new())
    }

    @Test fun `without the cloud and no Charts kept, Home has every other row`() = runBlocking {
        syncMovies("EN - First (2025)" to 1, "EN - Second (2025)" to 2, "EN - Third (2025)" to 3)
        cloud.charts("CA", movies = listOf(1, 2, 3))
        cloud.down = true

        val rows = rows()
        assertTrue(rows.none { it is HomeRow.Chart })
        assertEquals(setOf("First", "Second", "Third"), rows.new().toSet())
    }

    @Test fun `Trending this week mixes movies and shows the Provider has in a Content language, skipping what's shown above`() = runBlocking {
        serveShows("EN - Show 1 (2025)" to 501, "FR - Série (2025)" to 502)
        syncMovies(*(newest() + (1L..4L).map { "EN - Film $it (2025)" to it }).toTypedArray())
        tmdb(1, "Film 1")
        cloud.title("en", tmdbTitle(502, "Série", kind = "show", originalLanguage = "fr"))
        cloud.charts("CA", movies = listOf(2, 3, 4))
        // New 1 shows in New on your service, Film 2 in Top movies; 99 isn't on the Provider; the Série isn't in English.
        cloud.trending(movie(1), TitleId.Tmdb(TitleKind.Show, 501), movie(1001), movie(2), movie(99), TitleId.Tmdb(TitleKind.Show, 502), movie(1040))

        val rows = rows()
        assertEquals(listOf("Film 1", "Show 1"), rows.topNames(ChartList.Trending)?.take(2))
        assertTrue(rows.top(ChartList.Trending)!!.titles.none { it.name in setOf("New 1", "Film 2", "Série") })
    }

    @Test fun `Trending with fewer than 3 cards left is left out`() = runBlocking {
        syncMovies("EN - First (2025)" to 1, "EN - Second (2025)" to 2)
        cloud.trending(movie(1), movie(2))

        assertEquals(null, rows().top(ChartList.Trending))
    }

    @Test fun `a Title's details carry its Top 10 rank, as its Top row numbers it`() = runBlocking {
        serveShows("EN - Show 1 (2025)" to 501, "EN - Show 2 (2025)" to 502, "EN - Show 3 (2025)" to 503)
        syncMovies("EN - First (2025)" to 1)
        cloud.charts("CA", movies = listOf(1), shows = listOf(599, 503, 502, 501))

        val show = TitleId.Tmdb(TitleKind.Show, 502)
        assertEquals(ChartRank(2, ChartList.TopShows, "CA"), library.title(show, english, "en", region = "CA")!!.rank)
        assertEquals(listOf("Show 3", "Show 2", "Show 1"), rows().topNames(ChartList.TopShows))
        assertEquals(null, library.title(movie(1), english, "en", region = "FR")!!.rank)
    }
}
