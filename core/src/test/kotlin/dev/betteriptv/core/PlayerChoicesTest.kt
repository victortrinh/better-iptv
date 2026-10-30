package dev.betteriptv.core

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.time.Duration.Companion.seconds

/** What the Viewer picks in the player, remembered for the next file, and the next Episode offered when one ends. */
class PlayerChoicesTest {
    @get:Rule val folder = TemporaryFolder()
    private val provider = FakeProvider()
    private val credentials = InMemoryCredentials()
    private val libraries = mutableListOf<Library>()
    private lateinit var library: Library

    private fun openLibrary() = Library.open(folder.root.resolve("catalog.db").path, credentials).also { libraries += it }

    @Before fun logIn() = runBlocking {
        library = openLibrary()
        library.logIn(provider.login)
        Unit
    }

    @After fun tearDown() {
        libraries.forEach { it.close() }
        provider.close()
    }

    private val movie = TitleId.Tmdb(TitleKind.Movie, 1)
    private val chelseaDetective = TitleId.Tmdb(TitleKind.Show, 156240)
    private val fullHdTv = DeviceProfile(maxHeight = 1080, hdr = emptySet())
    private val english = listOf("en")

    private fun tracks(vararg languages: String?) = languages.map { Track(it) }

    private suspend fun syncMovies() {
        provider.serveMovies("EN - Movie (2025)" to "EN - MOVIES", "FR - Le Film (2025)" to "FR - FILMS")
        library.sync()
    }

    // Remembered choices

    @Test fun `the audio language the Viewer picks plays next time, and its Versions rank first`() = runBlocking {
        syncMovies()
        assertEquals("EN - Movie (2025)", library.playPlan(movie, listOf("en", "fr"), fullHdTv)!!.version.name)

        library.rememberAudio("fr")

        val plan = library.playPlan(movie, listOf("en", "fr"), fullHdTv)!!
        assertEquals("FR - Le Film (2025)", plan.version.name)
        assertEquals(listOf("FR - Le Film (2025)", "EN - Movie (2025)"), plan.versions.map { it.name })
        assertEquals(TrackChoice(audio = 1, subtitle = null), plan.chooseTracks(tracks("eng", "fre"), tracks(), fullHdTv))
        // Choosing a Version by hand still lists the remembered language's first after it.
        val chosen = library.chosenPlayPlan(movie, listingId = 1, listOf("en", "fr"))!!
        assertEquals(listOf("EN - Movie (2025)", "FR - Le Film (2025)"), chosen.versions.map { it.name })
        assertEquals("fr", chosen.remembered.audio)
    }

    @Test fun `remembered choices outlive the app`() = runBlocking {
        library.rememberAudio("fr")
        library.rememberSubtitles("en")
        library.close()

        assertEquals(RememberedTracks(audio = "fr", subtitles = "en"), openLibrary().rememberedTracks())
    }

    @Test fun `the subtitles the Viewer picks turn on next time when the file has them, whatever the audio`() = runBlocking {
        syncMovies()
        library.rememberSubtitles("en")
        val plan = library.playPlan(movie, english, fullHdTv)!!

        assertEquals(TrackChoice(audio = 0, subtitle = 1), plan.chooseTracks(tracks("eng"), tracks("fre", "eng"), fullHdTv))
        // Without them, downloaded ones show instead.
        assertEquals(TrackChoice(audio = 0, subtitle = null, downloaded = "en"), plan.chooseTracks(tracks("eng"), tracks("fre"), fullHdTv))
    }

    @Test fun `turning subtitles off is remembered too, even for a film in another language`() = runBlocking {
        syncMovies()
        assertEquals(TrackChoice(audio = null, subtitle = 0), library.playPlan(movie, english, fullHdTv)!!.chooseTracks(tracks("ger"), tracks("eng"), fullHdTv))

        library.rememberSubtitles(null)

        val plan = library.playPlan(movie, english, fullHdTv)!!
        assertEquals(TrackChoice(audio = null, subtitle = null), plan.chooseTracks(tracks("ger"), tracks("eng"), fullHdTv))
    }

