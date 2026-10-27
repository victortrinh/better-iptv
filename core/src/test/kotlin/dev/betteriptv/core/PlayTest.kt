package dev.betteriptv.core

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Pressing Play: which Version the play plan picks for a Device profile, its Tracks, and the one connection. */
class PlayTest {
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
    private val movie = TitleId.Tmdb(TitleKind.Movie, 1)

    private val dolbyVisionTv = DeviceProfile(maxHeight = 2160, hdr = setOf(Hdr.HDR10, Hdr.DolbyVision))
    private val hdr10Tv = DeviceProfile(maxHeight = 2160, hdr = setOf(Hdr.HDR10))
    private val sdr4kTv = DeviceProfile(maxHeight = 2160, hdr = emptySet())
    private val fullHdTv = DeviceProfile(maxHeight = 1080, hdr = emptySet())

    /** Syncs these Listings of one Movie, newest first, numbered from 1. */
    private suspend fun syncVersions(vararg listings: Pair<String, String>): SyncStatus {
        provider.serveMovies(*listings)
        return library.sync()
    }

    private suspend fun picked(device: DeviceProfile, contentLanguages: List<String> = english): String? =
        library.playPlan(movie, contentLanguages, device)?.version?.name

    /** The Ballerina-like spread: every quality in English, plus a better copy in German. */
    private suspend fun syncSpread() = syncVersions(
        "EN - Movie (2025)" to "EN - MOVIES",
        "4K-EN - Movie (2025)" to "4K MOVIES",
        "4K-EN - Movie (2025) HDR" to "4K HDR MOVIES",
        "4K-EN - Movie (2025) DV" to "4K DOLBY VISION MOVIES",
        "4K-DE-DV - Der Film (2025)" to "4K MOVIES",
    )

    @Test fun `a Dolby Vision 4K TV gets the 4K Dolby Vision Version in the Viewer's language`() = runBlocking {
        syncSpread()

        assertEquals("4K-EN - Movie (2025) DV", picked(dolbyVisionTv))
    }

    @Test fun `an HDR10 TV never gets Dolby Vision, but gets 4K HDR`() = runBlocking {
        syncSpread()

        assertEquals("4K-EN - Movie (2025) HDR", picked(hdr10Tv))
    }

    @Test fun `a 4K TV without HDR gets plain 4K`() = runBlocking {
        syncSpread()

        assertEquals("4K-EN - Movie (2025)", picked(sdr4kTv))
    }

    @Test fun `a 1080p TV never gets a 4K Version`() = runBlocking {
        syncSpread()

        assertEquals("EN - Movie (2025)", picked(fullHdTv))
        // Not even when the only Version in the Viewer's language is 4K.
        assertEquals("EN - Movie (2025)", picked(fullHdTv, listOf("de")))
    }

    @Test fun `with nothing the TV can display, nothing is picked`() = runBlocking {
        syncVersions("4K-FR-HDR - Le Film (2025)" to "4K MOVIES")

        assertNull(library.playPlan(movie, listOf("fr"), fullHdTv))
        assertEquals("4K-FR-HDR - Le Film (2025)", picked(hdr10Tv, listOf("fr")))
    }

    @Test fun `the Content language comes before quality`() = runBlocking {
        syncSpread()

        assertEquals("4K-DE-DV - Der Film (2025)", picked(dolbyVisionTv, listOf("de")))
        // The first Content language that has a Version wins.
        assertEquals("4K-DE-DV - Der Film (2025)", picked(dolbyVisionTv, listOf("it", "de", "en")))
        assertEquals("4K-EN - Movie (2025) DV", picked(dolbyVisionTv, listOf("en", "de")))
    }

    @Test fun `a known Source wins at the same quality, then the newest Listing`() = runBlocking {
        syncVersions(
            "DE - Der Film (2025)" to "DE - FILME",
            "DE-JO - Der Film (2025)" to "DE - FILME",
        )
        // Joyn is the Source; the plain Listing is newer.
        assertEquals("DE-JO - Der Film (2025)", picked(fullHdTv, listOf("de")))

        syncVersions(
            "DE - Der Film (2025)" to "DE - FILME",
            "DE - Der Film (2025) " to "DE - FILME",
        )
        assertEquals(1L, library.playPlan(movie, listOf("de"), fullHdTv)!!.version.listingId)
    }

    @Test fun `a CAM Version is picked last, even after other languages`() = runBlocking {
        syncVersions(
            "EN-CAM - Movie (2025)" to "EN - MOVIES",
            "4K-EN - Movie (2025)" to "CAM & NEW RELEASE",
            "FR - Le Film (2025)" to "FR - FILMS",
        )

        assertEquals("FR - Le Film (2025)", picked(dolbyVisionTv))

        syncVersions("EN-CAM - Movie (2025)" to "EN - MOVIES")
        assertEquals("EN-CAM - Movie (2025)", picked(dolbyVisionTv))
    }

