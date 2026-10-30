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

/** TMDB metadata on cards and details, from the fake Cloud, seen through the Library after a Sync of the fixture. */
class MetadataTest {
    @get:Rule val folder = TemporaryFolder()
    private val provider = FakeProvider()
    private val cloud = FakeCloud()
    private val credentials = InMemoryCredentials()
    private val libraries = mutableListOf<Library>()

    private fun openLibrary() = Library.open(folder.root.resolve("catalog.db").path, credentials, cloud.url).also { libraries += it }

    private lateinit var library: Library

    /** What the Sync sent the cloud (its Match requests), kept apart so tests see only what showing Titles sends. */
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
    private val godfather = TitleId.Tmdb(TitleKind.Movie, 238)
    private val ballerina = TitleId.Tmdb(TitleKind.Movie, 541671)

    private val leParrain = tmdbTitle(
        238,
        "The Godfather",
        titles = listOf(Triple("fr", "FR", "Le Parrain"), Triple("de", "DE", "Der Pate")),
        descriptions = listOf(Triple("en", "US", "Spanning the years 1945 to 1955…"), Triple("fr", "FR", "En 1945, à New York…")),
        genres = listOf("Drame", "Crime"),
        releaseDate = "1972-03-14",
        runtimeMinutes = 175,
        posterPath = "/parrain.jpg",
        backdropPath = "/godfather-backdrop.jpg",
        cast = listOf("Marlon Brando", "Al Pacino"),
    )

    private suspend fun card(id: TitleId, appLanguage: String, contentLanguages: List<String> = french) =
        library.titles(0, 1000, contentLanguages, appLanguage).single { it.id == id }

    private suspend fun details(id: TitleId, appLanguage: String, contentLanguages: List<String> = french) =
        library.title(id, contentLanguages, appLanguage) ?: throw AssertionError("no Title $id")

    @Test fun `a play plan knows the language the Title was released in, so the player can call its audio Original`() = runBlocking {
        cloud.title("fr", tmdbTitle(238, "The Godfather", originalLanguage = "en"))
        details(godfather, "fr")

        val plan = library.playPlan(godfather, french, DeviceProfile(maxHeight = 2160, hdr = emptySet())) ?: throw AssertionError("no plan")
        assertEquals("en", plan.originalLanguage)
        assertTrue(plan.isOriginal(Track("eng")))
        assertTrue(plan.isOriginal(Track("en-US")))
        assertFalse(plan.isOriginal(Track("fre")))
        assertFalse(plan.isOriginal(Track(null)))
    }

    @Test fun `without TMDB's metadata no audio is Original`() = runBlocking {
        val plan = library.playPlan(godfather, french, DeviceProfile(maxHeight = 2160, hdr = emptySet())) ?: throw AssertionError("no plan")
        assertNull(plan.originalLanguage)
        assertFalse(plan.isOriginal(Track("eng")))
    }

    @Test fun `a card and the details show TMDB's name, poster, description and genres in the App language`() = runBlocking {
        cloud.title("fr", leParrain)

        card(godfather, "fr").let {
            assertEquals("Le Parrain", it.name)
            assertEquals(1972, it.year)
            assertEquals("https://image.tmdb.org/t/p/w342/parrain.jpg", it.image)
        }
        details(godfather, "fr").let {
            assertEquals("Le Parrain", it.name)
            assertEquals("En 1945, à New York…", it.description)
            assertEquals(listOf("Drame", "Crime"), it.genres)
            assertEquals(175, it.runtimeMinutes)
            assertEquals("https://image.tmdb.org/t/p/w1280/godfather-backdrop.jpg", it.backdrop)
            assertEquals(listOf("Marlon Brando", "Al Pacino"), it.cast)
            assertTrue(it.fromTmdb)
        }
        // The Versions are still the Provider's, the Viewer's language first.
        assertEquals("fr", details(godfather, "fr").languages.first().language)
    }

    @Test fun `without a translation in the App language, the name and description are in English`() = runBlocking {
        cloud.title(
            "fr",
            tmdbTitle(
                541671,
                "Ballerina",
                titles = listOf(Triple("en", "US", "From the World of John Wick: Ballerina"), Triple("de", "DE", "Ballerina")),
                descriptions = listOf(Triple("en", "US", "Taking place during the events of John Wick: Chapter 3…")),
                genres = listOf("Action"),
            ),
        )

        assertEquals("From the World of John Wick: Ballerina", card(ballerina, "fr").name)
        assertEquals("Taking place during the events of John Wick: Chapter 3…", details(ballerina, "fr").description)
    }

