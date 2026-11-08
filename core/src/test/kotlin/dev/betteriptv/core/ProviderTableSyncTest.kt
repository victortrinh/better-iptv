package dev.betteriptv.core

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A Sync asks the cloud for its Provider's Provider table first, keeps it in the catalog database, and
 * describes the Listings it lands with Base plus that table (#431, ADR 0014).
 */
class ProviderTableSyncTest {
    @get:Rule val folder = TemporaryFolder()
    private val provider = FakeProvider()
    private val cloud = FakeCloud()
    private val credentials = InMemoryCredentials()
    private val libraries = mutableListOf<Library>()
    private val key get() = providerKey(provider.login)

    @After fun tearDown() {
        libraries.forEach { it.close() }
        cloud.close()
        provider.close()
    }

    /** A Library on the catalog at catalog.db, logged in to the fake Provider, which ships no Provider table for it unless [shipped]. */
    private suspend fun library(shipped: String? = null, database: String = "catalog.db"): Library {
        val library = Library.open(folder.root.resolve(database).path, credentials, cloud.url, { shipped }).also { libraries += it }
        if (!library.isLoggedIn) library.logIn(provider.login)
        return library
    }

    /** The fake Provider's movies are these Listings, each `name` filed under the Category `category`. */
    private fun listings(vararg listings: Pair<String, String>) {
        val categories = listings.map { it.second }.distinct()
        provider.movieCategoriesOverride = categories.withIndex()
            .joinToString(",", "[", "]") { (i, name) -> """{"category_id":"${i + 1}","category_name":"$name"}""" }
        provider.movieListOverride = listings.withIndex().joinToString(",", "[", "]") { (i, listing) ->
            """{"stream_id":${i + 1},"name":"${listing.first}","category_id":"${categories.indexOf(listing.second) + 1}","added":"${1_700_000_000 - i}"}"""
        }
    }

    private fun Library.language(id: Long) = movieListings(0, 10_000).single { it.id == id }.attributes.language?.value

    private val versions = arrayOf("CD - Der Pate (1972)" to "GERMANY SERIE", "DE - Der Pate (1972)" to "DE - FILME")

    @Test fun `a Sync asks for the Provider table first and describes its Listings with it`() = runBlocking {
        cloud.providerTables[key] = "[prefixes]\nCD | language=de\n"
        listings(*versions)
        val library = library()

        val synced = library.sync() as SyncStatus.Synced

        assertEquals("de", library.language(1))
        assertEquals("de", library.language(2))
        assertTrue(synced.last.unknownPrefixes.none { it.prefix == "CD" })
        val asked = cloud.received.filter { it.path != "/v1/devices" }
        assertEquals("/v1/provider-table", asked.first().path)
        assertEquals("{\"provider\":\"$key\"}", asked.first().body)
    }

    @Test fun `learned lines apply as Likely, a hand line over them and they over Base`() = runBlocking {
        cloud.providerTables[key] = "[prefixes]\nCD | language=de\n\n[learned prefixes]\nCD | language=fr\nDE | language=it\nXQ | language=pt; quality=4K\nQC | language=fr\n"
        listings(*versions, "XQ - O Padrinho (1972)" to "FILMES", "QC - Le Parrain (1972)" to "QC - FILMS")
        val library = library()

        val synced = library.sync() as SyncStatus.Synced

        // The hand line wins over the learned one.
        assertEquals("de", library.language(1))
        // A learned line that contradicts Base applies: it was measured.
        assertEquals("it", library.language(2))
        val learned = library.movieListings(0, 10_000).single { it.id == 3L }.attributes
        assertEquals(Known("pt", Provenance.Likely), learned.language)
        assertEquals(Known(Quality.UHD, Provenance.Likely), learned.quality)
        assertTrue(synced.last.unknownPrefixes.none { it.prefix == "XQ" })
        // A learned French keeps Base's Québec French.
        assertEquals("fr-CA", library.language(4))
    }

    @Test fun `a Multi-audio Prefix's Versions are Untagged, Likely in the Title's original language`() = runBlocking {
        cloud.providerTables[key] = "[learned prefixes]\nEN | multi-audio\n"
        cloud.titleFacts(TitleId.Numbered(TitleKind.Movie, 1), originalLanguage = "fr")
        provider.movieCategoriesOverride = """[{"category_id":"1","category_name":"EN - MOVIES"}]"""
        provider.movieListOverride = """[{"stream_id":1,"name":"EN - Amélie (2001)","tmdb":"1","category_id":"1","added":"1700000000"}]"""
        val library = library()

        library.sync()

        assertNull(library.language(1))
        val details = library.title(TitleId.Numbered(TitleKind.Movie, 1), listOf("en"), "en")!!
        val version = (details.languages + details.otherLanguages).flatMap { it.versions }.single()
        assertEquals(Known("fr", Provenance.Likely), version.attributes.language)
    }

    @Test fun `a Provider the cloud has no table for reports CD as unknown`() = runBlocking {
        listings(*versions)
        val library = library()

        val synced = library.sync() as SyncStatus.Synced

        assertNull(library.language(1))
        assertTrue(UnknownPrefix("CD", 1, "CD - Der Pate (1972)") in synced.last.unknownPrefixes)
        assertTrue(cloud.received.any { it.path == "/v1/provider-table" })
    }

    @Test fun `with the cloud down, a Sync uses the last table kept, even after the app reopens`() = runBlocking {
        cloud.providerTables[key] = "[prefixes]\nCD | language=de\n"
        listings(*versions)
        library().sync()
        cloud.down = true

        libraries.removeLast().close()
        val reopened = library()
        assertEquals("de", reopened.language(1))
        val synced = reopened.sync() as SyncStatus.Synced

        assertEquals("de", reopened.language(1))
        assertTrue(synced.last.unknownPrefixes.none { it.prefix == "CD" })
    }

    @Test fun `a device that never got a table, with the cloud down, uses Base alone`() = runBlocking {
        cloud.providerTables[key] = "[prefixes]\nCD | language=de\n"
        cloud.down = true
        listings(*versions)
        val library = library()

        val synced = library.sync() as SyncStatus.Synced

        assertNull(library.language(1))
        assertEquals("de", library.language(2))
        assertTrue(synced.last.unknownPrefixes.any { it.prefix == "CD" })
    }

    @Test fun `the cloud's answer wins over the table shipped with the app, which stands in until one comes`() = runBlocking {
        listings(*versions)
        cloud.down = true
        val library = library(shipped = "[prefixes]\nCD | language=de\n")
        library.sync()
        assertEquals("de", library.language(1))

        cloud.down = false
        cloud.providerTables[key] = "[prefixes]\nCD | language=fr\n"
        // Reopened, so the cloud isn't left alone for the retry pause its failure began.
        libraries.removeLast().close()
        val reopened = library(shipped = "[prefixes]\nCD | language=de\n")
        reopened.sync()

        assertEquals("fr", reopened.language(1))
    }

    @Test fun `a changed table is applied by the next Sync, and a held Sync's only once its catalog lands`() = runBlocking {
        cloud.providerTables[key] = "[prefixes]\nCD | language=de\n"
        listings(*versions)
        val library = library()
        library.sync()

        cloud.providerTables[key] = "[prefixes]\nCD | language=fr\n"
        // Between Syncs nothing changes.
        assertEquals("de", library.language(1))

        val held = library.sync(hold = true)
        assertTrue(held is SyncStatus.Ready)
        // Rows on screen keep the table they were described with until the held catalog lands between screens (ADR 0011).
        assertEquals("de", library.language(1))

        assertTrue(library.applyPendingCatalog())
        assertEquals("fr", library.language(1))
    }

    @Test fun `a held Sync's table waits with its catalog across a reopen, and a discarded one is never applied`() = runBlocking {
        cloud.providerTables[key] = "[prefixes]\nCD | language=de\n"
        listings(*versions)
        library().sync()
        cloud.providerTables[key] = "[prefixes]\nCD | language=fr\n"
        libraries.last().sync(hold = true)

        libraries.removeLast().close()
        val reopened = library()
        assertEquals("de", reopened.language(1))
        assertTrue(reopened.applyPendingCatalog())
        assertEquals("fr", reopened.language(1))

        // A Sync the Provider fails keeps the table the catalog on screen was described with.
        cloud.providerTables[key] = "[prefixes]\nCD | language=it\n"
        provider.movieListOverride = "not json"
        assertTrue(reopened.sync() is SyncStatus.Failed)
        assertEquals("fr", reopened.language(1))
    }

    @Test fun `a table the device can't read is ignored, keeping the last one`() = runBlocking {
        cloud.providerTables[key] = "[prefixes]\nCD | language=de\n"
        listings(*versions)
        val library = library()
        library.sync()

        cloud.providerTables[key] = "[prefixes]\nCD | quality=8K\n"
        assertTrue(library.sync() is SyncStatus.Synced)

        assertEquals("de", library.language(1))
    }

    @Test fun `a table kept for one Provider isn't used for another`() = runBlocking {
        cloud.providerTables[key] = "[prefixes]\nCD | language=de\n"
        listings(*versions)
        val library = library()
        library.sync()
        cloud.down = true

        FakeProvider().use { other ->
            other.movieCategoriesOverride = provider.movieCategoriesOverride
            other.movieListOverride = provider.movieListOverride
            library.logIn(ProviderLogin(other.login.address.replace("127.0.0.1", "localhost"), other.login.username, other.login.password))
            library.sync()
            assertNull(library.language(1))
        }
    }

    @Test fun `the surveyed Provider's household sees no change when the cloud serves its hand file`() = runBlocking {
        cloud.providerTables[key] = surveyedProviderTable
        val library = library()
        library.sync()

        assertEquals(resource("/snapshots/library-descriptions.txt"), libraryDescriptions(library))
    }

    @Test fun `every hand file in the repo reads as a Provider table`() {
        val folder = java.io.File(System.getProperty("user.dir")).resolve("../cloud/provider-tables")
        val files = folder.listFiles { file -> file.name.endsWith(".txt") }!!
        assertTrue(files.isNotEmpty())
        for (file in files) {
            assertTrue(file.name, Regex("[0-9a-f]{64}\\.txt").matches(file.name))
            PrefixTable.withProviderTable(file.readText())
        }
    }

    private fun resource(path: String) = javaClass.getResourceAsStream(path)!!.bufferedReader().use { it.readText() }
}
