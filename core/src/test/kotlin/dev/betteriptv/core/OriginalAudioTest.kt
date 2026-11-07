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

/**
 * Prefer original audio (#414): a Title plays with Original audio when a Version has it, found by
 * Header reads in order of likelihood. The spread is The Shadow's Edge (2025), a Chinese film, as
 * the surveyed Provider files it: a German-only 4K, an `NL` and an `SC` copy carrying Mandarin with
 * subtitles, and an `EN` copy.
 */
class OriginalAudioTest {
    @get:Rule val folder = TemporaryFolder()
    private val provider = FakeProvider()
    private val cloud = FakeCloud().servingSurveyedTable(provider)
    private lateinit var library: Library

    @Before fun logIn() = runBlocking {
        // The surveyed Provider's table, where `SC` is the Nordic subtitle culture rather than an unknown Prefix.
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

    private val movie = TitleId.Tmdb(TitleKind.Movie, 1)
    private val english = listOf("en")
    private val fullHdTv = DeviceProfile(maxHeight = 1080, hdr = emptySet())
    private val sdr4kTv = DeviceProfile(maxHeight = 2160, hdr = emptySet())

    // The files, as the Header reads of 2026-11-06 found them.
    private val germanOnly4k = matroskaFile(listOf(FileTrack("audio", "ger", "A_EAC3", channels = 6)), width = 3840, height = 1608, videoCodec = "V_MPEG4/ISO/AVC", size = 15_000_000)
    private val mandarinNordicSubs = matroskaFile(
        listOf(FileTrack("audio", "chi", "A_EAC3", channels = 6)) + listOf("dan", "fin", "nor", "swe").map { FileTrack("subtitle", it, "S_TEXT/UTF8") },
        videoCodec = "V_MPEG4/ISO/AVC", size = 4_500_000,
    )
    private val mandarinDutchSubs = matroskaFile(
        listOf(FileTrack("audio", "chi", "A_EAC3", channels = 6)) + listOf("dut", "ger", "eng", "chi").map { FileTrack("subtitle", it, "S_TEXT/UTF8") },
        size = 2_100_000,
    )
    private val englishStereo = matroskaFile(listOf(FileTrack("audio", null, "A_AAC")), videoCodec = "V_MPEG4/ISO/AVC", size = 3_600_000)

    /** Syncs these Listings of The Shadow's Edge, as (name, Category, file), newest first, numbered from 1. */
    private suspend fun syncVersions(vararg listings: Triple<String, String, ByteArray>) {
        val categories = listings.map { it.second }.distinct()
        provider.movieCategoriesOverride = categories.withIndex()
            .joinToString(",", "[", "]") { (i, name) -> """{"category_id":"${i + 1}","category_name":"$name"}""" }
        provider.movieListOverride = listings.withIndex().joinToString(",", "[", "]") { (i, listing) ->
            """{"stream_id":${i + 1},"name":"${listing.first}","tmdb":"1","category_id":"${categories.indexOf(listing.second) + 1}",""" +
                """"added":"${1_700_000_000 - i}","container_extension":"mkv"}"""
        }
        listings.forEachIndexed { i, listing -> provider.movieFiles[i + 1L] = listing.third }
        library.sync()
    }

    /** The surveyed spread: the 4K is Listing 1, the `SC` copy 2, the `NL` copy 3 and the `EN` copy 4. */
    private suspend fun syncSpread(originalLanguage: String = "zh") {
        cloud.title("en", tmdbTitle(1, "The Shadow's Edge", originalLanguage = originalLanguage))
        syncVersions(
            Triple("4K-DE - Shadow Chase: Im Netz der Diebe (2025)", "4K MOVIES", germanOnly4k),
            Triple("SC - The Shadow's Edge (2025)", "NORDIC MOVIES", mandarinNordicSubs),
            Triple("NL - The Shadow's Edge (2025)", "NL - MOVIES", mandarinDutchSubs),
            Triple("EN - The Shadow's Edge (2025)", "EN - MOVIES", englishStereo),
        )
    }

    /** Opens the Title's page as the app does: its details (which fetch TMDB's original language), then its Header reads. */
    private suspend fun openPage(contentLanguages: List<String> = english, preferOriginalAudio: Boolean = true): TitleDetails {
        val details = library.title(movie, contentLanguages, "en", preferOriginalAudio = preferOriginalAudio)!!
        library.readHeaders(movie, contentLanguages, preferOriginalAudio) {}
        return details
    }

    private fun readIds() = provider.ranges.map { it.substringBefore(':').toLong() }

    private fun tracks(vararg languages: String?) = languages.map { Track(it) }

    private suspend fun version(name: String): Version {
        val details = library.title(movie, english, "en")!!
        return (details.languages + details.otherLanguages).flatMap { group -> group.versions.flatMap { listOf(it) + it.backups } }.single { it.name == name }
    }

    // Picking

    @Test fun `the Mandarin copy with English subtitles in its file plays, its Mandarin Track and English subtitles chosen`() = runBlocking {
        syncSpread()
        openPage()

        val plan = library.playPlan(movie, english, sdr4kTv)!!

        assertEquals("NL - The Shadow's Edge (2025)", plan.version.name)
        assertEquals(Known("zh", Provenance.Confirmed), plan.version.attributes.language)
        // The `SC` copy, Mandarin at the same picture, is its Backup: a failing Mandarin copy gives way to another, not to a dub.
        assertEquals(listOf("SC - The Shadow's Edge (2025)"), plan.version.backups.map { it.name })
        assertEquals(TrackChoice(audio = 0, subtitle = 2), plan.chooseTracks(tracks("chi"), tracks("dut", "ger", "eng", "chi"), sdr4kTv))
        // On the copy with Nordic subtitles only, English ones are downloaded.
        assertEquals(TrackChoice(audio = 0, subtitle = null, downloaded = "en"), plan.chooseTracks(tracks("chi"), tracks("dan", "fin", "nor", "swe"), sdr4kTv))
    }

    @Test fun `Original audio beats a 4K dub with the setting on, and loses to it with the setting off`() = runBlocking {
        cloud.title("en", tmdbTitle(1, "The Shadow's Edge", originalLanguage = "zh"))
        syncVersions(
            Triple("4K-EN - The Shadow's Edge (2025)", "4K MOVIES", matroskaFile(listOf(FileTrack("audio", "eng", "A_EAC3", channels = 6)), width = 3840, height = 1608)),
            Triple("NL - The Shadow's Edge (2025)", "NL - MOVIES", mandarinDutchSubs),
        )
        openPage()

        assertEquals("NL - The Shadow's Edge (2025)", library.playPlan(movie, english, sdr4kTv)!!.version.name)
        assertEquals("4K-EN - The Shadow's Edge (2025)", library.playPlan(movie, english, sdr4kTv, preferOriginalAudio = false)!!.version.name)
    }

    @Test fun `a 4K with German first and Mandarin second has Original audio, and its Mandarin Track is chosen`() = runBlocking {
        cloud.title("en", tmdbTitle(1, "The Shadow's Edge", originalLanguage = "zh"))
        val germanThenMandarin = matroskaFile(
            listOf(FileTrack("audio", "ger", "A_EAC3", channels = 6), FileTrack("audio", "chi", "A_EAC3", channels = 6), FileTrack("subtitle", "eng", "S_TEXT/UTF8")),
            width = 3840, height = 1608,
        )
        syncVersions(
            Triple("4K-DE - Shadow Chase: Im Netz der Diebe (2025)", "4K MOVIES", germanThenMandarin),
            Triple("NL - The Shadow's Edge (2025)", "NL - MOVIES", mandarinDutchSubs),
        )
        openPage()

        val plan = library.playPlan(movie, english, sdr4kTv)!!
        assertEquals("4K-DE - Shadow Chase: Im Netz der Diebe (2025)", plan.version.name)
        assertEquals(Known(setOf("de", "zh"), Provenance.Confirmed), plan.version.attributes.audioLanguages)
        assertEquals(TrackChoice(audio = 1, subtitle = 0), plan.chooseTracks(tracks("ger", "chi"), tracks("eng"), sdr4kTv))
        // The Quality cap and the TV still apply: within 1080p, the Mandarin 1080p copy plays.
        assertEquals("NL - The Shadow's Edge (2025)", library.playPlan(movie, english, sdr4kTv, QualityCap.FullHD)!!.version.name)
        assertEquals("NL - The Shadow's Edge (2025)", library.playPlan(movie, english, fullHdTv)!!.version.name)
    }

    @Test fun `a Title released in a Content language plays exactly as before`() = runBlocking {
        syncSpread(originalLanguage = "en")
        openPage()

        val plan = library.playPlan(movie, english, sdr4kTv)!!
        assertEquals("EN - The Shadow's Edge (2025)", plan.version.name)
        assertEquals(plan, library.playPlan(movie, english, sdr4kTv, preferOriginalAudio = false)!!.copy(preferOriginalAudio = true))
        assertEquals(TrackChoice(audio = 0, subtitle = null), plan.chooseTracks(tracks("eng"), tracks("fre"), sdr4kTv))
    }

    @Test fun `the audio language the Viewer last picked beats the setting`() = runBlocking {
        syncSpread()
        openPage()
        library.rememberAudio("de")

        val plan = library.playPlan(movie, english, sdr4kTv)!!

        assertEquals("4K-DE - Shadow Chase: Im Netz der Diebe (2025)", plan.version.name)
        assertEquals(TrackChoice(audio = 0, subtitle = null), plan.chooseTracks(tracks("ger"), tracks("eng"), sdr4kTv))
    }

    @Test fun `a Version the Viewer chose by hand plays regardless`() = runBlocking {
        syncSpread()
        openPage()

        assertEquals("4K-DE - Shadow Chase: Im Netz der Diebe (2025)", library.chosenPlayPlan(movie, 1, english)!!.version.name)
    }

    @Test fun `TMDB's "No Language" changes nothing`() = runBlocking {
        syncSpread(originalLanguage = "xx")
        openPage()

        assertEquals("EN - The Shadow's Edge (2025)", library.playPlan(movie, english, sdr4kTv)!!.version.name)
        assertNull(library.title(movie, english, "en")!!.originalLanguage)
    }

    @Test fun `a Title with no Version in Original audio plays as today`() = runBlocking {
        cloud.title("en", tmdbTitle(1, "The Shadow's Edge", originalLanguage = "zh"))
        syncVersions(
            Triple("4K-DE - Shadow Chase: Im Netz der Diebe (2025)", "4K MOVIES", germanOnly4k),
            Triple("EN - The Shadow's Edge (2025)", "EN - MOVIES", englishStereo),
        )
        openPage()

        assertEquals("EN - The Shadow's Edge (2025)", library.playPlan(movie, english, sdr4kTv)!!.version.name)
    }

    // Read order

    @Test fun `the page reads the subtitle cultures' copies, then the best copy outside the Viewer's languages, then the Content languages`() = runBlocking {
        syncSpread()

        openPage()

        // `SC`, `NL`, the 4K German, then English; the Untagged copies would come first, had the Title any.
        assertEquals(listOf(2L, 3L, 1L, 4L), readIds())
        assertEquals(0, provider.refusedStreams)
    }

    @Test fun `Untagged Versions are read first`() = runBlocking {
        cloud.title("en", tmdbTitle(1, "The Shadow's Edge", originalLanguage = "zh"))
        syncVersions(
            Triple("EN - The Shadow's Edge (2025)", "EN - MOVIES", englishStereo),
            Triple("SC - The Shadow's Edge (2025)", "NORDIC MOVIES", mandarinNordicSubs),
            Triple("NF - The Shadow's Edge (2025)", "NETFLIX", mandarinDutchSubs),
        )

        openPage()

        assertEquals(listOf(3L, 2L, 1L), readIds())
    }

    @Test fun `with the setting off, the Viewer's languages are read first, as today`() = runBlocking {
        syncSpread()

        openPage(preferOriginalAudio = false)

        // English, the copy naming no language, then the other languages, German before Dutch.
        assertEquals(listOf(4L, 2L, 1L, 3L), readIds())
    }

    // Play during a read

    @Test fun `Play lets the read in flight finish, and the copy it Confirmed is the one that plays`() = runBlocking {
        syncSpread()
        library.title(movie, english, "en")
        // About 0.6 s a read: within Play's bound.
        provider.slowFiles = true
        provider.slowFileStep = 8 * 1024
        val reading = async { library.readHeaders(movie, english) {} }
        withTimeout(5_000) { while (provider.openStreamCount == 0) delay(10) }

        val pressed = TimeSource.Monotonic.markNow()
        val plan = library.pressPlay(movie, english, sdr4kTv)!!
        library.connect(plan.version).use { connection ->
            assertTrue("Play waited ${pressed.elapsedNow()}", pressed.elapsedNow() < PLAY_WAITS_FOR_READ + PROVIDER_RELEASE_GRACE)
            provider.play(connection.location).close()
        }
        reading.await()

        // The `SC` copy's read landed whole, and the plan names it, Confirmed Mandarin; the reads behind it were dropped.
        assertEquals("SC - The Shadow's Edge (2025)", plan.version.name)
        assertEquals(Known("zh", Provenance.Confirmed), plan.version.attributes.language)
        assertEquals(listOf("2:bytes=0-262143"), provider.ranges)
        assertEquals(0, provider.refusedStreams)
    }

    @Test fun `a read slower than the bound is cut off, and the stream opens anyway`() = runBlocking {
        syncSpread()
        library.title(movie, english, "en")
        // Five seconds a read.
        provider.slowFiles = true
        val reading = async { library.readHeaders(movie, english) {} }
        withTimeout(5_000) { while (provider.openStreamCount == 0) delay(10) }

        val pressed = TimeSource.Monotonic.markNow()
        val plan = library.pressPlay(movie, english, sdr4kTv)!!
        library.connect(plan.version).use { connection ->
            val waited = pressed.elapsedNow()
            assertTrue("Play waited $waited", waited >= PLAY_WAITS_FOR_READ && waited < PLAY_WAITS_FOR_READ + PROVIDER_RELEASE_GRACE * 2)
            provider.play(connection.location).close()
        }
        reading.await()

        // Nothing Confirmed: the English copy plays, and the cut-off read is read again next time.
        assertEquals("EN - The Shadow's Edge (2025)", plan.version.name)
        assertEquals(1, provider.ranges.size)
        assertNull(version("SC - The Shadow's Edge (2025)").attributes.audioLanguages)
        assertEquals(0, provider.refusedStreams)
    }

    // The guard on moving a Version's language

    @Test fun `a read moves a Version only to a language the app has reason to expect`() = runBlocking {
        cloud.title("en", tmdbTitle(1, "Gladiator II", originalLanguage = "en"))
        syncVersions(
            // Sichuan Yi on a Polish copy: nonsense, kept in the read, not acted on.
            Triple("PL - Gladiator II (2024)", "PL - FILMY", matroskaFile(listOf(FileTrack("audio", "iii", "A_AAC")))),
            // Hindi on a Dutch copy: a language the Prefix table knows.
            Triple("NL - Gladiator II (2024)", "NL - MOVIES", matroskaFile(listOf(FileTrack("audio", "hin", "A_AAC")))),
        )
        openPage(listOf("en"))

        val polish = version("PL - Gladiator II (2024)").attributes
        assertEquals(Known("pl", Provenance.Likely), polish.language)
        assertEquals(Known(emptySet<String>(), Provenance.Confirmed), polish.audioLanguages)
        val dutch = version("NL - Gladiator II (2024)").attributes
        assertEquals(Known("hi", Provenance.Confirmed), dutch.language)
        assertEquals(Known(setOf("hi"), Provenance.Confirmed), dutch.audioLanguages)
    }

    // Grouping on the Versions screen

    @Test fun `the Mandarin group sits with the Viewer's groups, and the German 4K is German only`() = runBlocking {
        syncSpread()

        val details = openPage().let { library.title(movie, english, "en")!! }

        assertEquals(listOf("en", "zh"), details.languages.map { it.language })
        assertEquals(listOf("de"), details.otherLanguages.map { it.language })
        assertEquals("zh", details.originalLanguage)
        val table = versionTable(details.languages + details.otherLanguages, english, details.originalLanguage)
        assertEquals(listOf(null, null, null, "de"), table.groups.flatMap { group -> group.rows.map { it.only } })
        assertTrue(VersionColumn.Audio in table.columns)

        // With the setting off, the Mandarin group is one of the other languages, and nothing is "only".
        val off = library.title(movie, english, "en", preferOriginalAudio = false)!!
        assertEquals(listOf("en"), off.languages.map { it.language })
        assertEquals(listOf("zh", "de"), off.otherLanguages.map { it.language })
        assertNull(off.originalLanguage)
    }
}