    @Test fun `a Title with no translation at all keeps the name it was released under`() = runBlocking {
        cloud.title("fr", tmdbTitle(238, "The Godfather", descriptions = listOf(Triple("en", "US", "Spanning…"))))

        assertEquals("The Godfather", card(godfather, "fr").name)
    }

    @Test fun `a Québec Viewer gets Québec's translation where TMDB has one`() = runBlocking {
        cloud.title(
            "fr",
            tmdbTitle(
                238,
                "The Godfather",
                titles = listOf(Triple("fr", "FR", "Le Parrain"), Triple("fr", "CA", "Le Parrain (Québec)")),
                descriptions = listOf(Triple("fr", "FR", "En France…"), Triple("fr", "CA", "Au Québec…")),
            ),
        )

        assertEquals("Le Parrain (Québec)", card(godfather, "fr-CA").name)
        assertEquals("Au Québec…", details(godfather, "fr-CA").description)
        assertEquals("Le Parrain", card(godfather, "fr-FR").name)
        // The cloud is asked in French either way.
        assertEquals(setOf("movie/238/fr"), cloud.metadataAsked.filter { it.startsWith("movie/238/") }.toSet())
    }

    @Test fun `metadata is kept on the device, so showing a Title again doesn't reach the cloud`() = runBlocking {
        cloud.title("fr", leParrain)
        card(godfather, "fr")
        details(godfather, "fr")
        card(godfather, "fr")
        assertEquals(1, cloud.metadataAsked.count { it == "movie/238/fr" })

        // Also after the app restarts, and for a Title TMDB doesn't have.
        library.close()
        library = openLibrary()
        assertEquals("Le Parrain", card(godfather, "fr").name)
        assertEquals(1, cloud.metadataAsked.count { it == "movie/238/fr" })
        assertEquals(1, cloud.metadataAsked.count { it == "movie/541671/fr" })

        // Each App language is kept apart.
        card(godfather, "en")
        assertEquals(1, cloud.metadataAsked.count { it == "movie/238/en" })
    }

    @Test fun `a page of cards is one request, and Unmatched Titles aren't asked about`() = runBlocking {
        library.titles(0, 1000, french, "fr")

        val requests = cloud.received.filter { it.path == "/v1/metadata" }
        // The fixture's 47 Titles with a TMDB ID fit in one batch of 50.
        assertEquals(1, requests.size)
        assertEquals(47, cloud.metadataAsked.size)
    }

    @Test fun `without the cloud, cards show what the Provider says, and the cloud isn't asked again right away`() = runBlocking {
        cloud.title("fr", leParrain)
        cloud.down = true

        card(godfather, "fr").let {
            assertEquals("Le Parrain", it.name)
            // The Provider's poster for the best French Version.
            assertFalse(it.image.orEmpty().startsWith("https://image.tmdb.org/"))
        }
        details(godfather, "fr").let {
            assertNull(it.description)
            assertFalse(it.fromTmdb)
        }
        assertEquals(1, cloud.received.size)
    }

    @Test fun `the device registers once and signs every request with its own token`() = runBlocking {
        cloud.title("fr", leParrain)
        card(godfather, "fr")
        card(godfather, "en")

        // It registered during the Sync, for its first Match request.
        assertEquals("/v1/devices", sentBySync.first().path)
        assertEquals(listOf("/v1/metadata", "/v1/metadata"), cloud.received.map { it.path })
        val token = sentBySync[1].headers["Authorization"]
        assertTrue(token.orEmpty().single().startsWith("Bearer "))
        assertTrue((sentBySync.drop(1) + cloud.received).all { it.headers["Authorization"] == token })

        // A token the cloud no longer knows is replaced.
        cloud.forgetDevices()
        card(ballerina, "de")
        assertEquals(listOf("/v1/metadata", "/v1/devices", "/v1/metadata"), cloud.received.drop(2).map { it.path })
    }

    @Test fun `the cloud never sees the Provider login or address`() = runBlocking {
        cloud.title("fr", leParrain)
        library.titles(0, 1000, french, "fr")
        details(godfather, "fr")

        val sent = (sentBySync + cloud.received).joinToString("\n") { "${it.path} ${it.headers} ${it.body}" }
        assertTrue(sentBySync.any { it.path == "/v1/match" })
        assertTrue(cloud.received.isNotEmpty())
        for (secret in listOf(provider.password, provider.username, provider.url)) {
            assertFalse("the cloud was sent \"$secret\"", secret in sent)
        }
    }
}
