package dev.betteriptv.core

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Instant

class SyncTest {
    @get:Rule val folder = TemporaryFolder()
    private val provider = FakeProvider()
    private val credentials = InMemoryCredentials()
    private val libraries = mutableListOf<Library>()

    private fun openLibrary() = Library.open(folder.root.resolve("catalog.db").path, credentials).also { libraries += it }

    @After fun tearDown() {
        libraries.forEach { it.close() }
        provider.close()
    }

    @Test fun `lists every movie Listing after Sync, newest first`() = runBlocking {
        val library = openLibrary()
        library.logIn(provider.login)

        library.sync()

        // The fixture's 148 movie Listings, less 3 music videos and 3 Sports replays.
        assertEquals(142, library.movieListingCount())
        val movies = library.movieListings(offset = 0, limit = 1000)
        assertEquals(142, movies.size)
        assertEquals(
            listOf(
                Triple(1107L, "EN - Oz the Great and Powerful (2013)", "EN - 2020 & OLD"),
                Triple(1018L, "4K-DE-DV - Der Pate (1972)", "DE - ²¹⁶⁰ᵖ DV ᴴᴰᴿ¹⁰/PLUS"),
                Triple(1135L, "DE - Die fantastische Welt von Oz (2013)", "DE - FILME 1940/2024"),
            ),
            movies.take(3).map { Triple(it.id, it.name, it.category) },
        )
        assertEquals(36, movies.count { "Ballerina" in it.name })
    }

    @Test fun `reopening shows the last synced catalog without the Provider`() = runBlocking {
        openLibrary().apply { logIn(provider.login); sync() }.close()
        provider.down = true

        val reopened = openLibrary()

        assertEquals(142, reopened.movieListingCount())
        assertEquals(142, (reopened.syncStatus.value as SyncStatus.Synced).last.movieListingCount)
    }

    @Test fun `a failed Sync keeps the previous catalog and never reports the login`() = runBlocking {
        val library = openLibrary()
        library.logIn(provider.login)
        library.sync()
        provider.cutMovieListMidway = true

        val status = library.sync()

        assertTrue(status is SyncStatus.Failed)
        assertEquals(142, status.last?.movieListingCount)
        assertFalse(status.toString().contains(provider.password))
        assertFalse(status.toString().contains(provider.username))
        assertEquals(142, library.movieListingCount())
        assertEquals("EN - Oz the Great and Powerful (2013)", library.movieListings(0, 1).single().name)
    }

    @Test fun `a later Sync replaces the catalog with what the Provider has now`() = runBlocking {
        val library = openLibrary()
        library.logIn(provider.login)
        library.sync()
        provider.movieListOverride = """[
            {"stream_id":7,"name":"EN - Newly Added (2026)","category_id":"1","added":"1800000000"},
            {"stream_id":8,"name":"EN - Older (2020)","category_id":"999","added":1600000000}
        ]"""

        library.sync()

        assertEquals(
            listOf(Triple(7L, "EN - Newly Added (2026)", "DE - FILME 1940/2024"), Triple(8L, "EN - Older (2020)", "")),
            library.movieListings(0, 10).map { Triple(it.id, it.name, it.category) },
        )
        assertEquals(2, library.movieListingCount())
    }

    @Test fun `Sync never opens a stream, so it works while one is playing`() = runBlocking {
        val library = openLibrary()
        library.logIn(provider.login)
        provider.openStream(1107).use { playing ->
            playing.read(ByteArray(1024))

            val status = library.sync()

            assertTrue(status is SyncStatus.Synced)
        }
        assertEquals(1, provider.streamRequests)
        assertEquals(0, provider.refusedStreams)
        assertEquals(0, provider.requestsWithoutGzip)
    }

    @Test fun `Sync with an expired subscription keeps the catalog and reports the expiry`() = runBlocking {
        val library = openLibrary()
        library.logIn(provider.login)
        library.sync()
        provider.expiresOn = Instant.parse("2026-01-15T00:00:00Z")

        val status = library.sync()

        assertEquals(Expired(Instant.parse("2026-01-15T00:00:00Z")), (status as SyncStatus.Failed).problem)
        assertEquals(142, library.movieListingCount())
    }
}