    @Test fun `an Audio-description Version is never picked automatically`() = runBlocking {
        syncVersions(
            "4K-EN - Movie (2025)" to "EN - AUDIO-DESCRIPTION",
            "FR - Le Film (2025)" to "FR - FILMS",
        )
        assertEquals("FR - Le Film (2025)", picked(dolbyVisionTv))

        syncVersions("EN - Movie (2025)" to "EN - AUDIO-DESCRIPTION")
        assertNull(library.playPlan(movie, english, dolbyVisionTv))
        // The Viewer can still choose it from the Versions list.
        val chosen = library.chosenPlayPlan(movie, 1, english)!!
        assertEquals("EN - Movie (2025)", chosen.version.name)
    }

    @Test fun `with no Version in a Content language, the best one overall plays`() = runBlocking {
        syncVersions(
            "FR - Le Film (2025)" to "FR - FILMS",
            "4K-DE-DV - Der Film (2025)" to "4K MOVIES",
            "4K-FR - Le Film (2025)" to "4K MOVIES",
        )

        assertEquals("4K-DE-DV - Der Film (2025)", picked(dolbyVisionTv, listOf("it")))
        assertEquals("4K-FR - Le Film (2025)", picked(hdr10Tv, listOf("it")))
    }

    @Test fun `Backups are the other playable Versions with the same language and quality`() = runBlocking {
        syncVersions(
            "4K-EN - Movie (2025)" to "4K MOVIES",
            "4K-EN - Movie (2025) " to "4K HDR MOVIES",
            "4K-EN - Movie (2025)  " to "4K MOVIES",
            "EN - Movie (2025)" to "EN - MOVIES",
        )

        val version = library.playPlan(movie, english, sdr4kTv)!!.version
        assertEquals(1L, version.listingId)
        assertEquals(listOf(3L), version.backups.map { it.listingId })
    }

    // Tracks

    private fun tracks(vararg languages: String?) = languages.map { Track(it) }

    @Test fun `the audio Track in the first Content language the file has is chosen, with no subtitles`() = runBlocking {
        syncVersions("NF - Movie (2025)" to "NETFLIX")
        val plan = library.playPlan(movie, listOf("fr", "en"), fullHdTv)!!

        // Files tag Tracks with ISO 639-2 codes as often as 639-1.
        val choice = plan.chooseTracks(audio = tracks("spa", "eng", "fre"), subtitles = tracks("eng", "fra"), fullHdTv)
        assertEquals(TrackChoice(audio = 2, subtitle = null), choice)
        assertEquals(TrackChoice(audio = 1, subtitle = null), plan.chooseTracks(tracks("de", "en"), tracks(), fullHdTv))
    }

    @Test fun `without audio in a Content language, subtitles in the first Content language are chosen when the file has them`() = runBlocking {
        syncVersions("4K-DE-DV - Der Film (2025)" to "4K MOVIES")
        val plan = library.playPlan(movie, listOf("fr", "en"), dolbyVisionTv)!!

        assertEquals(TrackChoice(audio = null, subtitle = 2), plan.chooseTracks(tracks("ger", null), tracks("eng", "ger", "fre", "fr"), dolbyVisionTv))
        // English subtitles don't stand in for French ones.
        assertEquals(TrackChoice(audio = null, subtitle = null), plan.chooseTracks(tracks("deu"), tracks("eng", null), dolbyVisionTv))
    }

    // Audio: passed through or decoded

    private val dts = "audio/vnd.dts"
    private val dtsHd = "audio/vnd.dts.hd"
    private val trueHd = "audio/true-hd"
    private val ac3 = "audio/ac3"
    private val aac = "audio/mp4a-latm"

    /** A Fire stick plugged straight into a TV: it decodes Dolby and AAC itself, and the TV takes nothing but PCM. */
    private val tvSpeakers = fullHdTv.copy(audioDecoders = setOf(aac, ac3, "audio/eac3"))

    /** The same stick with a soundbar that takes every Blu-ray format as it is. */
    private val soundbar = tvSpeakers.copy(audioPassthrough = setOf(ac3, "audio/eac3", dts, dtsHd, trueHd))

    @Test fun `DTS, DTS-HD and TrueHD pass through when the Device profile reports support, and are decoded in software otherwise`() {
        for (format in listOf(dts, dtsHd, trueHd)) {
            assertEquals(format, AudioOutput.Passthrough, soundbar.audioOutput(format))
            assertEquals(format, AudioOutput.SoftwareDecoder, tvSpeakers.audioOutput(format))
        }
    }

