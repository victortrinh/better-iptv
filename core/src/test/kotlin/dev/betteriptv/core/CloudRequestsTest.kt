package dev.betteriptv.core

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** What the device sends the fake cloud, and what it does with Wikidata's facts, seen through the Library after a Sync of the fixture. */
class CloudRequestsTest {
    @get:Rule val folder = TemporaryFolder()
    private val provider = FakeProvider()
    private val cloud = FakeCloud()
    private val credentials = InMemoryCredentials()
    private val libraries = mutableListOf<Library>()

    private fun openLibrary() = Library.open(folder.root.resolve("catalog.db").path, credentials, cloud.url).also { libraries += it }

    private lateinit var library: Library

    /** What the Sync sent the cloud (its Title facts requests), kept apart so tests see only what follows. */
    private lateinit var sentBySync: List<FakeCloud.Received>

    @Before fun syncFixture() = runBlocking {
        library = openLibrary()
        library.logIn(provider.login)
        library.sync()
        sentBySync = cloud.received.toList()
        cloud.received.clear()
    }

    @After fun tearDown() {
        libraries.forEach { it.close() }
        provider.close()
        cloud.close()
    }

    private val french = listOf("fr")
    private val godfather = TitleId.Numbered(TitleKind.Movie, 238)

    @Test fun `a play plan knows the language the Title was released in, so the player can call its audio Original`() = runBlocking {
        cloud.titleFacts(godfather, originalLanguage = "en")
        library.sync()
        library.title(godfather, french, "fr")

        val plan = library.playPlan(godfather, french, DeviceProfile(maxHeight = 2160, hdr = emptySet())) ?: throw AssertionError("no plan")
        assertEquals("en", plan.originalLanguage)
        assertTrue(plan.isOriginal(Track("eng")))
        assertTrue(plan.isOriginal(Track("en-US")))
        assertFalse(plan.isOriginal(Track("fre")))
        assertFalse(plan.isOriginal(Track(null)))
    }

    @Test fun `without Wikidata's facts no audio is Original`() = runBlocking {
        val plan = library.playPlan(godfather, french, DeviceProfile(maxHeight = 2160, hdr = emptySet())) ?: throw AssertionError("no plan")
        assertNull(plan.originalLanguage)
        assertFalse(plan.isOriginal(Track("eng")))
    }

    @Test fun `the device registers once and signs every request with its own token`() = runBlocking {
        // It registered during the Sync, for its first Title facts request.
        assertEquals("/v1/devices", sentBySync.first().path)
        assertTrue(sentBySync.drop(1).any { it.path == "/v1/title-facts" })
        val token = sentBySync[1].headers["Authorization"]
        assertTrue(token.orEmpty().single().startsWith("Bearer "))
        assertTrue(sentBySync.drop(1).all { it.headers["Authorization"] == token })

        // A token the cloud no longer knows is replaced: the facts the cloud couldn't answer are asked again.
        cloud.forgetDevices()
        library.resumeTitleFacts()
        assertEquals(listOf("/v1/title-facts", "/v1/devices", "/v1/title-facts"), cloud.received.take(3).map { it.path })
    }

    @Test fun `the cloud never sees the Provider login or address`() = runBlocking {
        library.titles(0, 1000, french, "fr")
        library.title(godfather, french, "fr")
        library.homeRows(french, "fr")

        val sent = (sentBySync + cloud.received).joinToString("\n") { "${it.path} ${it.headers} ${it.body}" }
        assertTrue(sentBySync.any { it.path == "/v1/title-facts" })
        for (secret in listOf(provider.password, provider.username, provider.url)) {
            assertFalse("the cloud was sent \"$secret\"", secret in sent)
        }
    }
}
