package dev.betteriptv.core

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * History sync between a household's TVs (#435): two devices, each with its own catalog database,
 * over one Provider and one cloud, faked.
 */
class HistorySyncTest {
    @get:Rule val folder = TemporaryFolder()
    private val provider = FakeProvider()
    private val cloud = FakeCloud()
    private val libraries = mutableListOf<Library>()

    @After fun tearDown() {
        libraries.forEach { it.close() }
        provider.close()
        cloud.close()
    }

    private val english = listOf("en")
    private val fullHdTv = DeviceProfile(maxHeight = 1080, hdr = emptySet())
    private val first = TitleId.Tmdb(TitleKind.Movie, 1)
    private val second = TitleId.Tmdb(TitleKind.Movie, 2)
    private val homeVideo = TitleId.Unmatched(TitleKind.Movie, 3)
    private val bureau = TitleId.Tmdb(TitleKind.Show, 62104)
    private val hour = 60 * 60_000L

    /** A TV of the household, logged in to the Provider (at [address], the same host by default) and Synced. */
    private suspend fun tv(name: String, address: String = provider.url): Library {
        val library = Library.open(folder.root.resolve("$name.db").path, InMemoryCredentials(), cloud.url).also { libraries += it }
        provider.movieCategoriesOverride = """[{"category_id":"1","category_name":"EN - MOVIES"}]"""
        provider.movieListOverride = listOf("EN - First (2020)" to "1", "EN - Second (2021)" to "2", "EN - Home Video (2019)" to "").withIndex().joinToString(",", "[", "]") { (i, movie) ->
            """{"stream_id":${i + 1},"name":"${movie.first}","tmdb":"${movie.second}","category_id":"1","added":"${1_700_000_000 - i}","container_extension":"mkv"}"""
        }
        provider.showCategoriesOverride = """[{"category_id":"2","category_name":"SERIES"}]"""
        provider.showListOverride = """[{"series_id":10,"name":"EN - The Bureau (2015)","tmdb":"62104","category_id":"2","last_modified":"1700000000"}]"""
        provider.showDetailsOverride[10] = """{"seasons":[],"info":{},"episodes":[${(1..3).joinToString(",") { episode(100L + it, 1, it) }}]}"""
        library.logIn(ProviderLogin(address, provider.login.username, provider.login.password))
        library.sync()
        library.episodes(bureau, english)
        return library
    }

    private fun episode(id: Long, season: Int, number: Int) =
        """{"id":"$id","episode_num":$number,"season":$season,"title":"EN - The Bureau (2015) - S0${season}E0$number","container_extension":"mkv","added":"1700000000",""" +
            """"info":{"video":{"width":1920,"height":1080},"audio":{"tags":{"language":"eng"}}}}"""

    private suspend fun Library.signIn(email: String = "home@example.com") {
        assertTrue(cloud.phoneSignsIn(startSignIn()!!.code, email))
        assertEquals(email, checkSignIn())
    }

    /** Stops [id] at [position] of 2 hours, then pushes, as the player does when the Viewer leaves it. */
    private fun Library.stop(id: TitleId, position: Long) {
        saveProgress(id, position, duration = 2 * hour)
        Thread.sleep(5)
    }

    /** "Remove from Continue watching" on the Title's page. */
    private suspend fun Library.remove(id: TitleId) {
        removeFromContinueWatching(id)
        Thread.sleep(5)
    }

    private suspend fun Library.stopEpisode(number: Int, position: Long) {
        saveProgress(episodePlayPlan(bureau, EpisodeNumber(1, number), english, fullHdTv)!!, position, duration = hour)
        Thread.sleep(5)
    }

    /** A pull and a screen entry: what the TV shows once the Viewer moves to Home. */
    private suspend fun Library.pullAndEnter() {
        assertTrue(syncHistory())
        applyPendingHistory()
    }

    private suspend fun Library.continueWatching() =
        homeRows(english, "en").filterIsInstance<HomeRow.ContinueWatching>().singleOrNull()?.titles.orEmpty()

    private suspend fun Library.watching() = continueWatching().map { it.title.id }

