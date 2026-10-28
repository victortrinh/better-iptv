package dev.betteriptv.core

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Header reads on a Show's details page: Confirming each Episode's Versions from their files. */
class EpisodeHeaderReadTest {
    @get:Rule val folder = TemporaryFolder()
    private val provider = FakeProvider()
    private val cloud = FakeCloud()
    private lateinit var library: Library

    @Before fun logIn() = runBlocking {
        library = Library.open(folder.root.resolve("catalog.db").path, InMemoryCredentials(), cloud.url)
        library.logIn(provider.login)
        Unit
    }

    @After fun tearDown() {
        library.close()
        provider.close()
        cloud.close()
    }

    private val show = TitleId.Tmdb(TitleKind.Show, 1399)
    private val fullHdTv = DeviceProfile(maxHeight = 1080, hdr = emptySet())

    /**
     * Syncs one Show with these show Listings, as (name, its Episodes as (id, the first audio Track's
     * language as the Provider's details name it)), numbered from 1, each Episode in season 1 in order.
     */
    private suspend fun syncShow(vararg listings: Pair<String, List<Pair<Long, String>>>) {
        provider.showCategoriesOverride = """[{"category_id":"1","category_name":"SERIES"}]"""
        provider.showListOverride = listings.withIndex().joinToString(",", "[", "]") { (i, listing) ->
            """{"series_id":${i + 1},"name":"${listing.first}","tmdb":"1399","category_id":"1","last_modified":"${1_700_000_000 - i}"}"""
        }
        listings.forEachIndexed { i, (name, episodes) ->
            provider.showDetailsOverride[i + 1L] = """{"seasons":[],"info":{},"episodes":[""" +
                episodes.withIndex().joinToString(",") { (number, episode) ->
                    """{"id":"${episode.first}","episode_num":${number + 1},"season":1,"title":"$name - S01E0${number + 1}","container_extension":"mkv",""" +
                        """"added":"1700000000","info":{"video":{"width":1920,"height":1080},"audio":{"tags":{"language":"${episode.second}"}}}}"""
                } + "]}"
        }
        library.sync()
    }

    /** Opens the Show's page as the app does: its Episodes, then the Header reads. */
    private suspend fun openShow(contentLanguages: List<String>, on: Library = library): Int {
        on.episodes(show, contentLanguages)
        var changes = 0
        on.readHeaders(show, contentLanguages) { changes++ }
        return changes
    }

    private suspend fun episodeVersion(number: Int, listingId: Long, contentLanguages: List<String>, on: Library = library): Version {
        val episode = on.episodes(show, contentLanguages)!!.seasons.single().episodes.single { it.number == number }
        return (episode.languages + episode.otherLanguages).flatMap { group -> group.versions.flatMap { listOf(it) + it.backups } }.single { it.listingId == listingId }
    }

    private fun readIds() = provider.ranges.map { it.substringBefore(':').toLong() }

    private val englishOnly = matroskaFile(listOf(FileTrack("audio", "eng", "A_AC3")))
    private val englishThenFrench = matroskaFile(listOf(FileTrack("audio", "eng", "A_EAC3", channels = 6), FileTrack("audio", "fre", "A_AC3"), FileTrack("subtitle", "fre", "S_TEXT/UTF8")))

    @Test fun `each Episode's best Version per language is read from the Provider's Shows, disagreeing first, the Viewer's languages before the rest`() = runBlocking {
        syncShow(
            "EN - Game of Thrones" to listOf(201L to "eng", 202L to "eng"),
            "FR - Game of Thrones" to listOf(101L to "eng", 102L to "eng"),
            "DE - Game of Thrones" to listOf(301L to "ger", 302L to "eng"),
        )
        listOf(101L, 102L, 201L, 202L, 301L, 302L).forEach { provider.episodeFiles[it] = englishThenFrench }

        openShow(listOf("fr", "en"))

        // The French Versions report English first; German is no language of the Viewer's.
        assertEquals(listOf(101L, 102L, 201L, 202L, 302L, 301L), readIds())
        assertEquals(6, provider.ranges.size)
        assertEquals(0, provider.refusedStreams)
    }

    @Test fun `an FR Episode with English first and French after it is Confirmed French, one with only English moves to English`() = runBlocking {
        syncShow("FR - Game of Thrones" to listOf(101L to "eng", 102L to "eng"))
        provider.episodeFiles[101] = englishThenFrench
        provider.episodeFiles[102] = englishOnly
        // Before the reads, the Prefix's French stands, Likely.
        assertEquals(Known("fr", Provenance.Likely), episodeVersion(1, 101, listOf("fr")).attributes.language)

        assertTrue(openShow(listOf("fr")) > 0)

        val first = episodeVersion(1, 101, listOf("fr")).attributes
        assertEquals(Known("fr", Provenance.Confirmed), first.language)
        assertEquals(Known(setOf("fr"), Provenance.Confirmed), first.subtitles)
        assertTrue(first.dolbyAudio)
        assertEquals(Known("en", Provenance.Confirmed), episodeVersion(2, 102, listOf("fr")).attributes.language)
        // What's read decides what Play starts, too.
        val plan = library.episodePlayPlan(show, EpisodeNumber(1, 2), listOf("fr"), fullHdTv)!!
        assertEquals(Known("en", Provenance.Confirmed), plan.version.attributes.language)
    }

    @Test fun `an Episode is never read twice, not even by another device on the same Provider`() = runBlocking {
        syncShow("FR - Game of Thrones" to listOf(101L to "eng"))
        provider.episodeFiles[101] = englishOnly
        openShow(listOf("fr"))
        openShow(listOf("fr"))
        assertEquals(1, provider.ranges.size)

        Library.open(folder.root.resolve("other.db").path, InMemoryCredentials(), cloud.url).use { other ->
            other.logIn(provider.login)
            other.sync()
            openShow(listOf("fr"), on = other)
            assertEquals(Known("en", Provenance.Confirmed), episodeVersion(1, 101, listOf("fr"), on = other).attributes.language)
        }
        assertEquals(1, provider.ranges.size)
    }

    @Test fun `nothing is read before the Show's Episodes are`() = runBlocking {
        syncShow("FR - Game of Thrones" to listOf(101L to "eng"))
        provider.episodeFiles[101] = englishOnly

        library.readHeaders(show, listOf("fr")) {}

        assertEquals(emptyList<String>(), provider.ranges)
    }

    @Test fun `Tracks seen while an Episode plays Confirm its Version`() = runBlocking {
        syncShow("FR - Game of Thrones" to listOf(101L to "eng"))
        library.episodes(show, listOf("fr"))
        val plan = library.episodePlayPlan(show, EpisodeNumber(1, 1), listOf("fr"), fullHdTv)!!
        val seen = FileContents(listOf(Track("eng"), Track("fre")), listOf(Track("fre")))

        library.playback(plan) { SeeingEngine(it, seen) }.play()

        assertEquals(Known("fr", Provenance.Confirmed), episodeVersion(1, 101, listOf("fr")).attributes.language)
        // The page doesn't read it again, and shares it.
        openShow(listOf("fr"))
        assertEquals(emptyList<String>(), provider.ranges)
        assertEquals(1, cloud.fileReads.size)
    }

    /** An engine that plays a file to its end at once, having seen [seen] in it. */
    private class SeeingEngine(override val engine: Engine, override val seen: FileContents) : PlayerEngine {
        override val position = 0L

        override suspend fun play(location: String, from: Long, events: (PlayerEvent) -> Unit) = events(PlayerEvent.Started)

        override suspend fun release() = Unit
    }
}
