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

/** What the Prefix table makes of Listings, seen through the Library after a Sync. */
class PrefixTableTest {
    @get:Rule val folder = TemporaryFolder()
    private val provider = FakeProvider()
    private val credentials = InMemoryCredentials()
    private val libraries = mutableListOf<Library>()

    private fun openLibrary() = Library.open(folder.root.resolve("catalog.db").path, credentials).also { libraries += it }

    private val library by lazy { openLibrary() }

    @Before fun logIn() = runBlocking { library.logIn(provider.login); Unit }

    @After fun tearDown() {
        libraries.forEach { it.close() }
        provider.close()
    }

    private fun movies() = library.movieListings(0, 10_000)

    private fun movie(id: Long) = movies().single { it.id == id }.attributes

    private fun likely(value: String) = Known(value, Provenance.Likely)

    /** Syncs these Listings, each `name` filed under the Category `category`. */
    private suspend fun syncListings(vararg listings: Pair<String, String>): SyncStatus {
        val categories = listings.map { it.second }.distinct()
        provider.movieCategoriesOverride = categories.withIndex()
            .joinToString(",", "[", "]") { (i, name) -> """{"category_id":"${i + 1}","category_name":${json(name)}}""" }
        provider.movieListOverride = listings.withIndex().joinToString(",", "[", "]") { (i, listing) ->
            """{"stream_id":${i + 1},"name":${json(listing.first)},"category_id":"${categories.indexOf(listing.second) + 1}","added":"${1_700_000_000 - i}"}"""
        }
        return library.sync()
    }

    private fun json(text: String) = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    @Test fun `language comes from the Prefix, including Québec French and Indian sub-languages`() = runBlocking {
        library.sync()

        assertEquals(likely("en"), movie(1107).language)
        assertEquals(likely("fr-CA"), movie(1083).language)
        assertEquals(likely("ta"), movie(1006).language)
        assertEquals(likely("te"), movie(1041).language)
        assertEquals(likely("gu"), movie(1137).language)
        assertEquals(likely("ckb"), movie(1112).language)
    }

    @Test fun `4K, HDR and Dolby Vision come from the Prefix and the Category`() = runBlocking {
        library.sync()

        movie(1097).let {
            assertEquals(Known(Quality.UHD, Provenance.Likely), it.quality)
            assertEquals(Known(Hdr.HDR10, Provenance.Likely), it.hdr)
            assertEquals(likely("fr"), it.language)
        }
        assertEquals(Known(Hdr.DolbyVision, Provenance.Likely), movie(1018).hdr)
        // "ES - PELÍCULAS ⁴ᴷ ᴴᴰᴿ ᴰᴼᴸᴮʸ ⱽᴵˢᴵÓᴺ": the Prefix only says HDR, the Category says Dolby Vision.
        assertEquals(Known(Hdr.DolbyVision, Provenance.Likely), movie(1064).hdr)
        // "IN - HINDI ⁴ᴷ ³⁸⁴⁰ᴾ"
        assertEquals(Known(Quality.UHD, Provenance.Likely), movie(1003).quality)
        movie(1107).let {
            assertNull(it.quality)
            assertNull(it.hdr)
        }
    }

    @Test fun `a Netflix Dolby Vision Category upgrades a 4K Source Prefix`() = runBlocking {
        syncListings("4K-NF - Therapy Done Right (2026)" to "NETFLIX MOVIES ⁴ᴷ ³⁸⁴⁰ᴾ ᴰᵒˡᵇʸ ⱽᶦˢᶦᵒⁿ")

        val attributes = movies().single().attributes
        assertEquals(likely("Netflix"), attributes.source)
        assertEquals(Known(Quality.UHD, Provenance.Likely), attributes.quality)
        assertEquals(Known(Hdr.DolbyVision, Provenance.Likely), attributes.hdr)
        assertNull(attributes.language)
    }

    @Test fun `a Source Prefix names no language, so the Version is Untagged`() = runBlocking {
        library.sync()

        movie(1029).let {
            assertEquals(likely("Netflix"), it.source)
            assertNull(it.language)
        }
        assertEquals(likely("Paramount"), movie(1103).source)
        // "TOP MOVIES BLURAY (MULTI-SUBS)"
        movie(1081).let {
            assertEquals(likely("Blu-ray"), it.source)
            assertEquals(Known(setOf("mul"), Provenance.Likely), it.subtitles)
        }
    }

    @Test fun `Dolby audio comes from a DO Prefix or a DOLBY AUDIO Category`() = runBlocking {
        library.sync()

        assertTrue(movie(1111).dolbyAudio)
        assertTrue(movie(1042).dolbyAudio)
        assertTrue(movie(1105).dolbyAudio) // "FR - LE DRENIER AJOUTEE ᴰᴼᴸᴮʸ ᴬᵁᴰᴵᴼ"
        assertFalse(movie(1004).dolbyAudio)
    }

    @Test fun `CAM copies are marked, and Turkish Yesilcam classics aren't`() = runBlocking {
        syncListings(
            "IN-CAM - Vibe (2026)" to "IN - CAM & NEW RELEASE",
            "FR - Superman (2025)" to "FR - FILM CAM",
            "EN-CAM - Superman  (2025)" to "EN - NEW RELEASE",
            "TR - Bal" to "TR - YESIL CAM SINEMA",
        )

        val byName = movies().associate { it.name to it.attributes }
        assertTrue(byName.getValue("IN-CAM - Vibe (2026)").cam)
        assertEquals(likely("hi"), byName.getValue("IN-CAM - Vibe (2026)").language)
        assertTrue(byName.getValue("FR - Superman (2025)").cam)
        assertTrue(byName.getValue("EN-CAM - Superman  (2025)").cam)
        assertFalse(byName.getValue("TR - Bal").cam)
    }