    private suspend fun Library.from(id: TitleId) = playPlan(id, english, fullHdTv)?.from

    @Test fun `a stop on one TV shows in the other's Continue watching after its next pull, at the same place and Episode`() = runBlocking {
        val living = tv("living")
        val bedroom = tv("bedroom")
        living.signIn()
        bedroom.signIn()
        living.stop(first, 62 * 60_000L)
        living.stopEpisode(2, 20 * 60_000L)
        assertTrue(living.syncHistory())

        assertEquals(emptyList<TitleId>(), bedroom.watching())
        bedroom.pullAndEnter()

        val watching = bedroom.continueWatching()
        assertEquals(listOf(bureau, first), watching.map { it.title.id })
        assertEquals(EpisodeNumber(1, 2), watching[0].episode?.number)
        assertTrue(watching[0].episode!!.started)
        assertEquals(62 * 60_000L, bedroom.from(first))
        assertEquals(20 * 60_000L, bedroom.episodePlayPlan(bureau, EpisodeNumber(1, 2), english, fullHdTv)!!.from)
    }

    @Test fun `a removal and a later watch on the other TV resolve by when each was made, either way`() = runBlocking {
        val living = tv("living")
        val bedroom = tv("bedroom")
        living.signIn()
        bedroom.signIn()
        living.stop(first, hour)
        living.stop(second, hour)
        living.syncHistory()
        bedroom.pullAndEnter()

        // Removed here, then watched there: the watch wins on both.
        living.remove(first)
        bedroom.stop(first, hour + 60_000L)
        // Watched there, then removed here: the removal wins on both, whichever pushes first.
        bedroom.stop(second, hour + 60_000L)
        living.remove(second)
        bedroom.syncHistory()
        living.syncHistory()
        living.pullAndEnter()
        bedroom.pullAndEnter()

        for (tv in listOf(living, bedroom)) {
            assertEquals(listOf(first), tv.watching())
            assertEquals(hour + 60_000L, tv.from(first))
            assertEquals(0L, tv.from(second))
        }
    }

    @Test fun `two TVs watching the same Title, the last to stop wins`() = runBlocking {
        val living = tv("living")
        val bedroom = tv("bedroom")
        living.signIn()
        bedroom.signIn()
        bedroom.stop(first, 30 * 60_000L)
        living.stop(first, 90 * 60_000L)
        // The bedroom's older stop reaches the cloud last.
        living.syncHistory()
        bedroom.syncHistory()
        living.pullAndEnter()
        bedroom.pullAndEnter()

        assertEquals(90 * 60_000L, living.from(first))
        assertEquals(90 * 60_000L, bedroom.from(first))
    }

    @Test fun `pulled rows don't change Home until the next screen entry, but a Title's Play continues from them at once`() = runBlocking {
        val living = tv("living")
        val bedroom = tv("bedroom")
        living.signIn()
        bedroom.signIn()
        living.stop(first, 62 * 60_000L)
        living.stop(second, 10 * 60_000L)
        living.syncHistory()

        assertTrue(bedroom.syncHistory())
        val changes = bedroom.catalogChanges.value
        assertEquals(emptyList<TitleId>(), bedroom.watching())
        assertEquals(changes, bedroom.catalogChanges.value)

        // The Title page reads where the other TV stopped before Play.
        assertEquals(62 * 60_000L, bedroom.from(first))

        assertTrue(bedroom.applyPendingHistory())
        assertEquals(listOf(second, first), bedroom.watching())
        assertFalse(bedroom.applyPendingHistory())
    }

    @Test fun `a TV offline queues its changes and pushes them once the cloud is back`() = runBlocking {
        val living = tv("living")
        val bedroom = tv("bedroom")
        living.signIn()
        bedroom.signIn()
        cloud.down = true
        living.stop(first, hour)
        living.stoppedPlaying()
        living.remove(first)
        living.stop(second, 2 * 60_000L)
        assertFalse(living.syncHistory())

        cloud.down = false
        assertTrue(living.syncHistory())
        bedroom.pullAndEnter()
        assertEquals(listOf(second), bedroom.watching())
        // Once pushed, nothing is pushed again.
        living.syncHistory()
        assertEquals(emptyList<Any>(), cloud.syncsAsked.last()["progress"])
    }

