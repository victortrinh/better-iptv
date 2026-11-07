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

    private val movie = TitleId.Tmdb(TitleKind.Movie, 1)
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

    @Test fun `a subtitle ripped from the Version's Source comes first in its language`() = runBlocking {
        cloud.subtitle(1, "en", "Movie.2025.1080p.BluRay.x264")
        val netflix = cloud.subtitle(1, "en", "Movie.2025.1080p.NF.WEB-DL.DDP5.1")
        val amazon = cloud.subtitle(1, "en", "Movie 2025 2160p AMZN WEB-DL")
        val fromNetflix = plan(listOf("en")).let { it.copy(version = it.version.copy(attributes = it.version.attributes.copy(source = Known("Netflix", Provenance.Likely)))) }

        val offers = library.subtitleOffers(fromNetflix, listOf("en")).map { it.release }
        assertEquals(listOf(netflix.release, "Movie.2025.1080p.BluRay.x264", amazon.release), offers)

        val fromAmazon = fromNetflix.copy(version = fromNetflix.version.copy(attributes = fromNetflix.version.attributes.copy(source = Known("Prime Video", Provenance.Likely))))
        assertEquals(amazon.release, library.subtitleOffers(fromAmazon, listOf("en")).first().release)
        // Without a Source, the cloud's order stands.
        assertEquals("Movie.2025.1080p.BluRay.x264", library.subtitleOffers(plan(listOf("en")), listOf("en")).first().release)
    }

    @Test fun `a Source's tag counts only as a whole word of the release`() {
        assertEquals(true, fromSource("The.Movie.2025.720p.NF.WEB-DL", "Netflix"))
        assertEquals(true, fromSource("Movie (2025) [Blu-ray] 1080p", "Blu-ray"))
        assertEquals(true, fromSource("Movie.2025.DSNP.WEB-DL", "Marvel"))
        assertEquals(false, fromSource("Mad.Max.Fury.Road.2015.1080p.WEB-DL", "Max"))
        assertEquals(false, fromSource("Movie.2025.NFO.WEB", "Netflix"))
        assertEquals(false, fromSource("Movie.2025.WEB", "Universal"))
    }

    @Test fun `without the cloud, or a TMDB ID, the Viewer plays on without downloads`() = runBlocking {
        cloud.subtitle(1, "fr", "Movie.2025.WEB")
        cloud.down = true
        assertEquals(emptyList<SubtitleOffer>(), library.subtitleOffers(plan(listOf("en")), listOf("fr")))

        cloud.down = false
        val unmatched = plan(listOf("en")).copy(titleId = TitleId.Unmatched(TitleKind.Movie, 1))
        cloud.received.clear()
        assertEquals(emptyList<SubtitleOffer>(), library.subtitleOffers(unmatched, listOf("fr")))
        assertEquals(emptyList<FakeCloud.Received>(), cloud.received.toList())
    }
}
