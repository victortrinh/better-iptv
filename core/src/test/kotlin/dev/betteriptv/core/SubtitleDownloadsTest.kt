package dev.betteriptv.core

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Downloaded subtitles: when the player offers and turns them on, and what the Library fetches from the cloud. */
class SubtitleDownloadsTest {
    @get:Rule val folder = TemporaryFolder()
    private val provider = FakeProvider()
    private val cloud = FakeCloud()
    private val credentials = InMemoryCredentials()
    private lateinit var library: Library

    @Before fun logIn() = runBlocking {
        library = Library.open(folder.root.resolve("catalog.db").path, credentials, cloud.url)
        library.logIn(provider.login)
        provider.serveMovies("EN - Movie (2025)" to "EN - MOVIES")
        library.sync()
        Unit
    }

    @After fun tearDown() {
        library.close()
        provider.close()
        cloud.close()
    }

    private val movie = TitleId.Numbered(TitleKind.Movie, 1)
    private val fullHdTv = DeviceProfile(maxHeight = 1080, hdr = emptySet())

    private fun tracks(vararg languages: String?) = languages.map { Track(it) }

    private suspend fun plan(contentLanguages: List<String>) = library.playPlan(movie, contentLanguages, fullHdTv)!!

    @Test fun `downloads are offered in each Content language the file has no subtitles in`() = runBlocking {
        val plan = plan(listOf("en", "fr"))

        assertEquals(listOf("fr"), plan.downloadLanguages(tracks("eng")))
        assertEquals(listOf("en", "fr"), plan.downloadLanguages(tracks("ger")))
        assertEquals(emptyList<String>(), plan.downloadLanguages(tracks("fre", "eng")))
        // Audio in a Content language plays without subtitles, downloaded or not.
        assertEquals(TrackChoice(audio = 0, subtitle = null), plan.chooseTracks(tracks("eng"), tracks(), fullHdTv))
    }

    @Test fun `downloaded subtitles turn on by themselves when no audio is in a Content language and the file lacks them`() = runBlocking {
        val plan = plan(listOf("en", "fr"))

        assertEquals(TrackChoice(audio = null, subtitle = null, downloaded = "en"), plan.chooseTracks(tracks("ger"), tracks("ger"), fullHdTv))
        // Embedded ones win.
        assertEquals(TrackChoice(audio = null, subtitle = 1), plan.chooseTracks(tracks("ger"), tracks("ger", "eng"), fullHdTv))
    }

    @Test fun `the subtitles the Viewer last picked are downloaded when the file lacks them, unless they turned subtitles off`() = runBlocking {
        library.rememberSubtitles("fr")
        val plan = plan(listOf("en"))

        assertEquals(TrackChoice(audio = 0, subtitle = null, downloaded = "fr"), plan.chooseTracks(tracks("eng"), tracks("eng"), fullHdTv))
        assertEquals(listOf("fr", "en"), plan.subtitleLanguages())

        library.rememberSubtitles(null)
        assertEquals(TrackChoice(audio = null, subtitle = null), plan(listOf("en")).chooseTracks(tracks("ger"), tracks(), fullHdTv, library.rememberedTracks()))
    }

    @Test fun `the Library finds and downloads a Title's subtitles through the cloud`() = runBlocking {
        val offered = cloud.subtitle(1, "fr", "Movie.2025.WEB")
        cloud.subtitle(1, "de", "German")

        val offers = library.subtitleOffers(plan(listOf("en")), listOf("fr"))

        assertEquals(listOf(SubtitleOffer(offered.id, "fr", "Movie.2025.WEB", hearingImpaired = false)), offers)
        assertEquals(offered.text, library.downloadSubtitle(offers.single())!!.text)
        assertNull(library.downloadSubtitle(SubtitleOffer("opensubtitles:fr:404", "fr", "gone", false)))
    }