    @Test fun `Audio-description Versions are marked`() = runBlocking {
        library.sync()

        assertTrue(movie(1047).audioDescription)
        assertEquals(likely("fr"), movie(1047).language)
        assertFalse(movie(1048).audioDescription)
    }

    @Test fun `a subtitled Category moves the Prefix language to the subtitles`() = runBlocking {
        syncListings(
            "FR - Coherence (2014) VOSTFR" to "FR - VOSTFR",
            "TR - Ballerina (2025)" to "TR - ALT YAZI",
        )

        val byName = movies().associate { it.name to it.attributes }
        byName.getValue("FR - Coherence (2014) VOSTFR").let {
            assertNull(it.language)
            assertEquals(Known(setOf("fr"), Provenance.Likely), it.subtitles)
        }
        assertNull(byName.getValue("TR - Ballerina (2025)").language)
    }

    @Test fun `Clutter and Sports replays don't appear in the movie list`() = runBlocking {
        val status = library.sync()

        val names = movies().map { it.name }
        assertEquals(150, library.movieListingCount())
        assertEquals(150, names.size)
        assertEquals(150, (status as SyncStatus.Synced).last.movieListingCount)
        assertFalse(names.any { it.startsWith("MV - ") })
        assertFalse(names.any { it.startsWith("SOC - ") })
    }

    @Test fun `separator rows, finished events, radio and audiobooks are Clutter`() = runBlocking {
        syncListings(
            "####### MAX PPV #######" to "EN - NEW RELEASE",
            "End | Rockets vs. Spurs | all | 24-10-2026 | 00:00 (GMT)" to "EN - NEW RELEASE",
            "EN - Wolfsburg vs Köln ENDED" to "EN - NEW RELEASE",
            "SE - Pippi Långstrump" to "SVENSKA AUDIOBOOKS",
            "EN - Classic Hits FM" to "EN - RADIO",
            "GR - Terzis Paliokeros" to "GR - ΚΑΡΑΟΚΕ",
            "SC - UFC 331: Van vs. Pantoja 2 (2026)" to "NORDIC SPORT",
            "S0C - Western Bulldogs - Sydney Swans 23.05.2024" to "SOCCER RUGBY",
            "DE - How We Ended Us (2023)" to "DE - FILME 1940/2024",
            "EN - Taylor Swift : The Eras Tour - The Final Show  (2025)" to "EN - CONCERTS",
        )

        assertEquals(
            listOf("DE - How We Ended Us (2023)", "EN - Taylor Swift : The Eras Tour - The Final Show  (2025)"),
            movies().map { it.name },
        )
    }

    @Test fun `every Prefix in the fixture is in the table`() = runBlocking {
        val status = library.sync()

        assertEquals(emptyList<UnknownPrefix>(), (status as SyncStatus.Synced).last.unknownPrefixes)
    }

    @Test fun `Prefixes the table doesn't know are reported after Sync, not guessed`() = runBlocking {
        val status = syncListings(
            "BR - Cold Pursuit (2019)" to "BR - MOVIES",
            "BR - The App (2019)" to "BR - MOVIES",
            "XQ-ZZ - Something (2020)" to "EN - NEW RELEASE",
            "Muriel's Wedding (1994)" to "EN - 2020 & OLD",
            "Spider-Man: No Way Home (2021)" to "EN - 2020 & OLD",
            "007 - Octopussy (1983)" to "JAMES BOND 007",
        )

        assertEquals(
            listOf(UnknownPrefix("BR", 2, "BR - Cold Pursuit (2019)"), UnknownPrefix("XQ-ZZ", 1, "XQ-ZZ - Something (2020)")),
            (status as SyncStatus.Synced).last.unknownPrefixes,
        )
        val cold = movies().single { it.name == "BR - Cold Pursuit (2019)" }.attributes
        assertEquals(VersionAttributes(), cold)
    }

    @Test fun `the unknown-Prefix report survives a restart`() = runBlocking {
        syncListings("BR - Cold Pursuit (2019)" to "BR - MOVIES")
        library.close()
        libraries.clear()
        provider.down = true

        val reopened = openLibrary()

        assertEquals(listOf(UnknownPrefix("BR", 1, "BR - Cold Pursuit (2019)")), reopened.syncStatus.value.last?.unknownPrefixes)
    }

    @Test fun `a Listing's language is its description's, worked out once per Prefix and Category`() {
        val table = PrefixTable.bundled()
        val listings = listOf(
            "EN - The Godfather (1972)" to "EN | MOVIES", "FR - Le Parrain (1972)" to "FR - FILMS VOSTFR", "QC - Le Parrain" to "QC - FILMS",
            "IT - Il Padrino" to "ITALIAN SUB ENG", "The Godfather" to "VOSTFR", "The Godfather" to "MOVIES", "4K-EN - Heat" to "4K MOVIES",
            "XX - Unknown Prefix" to "MOVIES", "EN - Heat" to "FR - FILMS VOSTFR", "KU - Film" to "KU - KURDISH MOVIES SUB",
        )
        // Twice: the second time from what the first worked out.
        repeat(2) {
            for ((name, category) in listings) {
                assertEquals("$name in $category", table.describe(name, category).attributes.language?.value, table.language(name, category))
            }
        }
    }
}
