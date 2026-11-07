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
 * Untagged Versions on a Title's details (#388): a Version whose Prefix names a Source but no language
 * (`D+`, `TOP`) is a copy of the release, so it is Likely in the Title's original language. A Prefix
 * that names neither (`SC`, the Nordic copies, which may be dubs) still says nothing.
 * The Listings are the surveyed Provider's Toy Story 5 (2026), with their Categories.
 */
class UntaggedVersionsTest {
    @get:Rule val folder = TemporaryFolder()
    private val provider = FakeProvider()
    private val cloud = FakeCloud()
    private lateinit var library: Library

    @Before fun logIn() = runBlocking {
        library = openSurveyedLibrary(folder.root.resolve("catalog.db").path, InMemoryCredentials(), cloud.url)
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

    private val toyStory5 = TitleId.Tmdb(TitleKind.Movie, 1084244)

    private val listings = listOf(
        "EN - Toy Story 5  (2026)" to "EN - KIDS",
        "TOP - Toy Story 5 (2026)" to "TOP KIDS BLURAY (MULTI-SUBS)",
        "4K-TOP - Toy Story 5 (2026)" to "TOP MOVIES ⁴ᴷ ³⁸⁴⁰ᴾ ᴰᴼᴸᴮʸ ᴬᵁᴰᴵᴼ",
        "4K-D+ - Toy Story 5 (2026)" to "DISNEY+ KIDS MOVIES ⁴ᴷ ³⁸⁴⁰ᴾ ᴰᵒˡᵇʸ ⱽᶦˢᶦᵒⁿ",
        "D+ - Toy Story 5 (2026)" to "DISNEY+ KIDS MOVIES",
        "FR - Toy Story 5 (2026)" to "FR - FILMS POUR ENFANTS",
        "SC - Toy Story 5 (NordicDub) (2026)" to "NORDIC KIDS",
    )

    /** Syncs Toy Story 5's Listings, each in its own Category, newest first. */
    private suspend fun sync() {
        val categories = listings.map { it.second }.distinct()
        provider.movieCategoriesOverride = categories.withIndex()
            .joinToString(",", "[", "]") { (i, name) -> """{"category_id":"${i + 1}","category_name":"$name"}""" }
        provider.movieListOverride = listings.withIndex().joinToString(",", "[", "]") { (i, listing) ->
            """{"stream_id":${i + 1},"name":"${listing.first}","tmdb":"1084244","category_id":"${categories.indexOf(listing.second) + 1}","added":"${1_700_000_000 - i}"}"""
        }
        library.sync()
    }

    private fun TitleDetails.group(language: String?) = (languages + otherLanguages).singleOrNull { it.language == language }

    private fun LanguageGroup.names() = versions.flatMap { listOf(it) + it.backups }.map { it.name }.toSet()

    @Test fun `a Source's copies are Likely in the Title's original language`() = runBlocking {
        cloud.title("en", tmdbTitle(1084244, "Toy Story 5", originalLanguage = "en"))
        sync()

        val details = library.title(toyStory5, listOf("en"), "en")!!
        assertEquals(
            setOf("EN - Toy Story 5  (2026)", "TOP - Toy Story 5 (2026)", "4K-TOP - Toy Story 5 (2026)", "4K-D+ - Toy Story 5 (2026)", "D+ - Toy Story 5 (2026)"),
            details.group("en")!!.names(),
        )
        val disney = details.group("en")!!.versions.flatMap { listOf(it) + it.backups }.single { it.name == "D+ - Toy Story 5 (2026)" }
        assertEquals(Known("en", Provenance.Likely), disney.attributes.language)
        // The Nordic copy may be a dub ("NordicDub"): its language stays unnamed.
        assertEquals(setOf("SC - Toy Story 5 (NordicDub) (2026)"), details.group(null)!!.names())
    }

    @Test fun `a Title released in French makes its Source copies Likely French`() = runBlocking {
        cloud.title("en", tmdbTitle(1084244, "Toy Story 5", originalLanguage = "fr"))
        sync()

        val details = library.title(toyStory5, listOf("en"), "en")!!
        assertEquals(setOf("EN - Toy Story 5  (2026)"), details.group("en")!!.names())
        assertEquals(5, details.group("fr")!!.names().size)
    }

    @Test fun `without TMDB's original language a Source's copies stay Untagged`() = runBlocking {
        sync()

        val details = library.title(toyStory5, listOf("en"), "en")!!
        assertEquals(5, details.group(null)!!.names().size)
        assertNull(details.group("en")!!.names().firstOrNull { it.startsWith("D+") })
    }

    @Test fun `TMDB's "no language" names none`() = runBlocking {
        cloud.title("en", tmdbTitle(1084244, "Toy Story 5", originalLanguage = "xx"))
        sync()

        assertEquals(5, library.title(toyStory5, listOf("en"), "en")!!.group(null)!!.names().size)
    }
}
