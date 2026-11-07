package dev.betteriptv.core

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.ZoneId
import java.time.ZoneOffset

/** The Prefix table as the Base table with a Provider's Provider table merged into it (ADR 0014). */
class ProviderTableTest {
    @get:Rule val folder = TemporaryFolder()
    private val provider = FakeProvider()
    private val libraries = mutableListOf<Library>()

    @After fun tearDown() {
        libraries.forEach { it.close() }
        provider.close()
    }

    /** A Library logged in to the fake Provider, whose Provider table is [providerTable] (none when null). */
    private suspend fun library(providerTable: String?): Library {
        val library = Library.open(folder.root.resolve("catalog-${libraries.size}.db").path, InMemoryCredentials(), null, { providerTable }).also { libraries += it }
        library.logIn(provider.login)
        return library
    }

    private fun resource(path: String) = javaClass.getResourceAsStream(path)!!.bufferedReader().use { it.readText() }

    /** Syncs these Listings, each `name` filed under the Category `category`. */
    private suspend fun Library.syncListings(vararg listings: Pair<String, String>): SyncStatus.Synced {
        val categories = listings.map { it.second }.distinct()
        provider.movieCategoriesOverride = categories.withIndex()
            .joinToString(",", "[", "]") { (i, name) -> """{"category_id":"${i + 1}","category_name":${json(name)}}""" }
        provider.movieListOverride = listings.withIndex().joinToString(",", "[", "]") { (i, listing) ->
            """{"stream_id":${i + 1},"name":${json(listing.first)},"category_id":"${categories.indexOf(listing.second) + 1}","added":"${1_700_000_000 - i}"}"""
        }
        return sync() as SyncStatus.Synced
    }

    private fun json(text: String) = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun Library.movie(id: Long) = movieListings(0, 10_000).single { it.id == id }.attributes

    @Test fun `with the hand file, the surveyed Provider's Listings are described as before the split`() = runBlocking {
        val library = library(surveyedProviderTable)
        library.sync()

        assertEquals(resource("/snapshots/library-descriptions.txt"), libraryDescriptions(library))
    }

    @Test fun `with the hand file, every Prefix, Category hint and quality word the table had reads as before the split`() {
        // Language, quality, Source, Clutter, Sports replay and Channel key of the fixture's Listings
        // and of one Listing per line the table had, against what the table made of them before.
        assertEquals(resource("/snapshots/fixture-descriptions.txt"), FixtureDescriptions.render(surveyedPrefixTable()))
        assertEquals(ZoneId.of("America/Toronto"), surveyedPrefixTable().eventTimeZone)
    }

    @Test fun `the bundled hand file is found by the Provider's host hash`() {
        assertNull(PrefixTable.bundledProviderTable(providerKey(provider.login)))
        assertEquals(surveyedProviderTable, PrefixTable.bundledProviderTable(SURVEYED_PROVIDER))
        assertNull(PrefixTable.bundledProviderTable("../prefix-table"))
    }

    @Test fun `over the Base table alone, a Prefix that moved to the hand file is unknown and names no language`() = runBlocking {
        val library = library(null)
        val synced = library.syncListings("CD - Der Pate (1972)" to "GERMANY SERIE", "DE - Der Pate (1972)" to "DE - FILME")

        assertTrue(UnknownPrefix("CD", 1, "CD - Der Pate (1972)") in synced.last.unknownPrefixes)
        assertNull(library.movie(1).language)
        assertEquals("de", library.movie(2).language?.value)
        assertEquals(ZoneOffset.UTC, PrefixTable.base().eventTimeZone.normalized())
    }

    @Test fun `the Base table holds none of the surveyed Provider's typos and own codes`() {
        val base = PrefixTable.base()
        val moved = listOf(
            "CD", "EQC", "IIR", "R", "AL", "K-FR", "4L-PL", "4M-AMZ", "S0C", "INKD", "BI", "N", "AC",
            "IN-EN", "IN-KD", "IN-TL", "SO-IN", "AF-EN", "EN-TOP", "DE-JO", "TOP", "4K-TOP", "SC", "AS", "AF", "EN-DO", "DE-DO", "SC-DO", "NF-DO",
        )
        for (prefix in moved) assertEquals(prefix, prefix, base.describe("$prefix - Heat (1995)", "MOVIES").unknownPrefix)
        // What most Providers share stays.
        for (prefix in listOf("EN", "DE", "QC", "IN", "4K-FR", "FR-4K", "NF", "4K-NF", "D+", "AMZ", "A+", "SOC")) {
            assertNull(prefix, base.describe("$prefix - Heat (1995)", "MOVIES").unknownPrefix)
        }
    }

