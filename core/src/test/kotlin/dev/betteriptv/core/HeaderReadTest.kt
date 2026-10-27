package dev.betteriptv.core

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.time.TimeSource

/** Header reads on a Movie's details page: Confirming Versions from their files, and giving way to Play. */
class HeaderReadTest {
    @get:Rule val folder = TemporaryFolder()
    private val provider = FakeProvider()
    private val cloud = FakeCloud()
    private val credentials = InMemoryCredentials()
    private lateinit var library: Library

    @Before fun logIn() = runBlocking {
        library = Library.open(folder.root.resolve("catalog.db").path, credentials, cloud.url)
        library.logIn(provider.login)
        Unit
    }

    @After fun tearDown() {
        library.close()
        provider.close()
        cloud.close()
    }

    private val movie = TitleId.Tmdb(TitleKind.Movie, 1)
    private val fullHdTv = DeviceProfile(maxHeight = 1080, hdr = emptySet())

    /** Syncs these Listings of one Movie, as (name, Category, file extension), newest first, numbered from 1. */
    private suspend fun syncVersions(vararg listings: Triple<String, String, String>) {
        val categories = listings.map { it.second }.distinct()
        provider.movieCategoriesOverride = categories.withIndex()
            .joinToString(",", "[", "]") { (i, name) -> """{"category_id":"${i + 1}","category_name":"$name"}""" }
        provider.movieListOverride = listings.withIndex().joinToString(",", "[", "]") { (i, listing) ->
            """{"stream_id":${i + 1},"name":"${listing.first}","tmdb":"1","category_id":"${categories.indexOf(listing.second) + 1}",""" +
                """"added":"${1_700_000_000 - i}","container_extension":"${listing.third}"}"""
        }
        library.sync()
    }

    private suspend fun readHeaders(contentLanguages: List<String> = listOf("en")): Int {
        var changes = 0
        library.readHeaders(movie, contentLanguages) { changes++ }
        return changes
    }

    private suspend fun version(name: String, contentLanguages: List<String> = listOf("en")): Version {
        val details = library.title(movie, contentLanguages, "en")!!
        return (details.languages + details.otherLanguages).flatMap { group -> group.versions.flatMap { listOf(it) + it.backups } }.single { it.name == name }
    }

    private val englishAndFrench = listOf(FileTrack("audio", "eng", "A_EAC3", channels = 6), FileTrack("audio", "fre", "A_AAC"), FileTrack("subtitle", "fre", "S_TEXT/UTF8"))

    @Test fun `opening details Confirms the best Version's Tracks, picture and bitrate from the start of its MKV`() = runBlocking {
        syncVersions("EN - Movie (2025)" to "EN - MOVIES" with "mkv")
        provider.movieFiles[1] = matroskaFile(englishAndFrench, width = 3840, height = 1600, transfer = 16, seconds = 100.0, size = 1_000_000)

        assertTrue(readHeaders() > 0)

        val attributes = version("EN - Movie (2025)").attributes
        assertEquals(Known("en", Provenance.Confirmed), attributes.language)
        assertEquals(Known(setOf("fr"), Provenance.Confirmed), attributes.subtitles)
        assertEquals(Known(Quality.UHD, Provenance.Confirmed), attributes.quality)
        assertEquals(Known(Hdr.HDR10, Provenance.Confirmed), attributes.hdr)
        assertEquals(Known(80_000L, Provenance.Confirmed), attributes.bitrate)
        assertTrue(attributes.dolbyAudio)
        // About the first 256 KB, through one range request.
        assertEquals(listOf("1:bytes=0-262143"), provider.ranges)
        assertEquals(0, provider.streamsOpen)
    }

