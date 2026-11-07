package dev.betteriptv.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch

/**
 * What the Sync status tells the Viewer while a Sync reads the catalog, in two stages, and what the
 * Matching status tells once its catalog lands; how a later Sync's catalog is held for the app.
 */
class SyncProgressTest {
    @get:Rule val folder = TemporaryFolder()
    private val provider = FakeProvider()
    private val cloud = FakeCloud()
    private val credentials = InMemoryCredentials()
    private lateinit var library: Library

    /** The status each time the Provider is asked for its movie Categories, once the Channels are read. */
    private val atMovies = CopyOnWriteArrayList<SyncStatus>()

    /** The status each time the Provider is asked for its show Categories, once the movies are read. */
    private val atShows = CopyOnWriteArrayList<SyncStatus>()

    @Before fun logIn() = runBlocking {
        library = Library.open(folder.root.resolve("catalog.db").path, credentials, cloud.url)
        library.logIn(provider.login)
        provider.serveChannels("CA: CBC HD" to "CA | NEWS", "CA: CTV HD" to "CA | NEWS", "US: CNN HD" to "US | NEWS")
        provider.onRequest = { action ->
            when (action) {
                "get_vod_categories" -> atMovies += library.syncStatus.value
                "get_series_categories" -> atShows += library.syncStatus.value
            }
        }
    }

    @After fun tearDown() {
        library.close()
        provider.close()
        cloud.close()
    }

    @Test fun `a first Sync lands the Channels before it reads the movies`() = runBlocking {
        var categoriesAtMovies: List<LiveCategory>? = null
        var moviesAtMovies = -1
        provider.onRequest = { action ->
            if (action == "get_vod_categories") {
                atMovies += library.syncStatus.value
                categoriesAtMovies = runBlocking { library.liveCategories() }
                moviesAtMovies = library.movieListingCount()
            }
        }

        library.sync()

        val running = atMovies.single() as SyncStatus.Running
        assertEquals(SyncStep.Movies, running.step)
        assertNull(running.last)
        assertNotNull(running.channelsAt)
        assertEquals(3, running.entriesRead)
        assertNull(running.fraction)
        assertEquals(listOf("CA | NEWS", "US | NEWS"), categoriesAtMovies!!.map { it.name })
        assertEquals(0, moviesAtMovies)
    }

    @Test fun `the status names each step in order, then the Sync is Synced`() = runBlocking {
        val seen = CopyOnWriteArrayList<SyncStatus>()
        val watching = launch(Dispatchers.Unconfined) { library.syncStatus.collect { seen += it } }

        val finished = library.sync()
        watching.cancel()

        assertEquals(listOf(SyncStep.Channels, SyncStep.Movies, SyncStep.Shows, SyncStep.Saving), seen.mapNotNull { (it as? SyncStatus.Running)?.step }.distinct())
        assertTrue(finished is SyncStatus.Synced)
        assertEquals(finished, seen.last())
        // The fixture's 156 movie Listings, less 3 music videos and 3 Sports replays, and 46 shows, after 3 Channels.
        assertEquals(3 + 150 + 46, (seen.last { it is SyncStatus.Running } as SyncStatus.Running).entriesRead)
        assertEquals(3, (finished as SyncStatus.Synced).last.channelListingCount)
    }

    @Test fun `a later Sync tells how far it is by the last Sync's counts`() = runBlocking {
        val last = (library.sync() as SyncStatus.Synced).last
        assertEquals(3 + 150 + 46, last.channelListingCount + last.movieListingCount + last.showListingCount)

        library.sync()

        val running = atShows.last() as SyncStatus.Running
        assertEquals(SyncStatus.Running(last, last.finishedAt, SyncStep.Shows, 153), running)
        assertEquals(153f / 199, running.fraction!!, 0.0001f)
    }

    @Test fun `a Sync that reads more than the last one never says it's done before it is`() = runBlocking {
        library.sync()
        provider.generatedMovies = 400

        library.sync()

        assertEquals(0.99f, (atShows.last() as SyncStatus.Running).fraction!!, 0.0001f)
    }

    @Test fun `the Sync's duration counts its swap`() = runBlocking {
        val before = System.nanoTime()
        var saving: Long? = null
        val watching = launch(Dispatchers.Unconfined) {
            library.syncStatus.collect { if (it is SyncStatus.Running && it.step == SyncStep.Saving && saving == null) saving = System.nanoTime() }
        }

        val finished = library.sync() as SyncStatus.Synced
        watching.cancel()

        assertNotNull(saving)
        assertTrue(finished.last.duration.toNanos() >= saving!! - before)
    }