    @Test fun `a subtitle ripped from the Version's Source is suggested, not moved or shown by itself`() = runBlocking {
        cloud.subtitle(1, "en", "Movie.2025.1080p.BluRay.x264")
        val netflix = cloud.subtitle(1, "en", "Movie.2025.1080p.NF.WEB-DL.DDP5.1")
        val amazon = cloud.subtitle(1, "en", "Movie 2025 2160p AMZN WEB-DL")
        val french = cloud.subtitle(1, "fr", "Movie.2025.NF.WEB")
        val fromNetflix = plan(listOf("en", "fr")).withSource("Netflix")

        // The cloud's order stands: the file shown by itself is still its first in the language.
        val offers = library.subtitleOffers(fromNetflix, listOf("en", "fr"))
        assertEquals(listOf("Movie.2025.1080p.BluRay.x264", netflix.release, amazon.release, french.release), offers.map { it.release })
        assertEquals(setOf(netflix.id, french.id), suggestedSubtitles(fromNetflix, offers))
        assertEquals(setOf(amazon.id), suggestedSubtitles(fromNetflix.withSource("Prime Video"), offers))
        assertEquals(emptySet<String>(), suggestedSubtitles(plan(listOf("en")), offers))
        assertEquals(emptySet<String>(), suggestedSubtitles(fromNetflix.withSource("Universal"), offers))
    }

    @Test fun `the suggested file is offered even when it is further down than the first three`() {
        val offers = (1..5).map { SubtitleOffer("opensubtitles:en:$it", "en", if (it == 5) "Movie.NF.WEB" else "Movie.WEB.$it", false) } +
            SubtitleOffer("opensubtitles:fr:6", "fr", "Film.WEB", false)

        assertEquals(listOf(1, 2, 3, 5, 6), offeredSubtitles(offers, listOf("en", "fr"), setOf("opensubtitles:en:5")).map { it.id.substringAfterLast(':').toInt() })
        assertEquals(listOf(1, 2, 3), offeredSubtitles(offers, listOf("en"), emptySet()).map { it.id.substringAfterLast(':').toInt() })
    }

    @Test fun `a Source's tag counts only as a whole word of the release`() {
        assertEquals(true, fromSource("The.Movie.2025.720p.NF.WEB-DL", "Netflix"))
        assertEquals(true, fromSource("Movie (2025) [Blu-ray] 1080p", "Blu-ray"))
        assertEquals(true, fromSource("Movie.2025.DSNP.WEB-DL", "Marvel"))
        assertEquals(false, fromSource("Mad.Max.Fury.Road.2015.1080p.WEB-DL", "Max"))
        assertEquals(false, fromSource("Movie.2025.NFO.WEB", "Netflix"))
        assertEquals(false, fromSource("Movie.2025.WEB", "Universal"))
    }

    @Test fun `a subtitle Track tagged with no language is in the one its name says`() = runBlocking {
        assertEquals("en", Track("und", label = "English SDH").spokenLanguage)
        assertEquals("fr", Track(null, label = "Français (forcés)").spokenLanguage)
        assertEquals("de", Track("und", label = "German").spokenLanguage)
        // The tag wins over the name, and a name naming no language says nothing.
        assertEquals("fr", Track("fre", label = "English").spokenLanguage)
        assertNull(Track("und", label = "SDH").spokenLanguage)
        assertNull(Track("und", label = "Track 2").spokenLanguage)
        assertNull(Track("und").spokenLanguage)

        // So a file whose only English subtitles are untagged but named needs no download.
        val plan = plan(listOf("en"))
        assertEquals(emptyList<String>(), plan.downloadLanguages(listOf(Track("und", label = "English"))))
        assertEquals(listOf("en"), plan.downloadLanguages(listOf(Track("und"))))
    }

    @Test fun `without the cloud, or a Title number, the Viewer plays on without downloads`() = runBlocking {
        cloud.subtitle(1, "fr", "Movie.2025.WEB")
        cloud.down = true
        assertEquals(emptyList<SubtitleOffer>(), library.subtitleOffers(plan(listOf("en")), listOf("fr")))

        cloud.down = false
        val unmatched = plan(listOf("en")).copy(titleId = TitleId.Unmatched(TitleKind.Movie, 1))
        cloud.received.clear()
        assertEquals(emptyList<SubtitleOffer>(), library.subtitleOffers(unmatched, listOf("fr")))
        assertEquals(emptyList<FakeCloud.Received>(), cloud.received.toList())
    }

    private fun PlayPlan.withSource(source: String) = copy(version = version.copy(attributes = version.attributes.copy(source = Known(source, Provenance.Likely))))
}
