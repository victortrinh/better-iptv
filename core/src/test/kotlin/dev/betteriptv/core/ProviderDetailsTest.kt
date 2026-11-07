package dev.betteriptv.core

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * What a Title is comes from the Provider (#473, ADR 0014), seen through the Library over the fake
 * Provider: a Movie's details read once when its page opens and kept for good, a Show's from its
 * Listing at Sync, the info under a focused Home row from stored data alone.
 */
class ProviderDetailsTest {
    @get:Rule val folder = TemporaryFolder()
    private val provider = FakeProvider()
    private val cloud = FakeCloud()
    private val credentials = InMemoryCredentials()
    private val libraries = mutableListOf<Library>()
    private lateinit var library: Library

    private fun openLibrary() = Library.open(folder.root.resolve("catalog.db").path, credentials, cloud.url).also { libraries += it }

    @Before fun logIn() = runBlocking {
        library = openLibrary()
        library.logIn(provider.login)
        provider.showCategoriesOverride = "[]"
        provider.showListOverride = "[]"
        Unit
    }

    @After fun tearDown() {
        libraries.forEach { it.close() }
        provider.close()
        cloud.close()
    }

    private val english = listOf("en")
    private val heat = TitleId.Tmdb(TitleKind.Movie, 949)
    private val theWire = TitleId.Tmdb(TitleKind.Show, 1438)

    private fun json(text: String) = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    /** A movie Listing as the Provider lists it: [name] in [category], of Title [tmdbId], [added] then, with the Provider's [rating]. */
    private class Movie(val name: String, val category: String, val tmdbId: Long?, val rating: String = "", val added: Long = 1_700_000_000)

    /** Serves these movie Listings, numbered from 1, under their Categories (numbered from 1 in order of first appearance), and Syncs. */
    private suspend fun syncMovies(vararg movies: Movie) {
        val categories = movies.map { it.category }.distinct()
        provider.movieCategoriesOverride = categories.withIndex().joinToString(",", "[", "]") { (i, name) -> """{"category_id":"${i + 1}","category_name":${json(name)}}""" }
        provider.movieListOverride = movies.withIndex().joinToString(",", "[", "]") { (i, movie) ->
            """{"stream_id":${i + 1},"name":${json(movie.name)},"tmdb":${json(movie.tmdbId?.toString().orEmpty())},"category_id":"${categories.indexOf(movie.category) + 1}",""" +
                """"added":"${movie.added}","rating":${json(movie.rating)},"container_extension":"mkv"}"""
        }
        library.sync()
    }

    /** Heat: an English Listing in an Action Category and a French one in a plain one, each rated. */
    private suspend fun syncHeat(added: Long = 1_700_000_000) =
        syncMovies(Movie("EN - Heat (1995)", "EN - ACTION", 949, "8.3", added), Movie("FR - Heat (1995)", "FR - FILMS", 949, "8.2", added - 1))

    private fun serveHeatDetails(id: Long = 1, trailer: String? = "https://www.youtube.com/watch?v=2GfZl4kuVNI") = provider.serveMovieDetails(
        id,
        plot = "<b>A group</b> of professional bank robbers start to feel the <i>heat</i> from police&hellip;  Twice  spaced.",
        genre = "Crime / Drama / Thriller",
        releaseDate = "1995-12-15",
        durationMinutes = 170,
        director = "Michael Mann",
        cast = "Al Pacino, Robert De Niro, Val Kilmer",
        rating = 8.3,
        backdrop = "https://art.example/heat-backdrop.jpg",
        trailer = trailer,
        bitrate = 9500,
    )

    private suspend fun page(id: TitleId, appLanguage: String = "en") = library.title(id, english, appLanguage) ?: throw AssertionError("no Title $id")

    private suspend fun card(id: TitleId) = library.homeRows(english, "en").filterIsInstance<HomeRow.NewOnYourService>().single().titles.single { it.id == id }

    private fun detailsCalls() = provider.requests.filter { it.startsWith("get_vod_info") }

    @Test fun `opening a Movie's page reads its details once, and shows what the Provider said`() = runBlocking {
        syncHeat()
        serveHeatDetails()

        val page = page(heat)

        assertEquals(listOf("get_vod_info 1"), detailsCalls())
        assertEquals("A group of professional bank robbers start to feel the heat from police… Twice spaced.", page.description)
        // The Category's genre first, then the details', each once.
        assertEquals(listOf("Action", "Crime", "Drama", "Thriller"), page.genres)
        assertEquals(1995, page.year)
        assertEquals(170, page.runtimeMinutes)
        assertEquals(listOf("Michael Mann"), page.directors)
        assertEquals(listOf("Al Pacino", "Robert De Niro", "Val Kilmer"), page.cast)
        assertEquals(8.3, page.rating!!, 0.001)
        assertEquals("2GfZl4kuVNI", page.trailer)
        // The details' bitrate Confirms the Version's, as before.
        assertEquals(Known(9_500_000L, Provenance.Confirmed), page.languages.single { it.language == "en" }.versions.single().attributes.bitrate)
    }

    @Test fun `opening the page again, in this run or the next, reads nothing, the details being kept for good`() = runBlocking {
        syncHeat()
        serveHeatDetails()
        page(heat)

        page(heat)
        assertEquals(1, detailsCalls().size)

        library.close()
        library = openLibrary()
        provider.movieDetails.clear()
        val kept = page(heat)
        assertEquals(1, detailsCalls().size)
        assertEquals(listOf("Michael Mann"), kept.directors)
        assertEquals("2GfZl4kuVNI", kept.trailer)
    }

    @Test fun `the details are read again only once the Provider changes the Listing`() = runBlocking {
        syncHeat()
        serveHeatDetails()
        page(heat)

        // The same Listing, synced again: nothing changed.
        syncHeat()
        page(heat)
        assertEquals(1, detailsCalls().size)

        // The Provider replaced the file (its `added` moved): the details are read anew.
        syncHeat(added = 1_700_000_500)
        page(heat)
        assertEquals(2, detailsCalls().size)
    }

    @Test fun `with the Provider unreachable, a page whose details were kept opens whole, and one never read opens without them`() = runBlocking {
        syncMovies(Movie("EN - Heat (1995)", "EN - ACTION", 949, "8.3"), Movie("EN - Collateral (2004)", "EN - THRILLER", 1538, "7.2"))
        serveHeatDetails()
        page(heat)
        provider.down = true

        val kept = page(heat)
        assertEquals("A group of professional bank robbers start to feel the heat from police… Twice spaced.", kept.description)
        assertEquals(listOf("Action", "Crime", "Drama", "Thriller"), kept.genres)
        assertEquals(listOf("Al Pacino", "Robert De Niro", "Val Kilmer"), kept.cast)
        assertEquals("2GfZl4kuVNI", kept.trailer)

        val unread = page(TitleId.Tmdb(TitleKind.Movie, 1538))
        assertEquals("Collateral", unread.name)
        assertNull(unread.description)
        assertEquals(listOf("Thriller"), unread.genres)
        assertEquals(7.2, unread.rating!!, 0.001)
        assertEquals(1, unread.languages.size)
    }

    @Test fun `a Movie filed under a genre Category has that genre before its details are read, and its details' genres join it after`() = runBlocking {
        syncHeat()
        serveHeatDetails()

        assertEquals(listOf("Action"), card(heat).about!!.genres)
        assertTrue(detailsCalls().isEmpty())

        page(heat)
        assertEquals(listOf("Action", "Crime", "Drama", "Thriller"), card(heat).about!!.genres)
    }

    @Test fun `twenty cards on Home cost the Provider nothing, and each tells its year, genre and rating`() = runBlocking {
        syncMovies(*(1..20).map { Movie("EN - Film $it (2024)", "EN - DRAMA", 1000L + it, if (it == 7) "" else "6.6") }.toTypedArray())
        provider.serveMovieDetails(3, plot = "Never read on focus.", genre = "Comedy")
        val before = provider.requests.size

        val cards = library.homeRows(english, "en").filterIsInstance<HomeRow.NewOnYourService>().single().titles

        assertEquals(20, cards.size)
        assertEquals(before, provider.requests.size)
        val card = cards.single { it.name == "Film 1" }
        assertEquals(2024, card.year)
        assertEquals(TitleAbout(listOf("Drama"), 6.6), card.about)
        // The Provider gave no rating for this one: none is told.
        assertEquals(TitleAbout(listOf("Drama"), null), cards.single { it.name == "Film 7" }.about)
    }

    @Test fun `the Trailer action shows only with a trailer key, on a Movie and on a Show`() = runBlocking {
        syncMovies(Movie("EN - Heat (1995)", "EN - ACTION", 949, "8.3"), Movie("EN - Collateral (2004)", "EN - THRILLER", 1538, "7.2"))
        serveHeatDetails(id = 1)
        serveHeatDetails(id = 2, trailer = "")
        provider.serveShows(
            FakeProvider.ShowListing("EN - The Wire (2002)", "SERIES", 1438, trailer = "lYq0q8Zb3Dk"),
            FakeProvider.ShowListing("EN - Deadwood (2004)", "SERIES", 1406, trailer = ""),
        )
        library.sync()

        assertEquals("2GfZl4kuVNI", page(heat).trailer)
        assertNull(page(TitleId.Tmdb(TitleKind.Movie, 1538)).trailer)
        assertEquals("lYq0q8Zb3Dk", page(theWire).trailer)
        assertNull(page(TitleId.Tmdb(TitleKind.Show, 1406)).trailer)
    }

    @Test fun `a Show's page shows its Listing's plot, genres, cast, director and rating with no call beyond its Episodes`() = runBlocking {
        provider.serveShows(
            FakeProvider.ShowListing(
                "EN - The Wire (2002)", "EN - SERIES", 1438,
                plot = "Baltimore drug scene,<br>seen through the eyes of drug dealers and law enforcement.",
                genre = "Crime / Drama",
                cast = "Dominic West, Idris Elba",
                director = "David Simon",
                rating = 8.6,
                releaseDate = "2002-06-02",
                backdrops = listOf("https://art.example/wire-1.jpg", "https://art.example/wire-2.jpg"),
                trailer = "lYq0q8Zb3Dk",
            ),
        )
        provider.showDetailsOverride[1] = """{"seasons":[],"info":{},"episodes":[{"id":"501","episode_num":1,"season":1,"title":"The Wire - S01E01 - The Target","container_extension":"mkv","added":"1700000000","info":{"movie_image":"https://stills.example/target.jpg","duration_secs":3720,"video":{"width":1920,"height":1080},"audio":{"tags":{"language":"eng"}}}}]}"""
        syncMovies(Movie("EN - Heat (1995)", "EN - ACTION", 949))
        val synced = provider.requests.size

        val page = page(theWire)
        val show = library.episodes(theWire, english) ?: throw AssertionError("no Episodes")

        assertEquals("Baltimore drug scene, seen through the eyes of drug dealers and law enforcement.", page.description)
        assertEquals(listOf("Crime", "Drama"), page.genres)
        assertEquals(listOf("Dominic West", "Idris Elba"), page.cast)
        assertEquals(listOf("David Simon"), page.directors)
        assertEquals(8.6, page.rating!!, 0.001)
        assertEquals(2002, page.year)
        assertEquals("lYq0q8Zb3Dk", page.trailer)
        // The Episode picker's stills and runtimes come from the same show details, and it lists the Provider's Episodes only.
        val episode = show.seasons.single().episodes.single()
        assertEquals("The Target", episode.name)
        assertEquals("https://stills.example/target.jpg", episode.still)
        assertEquals(62, episode.runtimeMinutes)
        assertEquals(listOf("get_series_info 1"), provider.requests.drop(synced))
    }

    @Test fun `a Show's genres in several languages fold into one set, named in the App language`() = runBlocking {
        provider.serveShows(
            FakeProvider.ShowListing("FR - The Wire (2002)", "SERIES", 1438, genre = "Drame / Comédie", rating = 8.6),
            FakeProvider.ShowListing("DE - The Wire (2002)", "SERIES", 1438, genre = "Komödie / Drama"),
            FakeProvider.ShowListing("ES - The Wire (2002)", "SERIES", 1438, genre = "Comedia / Drama"),
        )
        syncMovies(Movie("EN - Heat (1995)", "EN - ACTION", 949))

        assertEquals(listOf("Drama", "Comedy"), page(theWire).genres)
        assertEquals(listOf("Drame", "Comédie"), page(theWire, appLanguage = "fr-CA").genres)
        assertEquals(8.6, page(theWire).rating!!, 0.001)
    }

    @Test fun `a rating of zero or none is no rating, and a year comes from the details when the name has none`() = runBlocking {
        syncMovies(Movie("EN - Heat", "EN - ACTION", 949, "0"))
        provider.serveMovieDetails(1, releaseDate = "1995-12-15", rating = 0.0)

        val page = page(heat)

        assertNull(page.rating)
        assertEquals(1995, page.year)
        assertNull(card(heat).about!!.rating)
        assertNotNull(card(heat).about)
    }
}