    @Test fun `an FR Version without a French Track moves to the language it really has`() = runBlocking {
        syncVersions("FR - Le Film (2025)" to "FR - FILMS" with "mkv", "DE - Der Film (2025)" to "DE - FILME" with "mkv")
        provider.movieFiles[1] = matroskaFile(listOf(FileTrack("audio", "eng", "A_AC3")))
        provider.movieFiles[2] = matroskaFile(listOf(FileTrack("audio", "eng", "A_AC3"), FileTrack("audio", "ger", "A_AC3")))

        readHeaders(listOf("fr", "de"))

        assertEquals(Known("en", Provenance.Confirmed), version("FR - Le Film (2025)").attributes.language)
        // A German Track among others Confirms the Prefix's German.
        assertEquals(Known("de", Provenance.Confirmed), version("DE - Der Film (2025)").attributes.language)
    }

    @Test fun `the best unconfirmed Version per language is read, the Viewer's languages first`() = runBlocking {
        syncVersions(
            "4K-EN - Movie (2025)" to "4K MOVIES" with "mkv",
            "EN - Movie (2025)" to "EN - MOVIES" with "mkv",
            "DE - Der Film (2025)" to "DE - FILME" with "mkv",
        )
        (1L..3L).forEach { provider.movieFiles[it] = matroskaFile(englishAndFrench) }

        readHeaders()

        assertEquals(listOf("1:bytes=0-262143", "3:bytes=0-262143"), provider.ranges)
        assertEquals(Provenance.Likely, version("EN - Movie (2025)").attributes.language?.provenance)
    }

    @Test fun `an MP4 with its track index at the end is read at its start and its end`() = runBlocking {
        syncVersions("EN - Movie (2025)" to "EN - MOVIES" with "mp4")
        val file = mp4File(listOf(FileTrack("audio", "eng", "ec-3", channels = 6), FileTrack("subtitle", "spa", "tx3g")), seconds = 100, indexAtEnd = true)
        provider.movieFiles[1] = file

        readHeaders()

        val attributes = version("EN - Movie (2025)").attributes
        assertEquals(Known("en", Provenance.Confirmed), attributes.language)
        assertEquals(Known(setOf("es"), Provenance.Confirmed), attributes.subtitles)
        assertEquals(Known(Quality.FullHD, Provenance.Confirmed), attributes.quality)
        assertEquals(Known(file.size * 8L / 100, Provenance.Confirmed), attributes.bitrate)
        assertEquals(2, provider.ranges.size)
        assertTrue(provider.ranges[1], provider.ranges[1].startsWith("1:bytes=600"))
    }

    @Test fun `an MP4 with its track index at the start is read at its start only`() = runBlocking {
        syncVersions("EN - Movie (2025)" to "EN - MOVIES" with "mp4")
        provider.movieFiles[1] = mp4File(listOf(FileTrack("audio", "eng", "mp4a")), videoCodec = "hvc1", transfer = 16, indexAtEnd = false)

        readHeaders()

        assertEquals(Known(Hdr.HDR10, Provenance.Confirmed), version("EN - Movie (2025)").attributes.hdr)
        assertEquals(1, provider.ranges.size)
    }

    @Test fun `a Version is never read twice, not even by another device on the same Provider`() = runBlocking {
        syncVersions("EN - Movie (2025)" to "EN - MOVIES" with "mkv")
        provider.movieFiles[1] = matroskaFile(englishAndFrench, width = 3840, height = 2160)
        readHeaders()
        readHeaders()
        assertEquals(1, provider.ranges.size)

        // The living room's stick, on the same Provider, gets the read from the cloud.
        Library.open(folder.root.resolve("other.db").path, InMemoryCredentials(), cloud.url).use { other ->
            other.logIn(provider.login)
            other.sync()
            other.readHeaders(movie, listOf("en")) {}
            val details = other.title(movie, listOf("en"), "en")!!
            assertEquals(Known(Quality.UHD, Provenance.Confirmed), details.languages.single().versions.single().attributes.quality)
        }
        assertEquals(1, provider.ranges.size)
        // The cloud keys reads by a hash of the Provider's host, never the host itself.
        assertTrue(cloud.received.none { "127.0.0.1" in it.body })
    }

