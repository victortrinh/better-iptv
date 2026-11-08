package dev.betteriptv.core

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Duration

/**
 * Which Episode a Show's details open on, how far the Viewer got in each, and that a Show lists
 * the Episodes the Provider has and no others, seen through the Library.
 */
class NextEpisodeTest {
    @get:Rule val folder = TemporaryFolder()
    private val provider = FakeProvider()
    private val cloud = FakeCloud()
    private lateinit var library: Library

    private val english = listOf("en")
    private val fullHdTv = DeviceProfile(maxHeight = 1080, hdr = emptySet())
    private val bureau = TitleId.Numbered(TitleKind.Show, 62104)

    /** Syncs The Bureau: seasons 1 and 2 of three Episodes each, and one special. */
    @Before fun syncShow() = runBlocking {
        library = Library.open(folder.root.resolve("catalog.db").path, InMemoryCredentials(), cloud.url)
        library.logIn(provider.login)
        provider.showCategoriesOverride = """[{"category_id":"1","category_name":"SERIES"}]"""
        provider.showListOverride = """[{"series_id":1,"name":"EN - The Bureau (2015)","tmdb":"62104","category_id":"1","last_modified":"1700000000"}]"""
        val numbers = listOf(1 to 1, 1 to 2, 1 to 3, 2 to 1, 2 to 2, 2 to 3, 0 to 1)
        provider.showDetailsOverride[1] = details(numbers.mapIndexed { i, (season, number) -> episode(100L + i, season, number) })
        library.sync()
        // Episodes play from the show details read when the Show's details opened.
        library.episodes(bureau, english)
        Unit
    }

    @After fun tearDown() {
        library.close()
        provider.close()
        cloud.close()
    }

    private suspend fun episodes(appLanguage: String = "en") = library.episodes(bureau, english, appLanguage) ?: throw AssertionError("no Show")

    private fun ShowEpisodes.episode(season: Int, number: Int) = seasons.single { it.number == season }.episodes.single { it.number == number }

    private suspend fun plan(season: Int, number: Int) =
        library.episodePlayPlan(bureau, EpisodeNumber(season, number), english, fullHdTv) ?: throw AssertionError("no S${season}E$number")

    /** Plays S[season]E[number] to [minutes] of 50, as Playback records it. */
    private suspend fun play(season: Int, number: Int, minutes: Int) {
        library.saveProgress(plan(season, number), position = minutes * 60_000L, duration = 50 * 60_000L)
        // Each play is later than the last.
        Thread.sleep(5)
    }

    @Test fun `a Show not started opens on its first Episode`() = runBlocking {
        val show = episodes()

        assertEquals(EpisodeNumber(1, 1), show.next)
        assertTrue(show.seasons.flatMap { it.episodes }.all { it.progress == null && !it.watched })
    }

    @Test fun `it opens on the Episode after the one last Watched, or on the one the Viewer stopped in`() = runBlocking {
        play(1, 1, minutes = 48)
        play(1, 2, minutes = 47)
        assertEquals(EpisodeNumber(1, 3), episodes().next)

        play(1, 3, minutes = 20)
        val show = episodes()
        assertEquals(EpisodeNumber(1, 3), show.next)
        assertEquals(0.4f, show.episode(1, 3).progress)
        assertFalse(show.episode(1, 3).watched)
        assertEquals(1f, show.episode(1, 2).progress)
        assertTrue(show.episode(1, 2).watched)
        // Playing it again continues where the Viewer stopped; a Watched one starts over.
        assertEquals(20 * 60_000L, plan(1, 3).from)
        assertEquals(0L, plan(1, 2).from)
    }

    @Test fun `after a season's last Episode comes the next season's first, and after the Show's last it stays there`() = runBlocking {
        play(1, 3, minutes = 49)
        assertEquals(EpisodeNumber(2, 1), episodes().next)

        // The specials, listed last, aren't next after the last season.
        play(2, 3, minutes = 49)
        assertEquals(EpisodeNumber(2, 3), episodes().next)

        // Going back to an earlier one counts from there.
        play(1, 1, minutes = 49)
        assertEquals(EpisodeNumber(1, 2), episodes().next)
    }

    @Test fun `choosing a Version in an Episode's Versions plays that Episode from where the Viewer stopped`() = runBlocking {
        play(1, 2, minutes = 20)
        val chosen = episodes().episode(1, 2).languages.single().versions.single()

        val plan = library.chosenEpisodePlayPlan(bureau, EpisodeNumber(1, 2), chosen.listingId, english) ?: throw AssertionError("no plan")

        assertEquals(chosen.listingId, plan.version.listingId)
        assertEquals(EpisodeNumber(1, 2), plan.episode)
        assertEquals(20 * 60_000L, plan.from)
        // Another Episode's Version isn't this Episode's.
        assertNull(library.chosenEpisodePlayPlan(bureau, EpisodeNumber(1, 3), chosen.listingId, english))
    }

    @Test fun `the first minute of an Episode isn't recorded`() = runBlocking {
        play(2, 1, minutes = 0)

        assertEquals(EpisodeNumber(1, 1), episodes().next)
    }

    @Test fun `playing an Episode to its end Watches it`() = runBlocking {
        library.finished(plan(1, 1))

        assertEquals(EpisodeNumber(1, 2), episodes().next)
        assertTrue(episodes().episode(1, 1).watched)
    }

    @Test fun `a Show lists the Episodes the Provider has and no others`() = runBlocking {
        val show = episodes(appLanguage = "fr-CA")

        assertEquals(listOf(1, 2, 3), show.seasons.single { it.number == 2 }.episodes.map { it.number })
        assertTrue(cloud.received.none { "/v1/upcoming" in it.path })
    }

    @Test fun `Continue watching shows a Show at its next Episode, beside Movies, until the Viewer Watched its last`() = runBlocking {
        suspend fun resumes() = library.homeRows(english, appLanguage = "en").filterIsInstance<HomeRow.ContinueWatching>().singleOrNull()?.titles.orEmpty()
        assertTrue(resumes().isEmpty())

        play(1, 1, minutes = 20)
        var show = resumes().single()
        assertEquals(bureau, show.title.id)
        assertEquals(NextEpisode(EpisodeNumber(1, 1), name = null, started = true), show.episode)
        assertEquals(0.4f, show.progress)
        assertEquals(Duration.ofMinutes(30), show.left)

        // Watched, the next one waits to be started.
        play(1, 1, minutes = 49)
        show = resumes().single()
        assertEquals(NextEpisode(EpisodeNumber(1, 2), name = null, started = false), show.episode)
        assertNull(show.progress)
        assertNull(show.left)

        // The last of season 2 Watched (specials don't count), the Show leaves the row.
        play(2, 3, minutes = 49)
        assertTrue(resumes().isEmpty())
    }

    private fun details(episodes: List<String>) = """{"seasons":[],"info":{},"episodes":[${episodes.joinToString(",")}]}"""

    private fun episode(id: Long, season: Int, number: Int) =
        """{"id":"$id","episode_num":$number,"season":$season,"title":"EN - The Bureau (2015) - S0${season}E0$number","container_extension":"mkv","added":"1700000000",""" +
            """"info":{"video":{"width":1920,"height":1080},"audio":{"tags":{"language":"eng"}}}}"""
}
