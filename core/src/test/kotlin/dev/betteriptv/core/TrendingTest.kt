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
import java.time.Duration
import java.time.Instant

/**
 * Trending (#477): the Provider's Titles Wikipedia's readers looked up most over the week, seen
 * through the Library over the fake cloud's feed and Title facts and the fake Provider. Summed over
 * the week, a Title in both wikis counted once, kept to the Content languages, unranked, and the feed
 * asked at most once an hour.
 */
class TrendingTest {
    @get:Rule val folder = TemporaryFolder()
    private val provider = FakeProvider()
    private val cloud = FakeCloud()
    private val credentials = InMemoryCredentials()
    private lateinit var library: Library

    @Before fun logIn() = runBlocking {
        library = open()
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

    private fun open() = Library.open(folder.root.resolve("catalog.db").path, credentials, cloud.url)

    private val english = listOf("en")

    private fun movie(tmdbId: Long) = TitleId.Tmdb(TitleKind.Movie, tmdbId)
    private fun show(tmdbId: Long) = TitleId.Tmdb(TitleKind.Show, tmdbId)

    /**
     * Syncs these movie Listings, newest first, each a Title of its own number, after scripting each
     * one's English Wikipedia article as its name (`Film 1`) unless [articles] names another, and the
     * French one from [frwiki].
     */
    private suspend fun syncMovies(vararg listings: Pair<String, Long>, articles: Map<Long, String?> = emptyMap(), frwiki: Map<Long, String> = emptyMap()) {
        listings.forEach { (name, id) ->
            val article = if (id in articles) articles[id] else name.substringAfter(" - ").substringBefore(" (")
            cloud.titleFacts(movie(id), originalLanguage = "en", enwiki = article, frwiki = frwiki[id])
        }
        provider.movieCategoriesOverride = """[{"category_id":"1","category_name":"MOVIES"}]"""
        provider.movieListOverride = listings.withIndex().joinToString(",", "[", "]") { (i, listing) ->
            """{"stream_id":${i + 1},"name":"${listing.first}","tmdb":"${listing.second}","category_id":"1","added":"${1_700_000_000 - i}"}"""
        }
        library.sync()
    }

    /** Publishes the same English list on each of [days], ["Film 1" to 100] style. */
    private fun everyDay(days: List<String>, vararg articles: Pair<String, Long>, wiki: String = "en") = days.forEach { cloud.trending(wiki, it, *articles) }

    private val week = listOf("2026-11-06", "2026-11-05", "2026-11-04", "2026-11-03", "2026-11-02", "2026-11-01", "2026-10-31")

    private suspend fun rows(contentLanguages: List<String> = english, appLanguage: String = "en", now: Instant = Instant.now()) =
        library.homeRows(contentLanguages, appLanguage, "CA", emptyList(), now)

    private fun List<HomeRow>.trending() = filterIsInstance<HomeRow.Trending>().singleOrNull()?.titles?.map { it.name }

    @Test fun `a Title in the English list four days running outranks one in it twice, the week's views summed`() = runBlocking {
        syncMovies("EN - Twice (2025)" to 1, "EN - Four days (2025)" to 2, "EN - Once (2025)" to 3)
        // Twice has the most views on any one day, yet fewer over the week than Four days.
        everyDay(week.take(2), "Twice" to 150_000, "Four days" to 100_000)
        everyDay(week.subList(2, 4), "Four days" to 100_000)
        cloud.trending("en", week[6], "Once" to 120_000)

        assertEquals(listOf("Four days", "Twice", "Once"), rows().trending())
    }

    @Test fun `a Title in both wikis counts once, with its larger sum`() = runBlocking {
        syncMovies(
            "EN - The Godfather (1972)" to 1, "EN - Jailer 3 (2026)" to 2, "EN - MobLand (2025)" to 3,
            frwiki = mapOf(1L to "Le Parrain", 3L to "MobLand"),
        )
        cloud.trending("en", week[0], "Jailer 3" to 300_000, "MobLand" to 200_000, "The Godfather" to 10_000)
        // French readers looked The Godfather up more than all of English Wikipedia's did.
        cloud.trending("fr", week[0], "Le Parrain" to 400_000, "MobLand" to 50_000)

        val french = rows(appLanguage = "fr").filterIsInstance<HomeRow.Trending>().single().titles
        assertEquals(listOf("The Godfather", "Jailer 3", "MobLand"), french.map { it.name })
        assertEquals(french.map { it.id }.distinct(), french.map { it.id })
        // In English, only English Wikipedia's readers count.
        assertEquals(listOf("Jailer 3", "MobLand", "The Godfather"), rows(appLanguage = "en", now = Instant.now() + Duration.ofHours(2)).trending())
        assertEquals(listOf("fr", "en"), cloud.trendingAsked)
    }

    @Test fun `a Title with no Version in a Content language is out, and one the Provider lacks or no article names is never in`() = runBlocking {
        syncMovies("EN - English (2025)" to 1, "FR - Français (2025)" to 2, "EN - Second (2025)" to 3, "EN - Third (2025)" to 4, "EN - Nameless (2025)" to 5, articles = mapOf(5L to null))
        cloud.trending("en", week[0], "Français" to 900, "Not on the Provider" to 800, "English" to 700, "Second" to 600, "Third" to 500, "Nameless (film)" to 400)

        assertEquals(listOf("English", "Second", "Third"), rows().trending())
    }

    @Test fun `a row under 3 Titles is absent, and its Titles go to New`() = runBlocking {
        syncMovies("EN - First (2025)" to 1, "EN - Second (2025)" to 2, "EN - Third (2025)" to 3)
        cloud.trending("en", week[0], "First" to 900, "Second" to 800)

        val rows = rows()
        assertNull(rows.trending())
        assertEquals(setOf("First", "Second", "Third"), rows.filterIsInstance<HomeRow.NewOnYourService>().single().titles.map { it.name }.toSet())
    }

    @Test fun `entering Home twice within an hour asks the feed once, across a restart too, and again after it`() = runBlocking {
        syncMovies("EN - First (2025)" to 1, "EN - Second (2025)" to 2, "EN - Third (2025)" to 3)
        cloud.trending("en", week[0], "First" to 900, "Second" to 800, "Third" to 700)
        val now = Instant.now()
        rows(now = now)
        rows(now = now + Duration.ofMinutes(30))
        assertEquals(listOf("en"), cloud.trendingAsked)

        library.close()
        library = open()
        rows(now = now + Duration.ofMinutes(50))
        assertEquals(listOf("en"), cloud.trendingAsked)

        rows(now = now + Duration.ofMinutes(70))
        assertEquals(listOf("en", "en"), cloud.trendingAsked)
    }

    @Test fun `with the cloud down, last time's feed gives the same row`() = runBlocking {
        syncMovies("EN - First (2025)" to 1, "EN - Second (2025)" to 2, "EN - Third (2025)" to 3, "EN - Fourth (2025)" to 4)
        cloud.trending("en", week[0], "Third" to 900, "Second" to 800, "First" to 700)
        val before = rows().trending()

        cloud.down = true
        library.close()
        library = open()
        assertEquals(before, rows(now = Instant.now() + Duration.ofHours(3)).trending())
        assertEquals(listOf("Third", "Second", "First"), before)
    }

    @Test fun `with no feed kept and the cloud down, there is no Trending row and Home has every other`() = runBlocking {
        syncMovies("EN - First (2025)" to 1, "EN - Second (2025)" to 2, "EN - Third (2025)" to 3)
        cloud.trending("en", week[0], "First" to 900, "Second" to 800, "Third" to 700)
        cloud.down = true

        val rows = rows()
        assertTrue(rows.none { it is HomeRow.Trending })
        assertEquals(setOf("First", "Second", "Third"), rows.filterIsInstance<HomeRow.NewOnYourService>().single().titles.map { it.name }.toSet())
    }

    @Test fun `the Movies and Shows tabs' Trending hold their kind alone, Home's both`() = runBlocking {
        listOf(501L to "Slow Horses", 502L to "Black Doves", 503L to "The Diplomat").forEach { (id, name) -> cloud.titleFacts(show(id), originalLanguage = "en", enwiki = name) }
        provider.showCategoriesOverride = """[{"category_id":"2","category_name":"SERIES"}]"""
        provider.showListOverride = """[
            {"series_id":1,"name":"EN - Slow Horses (2022)","tmdb":"501","category_id":"2","last_modified":"1600000000"},
            {"series_id":2,"name":"EN - Black Doves (2024)","tmdb":"502","category_id":"2","last_modified":"1600000000"},
            {"series_id":3,"name":"EN - The Diplomat (2023)","tmdb":"503","category_id":"2","last_modified":"1600000000"}
        ]"""
        syncMovies("EN - Jailer 3 (2026)" to 1, "EN - Godzilla Minus One (2023)" to 2, "EN - xXx (2002)" to 3)
        cloud.trending("en", week[0], "Jailer 3" to 900, "Slow Horses" to 850, "Godzilla Minus One" to 800, "Black Doves" to 750, "xXx" to 700, "The Diplomat" to 650)

        assertEquals(listOf("Jailer 3", "Slow Horses", "Godzilla Minus One", "Black Doves", "xXx", "The Diplomat"), rows().trending())
        val movies = library.tabRows(TitleKind.Movie, english, "en", Instant.now())
        val shows = library.tabRows(TitleKind.Show, english, "en", Instant.now())
        assertEquals(listOf("Jailer 3", "Godzilla Minus One", "xXx"), movies.trending())
        assertEquals(TitleKind.Movie, movies.filterIsInstance<HomeRow.Trending>().single().kind)
        assertEquals(listOf("Slow Horses", "Black Doves", "The Diplomat"), shows.trending())
    }
}
