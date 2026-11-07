package dev.betteriptv.core

import com.squareup.moshi.Moshi
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A Sync finds candidate Tier tags itself (#439): a word before the colon at the start of a live
 * Listing's name whose removal makes it the same Channel as another live Listing. They go to the
 * cloud with the unknown-Prefixes report, for the weekly issue, and are never applied: a wrong Tier
 * tag merges two Channels, so one becomes a Tier tag only when the curator writes its hand line.
 *
 * The Base table already knows `VIP` and `GOLD`, so a new panel's package words here are `PLUS` and `MAX`.
 */
class CandidateTierTagsTest {
    @get:Rule val folder = TemporaryFolder()
    private val provider = FakeProvider()
    private val cloud = FakeCloud()
    private val libraries = mutableListOf<Library>()

    @After fun tearDown() {
        libraries.forEach { it.close() }
        cloud.close()
        provider.close()
    }

    private suspend fun library(): Library {
        val library = Library.open(folder.root.resolve("catalog.db").path, InMemoryCredentials(), cloud.url, { null }).also { libraries += it }
        library.logIn(provider.login)
        return library
    }

    private val panel = arrayOf(
        "PLUS: TNT SPORT 1 HD" to "UK| SPORTS",
        "MAX: TNT SPORT 1 SD" to "UK| SPORTS",
        "PLUS: TNT SPORT 2 HD" to "UK| SPORTS",
        "MAX: TNT SPORT 2" to "UK| SPORTS",
        "PLUS: SKY CINEMA" to "UK| MOVIES",
        "SKY CINEMA HD" to "UK| MOVIES",
        // Already Tier tags in the Base table: nothing to report.
        "VIP: EUROSPORT 1" to "UK| SPORTS",
        "GOLD: EUROSPORT 1 HD" to "UK| SPORTS",
        // The name's own words before a colon, merging nothing.
        "NHL: Jets vs Oilers" to "US| NHL",
        "Arena: Rangers vs Bruins" to "US| NHL",
        // An Event channel's teams are on several networks' channels: never a Tier tag.
        "NBA: Lakers vs Celtics (2026-11-08 19:00:00)" to "US| NBA",
        "ESPN: Lakers vs Celtics (2026-11-08 19:00:00)" to "US| NBA",
    )

    private fun reports() = cloud.received.filter { it.path == "/v1/unknown-prefixes" }
        .map { Moshi.Builder().build().adapter(Any::class.java).fromJson(it.body) as Map<*, *> }

    @Test fun `a Sync reports the words whose removal merges Channels, with how many Listings each merges`() = runBlocking {
        provider.serveChannels(*panel)
        val library = library()

        val synced = library.sync() as SyncStatus.Synced

        assertEquals(
            listOf(CandidateTierTag("PLUS", 3, "PLUS: TNT SPORT 1 HD"), CandidateTierTag("MAX", 2, "MAX: TNT SPORT 1 SD")),
            synced.last.candidateTierTags,
        )
        val report = reports().single()
        assertEquals(providerKey(provider.login), report["provider"])
        assertEquals(
            listOf(
                mapOf("word" to "PLUS", "listingCount" to 3.0, "example" to "PLUS: TNT SPORT 1 HD"),
                mapOf("word" to "MAX", "listingCount" to 2.0, "example" to "MAX: TNT SPORT 1 SD"),
            ),
            report["tierTags"],
        )
    }

    @Test fun `candidate Tier tags change no Channel until a hand line names them`() = runBlocking {
        provider.serveChannels(*panel.take(4).toTypedArray())
        val library = library()
        library.sync()

        val channels = library.categoryChannels(library.browsedCategories("GB").single()).map { it.key }
        assertEquals(listOf("PLUS: TNT SPORT 1", "MAX: TNT SPORT 1", "PLUS: TNT SPORT 2", "MAX: TNT SPORT 2"), channels)

        cloud.providerTables[providerKey(provider.login)] = "[tier tags]\nPLUS\nMAX\n"
        val synced = library.sync() as SyncStatus.Synced

        assertEquals(listOf("TNT SPORT 1", "TNT SPORT 2"), library.categoryChannels(library.browsedCategories("GB").single()).map { it.key })
        assertTrue(synced.last.candidateTierTags.isEmpty())
    }

    @Test fun `the candidates survive a restart, as the unknown Prefixes do`() = runBlocking {
        provider.serveChannels(*panel.take(4).toTypedArray())
        library().sync()
        libraries.removeLast().close()

        val reopened = library()

        assertEquals(listOf(CandidateTierTag("MAX", 2, "MAX: TNT SPORT 1 SD"), CandidateTierTag("PLUS", 2, "PLUS: TNT SPORT 1 HD")), reopened.syncStatus.value.last?.candidateTierTags)
    }
}
