package dev.betteriptv.core

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Sync against a catalog the size of the surveyed Provider's (144,031 movie Listings, 56 MB uncompressed). */
class SyncScaleTest {
    @get:Rule val folder = TemporaryFolder()
    private val provider = FakeProvider()
    private val library by lazy { Library.open(folder.root.resolve("catalog.db").path, InMemoryCredentials()) }

    @After fun tearDown() {
        library.close()
        provider.close()
    }

    @Test fun `a full-size catalog streams through a heap smaller than its JSON`() = runBlocking {
        provider.generatedMovies = 150_000
        library.logIn(provider.login)
        // The test JVM's heap is capped at 128 MB (core/build.gradle.kts), less than this movie list's
        // ~63 MB of JSON plus the strings and objects it would take to hold it.
        assertTrue(Runtime.getRuntime().maxMemory() <= 128L * 1024 * 1024)

        val status = library.sync()

        val last = (status as SyncStatus.Synced).last
        println("Sync of 150,000 movie Listings on this machine: ${last.duration.toMillis()} ms")
        assertEquals(150_000, library.movieListingCount())
        assertEquals("EN - Generated Movie 150000 (2020)", library.movieListings(0, 1).single().name)
        assertEquals("EN - Generated Movie 1 (2020)", library.movieListings(149_999, 50).single().name)
    }
}