    @Test fun `closed captions the Viewer turns on stay on for the next stream that has them, and outlive the app`() = runBlocking {
        syncMovies()
        val captions = Track(null, CEA_608)
        library.rememberClosedCaptions()

        val plan = library.playPlan(movie, english, fullHdTv)!!
        assertEquals(TrackChoice(audio = null, subtitle = 1), plan.chooseTracks(tracks(null), listOf(Track("eng"), captions), fullHdTv))
        // Without them, nothing shows: captions have no language to download subtitles in.
        assertEquals(TrackChoice(audio = 0, subtitle = null), plan.chooseTracks(tracks("eng"), tracks("eng"), fullHdTv))
        library.close()
        assertEquals(RememberedTracks(closedCaptions = true), openLibrary().rememberedTracks())
    }

    @Test fun `picking subtitles in a language turns closed captions off again`() = runBlocking {
        library.rememberClosedCaptions()
        library.rememberSubtitles("fr")

        assertEquals(RememberedTracks(subtitles = "fr"), library.rememberedTracks())
    }

    @Test fun `a pick made while playing applies to the next file the session opens, through what the Library remembers`() = runBlocking {
        syncMovies()
        val plan = library.playPlan(movie, english, fullHdTv)!!
        library.rememberAudio("fr")

        assertEquals(TrackChoice(audio = 1, subtitle = null), plan.chooseTracks(tracks("eng", "fre"), tracks(), fullHdTv, library.rememberedTracks()))
    }

    // The next Episode

    private suspend fun episodePlan(season: Int, number: Int): PlayPlan {
        library.sync()
        // The Show's details are read once, as its details page does.
        library.episodes(chelseaDetective, english)
        return library.episodePlayPlan(chelseaDetective, EpisodeNumber(season, number), english, fullHdTv) ?: throw AssertionError("no S${season}E$number")
    }

    @Test fun `an Episode plays its Best playable Version`() = runBlocking {
        val plan = episodePlan(1, 1)

        assertEquals(EpisodeNumber(1, 1), plan.episode)
        assertEquals(100001L, plan.version.listingId)
        assertEquals("en", plan.version.attributes.language?.value)
    }

    @Test fun `the next Episode is offered when one ends, then the next season's first`() = runBlocking {
        assertEquals(EpisodeNumber(1, 2), library.nextEpisodePlan(episodePlan(1, 1), fullHdTv)?.episode)
        // Season 1's last Episode in any Version: the Flemish one splits it into more.
        val seasonOneLast = library.episodes(chelseaDetective, english)!!.seasons.first { it.number == 1 }.episodes.last().number
        assertEquals(EpisodeNumber(2, 1), library.nextEpisodePlan(episodePlan(1, seasonOneLast), fullHdTv)?.episode)
    }

    @Test fun `after the Show's last Episode nothing is offered, and a Movie has no next Episode`() = runBlocking {
        library.sync()
        val seasons = library.episodes(chelseaDetective, english)!!.seasons
        val last = seasons.last().episodes.last()

        assertNull(library.nextEpisodePlan(episodePlan(last.season, last.number), fullHdTv))
        syncMovies()
        assertNull(library.nextEpisodePlan(library.playPlan(movie, english, fullHdTv)!!, fullHdTv))
    }

    @Test fun `an Episode streams from the Provider's Shows, and ending it doesn't mark the Show watched`() = runBlocking {
        val plan = episodePlan(1, 1)
        val player = ScriptedPlayer(provider)
        val session = library.playback(plan, player.engines)
        val job = launch { session.play() }

        withTimeout(5_000) { session.state.first { it is PlaybackState.Playing } }
        assertEquals(listOf(100001L to 0L), player.played.toList())
        assertEquals(listOf("series"), player.locations.map { it.substringAfter("//").split('/')[1] })
        player.end()
        withTimeout(5_000) { session.state.first { it == PlaybackState.Ended } }
        job.join()
        assertEquals(emptyList<InProgress>(), (library.homeRows(english, "en").filterIsInstance<HomeRow.ContinueWatching>().flatMap { it.titles }))
    }

