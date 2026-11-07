package dev.betteriptv.core

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A Title's card names and pictures it from the Provider's Listings (#474, ADR 0014), seen through
 * the Library over the fake Provider: the App language's Listing names it, else the English one,
 * else the first; its poster is the file most Listings share, ties to the App language's Listing,
 * then the English one, then the Best playable Version's.
 */
class ListingNamesTest {
    @get:Rule val folder = TemporaryFolder()
    private val provider = FakeProvider()
    private val cloud = FakeCloud()
    private val credentials = InMemoryCredentials()
    private lateinit var library: Library

    @Before fun logIn() = runBlocking {
        library = Library.open(folder.root.resolve("catalog.db").path, credentials, cloud.url)
        library.logIn(provider.login)
        provider.showCategoriesOverride = "[]"
        provider.showListOverride = "[]"
        Unit
    }

    @After fun tearDown() {
        library.close()
        provider.close()
        cloud.close()
    }

    private val film = TitleId.Tmdb(TitleKind.Movie, 4242)
    private val every = listOf("en", "fr", "de", "el", "tr")

    private fun json(text: String?) = if (text == null) "\"\"" else "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    /** Serves these movie Listings of Title 4242, each `name to poster`, numbered from 1 in one Category, and Syncs. */
    private suspend fun syncFilm(vararg listings: Pair<String, String?>) {
        provider.movieCategoriesOverride = """[{"category_id":"1","category_name":"MOVIES"}]"""
        provider.movieListOverride = listings.withIndex().joinToString(",", "[", "]") { (i, listing) ->
            """{"stream_id":${i + 1},"name":${json(listing.first)},"tmdb":"4242","category_id":"1","stream_icon":${json(listing.second)},""" +
                """"added":"${1_700_000_000 - i}","container_extension":"mkv"}"""
        }
        library.sync()
    }

    private suspend fun card(appLanguage: String, contentLanguages: List<String> = every) =
        library.titles(0, 10, contentLanguages, appLanguage).single { it.id == film }

    @Test fun aTitleIsNamedByItsListingInTheAppLanguageElseTheEnglishOne() = runBlocking {
        syncFilm("EN - Film (2024)" to null, "FR - Le Film (2024)" to null)

        assertEquals("Le Film", card("fr").name)
        assertEquals("Film", card("en").name)
        assertEquals("Film", card("de").name)
        assertEquals(2024, card("fr").year)
        assertEquals("Le Film", library.title(film, every, "fr")!!.name)
    }

    @Test fun aTitleWithOnlyAGreekListingIsNamedByIt() = runBlocking {
        syncFilm("GR - Η Ταινία (2024)" to null)

        assertEquals("Η Ταινία", card("fr").name)
        assertEquals(2024, card("fr").year)
    }

    @Test fun thePosterIsTheFileMostListingsShare() = runBlocking {
        syncFilm("EN - Film (2024)" to "https://art/b.jpg", "FR - Le Film (2024)" to "https://art/a.jpg", "DE - Der Film (2024)" to "https://art/a.jpg")

        assertEquals("https://art/a.jpg", card("en").image)
    }

    @Test fun aFileCountsOncePerLanguageSoACopyListedTwiceDoesNotOutvoteTheOthers() = runBlocking {
        // The surveyed Provider lists `DE - Der Astronaut: Project Hail Mary` twice with one file.
        syncFilm(
            "EN - Film (2024)" to "https://art/en.jpg", "FR - Le Film (2024)" to "https://art/fr.jpg",
            "DE - Der Film (2024)" to "https://art/de.jpg", "DE - Der Film (2024)" to "https://art/de.jpg",
        )

        assertEquals("https://art/en.jpg", card("en").image)
        assertEquals("https://art/fr.jpg", card("fr").image)
        assertEquals("https://art/de.jpg", card("de").image)
    }

    @Test fun withOnePosterEachTheAppLanguagesListingWinsThenTheEnglishOne() = runBlocking {
        syncFilm("EN - Film (2024)" to "https://art/en.jpg", "FR - Le Film (2024)" to "https://art/fr.jpg", "DE - Der Film (2024)" to "https://art/de.jpg")

        assertEquals("https://art/fr.jpg", card("fr").image)
        assertEquals("https://art/de.jpg", card("de").image)
        assertEquals("https://art/en.jpg", card("es").image)
    }

    @Test fun withoutTheAppLanguageOrEnglishTheBestPlayableVersionsPosterWins() = runBlocking {
        syncFilm("DE - Der Film (2024)" to "https://art/de.jpg", "FR-4K - Le Film (2024)" to "https://art/fr.jpg")

        // The 4K French Version is the best playable one in German and French; in German alone, the German one.
        assertEquals("https://art/fr.jpg", card("es", listOf("de", "fr")).image)
        assertEquals("https://art/de.jpg", card("es", listOf("de")).image)
    }

    @Test fun aListingAddedInAnotherLanguageChangesNoPoster() = runBlocking {
        syncFilm("EN - Film (2024)" to "https://art/en.jpg", "FR - Le Film (2024)" to "https://art/fr.jpg", "DE - Der Film (2024)" to "https://art/de.jpg")
        val before = card("fr").image

        syncFilm(
            "EN - Film (2024)" to "https://art/en.jpg", "FR - Le Film (2024)" to "https://art/fr.jpg", "DE - Der Film (2024)" to "https://art/de.jpg",
            "TR - Film (2024)" to "https://art/tr.jpg",
        )

        assertEquals(before, card("fr").image)
        assertEquals("https://art/fr.jpg", card("fr").image)
    }

    @Test fun aTitleWhoseListingsHaveNoPosterHasNone() = runBlocking {
        syncFilm("EN - Film (2024)" to null, "FR - Le Film (2024)" to null)

        assertNull(card("en").image)
    }
}
