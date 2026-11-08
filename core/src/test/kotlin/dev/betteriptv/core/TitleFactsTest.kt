package dev.betteriptv.core

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Title facts from Wikidata through the cloud (#476, ADR 0014): after a Sync the device asks the cloud,
 * in batches, for every Title number it has no answer for, keeps the answers with its catalog and lands
 * them between screens like a Pending catalog (ADR 0011). The Untagged Version's Likely and Prefer
 * original audio read the original language from them alone; a Title Wikidata
 * doesn't know says nothing until a Header read Confirms it, and without a cloud nothing is known.
 */
class TitleFactsTest {
    @get:Rule val folder = TemporaryFolder()
    private val provider = FakeProvider()
    private val cloud = FakeCloud().servingSurveyedTable(provider)
    private val credentials = InMemoryCredentials()
    private val libraries = mutableListOf<Library>()

    @After fun tearDown() {
        libraries.forEach { it.close() }
        provider.close()
        cloud.close()
    }

    /** A Library on catalog.db over the surveyed Provider's table, logged in to the fake Provider, on the cloud at [cloudAddress]. */
    private suspend fun library(cloudAddress: String? = cloud.url): Library {
        val library = openSurveyedLibrary(folder.root.resolve("catalog.db").path, credentials, cloudAddress).also { libraries += it }
        if (!library.isLoggedIn) library.logIn(provider.login)
        provider.showCategoriesOverride = "[]"
        provider.showListOverride = "[]"
        return library
    }

    /** A movie Listing: its name, its Category and the Title number the Provider files it under (null for an Unmatched Title). */
    private class Listing(val name: String, val category: String, val tmdb: Long?)

    /** The fake Provider's movies are these Listings, newest first, numbered from 1. */
    private fun serve(listings: List<Listing>) {
        val categories = listings.map { it.category }.distinct()
        provider.movieCategoriesOverride = categories.withIndex()
            .joinToString(",", "[", "]") { (i, name) -> """{"category_id":"${i + 1}","category_name":"$name"}""" }
        provider.movieListOverride = listings.withIndex().joinToString(",", "[", "]") { (i, listing) ->
            """{"stream_id":${i + 1},"name":"${listing.name}","tmdb":"${listing.tmdb ?: ""}","category_id":"${categories.indexOf(listing.category) + 1}",""" +
                """"added":"${1_700_000_000 - i}","container_extension":"mkv"}"""
        }
    }

    private val squidGame = TitleId.Numbered(TitleKind.Movie, 1)
    private val english = listOf("en")
    private val fullHdTv = DeviceProfile(maxHeight = 1080, hdr = emptySet())

    /** Squid Game as the Provider files it: a Netflix copy (Untagged: `NF` names a Source, no language) and an English dub. Listing 1 is the `NF` copy. */
    private val squidGameSpread = listOf(Listing("NF - Squid Game (2021)", "NETFLIX", 1), Listing("EN - Squid Game (2021)", "EN - MOVIES", 1))

    private fun movie(id: Long) = TitleId.Numbered(TitleKind.Movie, id)

    private fun tracks(vararg languages: String?) = languages.map { Track(it) }

    private fun TitleDetails.group(language: String?) = (languages + otherLanguages).singleOrNull { it.language == language }

    private fun TitleDetails.version(name: String) = (languages + otherLanguages).flatMap { group -> group.versions.flatMap { listOf(it) + it.backups } }.single { it.name == name }

    private fun LanguageGroup.names() = versions.flatMap { listOf(it) + it.backups }.map { it.name }.toSet()

    // What the device asks

    @Test fun `after a Sync the device asks for every Title number in batches of 300, never for an Unmatched Title`() = runBlocking {
        val titles = (1L..301L).map { Listing("EN - Film $it (2020)", "EN - MOVIES", it) }
        serve(titles + Listing("EN - No Number (2020)", "EN - MOVIES", null))
        titles.forEach { cloud.titleFacts(movie(it.tmdb!!), originalLanguage = "en") }
        val library = library()

        library.sync()

        val asked = cloud.titleFactsAsked
        assertEquals(listOf(300, 1), asked.map { it.size })
        assertEquals((1L..301L).map(::movie).toSet(), asked.flatten().toSet())
        assertEquals(1, library.title(movie(301), english, "en")!!.languages.size)
    }

    @Test fun `the next Sync asks again only for the Titles the cloud couldn't answer for, and nothing once every Title has its answer`() = runBlocking {
        serve(listOf(Listing("NF - Known (2021)", "NETFLIX", 1), Listing("NF - Unknown to Wikidata (2021)", "NETFLIX", 2), Listing("NF - Unavailable (2021)", "NETFLIX", 3)))
        cloud.titleFacts(movie(1), originalLanguage = "ko", imdbId = "tt10919420", enwiki = "Squid Game", frwiki = "Squid Game (série télévisée)")
        cloud.noTitleFacts(movie(2))
        val library = library()
        library.sync()
        assertEquals(listOf(listOf(movie(1), movie(2), movie(3))), cloud.titleFactsAsked)

        library.sync()
        // Only the unavailable one: the found Title and the one Wikidata has no item for are kept for good.
        assertEquals(listOf(movie(3)), cloud.titleFactsAsked.last())

        cloud.titleFacts(movie(3), originalLanguage = "ja")
        library.sync()
        assertEquals(listOf(movie(3)), cloud.titleFactsAsked.last())
        val requests = cloud.titleFactsAsked.size
        library.sync()
        assertEquals(requests, cloud.titleFactsAsked.size)
        assertEquals("ja", library.title(movie(3), english, "en")!!.originalLanguage)
    }

    @Test fun `the facts are kept with the catalog across a reopen, so they aren't asked for again`() = runBlocking {
        serve(squidGameSpread)
        cloud.titleFacts(squidGame, originalLanguage = "ko")
        library().sync()
        libraries.removeLast().close()

        val reopened = library()
        reopened.sync()

        assertEquals(1, cloud.titleFactsAsked.size)
        assertEquals("ko", reopened.title(squidGame, english, "en")!!.originalLanguage)
    }

    @Test fun `a batch the cloud fails is asked for again after the next Sync, and the catalog shows meanwhile`() = runBlocking {
        serve(squidGameSpread)
        cloud.titleFacts(squidGame, originalLanguage = "ko")
        cloud.failingTitleFacts = 1
        val library = library()

        library.sync()
        assertEquals(1, cloud.titleFactsAsked.size)
        assertNull(library.title(squidGame, english, "en")!!.originalLanguage)

        library.sync()
        assertEquals(2, cloud.titleFactsAsked.size)
        assertEquals("ko", library.title(squidGame, english, "en")!!.originalLanguage)
    }

    // Landing between screens (ADR 0011)

    @Test fun `facts fetched after a held Sync land between screens, never under focus`() = runBlocking {
        serve(squidGameSpread)
        val library = library()
        // The first Sync: the cloud couldn't answer for Squid Game yet.
        library.sync()
        assertEquals(setOf("NF - Squid Game (2021)"), library.title(squidGame, english, "en")!!.group(null)!!.names())
        cloud.titleFacts(squidGame, originalLanguage = "ko")

        assertTrue(library.sync(hold = true) is SyncStatus.Ready)
        assertTrue(library.applyPendingCatalog())
        // The catalog landed and the cloud answered, but the answer waits: the Versions screen in focus doesn't move.
        assertEquals(2, cloud.titleFactsAsked.size)
        assertEquals(setOf("NF - Squid Game (2021)"), library.title(squidGame, english, "en")!!.group(null)!!.names())
        assertNull(library.playPlan(squidGame, english, fullHdTv)!!.originalLanguage)

        val changes = library.catalogChanges.value
        assertTrue(library.applyPendingTitleFacts())

        assertTrue(library.catalogChanges.value > changes)
        val details = library.title(squidGame, english, "en")!!
        assertEquals(setOf("NF - Squid Game (2021)"), details.group("ko")!!.names())
        assertEquals("ko", details.originalLanguage)
        assertFalse(library.applyPendingTitleFacts())
    }

    @Test fun `facts waiting to land survive a reopen and land on the next screen entry`() = runBlocking {
        serve(squidGameSpread)
        val library = library()
        library.sync()
        cloud.titleFacts(squidGame, originalLanguage = "ko")
        library.sync(hold = true)
        library.applyPendingCatalog()
        libraries.removeLast().close()

        val reopened = library()
        assertNull(reopened.title(squidGame, english, "en")!!.originalLanguage)
        assertTrue(reopened.applyPendingTitleFacts())
        assertEquals("ko", reopened.title(squidGame, english, "en")!!.originalLanguage)
        // Landed, so not asked for again.
        reopened.sync()
        assertEquals(2, cloud.titleFactsAsked.size)
    }

    // What the facts decide

    @Test fun `a NF Untagged Version of a Title Wikidata says is Korean shows Korean outlined, and Prefer original audio picks it with subtitles on`() = runBlocking {
        serve(squidGameSpread)
        cloud.titleFacts(squidGame, originalLanguage = "ko", imdbId = "tt10919420", enwiki = "Squid Game")
        val library = library()
        library.sync()

        val details = library.title(squidGame, english, "en")!!
        assertEquals(Known("ko", Provenance.Likely), details.version("NF - Squid Game (2021)").attributes.language)
        assertEquals(setOf("NF - Squid Game (2021)"), details.group("ko")!!.names())
        assertEquals("ko", details.originalLanguage)

        // No Korean among the Content languages: the Netflix copy plays, its Korean audio with English subtitles downloaded when the file has none.
        val plan = library.playPlan(squidGame, english, fullHdTv)!!
        assertEquals("NF - Squid Game (2021)", plan.version.name)
        assertEquals("ko", plan.originalLanguage)
        assertEquals(TrackChoice(audio = 0, subtitle = null, downloaded = "en"), plan.chooseTracks(tracks("kor"), tracks(), fullHdTv))
        assertEquals(TrackChoice(audio = 0, subtitle = 0), plan.chooseTracks(tracks("kor"), tracks("eng"), fullHdTv))
        // With the setting off, the English dub plays as before.
        assertEquals("EN - Squid Game (2021)", library.playPlan(squidGame, english, fullHdTv, preferOriginalAudio = false)!!.version.name)
        // A Viewer who watches Korean needs no setting: the copy sits in their languages.
        assertEquals(setOf("NF - Squid Game (2021)"), library.title(squidGame, listOf("en", "ko"), "en")!!.group("ko")!!.names())
    }

    @Test fun `a Title Wikidata doesn't know says nothing about its language until a Header read Confirms it`() = runBlocking {
        val shadowsEdge = listOf(Listing("NF - The Shadow's Edge (2025)", "NETFLIX", 1), Listing("EN - The Shadow's Edge (2025)", "EN - MOVIES", 1))
        serve(shadowsEdge)
        cloud.noTitleFacts(movie(1))
        provider.movieFiles[1] = matroskaFile(listOf(FileTrack("audio", "chi", "A_EAC3", channels = 6)) + listOf("dut", "eng").map { FileTrack("subtitle", it, "S_TEXT/UTF8") })
        provider.movieFiles[2] = matroskaFile(listOf(FileTrack("audio", "eng", "A_AAC")))
        val library = library()
        library.sync()

        val before = library.title(movie(1), english, "en")!!
        // The Versions screen says "Language not named" for it.
        assertNull(before.version("NF - The Shadow's Edge (2025)").attributes.language)
        assertEquals(setOf("NF - The Shadow's Edge (2025)"), before.group(null)!!.names())
        assertNull(before.originalLanguage)
        assertEquals("EN - The Shadow's Edge (2025)", library.playPlan(movie(1), english, fullHdTv)!!.version.name)

        library.readHeaders(movie(1), english) {}

        val after = library.title(movie(1), english, "en")!!
        assertEquals(Known("zh", Provenance.Confirmed), after.version("NF - The Shadow's Edge (2025)").attributes.language)
        assertEquals(setOf("NF - The Shadow's Edge (2025)"), after.group("zh")!!.names())
        // Wikidata named no language, so Prefer original audio has nothing to act on: the English copy still plays.
        assertNull(after.originalLanguage)
        assertEquals("EN - The Shadow's Edge (2025)", library.playPlan(movie(1), english, fullHdTv)!!.version.name)
    }

    @Test fun `a Title whose item names no language is as unknown as one with no item`() = runBlocking {
        serve(squidGameSpread)
        cloud.titleFacts(squidGame, imdbId = "tt10919420")
        val library = library()
        library.sync()

        val details = library.title(squidGame, english, "en")!!
        assertNull(details.originalLanguage)
        assertEquals(setOf("NF - Squid Game (2021)"), details.group(null)!!.names())
        assertEquals("EN - Squid Game (2021)", library.playPlan(squidGame, english, fullHdTv)!!.version.name)
    }

    @Test fun `a Title released in a Content language plays exactly as before`() = runBlocking {
        serve(squidGameSpread)
        cloud.titleFacts(squidGame, originalLanguage = "en")
        val library = library()
        library.sync()

        val details = library.title(squidGame, english, "en")!!
        assertEquals(setOf("NF - Squid Game (2021)", "EN - Squid Game (2021)"), details.group("en")!!.names())
        assertNull(details.originalLanguage)
        val plan = library.playPlan(squidGame, english, fullHdTv)!!
        assertEquals(plan, library.playPlan(squidGame, english, fullHdTv, preferOriginalAudio = false)!!.copy(preferOriginalAudio = true))
    }

    @Test fun `with no cloud address nothing is asked, the language stays unknown and everything else works`() = runBlocking {
        serve(squidGameSpread)
        cloud.titleFacts(squidGame, originalLanguage = "ko")
        val library = library(cloudAddress = null)

        assertTrue(library.sync() is SyncStatus.Synced)

        assertTrue(cloud.received.isEmpty())
        val details = library.title(squidGame, english, "en")!!
        assertEquals(setOf("NF - Squid Game (2021)"), details.group(null)!!.names())
        assertEquals(setOf("EN - Squid Game (2021)"), details.group("en")!!.names())
        assertNull(details.originalLanguage)
        val plan = library.playPlan(squidGame, english, fullHdTv)!!
        assertEquals("EN - Squid Game (2021)", plan.version.name)
        assertNull(plan.originalLanguage)
        assertFalse(plan.isOriginal(Track("kor")))
        assertFalse(library.applyPendingTitleFacts())
    }
}