    @Test fun `formats the stick decodes itself pass through when they can, and use the stick's decoder otherwise`() {
        assertEquals(AudioOutput.Passthrough, soundbar.audioOutput(ac3))
        assertEquals(AudioOutput.PlatformDecoder, tvSpeakers.audioOutput(ac3))
        assertEquals(AudioOutput.PlatformDecoder, soundbar.audioOutput(aac))
        // Formats the app's decoder covers too, for a stick without them.
        assertEquals(AudioOutput.SoftwareDecoder, fullHdTv.audioOutput(ac3))
    }

    @Test fun `DTS-HD passes through as its DTS core, and Dolby Atmos as plain E-AC-3, to a soundbar that takes only those`() {
        val coreOnly = tvSpeakers.copy(audioPassthrough = setOf(ac3, "audio/eac3", dts))

        assertEquals(AudioOutput.Passthrough, coreOnly.audioOutput(dtsHd))
        assertEquals(AudioOutput.Passthrough, coreOnly.audioOutput("audio/eac3-joc"))
        assertEquals(AudioOutput.SoftwareDecoder, coreOnly.audioOutput(trueHd))
    }

    @Test fun `PCM audio always plays, with no decoder to go through`() {
        assertEquals(AudioOutput.PlatformDecoder, fullHdTv.audioOutput("audio/raw"))
    }

    @Test fun `a format nothing on the device plays has no output, and one the file doesn't name is taken to play`() {
        assertNull(tvSpeakers.audioOutput("audio/x-unknown"))
        assertEquals(AudioOutput.PlatformDecoder, tvSpeakers.audioOutput(null))
    }

    @Test fun `in the chosen language, audio that passes through or plays on the stick's decoder beats audio decoded in software`() = runBlocking {
        syncVersions("EN - Movie (2025)" to "EN - MOVIES")
        val plan = library.playPlan(movie, english, fullHdTv)!!
        // A Blu-ray rip: lossless TrueHD first, then the Dolby Digital compatibility Track.
        val audio = listOf(Track("fre", ac3), Track("eng", trueHd), Track("eng", ac3))

        assertEquals(TrackChoice(audio = 2, subtitle = null), plan.chooseTracks(audio, tracks(), tvSpeakers))
        // A soundbar takes the lossless Track as it is.
        assertEquals(TrackChoice(audio = 1, subtitle = null), plan.chooseTracks(audio, tracks(), soundbar))
        // Without a choice of format, software decoding plays it.
        assertEquals(TrackChoice(audio = 0, subtitle = null), plan.chooseTracks(listOf(Track("eng", dts), Track("fre", ac3)), tracks(), tvSpeakers))
    }

    @Test fun `audio nothing on the device plays is never chosen`() = runBlocking {
        syncVersions("EN - Movie (2025)" to "EN - MOVIES")
        val plan = library.playPlan(movie, listOf("en", "fr"), fullHdTv)!!

        val audio = listOf(Track("eng", "audio/x-unknown"), Track("fre", ac3))
        assertEquals(TrackChoice(audio = 1, subtitle = null), plan.chooseTracks(audio, tracks(), tvSpeakers))
    }

    // The connection gate

    @Test fun `a second stream waits until the first one is closed, so the Provider never refuses one`() = runBlocking {
        syncVersions(
            "EN - Movie (2025)" to "EN - MOVIES",
            "EN - Movie (2025) " to "EN - MOVIES",
        )
        val plan = library.playPlan(movie, english, fullHdTv)!!

        val first = library.connect(plan.version)
        val playing = provider.play(first.location)
        val second = async { library.connect(plan.version.backups.single()) }
        delay(300)
        assertFalse("a second stream opened while the first was in use", second.isCompleted)

        playing.close()
        first.close()
        withTimeout(2_000) { second.await() }.close()
        assertEquals(0, provider.refusedStreams)
        assertEquals(1, provider.streamRequests)
    }

    @Test fun `a connection's location plays the Version, and printing it never shows the login`() = runBlocking {
        syncVersions("EN - Movie (2025)" to "EN - MOVIES")

        library.connect(library.playPlan(movie, english, fullHdTv)!!.version).use { connection ->
            assertTrue(connection.location.endsWith("/1.mkv"))
            provider.play(connection.location).close()
            assertFalse(provider.password in connection.toString())
            assertFalse(provider.username in connection.toString())
        }
        assertEquals(0, provider.refusedStreams)
    }

    @Test fun `closing a connection twice releases it once`() = runBlocking {
        syncVersions("EN - Movie (2025)" to "EN - MOVIES")
        val version = library.playPlan(movie, english, fullHdTv)!!.version

        val first = library.connect(version)
        first.close()
        first.close()
        val second = withTimeout(1_000) { library.connect(version) }
        val third = async { library.connect(version) }
        delay(200)
        assertFalse(third.isCompleted)
        second.close()
        withTimeout(1_000) { third.await() }.close()
    }
}
