package dev.betteriptv.core

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * On a Provider nobody surveyed, the Versions whose Prefix neither the Base table nor the Provider
 * table knows are Header read first (#427), so the copies the app knows least about are Confirmed
 * within the first seconds. `XB` is a Prefix no table knows.
 */
class UnknownPrefixFirstTest {
    @get:Rule val folder = TemporaryFolder()
    private val provider = FakeProvider()
    private val cloud = FakeCloud()
    private lateinit var library: Library

    @Before fun logIn() = runBlocking {
        library = Library.open(folder.root.resolve("catalog.db").path, InMemoryCredentials(), cloud.url)
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

    private val movie = TitleId.Tmdb(TitleKind.Movie, 1)
    private val english = listOf("en")

    private val germanFullHd = matroskaFile(listOf(FileTrack("audio", "ger", "A_EAC3", channels = 6)), width = 1920, height = 1080)
    private val englishFile = matroskaFile(listOf(FileTrack("audio", "eng", "A_AAC")))
    private val mandarinFile = matroskaFile(listOf(FileTrack("audio", "chi", "A_AAC")))

    /** Syncs these Listings of one Movie, as (name, Category, file), newest first, numbered from 1. */
    private suspend fun syncVersions(originalLanguage: String, vararg listings: Triple<String, String, ByteArray>) {
        cloud.titleFacts(movie, originalLanguage = originalLanguage)
        val categories = listings.map { it.second }.distinct()
        provider.movieCategoriesOverride = categories.withIndex()
            .joinToString(",", "[", "]") { (i, name) -> """{"category_id":"${i + 1}","category_name":"$name"}""" }
        provider.movieListOverride = listings.withIndex().joinToString(",", "[", "]") { (i, listing) ->
            """{"stream_id":${i + 1},"name":"${listing.first}","tmdb":"1","category_id":"${categories.indexOf(listing.second) + 1}",""" +
                """"added":"${1_700_000_000 - i}","container_extension":"mkv"}"""
        }
        listings.forEachIndexed { i, listing -> provider.movieFiles[i + 1L] = listing.third }
        library.sync()
    }

    private suspend fun openPage(preferOriginalAudio: Boolean = true): TitleDetails {
        val details = library.title(movie, english, "en", preferOriginalAudio = preferOriginalAudio)!!
        library.readHeaders(movie, english, preferOriginalAudio) {}
        return details
    }

    private fun readIds() = provider.ranges.map { it.substringBefore(':').toLong() }

    @Test fun `the page reads an unknown-Prefix Version before an NF one`() = runBlocking {
        syncVersions(
            "zh",
            Triple("NF - Heat (1995)", "NETFLIX", mandarinFile),
            Triple("XB - Heat (1995)", "MOVIES", germanFullHd),
        )

        openPage()

        // Untagged (`NF`) would be first on a Title released outside the Viewer's languages.
        assertEquals(listOf(2L, 1L), readIds())
    }

    @Test fun `the unknown-Prefix Version goes first in every order, then the order as before`() = runBlocking {
        syncVersions(
            "en",
            Triple("EN - Heat (1995)", "EN - MOVIES", englishFile),
            Triple("NF - Heat (1995)", "NETFLIX", englishFile),
            Triple("XB - Heat (1995)", "MOVIES", germanFullHd),
        )

        // Released in English: the Content languages, then the copy naming no language.
        openPage()

        assertEquals(listOf(3L, 1L, 2L), readIds())
    }

    @Test fun `the best Version of each unknown Prefix is read, one per Prefix`() = runBlocking {
        syncVersions(
            "en",
            Triple("XB - Heat (1995)", "MOVIES", germanFullHd),
            Triple("EN - Heat (1995)", "EN - MOVIES", englishFile),
            Triple("XQ - Heat (1995)", "MOVIES", mandarinFile),
            Triple("XB - Heat (1995)", "MOVIES", germanFullHd),
        )

        openPage(preferOriginalAudio = false)

        // `XB`'s newest copy and `XQ`'s, then English; the second `XB` copy waits, as a second copy in a language does.
        assertEquals(listOf(1L, 3L, 2L), readIds())
    }

    @Test fun `once Confirmed, the unknown-Prefix Version's badges are solid and it sits in its language's group`() = runBlocking {
        syncVersions(
            "en",
            Triple("EN - Heat (1995)", "EN - MOVIES", englishFile),
            Triple("XB - Heat (1995)", "MOVIES", germanFullHd),
        )
        val before = openPage()
        // Before its read it names no language, and sits with the Untagged Versions.
        assertEquals(listOf("en", null), before.languages.map { it.language })
        assertEquals(emptyList<String?>(), before.otherLanguages.map { it.language })

        val details = library.title(movie, english, "en")!!

        assertEquals(listOf("en"), details.languages.map { it.language })
        assertEquals(listOf("de"), details.otherLanguages.map { it.language })
        val xb = details.otherLanguages.single().versions.single()
        assertEquals("XB - Heat (1995)", xb.name)
        assertEquals(Known("de", Provenance.Confirmed), xb.attributes.language)
        assertEquals(Known(Quality.FullHD, Provenance.Confirmed), xb.attributes.quality)
        val table = versionTable(details.languages + details.otherLanguages, english, details.originalLanguage)
        assertEquals(listOf("en", "de"), table.groups.map { it.language })
    }
}