    @Test fun `a Provider line overrides the Base table's for the same key`() = runBlocking {
        val library = library("[prefixes]\nEN | language=fr\nXB | language=de\n\n[category hints]\nVOSTFR | quality=4K\n")
        library.syncListings("EN - Heat (1995)" to "MOVIES", "XB - Heat (1995)" to "MOVIES", "FR - Heat (1995)" to "FR - FILMS VOSTFR")

        assertEquals("fr", library.movie(1).language?.value)
        assertEquals("de", library.movie(2).language?.value)
        // VOSTFR no longer moves the language to the subtitles; it now says 4K.
        assertEquals("fr", library.movie(3).language?.value)
        assertEquals(Quality.UHD, library.movie(3).quality?.value)
    }

    @Test fun `an empty Provider rule switches a Base meaning off and keeps the Prefix known`() = runBlocking {
        val library = library("[prefixes]\nA+ |\nNF\n")
        val synced = library.syncListings("A+ - Heat (1995)" to "MOVIES", "NF - Heat (1995)" to "MOVIES", "D+ - Heat (1995)" to "MOVIES")

        assertNull(library.movie(1).source)
        assertNull(library.movie(2).source)
        assertEquals("Disney+", library.movie(3).source?.value)
        assertEquals(emptyList<String>(), synced.last.unknownPrefixes.map { it.prefix }.filter { it in setOf("A+", "NF", "D+") })
    }

    @Test fun `a Provider's event time zone replaces the Base table's`() {
        assertEquals(ZoneId.of("Asia/Tokyo"), PrefixTable.withProviderTable("[event time zone]\nAsia/Tokyo\n").eventTimeZone)
        assertEquals(ZoneOffset.UTC, PrefixTable.withProviderTable("[prefixes]\nXB | language=de\n").eventTimeZone.normalized())
    }

    @Test fun `Clutter names from both tables apply`() = runBlocking {
        val library = library("[clutter names]\n^PROMO\\b\n")
        library.syncListings("PROMO Coming soon" to "MOVIES", "####### MAX PPV #######" to "MOVIES", "EN - Heat (1995)" to "MOVIES")

        assertEquals(listOf("EN - Heat (1995)"), library.movieListings(0, 10).map { it.name })
    }

    @Test fun `tier tags, quality words and country aliases from both tables apply`() {
        val table = PrefixTable.withProviderTable(
            "[tier tags]\nELITE\n\n[channel quality words]\nULTRA | quality=4K\nHD | quality=SD\n\n[country aliases]\nMENA | Middle East\nAR |\n",
        )

        val elite = table.channelName("ELITE: UK: SKY SPORTS 1 ULTRA")
        assertEquals("UK: SKY SPORTS 1", elite.key)
        assertEquals(Quality.UHD, elite.quality)
        val vip = table.channelName("VIP: UK: SKY SPORTS 1 FHD")
        assertEquals("UK: SKY SPORTS 1", vip.key)
        assertEquals(Quality.HD, vip.quality)
        // The Provider's HD replaces Base's.
        assertEquals(Quality.SD, table.channelName("UK: SKY SPORTS 1 HD").quality)
        assertEquals("Middle East", table.countryAlias("MENA"))
        assertEquals("Ex-Yugoslavia", table.countryAlias("EX YU"))
        // An empty alias drops Base's.
        assertNull(table.countryAlias("AR"))
    }

    @Test fun `genre words from both tables apply, and an empty genre drops a Base word`() {
        val table = PrefixTable.withProviderTable("[genre categories]\nBARN | Kids\nFILMS |\n")

        assertEquals(table.genreRank("KIDS"), table.genreRank("BARN"))
        assertNull(table.genreRank("FILMS"))
        assertEquals(table.genreRank("MOVIES"), table.genreRank("CINEMA"))
        assertTrue(table.genreRank("NEWS")!! < table.genreRank("KIDS")!!)
    }

    @Test fun `a key listed twice in one table is an error, once in each is not`() {
        assertThrows(IllegalArgumentException::class.java) { PrefixTable.withProviderTable("[prefixes]\nXB | language=de\nXB | language=fr\n") }
        assertFalse(PrefixTable.withProviderTable("[prefixes]\nEN | language=fr\n").describe("EN - Heat", "MOVIES").attributes.language?.value == "en")
    }
}
