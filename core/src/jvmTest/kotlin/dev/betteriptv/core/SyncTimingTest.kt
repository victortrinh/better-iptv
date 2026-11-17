package dev.betteriptv.core

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CopyOnWriteArrayList

/** What a Sync reports of where its time went, to whoever asked: every Step, and each part of Saving. */
class SyncTimingTest {
    @get:Rule val folder = TemporaryFolder()
    private val provider = FakeProvider()
    private val cloud = FakeCloud()
    private lateinit var library: Library
    private val timings = CopyOnWriteArrayList<SyncTiming>()

    @Before fun logIn() = runBlocking {
        library = Library.open(folder.root.resolve("catalog.db").path, InMemoryCredentials(), cloud.url)
        library.logIn(provider.login)
        provider.serveChannels("CA: CBC HD" to "CA | NEWS", "CA: CTV HD" to "CA | NEWS", "US: CNN HD" to "US | NEWS")
        provider.generatedMovies = 150
    }

    @After fun tearDown() {
        library.close()
        provider.close()
        cloud.close()
    }

    @Test fun `a first Sync that reads a Head start times it between the Channels and the Movies`() = runBlocking {
        library.onSyncTimed = { timings += it }

        library.sync(contentLanguages = listOf("en"))

        val timing = timings.single()
        assertEquals(listOf("Channels", "Head start", "Movies", "Shows", "Saving"), timing.steps.map { it.name })
        assertEquals(timing.steps.last().time, timing.saving.fold(kotlin.time.Duration.ZERO) { sum, part -> sum + part.time })
    }

    @Test fun `a first Sync reports a time for every Step and every part of Saving, with the Listing counts`() = runBlocking {
        library.onSyncTimed = { timings += it }

        library.sync()

        val timing = timings.single()
        assertEquals(listOf("Channels", "Movies", "Shows", "Saving"), timing.steps.map { it.name })
        assertEquals(
            listOf(
                "Number-year build", "Mismatch update", "Title insert", "Title key index", "Kept-Listing insert",
                "Search index: Titles", "Search index: people, genres", "Search index: replays", "Search index: merge", "Staging drop", "Commit", "Counts and pending Sync",
                "Table swap", "Index creation", "Stale details delete", "Sync record", "Swap commit",
            ),
            timing.saving.map { it.name },
        )
        assertEquals(3, timing.channelListings)
        assertEquals(150, timing.movieListings)
        assertEquals(46, timing.showListings)
    }

    @Test fun `the Saving parts are the whole of the Saving Step`() = runBlocking {
        library.onSyncTimed = { timings += it }

        library.sync()

        val timing = timings.single()
        assertEquals(timing.steps.last().time, timing.saving.fold(kotlin.time.Duration.ZERO) { sum, part -> sum + part.time })
    }

    @Test fun `a later Sync reports the Channel swap too, and a held one stops at its pending Sync`() = runBlocking {
        library.sync()
        library.onSyncTimed = { timings += it }

        library.sync()
        library.sync(hold = true)

        val (swapped, held) = timings
        assertTrue("Channel swap" in swapped.saving.map { it.name })
        assertEquals("Swap commit", swapped.saving.last().name)
        assertEquals("Counts and pending Sync", held.saving.last().name)
    }

    @Test fun `the report reads as one block, a line per Step and per part of Saving`() = runBlocking {
        library.onSyncTimed = { timings += it }

        library.sync()

        val lines = timings.single().toString().lines()
        assertTrue(lines.first(), Regex("""Sync took \d+\.\d{3} s: 3 Channel, 150 movie and \d+ show Listings""").matches(lines.first()))
        assertEquals(listOf("Channels", "Movies", "Shows", "Saving"), lines.filter { it.startsWith("  ") && !it.startsWith("    ") }.map { it.trim().substringBefore("  ") })
        assertEquals(timings.single().saving.size, lines.count { it.startsWith("    ") })
    }

    @Test fun `a Sync that fails reports nothing`() = runBlocking {
        library.onSyncTimed = { timings += it }
        provider.generatedMovies = 0
        provider.close()

        library.sync()

        assertTrue(timings.isEmpty())
    }

    @Test fun `a Sync nobody asked to time reports nothing`() = runBlocking {
        library.sync()

        assertTrue(library.syncStatus.value is SyncStatus.Synced)
        assertTrue(timings.isEmpty())
    }
}
