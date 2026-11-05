package dev.betteriptv.core

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * What the Episode picker (#347) reads from the Library: the Show's usual language set, an Episode's
 * languages only where they differ from it, and each season's TMDB stills and runtimes.
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

    @Test fun `an Episode not out yet doesn't count towards the usual set`() {
        val english = LanguageGroup("en", listOf(Version(1, "EN - Show", VersionAttributes())))
        val aired = Episode(1, 1, null, listOf(english), emptyList())
        val coming = (2..4).map { Episode(1, it, null, emptyList(), emptyList(), availableOn = java.time.LocalDate.of(2026, 12, 1)) }

        assertEquals(setOf("en"), ShowEpisodes(listOf(Season(1, listOf(aired) + coming)), 0).usualLanguages)
    }

    @Test fun `a season's stills and runtimes come from the cloud in the App language, once a run`() = runBlocking {
        syncLoveIsBlind()
        cloud.season(loveIsBlind.tmdbId, 2, "fr", Triple(1, "/one.jpg", 52), Triple(2, null, null))

        val stills = library.seasonStills(loveIsBlind, 2, appLanguage = "fr-CA")
        library.seasonStills(loveIsBlind, 2, appLanguage = "fr-CA")

        assertEquals(EpisodeStill(1, "https://image.tmdb.org/t/p/w300/one.jpg", 52), stills[1])
        assertEquals(EpisodeStill(2, null, null), stills[2])
        assertEquals(listOf("123250/2/fr"), cloud.seasonsAsked)
    }

    @Test fun `a Show TMDB doesn't know has no stills, and nothing is asked`() = runBlocking {
        val stills = library.seasonStills(TitleId.Unmatched(TitleKind.Show, 1), 1)

        assertTrue(stills.isEmpty())
        assertTrue(cloud.seasonsAsked.isEmpty())
    }

    private fun details(episodes: List<String>) = """{"seasons":[],"info":{},"episodes":[${episodes.joinToString(",")}]}"""

    private fun episode(id: Long, season: Int, number: Int, language: String) =
        """{"id":"$id","episode_num":$number,"season":$season,"title":"Love Is Blind - S0${season}E0$number","container_extension":"mkv","added":"1700000000",""" +
            """"info":{"video":{"width":1920,"height":1080},"audio":{"tags":{"language":"$language"}}}}"""
}
