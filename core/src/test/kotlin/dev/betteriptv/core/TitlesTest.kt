package dev.betteriptv.core

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** One card per Title, and a Title's Versions grouped by language, seen through the Library after a Sync. */
class TitlesTest {
    @get:Rule val folder = TemporaryFolder()
    private val provider = FakeProvider()
    private val credentials = InMemoryCredentials()
    private lateinit var library: Library

    @Before fun logIn() = runBlocking {
        library = Library.open(folder.root.resolve("catalog.db").path, credentials)
        library.logIn(provider.login)
        Unit
    }

    @After fun tearDown() {
        library.close()
        provider.close()
    }

    private val english = listOf("en")
    private val ballerina = TitleId.Tmdb(TitleKind.Movie, 541671)
    private val godfather = TitleId.Tmdb(TitleKind.Movie, 238)

    private suspend fun cards(contentLanguages: List<String> = english) = library.titles(0, 1000, contentLanguages, appLanguage = "en")

    private suspend fun title(id: TitleId, contentLanguages: List<String> = english): TitleDetails =
        library.title(id, contentLanguages, appLanguage = "en") ?: throw AssertionError("no Title $id")

    /** Every Version of a Title, shown or Backup. */
    private fun TitleDetails.allVersions() = (languages + otherLanguages).flatMap { group -> group.versions.flatMap { listOf(it) + it.backups } }

    private fun LanguageGroup.shownNames() = versions.map { it.name }

    /** Syncs these Listings, newest first, numbered from 1. */
    private suspend fun syncListings(vararg listings: Listing): SyncStatus {
        val categories = listings.map { it.category }.distinct()
        provider.movieCategoriesOverride = categories.withIndex()
            .joinToString(",", "[", "]") { (i, name) -> """{"category_id":"${i + 1}","category_name":${json(name)}}""" }
        provider.movieListOverride = listings.withIndex().joinToString(",", "[", "]") { (i, listing) ->
            """{"stream_id":${i + 1},"name":${json(listing.name)},"tmdb":${json(listing.tmdb)},"stream_icon":${json(listing.image)},""" +
                """"category_id":"${categories.indexOf(listing.category) + 1}","added":"${1_700_000_000 - i}"}"""
        }
        return library.sync()
    }

    private data class Listing(val name: String, val tmdb: String, val category: String = "MOVIES", val image: String = "")

    private fun json(text: String) = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    @Test fun `the fixture's Ballerina (2025) Listings are one Title`() = runBlocking {
        library.sync()

        assertEquals(1, cards().count { it.id == ballerina })
        // The fixture holds 32 of the 34 Ballerina (2025) Listings the surveyed Provider has.
        assertEquals(32, title(ballerina).allVersions().size)
        // Ballerina Assassin is another film.
        assertEquals(4, title(TitleId.Tmdb(TitleKind.Movie, 1478052)).allVersions().size)
    }

    @Test fun `Listings in different languages are one Title`() = runBlocking {
        library.sync()

        val names = title(godfather).allVersions().map { it.name }
        assertTrue("EN - The Godfather  (1972)" in names)
        assertTrue("4K-FR - Le Parrain (1972)" in names)
        assertTrue("4K-DE-DV - Der Pate (1972)" in names)
        assertEquals(29, names.size)
    }

    @Test fun `each Title gets one card, and Clutter and Sports replays get none`() = runBlocking {
        library.sync()

        // The fixture's 150 movie Listings: 47 Titles by TMDB ID, and 9 Listings without one.
        assertEquals(56, library.titleCount())
        val cards = cards()
        assertEquals(56, cards.size)
        assertEquals(56, cards.map { it.id }.distinct().size)
        assertEquals(9, cards.count { it.id is TitleId.Unmatched })
    }

    @Test fun `Titles are keyed by kind and TMDB ID, so a Show with the same number is another Title`() = runBlocking {
        library.sync()

        assertNotNull(library.title(ballerina, english, "en"))
        assertNull(library.title(TitleId.Tmdb(TitleKind.Show, 541671), english, "en"))
    }

    @Test fun `a Listing without a usable TMDB ID stands alone`() = runBlocking {
        library.sync()

        val unmatched = cards().filter { it.id is TitleId.Unmatched }.map { it.name }
        assertTrue("Dirty Hari" in unmatched)
        // Its "tmdb" is an IMDb ID.
        assertTrue(cards().single { it.name == "365 Days: This Day" }.id is TitleId.Unmatched)
    }

