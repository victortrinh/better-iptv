package dev.betteriptv.core

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The Title each screen's hero features (#348), seen through the Library after a Sync, with the
 * Charts and metadata from the fake Cloud: from the top five of the screen's Charts, skipping what
 * the Viewer is in and what wouldn't look right, a different one at each entry.
 */
class FeaturedTest {
    @get:Rule val folder = TemporaryFolder()
    private val provider = FakeProvider()
    private val cloud = FakeCloud()
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

    private fun open() = Library.open(folder.root.resolve("catalog.db").path, InMemoryCredentials(), cloud.url)

    private fun movie(tmdbId: Long) = TitleId.Tmdb(TitleKind.Movie, tmdbId)

    /** Syncs movies "Film [n]" (TMDB [n]) and shows "Show [n]" (TMDB 500 + [n]), each served with a textless backdrop and a logo unless left [bare]. */
    private suspend fun sync(movies: List<Long>, shows: List<Long> = emptyList(), bare: Set<String> = emptySet()) {
        provider.movieCategoriesOverride = if (movies.isEmpty()) "[]" else """[{"category_id":"1","category_name":"MOVIES"}]"""
        provider.movieListOverride = movies.withIndex().joinToString(",", "[", "]") { (i, n) ->
            """{"stream_id":${i + 1},"name":"EN - Film $n (2025)","tmdb":"$n","category_id":"1","added":"${1_700_000_000 - i}"}"""
        }
        provider.showCategoriesOverride = if (shows.isEmpty()) "[]" else """[{"category_id":"2","category_name":"SERIES"}]"""
        provider.showListOverride = shows.withIndex().joinToString(",", "[", "]") { (i, n) ->
            """{"series_id":${i + 1},"name":"EN - Show $n (2025)","tmdb":"${500 + n}","category_id":"2","last_modified":"${1_700_000_000 - i}"}"""
        }
        movies.forEach { serve("Film $it", it, "movie", bare) }
        shows.forEach { serve("Show $it", 500 + it, "show", bare) }
        library.sync()
    }

    private fun serve(name: String, tmdbId: Long, kind: String, bare: Set<String>) = cloud.title(
        "en",
        tmdbTitle(
            tmdbId, name, kind = kind, runtimeMinutes = 100, genres = listOf("Drama"),
            descriptions = listOf(Triple("en", "", "About $name.")),
            backdropPath = "/backdrop-$tmdbId.jpg",
            textlessBackdropPath = "/textless-$tmdbId.jpg".takeUnless { "$name no backdrop" in bare },
            logoPath = "/logo-$tmdbId.png".takeUnless { "$name no logo" in bare },
        ),
    )

    private suspend fun featured(screen: FeaturedScreen = FeaturedScreen.Home) = library.featured(screen, english, "en", "CA")?.name

    /** The names [screen] features at its next [entries] entries. */
    private suspend fun entries(entries: Int, screen: FeaturedScreen = FeaturedScreen.Home) = List(entries) { featured(screen) }

    @Test fun `Home features its Top movies and Top shows in turn, a different Title at each entry, from the top five of each`() = runBlocking {
        sync(movies = (1L..7L).toList(), shows = (1L..3L).toList())
        cloud.charts("CA", movies = (1L..7L).toList(), shows = (501L..503L).toList())

        assertEquals(
            listOf("Film 1", "Show 1", "Film 2", "Show 2", "Film 3", "Show 3", "Film 4", "Film 5", "Film 1"),
            entries(9),
        )
    }

    @Test fun `the featured Title carries what the hero shows`() = runBlocking {
        sync(movies = listOf(1, 2, 3))
        cloud.charts("CA", movies = listOf(1, 2, 3))

        val featured = library.featured(FeaturedScreen.Home, english, "en", "CA")!!
        assertEquals(movie(1), featured.id)
        assertEquals("Film 1", featured.name)
        assertEquals(100, featured.runtimeMinutes)
        assertEquals(listOf("Drama"), featured.genres)
        assertEquals("About Film 1.", featured.description)
        // Full size, textless: it fills the screen.
        assertEquals("https://image.tmdb.org/t/p/w1280/textless-1.jpg", featured.backdrop)
        assertEquals("https://image.tmdb.org/t/p/w500/logo-1.png", featured.logo)
        assertEquals("en", featured.best.language?.value)
    }

    @Test fun `it skips Titles in Continue watching and those without a textless backdrop or a logo`() = runBlocking {
        sync(movies = (1L..5L).toList(), bare = setOf("Film 2 no backdrop", "Film 4 no logo"))
        cloud.charts("CA", movies = (1L..5L).toList())
        library.saveProgress(movie(3), position = 30 * 60_000L, duration = 120 * 60_000L)

        assertEquals(listOf("Film 1", "Film 5", "Film 1"), entries(3))
    }

    @Test fun `Movies and Shows feature their own kind's Top row, each rotating on its own`() = runBlocking {
        sync(movies = listOf(1, 2, 3), shows = listOf(1, 2, 3))
        cloud.charts("CA", movies = listOf(1, 2, 3), shows = listOf(501, 502, 503))

        assertEquals(listOf("Film 1", "Film 2"), entries(2, FeaturedScreen.Movies))
        assertEquals(listOf("Show 1", "Show 2"), entries(2, FeaturedScreen.Shows))
        assertEquals("Film 3", featured(FeaturedScreen.Movies))
        assertEquals("Film 1", featured(FeaturedScreen.Home))
    }

    @Test fun `the rotation goes on where it was when the app starts again`() = runBlocking {
        sync(movies = listOf(1, 2, 3))
        cloud.charts("CA", movies = listOf(1, 2, 3))
        assertEquals(listOf("Film 1", "Film 2"), entries(2))

        library.close()
        library = open()

        assertEquals("Film 3", featured())
    }

    @Test fun `with no Title that qualifies, or a Top row too short to show, there is no featured Title`() = runBlocking {
        sync(movies = listOf(1, 2, 3, 4), bare = setOf("Film 1 no logo", "Film 2 no logo", "Film 3 no backdrop"))
        cloud.charts("CA", movies = listOf(1, 2, 3))
        assertNull(featured())

        // Film 4 qualifies, but a Top row of two isn't shown, so it isn't on screen to feature.
        cloud.charts("US", movies = listOf(4, 99))
        assertNull(library.featured(FeaturedScreen.Movies, english, "en", "US"))
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
        val show = TitleId.Tmdb(TitleKind.Show, 501)
        // Episodes play from the show details read when the Show's details opened.
        library.episodes(show, english)
        library.saveProgress(library.episodePlayPlan(show, EpisodeNumber(1, 1), english, fullHdTv)!!, position = 49 * 60_000L, duration = 50 * 60_000L)

        val plan = library.titlePlayPlan(show, english, "en", fullHdTv)!!
        assertEquals(EpisodeNumber(1, 2), plan.episode)
        assertEquals(library.episodePlayPlan(show, EpisodeNumber(1, 2), english, fullHdTv), plan)
    }
}