    /** Plays [plan] until it reaches [position] of [duration], then leaves, as the Viewer pressing Back would. */
    private suspend fun stopAt(plan: PlayPlan, position: Long, duration: Long? = 45 * 60_000L) = coroutineScope {
        val player = ScriptedPlayer(provider)
        val session = library.playback(plan, player.engines)
        val job = launch { session.play() }
        withTimeout(5_000) { session.state.first { it is PlaybackState.Playing } }
        player.duration = duration
        player.position = position
        job.cancelAndJoin()
    }

    @Test fun `a Show the Viewer never played plays its first Episode`() = runBlocking {
        episodePlan(1, 1)

        val next = library.upNext(chelseaDetective, english, fullHdTv)!!
        assertEquals(EpisodeNumber(1, 1), next.episode)
        assertEquals(0L, next.from)
        assertNull(library.upNext(movie, english, fullHdTv))
    }

    @Test fun `an Episode resumes where the Viewer stopped, and the Show's Play continues it`() = runBlocking {
        stopAt(episodePlan(1, 2), 12 * 60_000L)

        assertEquals(12 * 60_000L, episodePlan(1, 2).from)
        val next = library.upNext(chelseaDetective, english, fullHdTv)!!
        assertEquals(EpisodeNumber(1, 2), next.episode)
        assertEquals(12 * 60_000L, next.from)
        assertEquals(12f / 45, next.progress!!, 0.001f)
        assertEquals(EpisodeProgress(12 * 60_000L, 45 * 60_000L, watched = false), library.episodeProgress(chelseaDetective)[EpisodeNumber(1, 2)])
        // Another Episode starts from its beginning.
        assertEquals(0L, episodePlan(1, 1).from)
        // A Show's Episodes never land in Continue watching, which holds Movies.
        assertEquals(emptyList<InProgress>(), library.homeRows(english, "en").filterIsInstance<HomeRow.ContinueWatching>().flatMap { it.titles })
    }

    @Test fun `once the Viewer finishes an Episode, the Show's Play moves on to the next one, from its start`() = runBlocking {
        stopAt(episodePlan(1, 2), 12 * 60_000L)
        stopAt(episodePlan(1, 2), 42 * 60_000L)

        val next = library.upNext(chelseaDetective, english, fullHdTv)!!
        assertEquals(EpisodeNumber(1, 3), next.episode)
        assertEquals(0L, next.from)
        assertEquals(0L, episodePlan(1, 2).from)
        assertEquals(true, library.episodeProgress(chelseaDetective)[EpisodeNumber(1, 2)]?.watched)
    }

    @Test fun `playing an Episode to its end moves the Show's Play on too, and after the last Episode it starts over`() = runBlocking {
        val seasons = library.run { sync(); episodes(chelseaDetective, english)!!.seasons }
        val last = seasons.last().episodes.last()
        val player = ScriptedPlayer(provider)
        val session = library.playback(episodePlan(last.season, last.number), player.engines)
        val job = launch { session.play() }
        withTimeout(5_000) { session.state.first { it is PlaybackState.Playing } }
        player.end()
        job.join()

        assertEquals(EpisodeNumber(1, 1), library.upNext(chelseaDetective, english, fullHdTv)?.episode)
    }

    @Test fun `an Episode says when its credits begin, and seeking back out of them takes it back`() = runBlocking {
        val plan = episodePlan(1, 1)
        val player = ScriptedPlayer(provider)
        val session = library.playback(plan, player.engines)
        val job = launch { session.play() }
        withTimeout(5_000) { session.state.first { it is PlaybackState.Playing } }

        // 45 minutes: the credits are its last 10%, from 40:30.
        player.duration = 45 * 60_000L
        player.position = 40 * 60_000L
        delay(1_500)
        assertEquals(false, (session.state.value as PlaybackState.Playing).credits)
        player.position = 41 * 60_000L
        withTimeout(5_000) { session.state.first { (it as? PlaybackState.Playing)?.credits == true } }
        player.position = 10 * 60_000L
        withTimeout(5_000) { session.state.first { (it as? PlaybackState.Playing)?.credits == false } }
        job.cancelAndJoin()
    }

