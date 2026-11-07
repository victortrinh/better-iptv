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
 * A Title's backdrop is the Provider's (#475, ADR 0014), seen through the Library over the fake
 * Provider: a Show's the first on its Listing, stored at Sync; a Movie's the one its details give,
 * stored with them; one the app refused once decoded is none; Continue watching's still is the
 * backdrop, else the poster.
 */
class BackdropsTest {
    @get:Rule val folder = TemporaryFolder()
    private val provider = FakeProvider()
    private val cloud = FakeCloud()
    private lateinit var library: Library

    private val english = listOf("en")
    private val heat = TitleId.Tmdb(TitleKind.Movie, 949)
    private val ronin = TitleId.Tmdb(TitleKind.Movie, 8195)
    private val theWire = TitleId.Tmdb(TitleKind.Show, 1438)

    @Before fun logIn() = runBlocking {
        library = Library.open(folder.root.resolve("catalog.db").path, InMemoryCredentials(), cloud.url)
        library.logIn(provider.login)
        provider.movieCategoriesOverride = """[{"category_id":"1","category_name":"MOVIES"}]"""
        provider.movieListOverride = """[
            {"stream_id":1,"name":"EN - Heat (1995)","tmdb":"949","category_id":"1","added":"1700000000","stream_icon":"https://art.example/heat-poster.jpg","container_extension":"mkv"},
            {"stream_id":2,"name":"EN - Ronin (1998)","tmdb":"8195","category_id":"1","added":"1699999999","stream_icon":"https://art.example/ronin-poster.jpg","container_extension":"mkv"}
        ]"""
        provider.serveMovieDetails(1, name = "Heat", backdrop = "https://art.example/heat-backdrop.jpg")
        provider.serveMovieDetails(2, name = "Ronin")
        provider.serveShows(
            FakeProvider.ShowListing(
                "EN - The Wire (2002)", "SERIES", 1438,
                backdrops = listOf("https://art.example/wire-1.jpg", "https://art.example/wire-2.jpg"),
            ),
        )
        library.sync()
        Unit
    }

    @After fun tearDown() {
        library.close()
        provider.close()
        cloud.close()
    }

    @Test fun `a Show's backdrop is the first on its Listing, there since Sync with no details call`() = runBlocking {
        assertEquals("https://art.example/wire-1.jpg", library.title(theWire, english, "en")!!.backdrop)
        assertTrue(provider.requests.none { it.startsWith("get_series_info") || it.startsWith("get_vod_info") })
    }

    @Test fun `a Movie's backdrop is the one its details give, once its page read them`() = runBlocking {
        assertEquals("https://art.example/heat-backdrop.jpg", library.title(heat, english, "en")!!.backdrop)
        // Kept with the details: the next page needs no call.
        provider.serveMovieDetails(1, name = "Heat")
        assertEquals("https://art.example/heat-backdrop.jpg", library.title(heat, english, "en")!!.backdrop)
        assertEquals(listOf(1L), provider.movieDetailsRequests)
        // Details with none give none: the page shows the poster.
        assertNull(library.title(ronin, english, "en")!!.backdrop)
    }

    @Test fun `a refused backdrop is none`() = runBlocking {
        library.refuseBackdrop("https://art.example/wire-1.jpg")

        // A Show's other backdrops aren't tried: the Provider's first is its backdrop, or there is none.
        assertNull(library.title(theWire, english, "en")!!.backdrop)
    }

    @Test fun `Continue watching shows the backdrop as its still, else the poster`() = runBlocking {
        library.title(heat, english, "en")
        library.title(ronin, english, "en")
        library.saveProgress(heat, position = 30 * 60_000L, duration = 120 * 60_000L)
        Thread.sleep(5)
        library.saveProgress(ronin, position = 30 * 60_000L, duration = 120 * 60_000L)

        val resumes = library.homeRows(english, "en").filterIsInstance<HomeRow.ContinueWatching>().single().titles
        assertEquals(listOf("https://art.example/ronin-poster.jpg", "https://art.example/heat-backdrop.jpg"), resumes.map { it.still })
    }
}
