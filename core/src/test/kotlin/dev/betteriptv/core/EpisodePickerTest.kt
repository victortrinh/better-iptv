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
 * What the Episode picker (#347) reads from the Library: the Show's usual language set, an Episode's
 * languages only where they differ from it, and each Episode's still and runtime from the Provider's
 * show details (#473).
 */
class EpisodePickerTest {
    @get:Rule val folder = TemporaryFolder()
    private val provider = FakeProvider()
    private val cloud = FakeCloud()
    private val credentials = InMemoryCredentials()
    private lateinit var library: Library

    private val loveIsBlind = TitleId.Tmdb(TitleKind.Show, 123250)

    @Before fun logIn() = runBlocking {
        library = Library.open(folder.root.resolve("catalog.db").path, credentials, cloud.url)
        library.logIn(provider.login)
        Unit
    }

    @After fun tearDown() {
        library.close()
        provider.close()
        cloud.close()
    }

    /** Love is Blind in English and French, two seasons of three Episodes, the French Version missing S2 E3. */
    private suspend fun syncLoveIsBlind() {
        provider.showCategoriesOverride = """[{"category_id":"1","category_name":"SERIES"}]"""
        provider.showListOverride = """[
            {"series_id":1,"name":"EN - Love Is Blind (2020)","tmdb":"123250","category_id":"1","last_modified":"1700000001"},
            {"series_id":2,"name":"FR - Love Is Blind (2020)","tmdb":"123250","category_id":"1","last_modified":"1700000000"}
        ]"""
        val numbers = (1..2).flatMap { season -> (1..3).map { season to it } }
        provider.showDetailsOverride[1] = details(numbers.map { (season, number) -> episode(100L + season * 10 + number, season, number, "eng") })
        provider.showDetailsOverride[2] = details(numbers.filter { it != (2 to 3) }.map { (season, number) -> episode(200L + season * 10 + number, season, number, "fre") })
        library.sync()
    }

    private fun ShowEpisodes.episode(season: Int, number: Int): Episode =
        seasons.single { it.number == season }.episodes.single { it.number == number }

    @Test fun `the Show's usual languages are the ones most of its Episodes have, and an Episode names its own only when they differ`() = runBlocking {
        syncLoveIsBlind()

        val show = library.episodes(loveIsBlind, listOf("fr", "en")) ?: throw AssertionError("no Show")

        assertEquals(setOf("en", "fr"), show.usualLanguages)
        // Five of six Episodes are in both: they say nothing.
        assertEquals(emptyList<String>(), show.languagesNamed(show.episode(1, 1)))
        assertEquals(emptyList<String>(), show.languagesNamed(show.episode(2, 2)))
        // The one only in English says so.
        assertEquals(listOf("en"), show.languagesNamed(show.episode(2, 3)))
    }

    @Test fun `a language that isn't named is never shown as one`() {
        val english = LanguageGroup("en", listOf(Version(1, "EN - Show", VersionAttributes())))
        val untagged = LanguageGroup(null, listOf(Version(2, "Show", VersionAttributes())))
        val episodes = (1..3).map { Episode(1, it, null, listOf(english), emptyList()) } +
            Episode(1, 4, null, listOf(english), listOf(untagged)) +
            Episode(1, 5, null, emptyList(), listOf(untagged))
        val show = ShowEpisodes(listOf(Season(1, episodes)), 0)

        assertEquals(setOf("en"), show.usualLanguages)
        // English and an unnamed Version is the usual set, as far as the Viewer can tell.
        assertEquals(emptyList<String>(), show.languagesNamed(episodes[3]))
        // Only an unnamed Version: nothing to name.
        assertEquals(emptyList<String>(), show.languagesNamed(episodes[4]))
    }

    @Test fun `an Episode's still and runtime come from the Provider's show details, and nothing is asked of the cloud`() = runBlocking {
        library.sync()
        cloud.received.clear()

        // The fixture's Der Scheich: eight Episodes, each with a still and a length (#473).
        val show = library.episodes(TitleId.Tmdb(TitleKind.Show, 216963), listOf("de")) ?: throw AssertionError("no Show")

        val first = show.episode(1, 1)
        assertEquals("Die Natur lügt nicht", first.name)
        assertTrue(first.still!!, first.still!!.startsWith("https://stills.example/"))
        // 3,031 s, rounded to the minute.
        assertEquals(51, first.runtimeMinutes)
        assertEquals(8, show.seasons.single().episodes.size)
        assertTrue(show.seasons.single().episodes.all { it.still != null && it.runtimeMinutes != null })
        assertTrue(cloud.received.none { "/v1/season" in it.path || "/v1/upcoming" in it.path })
    }

    @Test fun `an Episode whose details give no still or length has none`() = runBlocking {
        syncLoveIsBlind()

        val show = library.episodes(loveIsBlind, listOf("fr", "en")) ?: throw AssertionError("no Show")

        assertNull(show.episode(1, 1).still)
        assertNull(show.episode(1, 1).runtimeMinutes)
    }

    private fun details(episodes: List<String>) = """{"seasons":[],"info":{},"episodes":[${episodes.joinToString(",")}]}"""

    private fun episode(id: Long, season: Int, number: Int, language: String) =
        """{"id":"$id","episode_num":$number,"season":$season,"title":"Love Is Blind - S0${season}E0$number","container_extension":"mkv","added":"1700000000",""" +
            """"info":{"video":{"width":1920,"height":1080},"audio":{"tags":{"language":"$language"}}}}"""
}
