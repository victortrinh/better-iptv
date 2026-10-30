package dev.betteriptv.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CopyOnWriteArrayList

/** What the Sync status tells the Viewer while a Sync reads the catalog, and while its suspects are Matched by name. */
class SyncProgressTest {
    @get:Rule val folder = TemporaryFolder()
    private val provider = FakeProvider()
    private val cloud = FakeCloud()
    private val credentials = InMemoryCredentials()
    private lateinit var library: Library

    /** The status each time the Provider is asked for its show Categories, once the movies are read. */
    private val atShows = CopyOnWriteArrayList<SyncStatus>()

    /** The status each time the cloud is asked to Match a batch. */
    private val atMatch = CopyOnWriteArrayList<SyncStatus>()

    @Before fun logIn() = runBlocking {
        library = Library.open(folder.root.resolve("catalog.db").path, credentials, cloud.url)
        library.logIn(provider.login)
        provider.onRequest = { action -> if (action == "get_series_categories") atShows += library.syncStatus.value }
        cloud.onMatch = { atMatch += library.syncStatus.value }
    }

    @After fun tearDown() {
        library.close()
        provider.close()
        cloud.close()
    }

    @Test fun `a first Sync counts the Listings read, with nothing to judge how far it is`() = runBlocking {
        library.sync()

        // The fixture's 156 movie Listings, less 3 music videos and 3 Sports replays.
        val running = atShows.single() as SyncStatus.Running
        assertEquals(SyncStatus.Running(null, moviesRead = 150, showsRead = 0), running)
        assertNull(running.fraction)
    }

    @Test fun `a later Sync tells how far it is by the last Sync's counts`() = runBlocking {
        val last = (library.sync() as SyncStatus.Synced).last
        assertEquals(150 + 45, last.movieListingCount + last.showListingCount)

        library.sync()

        val running = atShows.last() as SyncStatus.Running
        assertEquals(SyncStatus.Running(last, moviesRead = 150, showsRead = 0), running)
        assertEquals(150f / 195, running.fraction!!, 0.0001f)
    }

    @Test fun `a Sync that reads more than the last one never says it's done before it is`() = runBlocking {
        library.sync()
        provider.generatedMovies = 400

        library.sync()

        assertEquals(0.99f, (atShows.last() as SyncStatus.Running).fraction!!, 0.0001f)
    }

    @Test fun `the status counts the names asked to Match before the Titles regroup, then shows the regrouped catalog`() = runBlocking {
        serveSuspects(60)
        (1..60).forEach { cloud.match("Lost Film $it", 1_000L + it) }

        val finished = library.sync() as SyncStatus.Synced

        val synced = atMatch.map { it as SyncStatus.Matching }.first().last
        assertEquals(0, synced.rematchedListings)
        assertEquals(listOf(SyncStatus.Matching(synced, 0, 60), SyncStatus.Matching(synced, 25, 60), SyncStatus.Matching(synced, 50, 60)), atMatch)
        assertEquals(60, finished.last.rematchedListings)
        assertEquals(finished, library.syncStatus.value)
    }

    @Test fun `from the moment the new catalog shows, the status says names are being Matched until the Titles regroup`() = runBlocking {
        serveSuspects(60)
        val seen = CopyOnWriteArrayList<SyncStatus>()
        val watching = launch(Dispatchers.Unconfined) { library.syncStatus.collect { seen += it } }

        val finished = library.sync()
        watching.cancel()

        // A first Sync: every status carrying a catalog comes once the new one shows.
        val withCatalog = seen.filter { it.last != null }
        assertEquals(SyncStatus.Matching(withCatalog.first().last!!, 0, null), withCatalog.first())
        assertTrue(withCatalog.dropLast(1).all { it is SyncStatus.Matching })
        assertEquals(finished, withCatalog.last())
    }

    @Test fun `when the cloud can't answer, the status goes back to the finished Sync`() = runBlocking {
        serveSuspects(60)
        cloud.failingMatches = 100

        val status = library.sync()

        assertTrue(atMatch.isNotEmpty() && atMatch.all { it is SyncStatus.Matching })
        assertTrue(status is SyncStatus.Synced)
        assertEquals(status, library.syncStatus.value)
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
