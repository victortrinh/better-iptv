package dev.betteriptv.core

import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The Kids catalog (CONTEXT.md) and what a Kids profile sees of the library, through the Library after a Sync (#433). */
class KidsCatalogTest {
    @get:Rule val folder = TemporaryFolder()
    private val provider = FakeProvider()
    private val cloud = FakeCloud()
    private val libraries = mutableListOf<Library>()

    @After fun tearDown() {
        libraries.forEach { it.close() }
        provider.close()
        cloud.close()
    }

    private suspend fun library(cloudAddress: String? = cloud.url, providerTable: String? = null): Library {
        val library = Library.open(folder.root.resolve("catalog-${libraries.size}.db").path, InMemoryCredentials(), cloudAddress, { providerTable }).also { libraries += it }
        library.logIn(provider.login)
        return library
    }

    private fun movie(tmdbId: Long) = TitleId.Tmdb(TitleKind.Movie, tmdbId)

    private fun show(tmdbId: Long) = TitleId.Tmdb(TitleKind.Show, tmdbId)

    /** A movie Listing to serve: its [name], its Title's [tmdbId] and the Category it is filed under. */
    private class Filed(val name: String, val tmdbId: Long, val category: String)

    /** Serves these movie Listings, newest first, numbered from 1, each filed under its Category. */
    private fun serveMovies(vararg listings: Filed) {
        val categories = listings.map { it.category }.distinct()
        provider.movieCategoriesOverride = categories.withIndex().joinToString(",", "[", "]") { (i, name) -> """{"category_id":"${i + 1}","category_name":"$name"}""" }
        provider.movieListOverride = listings.withIndex().joinToString(",", "[", "]") { (i, listing) ->
            """{"stream_id":${i + 1},"name":"${listing.name}","tmdb":"${listing.tmdbId}","category_id":"${categories.indexOf(listing.category) + 1}","added":"${1_700_000_000 - i}"}"""
        }
    }

    /** Serves these show Listings, newest first, numbered from 1, each filed under its Category. */
    private fun serveShows(vararg listings: Filed) {
        val categories = listings.map { it.category }.distinct()
        provider.showCategoriesOverride = categories.withIndex().joinToString(",", "[", "]") { (i, name) -> """{"category_id":"${i + 101}","category_name":"$name"}""" }
        provider.showListOverride = listings.withIndex().joinToString(",", "[", "]") { (i, listing) ->
            """{"series_id":${i + 1},"name":"${listing.name}","tmdb":"${listing.tmdbId}","category_id":"${categories.indexOf(listing.category) + 101}","last_modified":"${1_700_000_000 - i}"}"""
        }
    }

    private fun tmdb(tmdbId: Long, name: String, kind: String = "movie", popularity: Double = 10.0, genres: List<String> = emptyList(), hero: Boolean = false) =
        cloud.title(
            "en",
            tmdbTitle(
                tmdbId, name, kind = kind, popularity = popularity, genres = genres, backdropPath = "/still-$tmdbId.jpg",
                textlessBackdropPath = "/textless-$tmdbId.jpg".takeIf { hero }, logoPath = "/logo-$tmdbId.png".takeIf { hero },
            ),
        )

    /** Puts [library] on a new Kids profile. */
    private suspend fun onKidsProfile(library: Library) {
        library.switchProfile(library.addProfile("Kids", kids = true)!!.id)
        assertTrue(library.kids)
    }

    private val english = listOf("en")

    @Test fun `on the fixture, the Kids catalog is the Titles filed under Kids Categories`() = runBlocking {
        val library = library(cloudAddress = null)
        library.sync()

        val catalog = library.kidsCatalog()
        // The Canterville Ghost is filed under EN - KIDS; nothing under EN - ACTION or EN - DRAMA is.
        assertEquals(listOf(movie(269509)), catalog.getValue(TitleKind.Movie))
        assertEquals(emptyList<TitleId>(), catalog.getValue(TitleKind.Show))
        assertTrue(library.titleCount(TitleKind.Movie) > 1)
    }

    @Test fun `a Title filed under a Kids Category and another is in the Kids catalog, one filed under the other alone is not`() = runBlocking {
        serveMovies(
            Filed("EN - Both (2025)", 1, "EN - KIDS"),
            Filed("EN - Both (2025)", 1, "EN - MOVIES"),
            Filed("EN - Grown-up (2025)", 2, "EN - MOVIES"),
            Filed("FR - Dessin (2025)", 3, "FR - DESSINS ANIMÉS"),
            Filed("FR - Anime (2025)", 4, "FR - ANIMEE"),
        )
        serveShows(Filed("EN - Cartoon", 11, "EN - KIDS"), Filed("EN - Drama", 12, "EN - SERIES"))
        val library = library(cloudAddress = null)
        library.sync()

        val catalog = library.kidsCatalog()
        assertEquals(listOf(movie(1), movie(3)), catalog.getValue(TitleKind.Movie))
        assertEquals(listOf(show(11)), catalog.getValue(TitleKind.Show))
    }

    @Test fun `a Provider table that empties a Kids word takes its Categories out of the Kids catalog`() = runBlocking {
        serveMovies(Filed("EN - Family film (2025)", 1, "EN - FAMILY"), Filed("EN - Kids film (2025)", 2, "EN - KIDS"))
        provider.showCategoriesOverride = "[]"
        provider.showListOverride = "[]"
        val library = library(cloudAddress = null, providerTable = "[genre categories]\nFAMILY |\n")
        library.sync()

        assertEquals(listOf(movie(2)), library.kidsCatalog().getValue(TitleKind.Movie))
    }

    @Test fun `TMDB's Animation and Family genres add to the Kids catalog while TMDB is there`() = runBlocking {
        serveMovies(*(1L..6L).map { Filed("EN - Film $it (2025)", it, "EN - MOVIES") }.toTypedArray(), Filed("EN - Kid (2025)", 7, "EN - KIDS"))
        provider.showCategoriesOverride = "[]"
        provider.showListOverride = "[]"
        cloud.genre(TitleKind.Movie, "Animation", 1, 2, 999)
        cloud.genre(TitleKind.Movie, "Family", 3)
        cloud.genre(TitleKind.Movie, "Drama", 4, 5)
        (1L..7L).forEach { tmdb(it, "Film $it") }
        val library = library()
        library.sync()
        onKidsProfile(library)

        val shown = library.tabRows(TitleKind.Movie, english, "en").flatMap { row ->
            when (row) {
                is HomeRow.NewForKids -> row.titles
                is HomeRow.ForKids -> row.titles
                is HomeRow.Genre -> row.titles
                else -> emptyList()
            }
        }.map { it.id }.toSet()
        assertEquals(setOf(movie(1), movie(2), movie(3), movie(7)), shown)
    }

    @Test fun `Home on a Kids profile shows Continue watching, New for kids, Movies for kids, Shows for kids, then genre rows, and no Charts`() = runBlocking {
        val kidsMovies = (1L..45L).map { Filed("EN - Kid movie $it (2025)", it, "EN - KIDS") }
        serveMovies(*kidsMovies.toTypedArray(), Filed("EN - Grown-up (2025)", 100, "EN - MOVIES"))
        serveShows(*(1L..20L).map { Filed("EN - Kid show $it", 200 + it, "EN - KIDS") }.toTypedArray(), Filed("EN - Drama", 300, "EN - SERIES"))
        (1L..45L).forEach { tmdb(it, "Kid movie $it", popularity = it.toDouble(), genres = listOf("Animation")) }
        tmdb(100, "Grown-up", popularity = 1000.0)
        (1L..20L).forEach { tmdb(200 + it, "Kid show $it", kind = "show", popularity = it.toDouble()) }
        tmdb(300, "Drama", kind = "show", popularity = 1000.0)
        cloud.charts("CA", movies = listOf(100, 1, 2, 3), shows = listOf(300, 201, 202, 203))
        val library = library()
        library.sync()
        val normal = library.homeRows(english, "en", "CA")
        onKidsProfile(library)
        library.saveProgress(movie(5), position = 30 * 60_000L, duration = 120 * 60_000L)

        val rows = library.homeRows(english, "en", "CA")

        assertEquals(
            listOf(HomeRow.ContinueWatching::class, HomeRow.NewForKids::class, HomeRow.ForKids::class, HomeRow.ForKids::class, HomeRow.Genre::class),
            rows.map { it::class },
        )
        assertEquals(listOf(TitleKind.Movie, TitleKind.Show), rows.filterIsInstance<HomeRow.ForKids>().map { it.kind })
        val shown = rows.flatMap { row ->
            when (row) {
                is HomeRow.ContinueWatching -> row.titles.map { it.title }
                is HomeRow.NewForKids -> row.titles
                is HomeRow.ForKids -> row.titles
                is HomeRow.Genre -> row.titles
                else -> emptyList()
            }
        }.map { it.id }
        assertFalse(movie(100) in shown)
        assertFalse(show(300) in shown)
        // The most popular of the 40 newest Kids movies leads New for kids.
        assertEquals("Kid movie 40", rows.filterIsInstance<HomeRow.NewForKids>().single().titles.first().name)
        // A normal profile's rows are unchanged: its Charts are there, and nothing for kids.
        assertTrue(normal.any { it is HomeRow.Chart })
        assertTrue(normal.none { it is HomeRow.NewForKids || it is HomeRow.ForKids })
        library.switchProfile(Profile.FIRST_ID)
        assertEquals(normal, library.homeRows(english, "en", "CA"))
    }

    @Test fun `the Movies and Shows tabs on a Kids profile show New for kids, the kind for kids, then genre rows, and no Charts`() = runBlocking {
        serveMovies(*(1L..45L).map { Filed("EN - Kid movie $it (2025)", it, "EN - KIDS") }.toTypedArray(), Filed("EN - Grown-up (2025)", 100, "EN - MOVIES"))
        serveShows(*(1L..45L).map { Filed("EN - Kid show $it", 200 + it, "EN - KIDS") }.toTypedArray())
        (1L..45L).forEach {
            tmdb(it, "Kid movie $it", popularity = it.toDouble(), genres = listOf("Animation"))
            tmdb(200 + it, "Kid show $it", kind = "show", popularity = it.toDouble(), genres = listOf("Animation"))
        }
        cloud.charts("CA", movies = listOf(100, 1, 2, 3))
        val library = library()
        library.sync()
        onKidsProfile(library)

        for (kind in TitleKind.entries) {
            val rows = library.tabRows(kind, english, "en", "CA")
            assertEquals(listOf(HomeRow.NewForKids::class, HomeRow.ForKids::class, HomeRow.Genre::class), rows.map { it::class })
            assertEquals(kind, rows.filterIsInstance<HomeRow.NewForKids>().single().kind)
            assertEquals(kind, rows.filterIsInstance<HomeRow.ForKids>().single().kind)
            assertTrue(rows.flatMap { (it as? HomeRow.ForKids)?.titles.orEmpty() + (it as? HomeRow.NewForKids)?.titles.orEmpty() }.all { it.id.kind == kind })
        }
    }

    @Test fun `the hero on a Kids profile features a Title of the Kids catalog`() = runBlocking {
        serveMovies(Filed("EN - Kid (2025)", 1, "EN - KIDS"), Filed("EN - Grown-up (2025)", 2, "EN - MOVIES"))
        provider.showCategoriesOverride = "[]"
        provider.showListOverride = "[]"
        tmdb(1, "Kid", hero = true)
        tmdb(2, "Grown-up", popularity = 1000.0, hero = true)
        cloud.charts("CA", movies = listOf(2, 1, 3))
        val library = library()
        library.sync()
        onKidsProfile(library)

        repeat(3) { assertEquals(movie(1), library.featured(FeaturedScreen.Home, english, "en", "CA")?.id) }
        assertEquals(movie(1), library.featured(FeaturedScreen.Movies, english, "en", "CA")?.id)
        assertEquals(null, library.featured(FeaturedScreen.Shows, english, "en", "CA"))
    }

    @Test fun `Live on a Kids profile shows the Favourites, then the Channels of Kids Categories as one row per country`() = runBlocking {
        provider.serveChannels(
            "US: ESPN HD" to "US| SPORTS",
            "US: NICK HD" to "US| KIDS ᴴᴰ/ᴿᴬᵂ ⁶⁰ᶠᵖˢ",
            "US: DISNEY HD" to "US| KIDS ᴴᴰ/ᴿᴬᵂ ⁶⁰ᶠᵖˢ",
            "CA: TELETOON" to "CA| KIDS EN",
            "CA: TVA" to "CA| GENERAL",
            "FR: GULLI" to "FR| ENFANTS HD/4K",
        )
        val library = library(cloudAddress = null)
        library.sync()
        onKidsProfile(library)
        val espn = library.search("ESPN", english, "en").last()
        assertTrue(espn.channels.isEmpty())
        library.setFavourite("US: ESPN", true)

        val rows = library.liveRows(region = "CA", contentLanguages = english)

        assertEquals(listOf("US: ESPN"), (rows.first() as HomeRow.Favourites).channels.map { it.key })
        val kidsRows = rows.drop(1).map { it as HomeRow.KidsChannels }
        assertEquals(listOf("CA", "US", "FR"), kidsRows.map { it.country.code })
        assertEquals(listOf(listOf("CA: TELETOON"), listOf("US: NICK", "US: DISNEY"), listOf("FR: GULLI")), kidsRows.map { row -> row.channels.map { it.key } })
        assertTrue(rows.none { it is HomeRow.Channels })
    }

    @Test fun `Search on a Kids profile finds only the Kids catalog's Titles and Kids Categories' Channels`() = runBlocking {
        serveMovies(Filed("EN - Dragon Kid (2025)", 1, "EN - KIDS"), Filed("EN - Dragon Grown-up (2025)", 2, "EN - MOVIES"))
        serveShows(Filed("EN - Dragon Show", 11, "EN - KIDS"), Filed("EN - Dragon Drama", 12, "EN - SERIES"))
        provider.serveChannels("US: DRAGON KIDS TV" to "US| KIDS", "US: DRAGON NEWS" to "US| NEWS")
        val library = library(cloudAddress = null)
        library.sync()

        val everything = library.search("dragon", english, "en").last()
        assertEquals(4, everything.titles.size)
        assertEquals(2, everything.channels.size)

        onKidsProfile(library)
        val kids = library.search("dragon", english, "en").last()
        assertEquals(setOf(movie(1), show(11)), kids.titles.map { it.id }.toSet())
        assertEquals(listOf("US: DRAGON KIDS TV"), kids.channels.map { it.key })
    }

    @Test fun `Play on a Kids profile picks the Best playable Version, as on any profile`() = runBlocking {
        serveMovies(Filed("FR - Kid (2025)", 1, "EN - KIDS"), Filed("EN - Kid (2025)", 1, "EN - KIDS"))
        provider.showCategoriesOverride = "[]"
        provider.showListOverride = "[]"
        val library = library(cloudAddress = null)
        library.sync()
        val tv = DeviceProfile(maxHeight = 1080, hdr = emptySet())
        val normal = library.playPlan(movie(1), english, tv)!!
        onKidsProfile(library)

        val plan = library.playPlan(movie(1), english, tv)!!
        assertEquals("EN - Kid (2025)", plan.version.name)
        assertEquals(normal.version, plan.version)
    }
}
