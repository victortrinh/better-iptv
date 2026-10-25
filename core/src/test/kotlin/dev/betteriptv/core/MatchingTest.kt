package dev.betteriptv.core

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Mismatches and Listings without a TMDB ID Matched by name through the fake Cloud, seen through the Library after a Sync of the fixture. */
class MatchingTest {
    @get:Rule val folder = TemporaryFolder()
    private val provider = FakeProvider()
    private val cloud = FakeCloud()
    private val credentials = InMemoryCredentials()
    private val libraries = mutableListOf<Library>()
    private lateinit var library: Library

    private fun openLibrary() = Library.open(folder.root.resolve("catalog.db").path, credentials, cloud.url).also { libraries += it }

    @Before fun logIn() = runBlocking {
        // What TMDB makes of the fixture's odd names.
        cloud.match("Mohgiben MBBS", null)
        cloud.match("Titanic", titanic.tmdbId)
        cloud.match("Forrest Gump", forrestGump.tmdbId)
        library = openLibrary()
        library.logIn(provider.login)
        Unit
    }

    @After fun tearDown() {
        libraries.forEach { it.close() }
        provider.close()
        cloud.close()
    }

    private val munnaBhai = TitleId.Tmdb(TitleKind.Movie, 19625)
    private val titanic = TitleId.Tmdb(TitleKind.Movie, 597)
    private val forrestGump = TitleId.Tmdb(TitleKind.Movie, 13)

    private suspend fun cards() = library.titles(0, 1000, listOf("en"), appLanguage = "en")

    private suspend fun versionNames(id: TitleId): List<String> {
        val details = library.title(id, listOf("en"), appLanguage = "en") ?: throw AssertionError("no Title $id")
        return (details.languages + details.otherLanguages).flatMap { group -> group.versions.flatMap { listOf(it) + it.backups } }.map { it.name }
    }

    @Test fun `a Mismatch no longer sits under the Title its Provider ID points to, and stands alone when it can't be looked up`() = runBlocking {
        library.sync()

        assertEquals(listOf("IN - Munna Bhai M.B.B.S.  (2003)", "IN - Munna Bhai M.B.B.S."), versionNames(munnaBhai))
        assertTrue(cards().single { it.name == "Mohgiben MBBS" }.id is TitleId.Unmatched)
    }

    @Test fun `Listings without a TMDB ID land under their Title, found by name and a year one off`() = runBlocking {
        library.sync()

        assertTrue("EN - Titanic  (1996)" in versionNames(titanic))
        // It carried an IMDb ID instead.
        assertTrue("GR - Forrest Gump (1994)" in versionNames(forrestGump))
        assertEquals(1, cards().count { it.id == titanic })
    }

    @Test fun `only Listings whose name matches none of their siblings', or that have no ID, are sent to the cloud`() = runBlocking {
        library.sync()

        val asked = cloud.matchAsked.associateBy { it["name"] }
        // Both Munna Bhai Listings have the same name, so only the odd one out is checked.
        assertFalse("Munna Bhai M.B.B.S." in asked)
        assertEquals(mapOf("kind" to "movie", "name" to "Mohgiben MBBS", "year" to 2023.0, "language" to "gu", "tmdbId" to 19625.0), asked["Mohgiben MBBS"])
        assertEquals(mapOf("kind" to "movie", "name" to "Titanic", "year" to 1996.0, "language" to "en", "tmdbId" to null), asked["Titanic"])
        // Ballerina's French Listings agree with each other.
        assertFalse(cloud.matchAsked.any { it["name"] == "Ballerina" && it["language"] == "fr" })
    }

    @Test fun `names that differ only in punctuation or an acronym's dots vouch for each other`() = runBlocking {
        provider.movieCategoriesOverride = """[{"category_id":"1","category_name":"MOVIES"}]"""
        provider.movieListOverride = """[
            {"stream_id":1,"name":"EN - Munna Bhai M.B.B.S. (2003)","tmdb":"19625","category_id":"1","added":"1700000002"},
            {"stream_id":2,"name":"IN - Munna Bhai MBBS (2003)","tmdb":"19625","category_id":"1","added":"1700000001"},
            {"stream_id":3,"name":"EN - Lilo & Stitch (2025)","tmdb":"552524","category_id":"1","added":"1700000000"},
            {"stream_id":4,"name":"FR - Lilo et Stitch (2025)","tmdb":"552524","category_id":"1","added":"1699999999"},
            {"stream_id":5,"name":"4K-EN - Lilo  Stitch (2025)","tmdb":"552524","category_id":"1","added":"1699999998"}
        ]"""

        library.sync()

        assertEquals(listOf("Lilo et Stitch"), cloud.matchAsked.map { it["name"] })
    }

    @Test fun `the Sync counts the Listings it Matched by name instead of their Provider ID`() = runBlocking {
        val status = library.sync()

        assertEquals(3, (status as SyncStatus.Synced).last.rematchedListings)
        assertEquals(status, library.syncStatus.value)
    }

    @Test fun `answers are kept on the device, so the next Sync has the right Titles without asking the cloud`() = runBlocking {
        library.sync()
        val asked = cloud.matchAsked.size
        library.close()
        cloud.down = true

        library = openLibrary()
        library.sync()

        assertEquals(asked, cloud.matchAsked.size)
        assertTrue("EN - Titanic  (1996)" in versionNames(titanic))
        assertFalse("IN-GU - Mohgiben.MBBS.2023" in versionNames(munnaBhai))
    }

    @Test fun `without the cloud, the Sync still finishes, trusting the Provider's IDs`() = runBlocking {
        cloud.down = true

        val status = library.sync()

        assertTrue(status is SyncStatus.Synced)
        assertTrue("IN-GU - Mohgiben.MBBS.2023" in versionNames(munnaBhai))
        assertTrue(cards().single { it.name == "Titanic" && it.year == 1996 }.id is TitleId.Unmatched)
    }

    @Test fun `a batch the cloud fails doesn't stop the batches after it`() = runBlocking {
        provider.movieCategoriesOverride = """[{"category_id":"1","category_name":"MOVIES"}]"""
        // Thirty Listings without an ID ask the cloud two batches.
        provider.movieListOverride = (1..30).joinToString(",", "[", "]") { n ->
            cloud.match("Film $n", 1000L + n)
            """{"stream_id":$n,"name":"EN - Film $n (2020)","category_id":"1","added":"${1700000000 + n}"}"""
        }
        cloud.failingMatches = 1

        library.sync()

        assertEquals(2, cloud.matchRequests.size)
        val answered = cloud.matchRequests[1].map { "Film ${(it["name"] as String).substringAfter("Film ")}" }
        assertEquals(answered.sorted(), cards().filter { it.id is TitleId.Tmdb }.map { it.name }.sorted())
    }

    @Test fun `the Sync stops asking the cloud after a few batches fail in a row`() = runBlocking {
        provider.movieCategoriesOverride = """[{"category_id":"1","category_name":"MOVIES"}]"""
        provider.movieListOverride = (1..200).joinToString(",", "[", "]") { n ->
            """{"stream_id":$n,"name":"EN - Film $n (2020)","category_id":"1","added":"${1700000000 + n}"}"""
        }
        cloud.failingMatches = Int.MAX_VALUE

        val status = library.sync()

        assertTrue(status is SyncStatus.Synced)
        assertEquals(3, cloud.matchRequests.size)
    }
}
