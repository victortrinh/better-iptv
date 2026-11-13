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
import java.util.concurrent.CountDownLatch
import kotlin.time.Duration.Companion.seconds
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

    private val movie = TitleId.Numbered(TitleKind.Movie, 1)
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
        assertEquals(0, provider.openStreamCount)
    }

    @Test fun `a Movie's Versions are checked once each language's best one is read, or can't be`() = runBlocking {
        syncVersions("EN - Movie (2025)" to "EN - MOVIES" with "mkv", "FR - Le Film (2025)" to "FR - FILMS" with "avi")
        provider.movieFiles[1] = matroskaFile(englishAndFrench)

        // Nothing read yet: what the page shows is guessed from the Prefixes.
        assertEquals(false, library.versionsChecked(movie, listOf("en", "fr")))
        readHeaders(listOf("en", "fr"))
        // An AVI isn't Header read, so it leaves nothing to wait for.
        assertEquals(true, library.versionsChecked(movie, listOf("en", "fr")))
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

    @Test fun `the best unread Version per language is read first, the Viewer's languages first, then the rest`() = runBlocking {
        syncVersions(
            "4K-EN - Movie (2025)" to "4K MOVIES" with "mkv",
            "EN - Movie (2025)" to "EN - MOVIES" with "mkv",
            "DE - Der Film (2025)" to "DE - FILME" with "mkv",
        )
        (1L..3L).forEach { provider.movieFiles[it] = matroskaFile(englishAndFrench) }

        readHeaders()

        assertEquals(listOf("1:bytes=0-262143", "3:bytes=0-262143", "2:bytes=0-262143"), provider.ranges)
        assertEquals(Provenance.Confirmed, version("EN - Movie (2025)").attributes.language?.provenance)
    }

    /** A read moves a Version to the quality and language its file has, which leaves another one the best unread of its group. */
    @Test fun `one visit settles a Movie whose reads correct its Prefixes, and the next visits read nothing and change nothing`() = runBlocking {
        syncVersions(
            "4K-EN - Movie (2025)" to "4K MOVIES" with "mkv",
            "4K-EN - Movie (2025)" to "4K MOVIES" with "mkv",
            "4K-FR - Le Film (2025)" to "4K MOVIES" with "mkv",
            "EN - Movie (2025)" to "EN - MOVIES" with "mkv",
            "FR - Le Film (2025)" to "FR - FILMS" with "mkv",
            "DE - Der Film (2025)" to "DE - FILME" with "mkv",
        )
        val english = listOf(FileTrack("audio", "eng", "A_AC3"))
        // Two `4K-EN` copies that are 1080p and 720p, a `4K-FR` and a `DE` one in English, a plain `EN` one in 4K.
        provider.movieFiles[1] = matroskaFile(english, width = 1920, height = 800)
        provider.movieFiles[2] = matroskaFile(english, width = 1280, height = 536)
        provider.movieFiles[3] = matroskaFile(english, width = 3840, height = 1600)
        provider.movieFiles[4] = matroskaFile(english, width = 3840, height = 1600)
        provider.movieFiles[5] = matroskaFile(listOf(FileTrack("audio", "fre", "A_AC3")))
        provider.movieFiles[6] = matroskaFile(english)
        val languages = listOf("en", "fr")
        val device = DeviceProfile(maxHeight = 2160, hdr = emptySet())
        suspend fun page() = library.playPlan(movie, languages, device)!!.version to library.title(movie, languages, "en")!!.let { it.languages + it.otherLanguages }

        readHeaders(languages)

        assertEquals(true, library.versionsChecked(movie, languages))
        assertEquals(6, provider.ranges.size)
        val settled = page()
        assertEquals(Known(Quality.UHD, Provenance.Confirmed), settled.first.attributes.quality)
        repeat(3) {
            assertEquals(0, readHeaders(languages))
            assertEquals(true, library.versionsChecked(movie, languages))
            assertEquals(settled, page())
        }
        assertEquals(6, provider.ranges.size)
    }

    @Test fun `the page hears how many Versions are left to check, down to none`() = runBlocking {
        syncVersions(
            "4K-EN - Movie (2025)" to "4K MOVIES" with "mkv",
            "DE - Der Film (2025)" to "DE - FILME" with "mkv",
        )
        (1L..2L).forEach { provider.movieFiles[it] = matroskaFile(englishAndFrench) }

        val left = mutableListOf<Int>()
        library.readHeaders(movie, listOf("en"), checking = { left += it }) {}

        assertEquals(listOf(2, 1, 0), left)
    }

    @Test fun `a Title whose Versions are all Confirmed has nothing left to check`() = runBlocking {
        syncVersions("EN - Movie (2025)" to "EN - MOVIES" with "mkv")
        provider.movieFiles[1] = matroskaFile(englishAndFrench)
        readHeaders()

        val left = mutableListOf<Int>()
        library.readHeaders(movie, listOf("en"), checking = { left += it }) {}

        assertTrue("never says it is checking: $left", left.none { it > 0 })
    }

    @Test fun `a refused read ends the checking`() = runBlocking {
        syncVersions(
            "4K-EN - Movie (2025)" to "4K MOVIES" with "mkv",
            "DE - Der Film (2025)" to "DE - FILME" with "mkv",
        )
        (1L..2L).forEach { provider.movieFiles[it] = matroskaFile(englishAndFrench) }
        provider.refuseRangesAfter = 0

        val left = mutableListOf<Int>()
        library.readHeaders(movie, listOf("en"), checking = { left += it }) {}

        assertEquals(0, left.last())
    }

    /** Glass Onion's 13 Listings on the surveyed Provider, as the stick's catalog has them (#505). */
    private val glassOnion = arrayOf(
        "GR - Glass Onion: Στα Μαχαίρια (2022)" to "GR - NETFLIX" with "mkv",
        "JP - Glass Onion: A Knives Out Mystery (2022)" to "JP - JAPANESE FILM" with "mkv",
        "GR - Glass Onion A Knives Out Mystery (2022)" to "GR - NETFLIX" with "mkv",
        "4K-PL - Glass Onion: Film z serii „Na noże” (2022)" to "PL - FILMY 4K" with "mkv",
        "4K-NF - Glass Onion: A Knives Out Mystery (2022)" to "NETFLIX MOVIES ⁴ᴷ ³⁸⁴⁰ᴾ ᴰᵒˡᵇʸ ⱽᶦˢᶦᵒⁿ" with "mkv",
        "ES - Puñales por la espalda: El misterio de Glass Onion (2022)" to "ES - NETFLIX" with "mkv",
        "IR - Glass Onion: A Knives Out Mystery (2022) چاقو کشی 2" to "IR - PERSIAN SUB/DUB" with "mkv",
        "NL - Glass Onion: A Knives Out Mystery (2022)" to "NL - 2022 & OUD" with "mkv",
        "SE - Glass Onion: A Knives Out Mystery (2022)" to "SVENSKA NYA UTGÅVAN" with "mp4",
        "4K-EN - Glass Onion: A Knives Out Mystery  (2022)" to "EN - MOVIES ⁴ᴷ ³⁸⁴⁰ᴾ" with "mkv",
        "DE - Glass Onion: A Knives Out Mystery (2022)" to "DE - FILME 1940/2024" with "mkv",
        "NF - Glass Onion: A Knives Out Mystery (2022)" to "NETFLIX MOVIES" with "mkv",
        "EN - Glass Onion: A Knives Out Mystery  (2022)" to "EN - NEW RELEASE" with "mp4",
    )

    @Test fun `on a 13-Version Title, the page stops saying it checks once its reads end`() = runBlocking {
        syncVersions(*glassOnion)
        (1L..13L).forEach { provider.movieFiles[it] = if (glassOnion[it.toInt() - 1].third == "mp4") mp4File(listOf(FileTrack("audio", "eng", "mp4a"))) else matroskaFile(englishAndFrench) }

        val left = mutableListOf<Int>()
        library.readHeaders(movie, listOf("en"), checking = { left += it }) {}

        // Every Version, the best unread one of each language and Prefix no table knows first, down to none.
        assertEquals((13 downTo 0).toList(), left)
    }

    @Test fun `a Provider that stops sending a file mid-read ends the checking within the read's bound`() = runBlocking {
        syncVersions(*glassOnion)
        provider.stalledFiles = true
        library.headerReadBound = 1.seconds

        val left = mutableListOf<Int>()
        val started = TimeSource.Monotonic.markNow()
        withTimeout(10_000) { library.readHeaders(movie, listOf("en"), checking = { left += it }) {} }

        assertEquals(listOf(13, 0), left)
        assertTrue("took ${started.elapsedNow()}", started.elapsedNow() < 3.seconds)
        // One request, then nothing more for now: the Provider isn't answering.
        assertEquals(1, provider.streamRequests)
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

    @Test fun `a Movie's details come with what other devices on the Provider read, so its badges don't change after the page arrives`() = runBlocking {
        syncVersions("EN - Movie (2025)" to "EN - MOVIES" with "mkv")
        provider.movieFiles[1] = matroskaFile(englishAndFrench, width = 3840, height = 2160)
        readHeaders()

        Library.open(folder.root.resolve("other.db").path, InMemoryCredentials(), cloud.url).use { other ->
            other.logIn(provider.login)
            other.sync()
            // The page reads the details and the plan before it fades in, and its Header reads only after.
            val details = other.title(movie, listOf("en"), "en")!!
            assertEquals(Known(Quality.UHD, Provenance.Confirmed), details.languages.single().versions.single().attributes.quality)
            val plan = other.playPlan(movie, listOf("en"), DeviceProfile(maxHeight = 2160, hdr = emptySet()))!!
            assertEquals(Known(Quality.UHD, Provenance.Confirmed), plan.version.attributes.quality)
        }
        assertEquals(1, provider.ranges.size)
    }

    @Test fun `a slow cloud holds a Movie's details no longer than a page waits to arrive, and its answer is kept`() = runBlocking {
        syncVersions("EN - Movie (2025)" to "EN - MOVIES" with "mkv")
        provider.movieFiles[1] = matroskaFile(englishAndFrench, width = 3840, height = 2160)
        readHeaders()

        Library.open(folder.root.resolve("other.db").path, InMemoryCredentials(), cloud.url).use { other ->
            other.logIn(provider.login)
            other.sync()
            val hanging = CountDownLatch(1)
            cloud.hangingFileReadLookUps = hanging
            val asked = TimeSource.Monotonic.markNow()
            val details = other.title(movie, listOf("en"), "en")!!
            assertTrue(asked.elapsedNow().inWholeMilliseconds < 1_000)
            assertNull(details.languages.single().versions.single().attributes.quality)
            hanging.countDown()
            cloud.hangingFileReadLookUps = null
            withTimeout(5_000) {
                while (other.title(movie, listOf("en"), "en")!!.languages.single().versions.single().attributes.quality == null) delay(20)
            }
        }
    }

    @Test fun `pressing Play lets a read in progress finish within its bound, and cuts it off past it`() = runBlocking {
        syncVersions("EN - Movie (2025)" to "EN - MOVIES" with "mkv", "FR - Le Film (2025)" to "FR - FILMS" with "mkv")
        provider.movieFiles[1] = matroskaFile(englishAndFrench)
        provider.movieFiles[2] = matroskaFile(englishAndFrench)
        // Five seconds a read: past Play's bound.
        provider.slowFiles = true
        val reading = async { readHeaders() }
        withTimeout(5_000) { while (provider.openStreamCount == 0) delay(10) }

        val pressed = TimeSource.Monotonic.markNow()
        val plan = library.pressPlay(movie, listOf("en"), fullHdTv)!!
        library.connect(plan.version).use { connection ->
            // The read gave way at the bound, and the Provider counted it closed before playback opened the file.
            val waited = pressed.elapsedNow()
            assertTrue("Play waited $waited", waited >= PLAY_WAITS_FOR_READ && waited < PLAY_WAITS_FOR_READ + PROVIDER_RELEASE_GRACE * 2)
            reading.await()
            provider.play(connection.location).close()
        }
        assertEquals(0, provider.refusedStreams)
        // Nothing was kept of the cut-off read, so the next details page reads it again; the read behind it never started.
        assertEquals(Provenance.Likely, version("EN - Movie (2025)").attributes.language?.provenance)
        assertEquals(1, provider.ranges.size)

        // A read quick enough lands whole, and the plan made after it names what it Confirmed.
        provider.slowFileStep = 8 * 1024
        val again = async { readHeaders() }
        // The cut-off read's server thread may still be winding down: wait for the new range request itself.
        withTimeout(5_000) { while (provider.ranges.size < 2) delay(10) }
        val quick = library.pressPlay(movie, listOf("en"), fullHdTv)!!
        library.connect(quick.version).close()
        again.await()
        assertEquals(Known("en", Provenance.Confirmed), quick.version.attributes.language)
        assertEquals(2, provider.ranges.size)
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

    @Test fun `a read report carries the Listing, its file, its Prefix and its hints, and no name`() = runBlocking {
        syncVersions("SC - Movie (2025)" to "SC - MULTI-SUBS FILMS" with "mkv")
        provider.movieFiles[1] = matroskaFile(englishAndFrench)

        readHeaders()

        val report = cloud.fileReadReports.single()
        assertEquals(setOf("listingId", "file", "prefix", "hints"), report.keys)
        assertEquals(1L, (report["listingId"] as Number).toLong())
        assertEquals("SC", report["prefix"])
        assertEquals(listOf("MULTI-SUBS"), report["hints"])
        val sent = cloud.received.single { it.path == "/v1/file-reads" }.body
        assertTrue("Movie" !in sent && "FILMS" !in sent)
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
        // Reported with its Prefix, and the Category's hints (none here).
        val report = cloud.fileReadReports.single()
        assertEquals("FR", report["prefix"])
        assertEquals(emptyList<String>(), report["hints"])
        assertTrue("Le Film" !in cloud.received.single { it.path == "/v1/file-reads" }.body)
    }

    /** An engine that plays a file to its end at once, having seen [seen] in it. */
    private class SeeingEngine(override val engine: Engine, override val seen: FileContents) : PlayerEngine {
        override val position = 0L

        override suspend fun play(location: String, from: Long, events: (PlayerEvent) -> Unit) = events(PlayerEvent.Started)

        override suspend fun release() = Unit
    }

    private infix fun Pair<String, String>.with(container: String) = Triple(first, second, container)
}
