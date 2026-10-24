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

        assertEquals(148, library.movieCount())
        val movies = library.movies(offset = 0, limit = 1000)
        assertEquals(148, movies.size)
        assertEquals(
            listOf(
                MovieListing(1107, "EN - Oz the Great and Powerful (2013)", "EN - 2020 & OLD"),
                MovieListing(1018, "4K-DE-DV - Der Pate (1972)", "DE - ²¹⁶⁰ᵖ DV ᴴᴰᴿ¹⁰/PLUS"),
                MovieListing(1135, "DE - Die fantastische Welt von Oz (2013)", "DE - FILME 1940/2024"),
            ),
            movies.take(3),
        )
        assertEquals(36, movies.count { "Ballerina" in it.name })
    }

    @Test fun `reopening shows the last synced catalog without the Provider`() = runBlocking {
        openLibrary().apply { logIn(provider.login); sync() }.close()
        provider.down = true

        val reopened = openLibrary()

        assertEquals(148, reopened.movieCount())
        assertEquals(148, (reopened.syncStatus.value as SyncStatus.Synced).last.movieCount)
    }

    @Test fun `a failed Sync keeps the previous catalog and never reports the login`() = runBlocking {
        val library = openLibrary()
        library.logIn(provider.login)
        library.sync()
        provider.cutMovieListMidway = true

        val status = library.sync()

        assertTrue(status is SyncStatus.Failed)
        assertEquals(148, status.last?.movieCount)
        assertFalse(status.toString().contains(provider.password))
        assertFalse(status.toString().contains(provider.username))
        assertEquals(148, library.movieCount())
        assertEquals("EN - Oz the Great and Powerful (2013)", library.movies(0, 1).single().name)
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
            listOf(MovieListing(7, "EN - Newly Added (2026)", "DE - FILME 1940/2024"), MovieListing(8, "EN - Older (2020)", "")),
            library.movies(0, 10),
        )
        assertEquals(2, library.movieCount())
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
        assertEquals(148, library.movieCount())
    }
}