    @Test fun `a card shows the best Version's cleaned name in the Viewer's language, newest Title first`() = runBlocking {
        syncListings(
            Listing("EN - Newest (2026)", "5"),
            Listing("FR - Le Parrain (1972)", "238"),
            Listing("4K-DE-DV - Der Pate (1972)", "238"),
            Listing("EN - The Godfather  (1972)", "238"),
            Listing("4K-EN - The Godfather  (1972)", "238"),
            Listing("FR - Older (2020)", "6"),
        )

        assertEquals(
            listOf("Newest" to 2026, "The Godfather" to 1972, "Older" to 2020),
            cards(english).map { it.name to it.year },
        )
        assertEquals("Le Parrain", cards(listOf("fr")).single { it.id == godfather }.name)
        // No Version in the Viewer's language: the best Version overall.
        assertEquals("Der Pate", cards(listOf("it")).single { it.id == godfather }.name)
        cards(english).single { it.id == godfather }.best.let {
            assertEquals(Known(Quality.UHD, Provenance.Likely), it.quality)
            assertEquals(Known("en", Provenance.Likely), it.language)
        }
    }

    @Test fun `changing the Content languages changes each card's Version and the order of a Title's languages`() = runBlocking {
        syncListings(
            Listing("4K-EN - The Godfather  (1972)", "238"),
            Listing("FR - Le Parrain (1972)", "238"),
            Listing("QC - Le Parrain (1972)", "238"),
            Listing("DE - Der Pate (1972)", "238"),
        )

        assertEquals(Known("en", Provenance.Likely), cards(english).single().best.language)
        assertEquals(listOf("en"), title(godfather, english).languages.map { it.language })
        assertEquals("fr", cards(listOf("fr")).single().best.language?.value)
        assertEquals(listOf("fr", "en"), title(godfather, listOf("fr", "en")).languages.map { it.language })
        title(godfather, listOf("de")).let { details ->
            assertEquals(listOf("de"), details.languages.map { it.language })
            assertEquals(listOf("fr", "en"), details.otherLanguages.map { it.language })
        }
    }

    @Test fun `the Content language choices are the catalog's languages, most Listings first`() = runBlocking {
        provider.showCategoriesOverride = """[{"category_id":"1","category_name":"SHOWS"}]"""
        provider.showListOverride = """[{"series_id":1,"name":"EN - A Show","category_id":"1"}]"""
        syncListings(
            Listing("FR - Le Parrain (1972)", "238"),
            Listing("QC - Le Parrain (1972)", "238"),
            Listing("EN - The Godfather  (1972)", "238"),
            Listing("FR - Older (2020)", "6"),
            Listing("DE - Der Pate (1972)", "238"),
            Listing("DE - Älter (2020)", "6"),
            Listing("Untagged (2021)", "7"),
        )

        // Québec French counts as French; Untagged Listings add no language.
        // Show Listings count too.
        assertEquals(listOf("fr", "de", "en"), library.catalogLanguages())
    }

    @Test fun `a card's name drops a country code and reads a dotted name as words`() = runBlocking {
        syncListings(
            Listing("EN - Reacher (US)", "108978"),
            Listing("IN-GU - Mohgiben.MBBS.2023", "19625"),
            Listing("QC - P-A.Méthot.Le.Party.Du.Millénaire.2022", ""),
            Listing("NF - Mr. Right (2016) HEVC", "333385"),
            // 2049 is part of the name: a year still to come.
            Listing("EN - Blade.Runner.2049", "335984"),
        )

        assertEquals(
            listOf("Reacher" to null, "Mohgiben MBBS" to 2023, "P-A Méthot Le Party Du Millénaire" to 2022, "Mr. Right" to 2016, "Blade.Runner.2049" to null),
            cards().map { it.name to it.year },
        )
    }

    @Test fun `a card uses the Provider's image, from the best Version that has one`() = runBlocking {
        syncListings(
            Listing("4K-EN - The Godfather (1972)", "238"),
            Listing("EN - The Godfather (1972)", "238", image = "http://images.example/godfather.jpg"),
            Listing("EN - Older (2020)", "6"),
        )

        assertEquals("http://images.example/godfather.jpg", cards().single { it.id == godfather }.image)
        assertEquals("http://images.example/godfather.jpg", title(godfather).image)
        assertNull(cards().single { it.name == "Older" }.image)
    }