    @Test fun `a held Sync keeps the catalog on screen until it is applied`() = runBlocking {
        val first = (library.sync() as SyncStatus.Synced).last
        provider.generatedMovies = 400
        val changesBefore = library.catalogChanges.value

        val held = library.sync(hold = true)

        assertEquals(SyncStatus.Ready(first, (held as SyncStatus.Ready).computedAt), held)
        assertTrue(library.hasPendingCatalog)
        assertEquals(150, library.movieListingCount())
        assertEquals(changesBefore, library.catalogChanges.value)

        assertTrue(library.applyPendingCatalog())

        assertFalse(library.hasPendingCatalog)
        assertEquals(400, library.movieListingCount())
        assertEquals(400, (library.syncStatus.value as SyncStatus.Synced).last.movieListingCount)
        assertTrue(library.catalogChanges.value > changesBefore)
        assertFalse(library.applyPendingCatalog())
    }

    @Test fun `a held catalog survives reopening and is applied then`() = runBlocking {
        library.sync()
        provider.generatedMovies = 400
        library.sync(hold = true)
        library.close()

        library = Library.open(folder.root.resolve("catalog.db").path, credentials, cloud.url)

        assertTrue(library.syncStatus.value is SyncStatus.Ready)
        assertEquals(150, library.movieListingCount())
        assertTrue(library.applyPendingCatalog())
        assertEquals(400, library.movieListingCount())
    }

    @Test fun `a Sync started over a held catalog discards it`() = runBlocking {
        library.sync()
        provider.generatedMovies = 400
        library.sync(hold = true)
        provider.generatedMovies = 20
        var pendingAtAccount: Boolean? = null
        provider.onRequest = { action -> if (action == "get_vod_categories") pendingAtAccount = library.hasPendingCatalog }

        library.sync()

        assertEquals(false, pendingAtAccount)
        assertEquals(20, library.movieListingCount())
    }

    @Test fun `applying while a Sync runs does nothing and doesn't wait`() = runBlocking {
        library.sync()
        provider.generatedMovies = 400
        library.sync(hold = true)
        val reached = CountDownLatch(1)
        val release = CountDownLatch(1)
        provider.onRequest = { action ->
            if (action == "get_series_categories") {
                reached.countDown()
                release.await()
            }
        }
        val sync = async(Dispatchers.IO) { library.sync() }
        reached.await()

        val applied = withTimeout(5_000) { library.applyPendingCatalog() }

        assertFalse(applied)
        release.countDown()
        sync.await()
        assertEquals(400, library.movieListingCount())
    }

    @Test fun `a first Sync that fails after the Channels landed keeps them and names the step`() = runBlocking {
        provider.cutMovieListMidway = true

        val status = library.sync() as SyncStatus.Failed

        assertTrue(status.problem is Unreachable)
        assertNull(status.last)
        assertNotNull(status.channelsAt)
        assertEquals(SyncStep.Movies, status.step)
        assertEquals(listOf("CA | NEWS", "US | NEWS"), library.liveCategories().map { it.name })
        library.close()

        library = Library.open(folder.root.resolve("catalog.db").path, credentials, cloud.url)

        assertEquals(SyncStatus.NeverSynced(status.channelsAt), library.syncStatus.value)
        assertEquals(2, library.liveCategories().size)
    }

    @Test fun `the Sync finishes even when the unknown-Prefix report fails`() = runBlocking {
        provider.movieCategoriesOverride = """[{"category_id":"1","category_name":"XB - MOVIES"}]"""
        provider.movieListOverride = """[{"stream_id":1,"name":"XB - Cold Pursuit (2019)","tmdb":"","category_id":"1","added":"1700000000"}]"""
        provider.showCategoriesOverride = "[]"
        provider.showListOverride = "[]"
        cloud.failingPrefixReports = true

        val status = library.sync() as SyncStatus.Synced

        assertEquals(listOf(UnknownPrefix("XB", 1, "XB - Cold Pursuit (2019)")), status.last.unknownPrefixes)
        assertEquals(status, library.syncStatus.value)
    }

    @Test fun `a held Sync asks the cloud about its Titles once its catalog is applied, not before`() = runBlocking {
        library.sync()
        provider.movieCategoriesOverride = """[{"category_id":"1","category_name":"MOVIES"}]"""
        provider.movieListOverride = """[{"stream_id":1,"name":"EN - Heat (1995)","tmdb":"949","category_id":"1","added":"1700000000"}]"""
        val askedBefore = factsRequests()

        library.sync(hold = true)

        assertEquals(askedBefore, factsRequests())
        library.applyPendingCatalog()
        assertTrue(factsRequests() > askedBefore)
    }

    private fun factsRequests() = cloud.received.count { it.path == "/v1/title-facts" }
}
