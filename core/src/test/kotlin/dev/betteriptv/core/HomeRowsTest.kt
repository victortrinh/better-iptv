package dev.betteriptv.core

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Duration

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

    private suspend fun rows(contentLanguages: List<String> = english) = library.homeRows(contentLanguages, appLanguage = "en")

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
}