    @Test fun `signing in merges the TV's history into the Account, the latest change winning`() = runBlocking {
        val living = tv("living")
        val bedroom = tv("bedroom")
        bedroom.signIn()
        bedroom.stop(first, 30 * 60_000L)
        bedroom.syncHistory()
        // The living room watched before it signed in, later than the bedroom.
        living.stop(first, 70 * 60_000L)
        living.stop(second, 20 * 60_000L)

        living.signIn()
        living.pullAndEnter()
        bedroom.pullAndEnter()

        for (tv in listOf(living, bedroom)) {
            assertEquals(listOf(second, first), tv.watching())
            assertEquals(70 * 60_000L, tv.from(first))
        }
    }

    @Test fun `signing out leaves the TV with no history, and its catalog and login`() = runBlocking {
        val living = tv("living")
        living.signIn()
        living.stop(first, hour)
        living.syncHistory()

        cloud.signOut("home@example.com")
        assertNull(living.checkSignIn())

        assertEquals(emptyList<TitleId>(), living.watching())
        assertEquals(0L, living.from(first))
        assertTrue(living.isLoggedIn)
        assertTrue(living.syncStatus.value is SyncStatus.Synced)
        assertTrue(living.title(first, english, "en") != null && living.title(second, english, "en") != null)
        // Signing in again brings the Account's history back.
        living.signIn()
        living.pullAndEnter()
        assertEquals(listOf(first), living.watching())
    }

    @Test fun `a TV signed out from elsewhere learns it at its next sync and forgets the Account's history`() = runBlocking {
        val living = tv("living")
        living.signIn()
        living.stop(first, hour)
        living.syncHistory()

        cloud.signOut("home@example.com")
        assertFalse(living.syncHistory())

        assertNull(living.account.value)
        assertEquals(emptyList<TitleId>(), living.watching())
        assertTrue(living.isLoggedIn)
    }

    @Test fun `a Listing's progress stays with its Provider, and a TMDB ID's follows the Account to another`() = runBlocking {
        val living = tv("living")
        // The same panel by another name: another Provider host, whose Listing 3 may be anything.
        val elsewhere = tv("elsewhere", provider.url.replace("127.0.0.1", "localhost"))
        val sameProvider = tv("bedroom")
        listOf(living, elsewhere, sameProvider).forEach { it.signIn() }
        living.stop(homeVideo, hour)
        living.stop(first, hour)
        living.syncHistory()

        elsewhere.pullAndEnter()
        sameProvider.pullAndEnter()

        assertEquals(listOf(first), elsewhere.watching())
        assertEquals(0L, elsewhere.from(homeVideo))
        assertEquals(listOf(first, homeVideo), sameProvider.watching())
        assertEquals(hour, sameProvider.from(homeVideo))
        val pushed = cloud.syncsAsked.first { (it["progress"] as List<*>).isNotEmpty() }
        assertEquals(providerKey(provider.login), pushed["provider"])
    }

    @Test fun `a TV whose cursor is older than a removal the cloud dropped gets everything again, and forgets what isn't in it`() = runBlocking {
        val living = tv("living")
        living.signIn()
        living.stop(first, hour)
        living.stop(second, hour)
        living.pullAndEnter()

        cloud.purgeHistory("home@example.com", "movie/2")
        living.pullAndEnter()

        assertEquals(listOf(first), living.watching())
        assertEquals(0L, living.from(second))
    }

    @Test fun `a TV that never signs in keeps its history on the device and never syncs`() = runBlocking {
        val living = tv("living")
        living.stop(first, hour)
        living.stoppedPlaying()
        living.remove(second)

        assertFalse(living.syncHistory())
        assertFalse(living.applyPendingHistory())
        assertEquals(listOf(first), living.watching())
        assertTrue(cloud.received.none { it.path == "/v1/sync" })
    }
}