    @Test fun `a Movie never says it's in its credits`() = runBlocking {
        syncMovies()
        val player = ScriptedPlayer(provider)
        val session = library.playback(library.playPlan(movie, english, fullHdTv)!!, player.engines)
        val job = launch { session.play() }
        withTimeout(5_000) { session.state.first { it is PlaybackState.Playing } }

        player.duration = 120 * 60_000L
        player.position = 119 * 60_000L
        delay(1_500)
        assertEquals(false, (session.state.value as PlaybackState.Playing).credits)
        job.cancelAndJoin()
    }

    @Test fun `the credits are a file's last 10 percent, or its last 10 minutes when that is less, and unknown without its length`() {
        // A 22-minute Episode: from 19:48.
        assertEquals(false, inCredits(19 * 60_000L + 47_000, 22 * 60_000L))
        assertEquals(true, inCredits(19 * 60_000L + 48_000, 22 * 60_000L))
        // A 3-hour film: 10% would be 18 minutes, so from 2:50.
        assertEquals(false, inCredits(169 * 60_000L, 180 * 60_000L))
        assertEquals(true, inCredits(170 * 60_000L, 180 * 60_000L))
        assertEquals(false, inCredits(60 * 60_000L, null))
        assertEquals(false, inCredits(0, 0))
    }

    @Test fun `the next Episode counts down when the Viewer allows it, three times in a row with no key pressed`() {
        assertEquals(10.seconds, UpNext.COUNTDOWN)
        assertEquals(listOf(true, true, true, false), (0..3).map { UpNext.countsDown(automatic = true, inARow = it) })
        assertEquals(false, UpNext.countsDown(automatic = false, inARow = 0))
    }

    // Refresh rate

    @Test fun `the TV switches to the lowest refresh rate that fits the film, and stays when it already fits`() {
        val rates = listOf(23.976025f, 24f, 25f, 29.97003f, 50f, 59.94006f, 60f)

        assertEquals(23.976025f, refreshRateFor(23.976f, current = 60f, rates))
        assertEquals(24f, refreshRateFor(24f, current = 60f, rates))
        assertEquals(50f, refreshRateFor(25f, current = 60f, listOf(50f, 60f)))
        assertNull(refreshRateFor(29.97f, current = 59.94006f, rates))
        // A TV with only 60 Hz has nothing that fits a 23.976 fps film.
        assertNull(refreshRateFor(23.976f, current = 60f, listOf(60f)))
    }

    @Test fun `the frame rate is measured from frame times rounded to the millisecond, dropped frames and all`() {
        // Matroska's times: 23.976 fps as 41 and 42 ms steps.
        fun mkv(fps: Double, frames: Int, from: Long = 0) = (0 until frames).map { from + Math.round(it * 1000 / fps) * 1000 }

        assertEquals(24000f / 1001, frameRateOf(mkv(24000.0 / 1001, 96)))
        assertEquals(24f, frameRateOf(mkv(24.0, 96)))
        assertEquals(25f, frameRateOf(mkv(25.0, 96, from = 3_600_000_000)))
        assertEquals(50f, frameRateOf(mkv(50.0, 120)))
        // A frame dropped as playback starts is a double step, not a slower film.
        assertEquals(24000f / 1001, frameRateOf(mkv(24000.0 / 1001, 100).filterIndexed { i, _ -> i != 3 && i != 40 }))
        assertNull(frameRateOf(mkv(24.0, 20)))
        assertNull(frameRateOf(mkv(24.0, 96).reversed()))
    }
}