    @Test fun `Versions are grouped by language, Content languages first, the rest under Other languages`() = runBlocking {
        library.sync()

        title(ballerina, listOf("fr", "en")).let { details ->
            assertEquals(listOf("fr", "en", null), details.languages.map { it.language })
            assertEquals(
                // Most Versions first: Spanish 4, German 3, Hindi 2, then one each. `TR - ... [TR - ALT YAZI]` is
                // subtitled in Turkish, so it's Untagged.
                listOf("es", "de", "hi", "el", "fa", "he", "kmr", "nl", "pl", "pt", "ro", "te", "tr"),
                details.otherLanguages.map { it.language },
            )
        }
        assertEquals(listOf("en", null), title(ballerina, english).languages.map { it.language })
    }

    @Test fun `within a language, the best quality comes first and equivalent Versions become its Backups`() = runBlocking {
        library.sync()

        val french = title(ballerina, listOf("fr")).languages.first()
        assertEquals(
            listOf("4K-FR-HDR - Ballerina (2025)", "4K-FR - Ballerina (2025)", "FR - Ballerina (2025)"),
            french.shownNames(),
        )
        assertEquals(listOf(0, 1, 1), french.versions.map { it.backups.size })
        assertEquals(5, french.versionCount)

        val english = title(ballerina).languages.first()
        assertEquals(1, english.versions.size)
        assertEquals(2, english.versions.single().backups.size)
    }

    @Test fun `Backups are ranked like Versions, a known Source first, then the newest Listing`() = runBlocking {
        syncListings(
            Listing("NL - Film (2020)", "7"),
            Listing("VP-NL - Film (2020)", "7"),
            Listing("NL - Film (2020)", "7", category = "NL - NEW"),
        )

        val version = title(TitleId.Tmdb(TitleKind.Movie, 7), listOf("nl")).languages.single().versions.single()
        assertEquals(2L, version.listingId)
        assertEquals(listOf(1L, 3L), version.backups.map { it.listingId })
    }

    @Test fun `CAM Versions rank last and Audio-description Versions are labelled, never Backups of plain ones`() = runBlocking {
        syncListings(
            Listing("EN-CAM - Film (2025)", "8"),
            Listing("EN - Film (2025)", "8", category = "EN - AUDIO-DESCRIPTION"),
            Listing("EN - Film (2025)", "8"),
            Listing("4K-EN - Film (2025)", "8"),
        )

        val versions = title(TitleId.Tmdb(TitleKind.Movie, 8)).languages.single().versions
        assertEquals(listOf(4L, 3L, 2L, 1L), versions.map { it.listingId })
        assertEquals(listOf(false, false, true, false), versions.map { it.attributes.audioDescription })
        assertEquals(listOf(false, false, false, true), versions.map { it.attributes.cam })
        assertTrue(versions.all { it.backups.isEmpty() })
    }

    @Test fun `Québec French Versions are under French, apart from France French ones`() = runBlocking {
        syncListings(
            Listing("QC - Les Invasions barbares (2003)", "11042"),
            Listing("FR - Les Invasions barbares (2003)", "11042"),
            Listing("EN - The Barbarian Invasions (2003)", "11042"),
        )

        val details = title(TitleId.Tmdb(TitleKind.Movie, 11042), listOf("fr"))
        val french = details.languages.single()
        assertEquals("fr", french.language)
        assertEquals(setOf("fr-CA", "fr"), french.versions.map { it.attributes.language?.value }.toSet())
        assertTrue(french.versions.all { it.backups.isEmpty() })
        assertEquals(listOf("en"), details.otherLanguages.map { it.language })
        // A Québec Viewer's French is French too.
        assertEquals(listOf("fr"), title(TitleId.Tmdb(TitleKind.Movie, 11042), listOf("fr-CA")).languages.map { it.language })
    }

    @Test fun `a later Sync replaces the Titles`() = runBlocking {
        library.sync()
        syncListings(Listing("EN - Only One (2026)", "9"))

        assertEquals(1, library.titleCount())
        assertNull(library.title(ballerina, english, "en"))
        assertEquals("Only One", cards().single().name)
    }
}
