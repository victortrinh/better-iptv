package dev.betteriptv.core

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The Title each screen's hero features (#348, #475), seen through the Library over the fake
 * Provider: from Trending (#477), else New on your service, with the Provider's backdrop, skipping what the Viewer is in,
 * a different one at each entry, and at most one Provider details call per entry.
 */
class FeaturedTest {
    @get:Rule val folder = TemporaryFolder()
    private val provider = FakeProvider()
    private val cloud = FakeCloud()
    private val credentials = InMemoryCredentials()
    private lateinit var library: Library

    private val english = listOf("en")
    private val fullHdTv = DeviceProfile(maxHeight = 1080, hdr = emptySet())

    @Before fun logIn() = runBlocking {
        library = open()
        library.logIn(provider.login)
        Unit
    }

    @After fun tearDown() {
        library.close()
        provider.close()
        cloud.close()
    }

    private fun open() = Library.open(folder.root.resolve("catalog.db").path, credentials, cloud.url)

    private fun movie(titleNumber: Long) = TitleId.Numbered(TitleKind.Movie, titleNumber)
    private fun show(n: Long) = TitleId.Numbered(TitleKind.Show, 500 + n)

    private fun movieBackdrop(n: Long) = "https://art.example/film-$n.jpg"
    private fun showBackdrop(n: Long) = "https://art.example/show-$n.jpg"

    /**
     * Syncs movies "Film [n]" (Title number [n], stream id = its place from 1, newest first) and
     * shows "Show [n]" (Title number 500 + [n]), each in English unless [french]. A movie's details
     * give a backdrop unless it is in [bare]; a show's Listing carries two unless it is in [bare].
     */
    private suspend fun sync(movies: List<Long>, shows: List<Long> = emptyList(), bare: Set<Long> = emptySet(), french: Set<Long> = emptySet()) {
        provider.movieCategoriesOverride = if (movies.isEmpty()) "[]" else """[{"category_id":"1","category_name":"MOVIES"}]"""
        provider.movieListOverride = movies.withIndex().joinToString(",", "[", "]") { (i, n) ->
            val prefix = if (n in french) "FR" else "EN"
            """{"stream_id":${i + 1},"name":"$prefix - Film $n (2025)","tmdb":"$n","category_id":"1","added":"${1_700_000_000 - i}","stream_icon":"https://art.example/poster-$n.jpg","container_extension":"mkv"}"""
        }
        movies.withIndex().forEach { (i, n) ->
            provider.serveMovieDetails(
                (i + 1).toLong(), name = "Film $n", plot = "About Film $n.", genre = "Drama", durationMinutes = 100,
                backdrop = movieBackdrop(n).takeUnless { n in bare },
            )
        }
        provider.showCategoriesOverride = if (shows.isEmpty()) "[]" else """[{"category_id":"2","category_name":"SERIES"}]"""
        provider.showListOverride = shows.withIndex().joinToString(",", "[", "]") { (i, n) ->
            val backdrops = if (n + 100 in bare) "[]" else """["${showBackdrop(n)}","https://art.example/show-$n-second.jpg"]"""
            """{"series_id":${i + 1},"name":"EN - Show $n (2025)","tmdb":"${500 + n}","category_id":"2","last_modified":"${1_700_000_000 - i}","plot":"About Show $n.","backdrop_path":$backdrops}"""
        }
        library.sync()
    }

    private suspend fun featured(screen: FeaturedScreen = FeaturedScreen.Home) = library.featured(screen, english, "en")?.name

    /** The names [screen] features at its next [entries] entries, each costing the Provider at most one details call. */
    private suspend fun entries(entries: Int, screen: FeaturedScreen = FeaturedScreen.Home) = List(entries) {
        val before = provider.movieDetailsRequests.size
        featured(screen).also { assertTrue("details calls in one entry", provider.movieDetailsRequests.size - before <= 1) }
    }

    @Test fun `Home features New on your service's Titles in turn, a different one at each entry, the sixth repeating the first`() = runBlocking {
        sync(movies = (1L..7L).toList())

        assertEquals(listOf("Film 1", "Film 2", "Film 3", "Film 4", "Film 5", "Film 1"), entries(6))
    }

    /** Puts [ids] in Trending, most viewed first: each one's English Wikipedia article in its Title facts, and the article in the cloud's feed. Before the Sync. */
    private fun trending(vararg ids: TitleId.Numbered) {
        ids.forEach { cloud.titleFacts(it, originalLanguage = "en", enwiki = "Article $it") }
        cloud.trending("en", "2026-11-06", *ids.mapIndexed { i, id -> "Article $id" to 1_000L - i }.toTypedArray())
    }

    @Test fun `the hero features Trending's Titles first, in turn, by views`() = runBlocking {
        trending(movie(6), movie(4), show(2))
        sync(movies = (1L..7L).toList(), shows = listOf(1, 2))

        assertEquals(listOf("Film 6", "Show 2", "Film 4", "Film 6"), entries(4))
        assertEquals(listOf("Film 6", "Film 4"), entries(2, FeaturedScreen.Movies))
        assertEquals("Show 2", featured(FeaturedScreen.Shows))
    }

    @Test fun `with no Trending Title that qualifies, the hero falls back to New on your service`() = runBlocking {
        // Film 6 is in Continue watching, Film 7 has no backdrop, Film 5 isn't in a Content language.
        trending(movie(6), movie(7), movie(5))
        sync(movies = (1L..7L).toList(), bare = setOf(7), french = setOf(5))
        library.saveProgress(movie(6), position = 30 * 60_000L, duration = 120 * 60_000L)
        library.title(movie(7), english, "en")

        assertEquals(listOf("Film 1", "Film 2"), entries(2))
    }

    @Test fun `once a featured Movie's details are kept, entering again costs the Provider no call`() = runBlocking {
        sync(movies = (1L..3L).toList())
        entries(3)
        assertEquals(listOf(1L, 2L, 3L), provider.movieDetailsRequests)

        assertEquals(listOf("Film 1", "Film 2", "Film 3"), entries(3))
        assertEquals(3, provider.movieDetailsRequests.size)
    }

    @Test fun `Home takes movies and shows in turn, a movie first`() = runBlocking {
        sync(movies = listOf(1, 2), shows = listOf(1, 2))

        assertEquals(listOf("Film 1", "Show 1", "Film 2", "Show 2", "Film 1"), entries(5))
    }

    @Test fun `a Show's backdrop is the first on its Listing, and featuring it costs no details call`() = runBlocking {
        sync(movies = emptyList(), shows = listOf(1, 2))

        val featured = library.featured(FeaturedScreen.Shows, english, "en")!!
        assertEquals(showBackdrop(1), featured.backdrop)
        assertEquals("About Show 1.", featured.description)
        assertTrue(provider.requests.none { it.startsWith("get_vod_info") || it.startsWith("get_series_info") })
    }

    @Test fun `the featured Title carries what the hero shows, from the Provider's details`() = runBlocking {
        sync(movies = listOf(1, 2, 3))

        val featured = library.featured(FeaturedScreen.Home, english, "en")!!
        assertEquals(movie(1), featured.id)
        assertEquals("Film 1", featured.name)
        assertEquals(2025, featured.year)
        assertEquals(100, featured.runtimeMinutes)
        assertEquals(listOf("Drama"), featured.genres)
        assertEquals("About Film 1.", featured.description)
        assertEquals(movieBackdrop(1), featured.backdrop)
        assertEquals("en", featured.best.language?.value)
    }

    @Test fun `it skips Titles in Continue watching, in no Content language, and those without a backdrop`() = runBlocking {
        sync(movies = (1L..5L).toList(), bare = setOf(2), french = setOf(4))
        library.saveProgress(movie(3), position = 30 * 60_000L, duration = 120 * 60_000L)
        // Film 2's page was opened: its details are kept, with no backdrop.
        library.title(movie(2), english, "en")

        assertEquals(listOf("Film 1", "Film 5", "Film 1"), entries(3))
    }

    @Test fun `a Movie read with no backdrop is passed over, and the entry reads no other`() = runBlocking {
        sync(movies = listOf(1, 2), bare = setOf(1))

        // Film 1's details give no backdrop, and Film 2's aren't read on the same entry.
        assertNull(featured())
        assertEquals(listOf(1L), provider.movieDetailsRequests)
        assertEquals(listOf("Film 2", "Film 2"), entries(2))
        assertEquals(listOf(1L, 2L), provider.movieDetailsRequests)
    }

    @Test fun `without reading details, only Titles whose backdrop is kept are featured`() = runBlocking {
        sync(movies = listOf(1, 2), shows = listOf(1))

        assertEquals("Show 1", library.featured(FeaturedScreen.Home, english, "en", readDetails = false)?.name)
        assertTrue(provider.movieDetailsRequests.isEmpty())
    }

    @Test fun `a backdrop the app refused is none`() = runBlocking {
        sync(movies = listOf(1, 2))
        library.title(movie(1), english, "en")
        library.refuseBackdrop(movieBackdrop(1))

        assertEquals(listOf("Film 2", "Film 2"), entries(2))
        assertNull(library.title(movie(1), english, "en")!!.backdrop)

        // Remembered on the device.
        library.close()
        library = open()
        assertNull(library.title(movie(1), english, "en")!!.backdrop)
    }

    @Test fun `Movies and Shows feature their own kind, each rotating on its own`() = runBlocking {
        sync(movies = listOf(1, 2, 3), shows = listOf(1, 2, 3))

        assertEquals(listOf("Film 1", "Film 2"), entries(2, FeaturedScreen.Movies))
        assertEquals(listOf("Show 1", "Show 2"), entries(2, FeaturedScreen.Shows))
        assertEquals("Film 3", featured(FeaturedScreen.Movies))
        assertEquals("Film 1", featured(FeaturedScreen.Home))
    }

    @Test fun `the rotation goes on where it was when the app starts again`() = runBlocking {
        sync(movies = listOf(1, 2, 3))
        assertEquals(listOf("Film 1", "Film 2"), entries(2))

        library.close()
        library = open()

        assertEquals("Film 3", featured())
    }

    @Test fun `with no Title that qualifies, there is no featured Title`() = runBlocking {
        sync(movies = listOf(1), shows = listOf(1), bare = setOf(1, 101))
        assertNull(featured())
        assertNull(featured())
    }

    @Test fun `Play starts what the Title page's Play would`() = runBlocking {
        sync(movies = listOf(1, 2, 3))
        library.saveProgress(movie(1), position = 30 * 60_000L, duration = 120 * 60_000L)

        assertEquals(library.playPlan(movie(1), english, fullHdTv), library.titlePlayPlan(movie(1), english, "en", fullHdTv))
        assertEquals(30 * 60_000L, library.titlePlayPlan(movie(1), english, "en", fullHdTv)!!.from)
    }

    @Test fun `a Show's Play starts its Episode to watch next`() = runBlocking {
        provider.showDetailsOverride[1] = """{"seasons":[],"info":{},"episodes":[${listOf(1, 2).joinToString(",") { n ->
            """{"id":"10$n","episode_num":$n,"season":1,"title":"EN - Show 1 (2025) - S01E0$n","container_extension":"mkv","added":"1700000000","info":{"video":{"width":1920,"height":1080}}}"""
        }}]}"""
        sync(movies = emptyList(), shows = listOf(1))
        val show = show(1)
        // Episodes play from the show details read when the Show's details opened.
        library.episodes(show, english)
        library.saveProgress(library.episodePlayPlan(show, EpisodeNumber(1, 1), english, fullHdTv)!!, position = 49 * 60_000L, duration = 50 * 60_000L)

        val plan = library.titlePlayPlan(show, english, "en", fullHdTv)!!
        assertEquals(EpisodeNumber(1, 2), plan.episode)
        assertEquals(library.episodePlayPlan(show, EpisodeNumber(1, 2), english, fullHdTv), plan)
    }
}
