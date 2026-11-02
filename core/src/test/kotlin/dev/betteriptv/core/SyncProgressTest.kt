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

    /** The Sync and Matching statuses each time the cloud is asked to Match a batch. */
    private val atMatch = CopyOnWriteArrayList<Pair<SyncStatus, MatchingStatus?>>()

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
        cloud.onMatch = { atMatch += library.syncStatus.value to library.matchingStatus.value }
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
        // The fixture's 156 movie Listings, less 3 music videos and 3 Sports replays, and 45 shows, after 3 Channels.
        assertEquals(3 + 150 + 45, (seen.last { it is SyncStatus.Running } as SyncStatus.Running).entriesRead)
        assertEquals(3, (finished as SyncStatus.Synced).last.channelListingCount)
    }

    @Test fun `a later Sync tells how far it is by the last Sync's counts`() = runBlocking {
        val last = (library.sync() as SyncStatus.Synced).last
        assertEquals(3 + 150 + 45, last.channelListingCount + last.movieListingCount + last.showListingCount)

        library.sync()

        val running = atShows.last() as SyncStatus.Running
        assertEquals(SyncStatus.Running(last, last.finishedAt, SyncStep.Shows, 153), running)
        assertEquals(153f / 198, running.fraction!!, 0.0001f)
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
        assertNull(library.matchingStatus.value)

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

    @Test fun `Matching counts the names asked under its own status while the Sync reads Synced`() = runBlocking {
        serveSuspects(60)
        (1..60).forEach { cloud.match("Lost Film $it", 1_000L + it) }

        val finished = library.sync() as SyncStatus.Synced

        val synced = (atMatch.first().first as SyncStatus.Synced).last
        assertEquals(0, synced.rematchedListings)
        assertTrue(atMatch.all { it.first is SyncStatus.Synced })
        assertEquals(listOf(MatchingStatus(0, 60), MatchingStatus(25, 60), MatchingStatus(50, 60)), atMatch.map { it.second })
        assertEquals(60, finished.last.rematchedListings)
        assertEquals(finished, library.syncStatus.value)
        assertNull(library.matchingStatus.value)
    }

    @Test fun `from the moment the new catalog shows, the Matching status says names are being found`() = runBlocking {
        serveSuspects(60)
        val seen = CopyOnWriteArrayList<Pair<SyncStatus, MatchingStatus?>>()
        val watching = launch(Dispatchers.Unconfined) { library.matchingStatus.collect { seen += library.syncStatus.value to it } }

        library.sync()
        watching.cancel()

        assertEquals(listOf(null, MatchingStatus(0, null), MatchingStatus(0, 60), MatchingStatus(25, 60), MatchingStatus(50, 60), MatchingStatus(60, 60), null), seen.map { it.second })
        assertTrue(seen.drop(1).all { it.first is SyncStatus.Synced })
    }

    @Test fun `when the cloud can't answer, Matching ends and the status stays at the finished Sync`() = runBlocking {
        serveSuspects(60)
        cloud.failingMatches = 100

        val status = library.sync()

        assertTrue(atMatch.isNotEmpty() && atMatch.all { it.second != null })
        assertTrue(status is SyncStatus.Synced)
        assertEquals(status, library.syncStatus.value)
        assertNull(library.matchingStatus.value)
    }

    @Test fun `Matching ends even when the unknown-Prefix report fails`() = runBlocking {
        provider.movieCategoriesOverride = """[{"category_id":"1","category_name":"XB - MOVIES"}]"""
        provider.movieListOverride = """[{"stream_id":1,"name":"XB - Cold Pursuit (2019)","tmdb":"","category_id":"1","added":"1700000000"}]"""
        provider.showListOverride = "[]"
        cloud.failingPrefixReports = true

        val status = library.sync() as SyncStatus.Synced

        assertEquals(listOf(UnknownPrefix("XB", 1, "XB - Cold Pursuit (2019)")), status.last.unknownPrefixes)
        assertNull(library.matchingStatus.value)
        assertEquals(0, cloud.matchRequests.size)
    }

    @Test fun `while a first Sync's names are Matched, both tabs count their Titles and read their rows`() = runBlocking {
        val before = library.catalogChanges.value
        // What each tab would read at the first Matching batch: its Title count and its rows.
        val during = CopyOnWriteArrayList<Triple<SyncStatus, Int, List<Int>>>()
        cloud.onMatch = {
            if (during.isEmpty()) {
                val counts = TitleKind.entries.map { library.titleCount(it) }
                val rows = runBlocking { TitleKind.entries.map { library.tabRows(it, listOf("en"), "en").size } }
                during += Triple(library.syncStatus.value, library.catalogChanges.value, counts + rows)
            }
        }

        library.sync()

        val (status, catalog, read) = during.single()
        assertTrue(status is SyncStatus.Synced)
        // The catalog has changed by then, so a tab that counted none before the swap counts again.
        assertTrue(catalog > before)
        assertTrue("$read", read.all { it > 0 })
        // And again once the Titles regroup.
        assertTrue(library.catalogChanges.value > catalog)
    }

    @Test fun `a held Sync's Matching runs once its catalog is applied, not before`() = runBlocking {
        library.sync()
        serveSuspects(60)
        (1..60).forEach { cloud.match("Lost Film $it", 1_000L + it) }
        val askedBefore = cloud.matchRequests.size

        library.sync(hold = true)

        assertEquals(askedBefore, cloud.matchRequests.size)
        library.applyPendingCatalog()
        assertTrue(cloud.matchRequests.size > askedBefore)
        assertEquals(60, (library.syncStatus.value as SyncStatus.Synced).last.rematchedListings)
    }

    @Test fun `Sync now during Matching starts at once`() = runBlocking {
        serveSuspects(60)
        (1..60).forEach { cloud.match("Lost Film $it", 1_000L + it) }
        val matching = CountDownLatch(1)
        val release = CountDownLatch(1)
        cloud.onMatch = {
            matching.countDown()
            release.await()
        }
        val first = async(Dispatchers.IO) { library.sync() }
        matching.await()
        val second = async(Dispatchers.IO) { library.sync() }

        withTimeout(5_000) { while (library.syncStatus.value !is SyncStatus.Running) kotlinx.coroutines.delay(10) }

        assertNotNull(library.matchingStatus.value)
        release.countDown()
        first.await()
        second.await()
        assertNull(library.matchingStatus.value)
    }

    @Test fun `when no batch is answered for a while, Matching ends and the rest are asked next Sync`() = runBlocking {
        serveSuspects(60)
        (1..60).forEach { cloud.match("Lost Film $it", 1_000L + it) }
        val clock = MovingClock()
        library.matchClock = clock
        var batches = 0
        cloud.onMatch = {
            atMatch += library.syncStatus.value to library.matchingStatus.value
            // The second batch isn't answered, after as long as Matching waits for one.
            if (++batches == 2) {
                clock.now += Library.MATCH_STALL_AFTER
                cloud.failingMatches = 1
            }
        }

        val first = library.sync()

        assertEquals(2, batches)
        assertTrue(first is SyncStatus.Synced)
        assertEquals(first, library.syncStatus.value)
        assertNull(library.matchingStatus.value)
        assertEquals(25, (first as SyncStatus.Synced).last.rematchedListings)
        val asked = cloud.matchAsked.size

        val second = library.sync() as SyncStatus.Synced

        // Only the 35 left unanswered are asked.
        assertEquals(35, cloud.matchAsked.size - asked)
        assertEquals(60, second.last.rematchedListings)
    }

    /** A clock that stands still until a test moves it on. */
    private class MovingClock : Clock() {
        @Volatile var now: Instant = Instant.parse("2026-11-01T12:00:00Z")
        override fun instant(): Instant = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
    }

    /** Serves [count] movie Listings without a TMDB ID, each needing its own Match, and no shows. */
    private fun serveSuspects(count: Int) {
        provider.movieCategoriesOverride = """[{"category_id":"1","category_name":"MOVIES"}]"""
        provider.movieListOverride = (1..count).joinToString(",", "[", "]") {
            """{"stream_id":$it,"name":"Lost Film $it","tmdb":"","category_id":"1","added":"${1_700_000_000 - it}"}"""
        }
        provider.showListOverride = "[]"
    }
}