    @Test fun `pressing Play stops a read in progress at once`() = runBlocking {
        syncVersions("EN - Movie (2025)" to "EN - MOVIES" with "mkv")
        provider.movieFiles[1] = matroskaFile(englishAndFrench)
        provider.slowFiles = true
        val reading = async { readHeaders() }
        withTimeout(5_000) { while (provider.streamsOpen == 0) delay(10) }

        val pressed = TimeSource.Monotonic.markNow()
        val plan = library.playPlan(movie, listOf("en"), fullHdTv)!!
        library.connect(plan.version).use { connection ->
            // The read gave way, and the Provider counted it closed before playback opened the file.
            assertTrue("Play waited ${pressed.elapsedNow()}", pressed.elapsedNow().inWholeMilliseconds < 1_500)
            reading.await()
            provider.play(connection.location).close()
        }
        assertEquals(0, provider.refusedStreams)
        // Nothing was kept of the cut-off read, so the next details page reads it again.
        assertEquals(Provenance.Likely, version("EN - Movie (2025)").attributes.language?.provenance)
    }

    @Test fun `nothing is read while a Version plays`() = runBlocking {
        syncVersions("EN - Movie (2025)" to "EN - MOVIES" with "mkv")
        provider.movieFiles[1] = matroskaFile(englishAndFrench)
        val plan = library.playPlan(movie, listOf("en"), fullHdTv)!!

        library.connect(plan.version).use { readHeaders() }

        assertEquals(emptyList<String>(), provider.ranges)
    }

    @Test fun `the bitrate from the Provider's movie details ranks equal Versions`() = runBlocking {
        syncVersions("EN - Movie (2025)" to "EN - MOVIES" with "avi", "EN - Movie (2025)" to "EN - MOVIES" with "avi")
        provider.movieDetails[2] = """{"info":{"bitrate":9500,"video":{"width":1920}},"movie_data":{"stream_id":2}}"""
        // Without a bitrate, the newest Listing wins.
        assertEquals(1L, library.playPlan(movie, listOf("en"), fullHdTv)!!.version.listingId)

        readHeaders()

        val plan = library.playPlan(movie, listOf("en"), fullHdTv)!!
        assertEquals(2L, plan.version.listingId)
        assertEquals(Known(9_500_000L, Provenance.Confirmed), plan.version.attributes.bitrate)
        assertNull(plan.version.backups.single().attributes.bitrate)
        // An AVI isn't Header read.
        assertEquals(emptyList<String>(), provider.ranges)
    }

    @Test fun `Tracks seen during Playback Confirm the Version`() = runBlocking {
        syncVersions("FR - Le Film (2025)" to "FR - FILMS" with "mkv")
        val plan = library.playPlan(movie, listOf("fr"), fullHdTv)!!
        val seen = FileContents(listOf(Track("fra", "audio/eac3", 6), Track("eng")), listOf(Track("eng")), width = 1920, height = 1080)

        library.playback(plan) { SeeingEngine(it, seen) }.play()

        val attributes = version("FR - Le Film (2025)", listOf("fr")).attributes
        assertEquals(Known("fr", Provenance.Confirmed), attributes.language)
        assertEquals(Known(setOf("en"), Provenance.Confirmed), attributes.subtitles)
        assertEquals(Known(Quality.FullHD, Provenance.Confirmed), attributes.quality)
        // The next details page shares it, and doesn't read the file.
        readHeaders(listOf("fr"))
        assertEquals(emptyList<String>(), provider.ranges)
        assertEquals(1, cloud.fileReads.size)
    }

    /** An engine that plays a file to its end at once, having seen [seen] in it. */
    private class SeeingEngine(override val engine: Engine, override val seen: FileContents) : PlayerEngine {
        override val position = 0L

        override suspend fun play(location: String, from: Long) = Unit

        override fun release() = Unit
    }

    private infix fun Pair<String, String>.with(container: String) = Triple(first, second, container)
}
