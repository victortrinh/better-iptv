package dev.betteriptv.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel as Seen
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.time.Duration.Companion.seconds

/**
 * A Title's page and a Show's Episode picker as the Core hands them ([TitlePageScreen], #590), asked
 * of the Library as an app asks for a screen ([Library.screen]): the actions and what each will do,
 * the Version the badges tell of, the Versions and the Episodes with the one to open on, and their
 * updates as live state, which arrive without the content being asked for again.
 */
class TitlePageScreenTest {
    @get:Rule val folder = TemporaryFolder()
    private val provider = FakeProvider()
    private val cloud = FakeCloud()
    private val player = ScriptedPlayer(provider)
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

    private val english = listOf("en")
    private val fullHdTv = DeviceProfile(maxHeight = 1080, hdr = emptySet())
    private val movie = TitleId.Numbered(TitleKind.Movie, 1)
    private val bureau = TitleId.Numbered(TitleKind.Show, 62104)

    private fun page(id: TitleId) = TitlePageScreen(id, english, appLanguage = "en") { fullHdTv }

    /** The page once its Versions are checked: what it settles on while it shows, with nothing left to Header read. */
    private suspend fun settled(id: TitleId): TitlePageContent = withTimeout(20.seconds) { library.screen(page(id)).first { it.checked } }

    /** Syncs one Movie with an English Version (Listing 1) and a French one (Listing 2), and no Show. */
    private suspend fun syncMovie(first: String = "EN - Movie (2025)") {
        provider.movieCategoriesOverride = """[{"category_id":"1","category_name":"EN - MOVIES"},{"category_id":"2","category_name":"FR - FILMS"}]"""
        provider.movieListOverride = """[{"stream_id":1,"name":"$first","tmdb":"1","category_id":"1","added":"1700000001","container_extension":"mkv"},""" +
            """{"stream_id":2,"name":"FR - Le Film (2025)","tmdb":"1","category_id":"2","added":"1700000000","container_extension":"mkv"}]"""
        provider.showCategoriesOverride = "[]"
        provider.showListOverride = "[]"
        library.sync()
    }

    /** Syncs The Bureau: seasons 1 and 2 of three Episodes each, the first named "Pilot", and one special. */
    private suspend fun syncShow(episodes: List<Pair<Int, Int>> = listOf(1 to 1, 1 to 2, 1 to 3, 2 to 1, 2 to 2, 2 to 3, 0 to 1)) {
        provider.showCategoriesOverride = """[{"category_id":"1","category_name":"SERIES"}]"""
        provider.showListOverride = """[{"series_id":1,"name":"EN - The Bureau (2015)","tmdb":"62104","category_id":"1","last_modified":"1700000000"}]"""
        provider.showDetailsOverride[1] = episodes.mapIndexed { i, (season, number) -> episode(100L + i, season, number) }
            .joinToString(",", """{"seasons":[],"info":{},"episodes":[""", "]}")
        library.sync()
    }

    private fun episode(id: Long, season: Int, number: Int) =
        """{"id":"$id","episode_num":$number,"season":$season,"title":"EN - The Bureau (2015) - S0${season}E0$number${if (season == 1 && number == 1) " - Pilot" else ""}",""" +
            """"container_extension":"mkv","added":"1700000000","info":{"video":{"width":1920,"height":1080},"audio":{"tags":{"language":"eng"}}}}"""

    /** Plays S[season]E[number] of The Bureau to [minutes] of 50, as Playback records it. */
    private suspend fun playEpisode(season: Int, number: Int, minutes: Int) {
        library.episodes(bureau, english)
        val plan = library.episodePlayPlan(bureau, EpisodeNumber(season, number), english, fullHdTv) ?: throw AssertionError("no S${season}E$number")
        library.saveProgress(plan, position = minutes * 60_000L, duration = 50 * 60_000L)
        // Each play is later than the last.
        Thread.sleep(5)
    }

    private fun TitlePageContent.actionKinds() = actions.map { it::class.simpleName }

    private inline fun <reified Action : TitleAction> TitlePageContent.action(): Action = actions.filterIsInstance<Action>().single()

    /** A page as an app holds it while the screen shows: collected once, each content it is handed kept in order. */
    private inner class Watched(scope: CoroutineScope, id: TitleId) {
        private val seen = Seen<TitlePageContent>(Seen.UNLIMITED)
        private val collecting = scope.launch(Dispatchers.Default) { library.screen(page(id)).collect { seen.send(it) } }

        /** The first content the page is handed, from now on, that [shows] what is waited for, with those handed before it. */
        suspend fun until(shows: (TitlePageContent) -> Boolean): List<TitlePageContent> = withTimeout(20.seconds) {
            val handed = mutableListOf(seen.receive())
            while (!shows(handed.last())) handed += seen.receive()
            handed
        }

        suspend fun nextThat(shows: (TitlePageContent) -> Boolean): TitlePageContent = until(shows).last()

        suspend fun leave() = collecting.cancelAndJoin()
    }

    // A Movie's page

    @Test fun `a Movie never played plays from its start, the Best playable Version, with its Versions to choose from`() = runBlocking {
        syncMovie()

        val content = settled(movie)

        assertEquals("Movie", content.title.name)
        assertEquals(listOf("PlayFromStart", "Versions"), content.actionKinds())
        val plan = content.play.plan!!
        assertEquals("EN - Movie (2025)", plan.version.name)
        assertEquals(0L, plan.from)
        // The badges tell of the Version Play starts, and the Versions action names it.
        assertEquals(plan.version, content.best)
        assertEquals(TitleAction.Versions(plan.version, count = 2), content.action<TitleAction.Versions>())
        assertNull(content.nothingToPlay)
        // The Versions screen lists the Movie's, the Viewer's language first; a Movie has no Episode picker.
        val versions = content.versions!!
        assertNull(versions.episode)
        assertEquals(listOf("en"), versions.languages.map { it.language })
        assertEquals(listOf("fr"), versions.otherLanguages.map { it.language })
        assertEquals(english, versions.contentLanguages)
        assertNull(content.episodes)
        assertFalse(content.waitingForEpisodes)
    }

    @Test fun `a Movie stopped partway continues from there, with Restart and Remove from Continue watching`() = runBlocking {
        syncMovie()
        library.saveProgress(movie, position = 30 * 60_000L, duration = 120 * 60_000L)

        val content = settled(movie)

        assertEquals(listOf("Continue", "Restart", "RemoveFromContinueWatching", "Versions"), content.actionKinds())
        val plan = content.play.plan!!
        assertEquals(30 * 60_000L, plan.from)
        assertEquals(0.25f, plan.progress)
        // Restart plays the same Version from its beginning.
        assertEquals(plan.fromStart(), content.action<TitleAction.Restart>().plan)
        assertEquals(0L, content.action<TitleAction.Restart>().plan.from)
    }

    @Test fun `a Movie the Viewer Watched plays from its start again`() = runBlocking {
        syncMovie()
        // In its credits, it counts as Watched.
        library.saveProgress(movie, position = 119 * 60_000L, duration = 120 * 60_000L)

        val content = settled(movie)

        assertEquals(listOf("PlayFromStart", "Versions"), content.actionKinds())
        assertEquals(0L, content.play.plan!!.from)
    }

    @Test fun `a Movie with no Version the TV can play has nothing to start, and the page says why`() = runBlocking {
        provider.movieCategoriesOverride = """[{"category_id":"1","category_name":"EN - MOVIES"}]"""
        provider.movieListOverride = """[{"stream_id":1,"name":"4K-EN - Movie (2025)","tmdb":"1","category_id":"1","added":"1700000001","container_extension":"mkv"}]"""
        library.sync()

        val content = settled(movie)

        // Its only Version is 4K, which this Full HD TV can't display.
        assertEquals(TitleAction.PlayFromStart(null), content.play)
        assertEquals(NothingToPlay.NoVersion, content.nothingToPlay)
        // With nothing to play, the badges tell of the best Version there is, and the Versions action names none.
        assertEquals("4K-EN - Movie (2025)", content.best?.name)
        assertEquals(TitleAction.Versions(null, count = 1), content.action<TitleAction.Versions>())
    }

    @Test fun `a Movie with a trailer has a Trailer action before Versions, and a Kids page neither`() = runBlocking {
        syncMovie()
        provider.serveMovieDetails(1, plot = "A film.", trailer = "dQw4w9WgXcQ")

        val content = settled(movie)

        assertEquals(listOf("PlayFromStart", "Trailer", "Versions"), content.actionKinds())
        assertEquals("dQw4w9WgXcQ", content.action<TitleAction.Trailer>().key)

        // The same page on a Kids profile: Play alone, the Best playable Version as on any profile.
        val kids = titlePageContent(content.title, content.play.plan, null, english, kids = true)
        assertEquals(listOf("PlayFromStart"), kids.actionKinds())
        assertNull(kids.title.trailer)
        assertNull(kids.versions)
        assertEquals(content.play.plan, kids.play.plan)
    }

    @Test fun `nothing is handed for a Title the catalog doesn't have`() = runBlocking {
        syncMovie()

        assertNull(withTimeoutOrNull(500) { library.screen(page(TitleId.Numbered(TitleKind.Movie, 999))).first() })
    }

    // A Show's page and its Episode picker

    @Test fun `a Show nothing was played of plays its first Episode, and the picker opens on it`() = runBlocking {
        syncShow()

        val content = settled(bureau)

        assertEquals(listOf("PlayEpisode", "Episodes", "Versions"), content.actionKinds())
        val play = content.play as TitleAction.PlayEpisode
        assertEquals(EpisodeNumber(1, 1), play.episode)
        assertEquals("Pilot", play.name)
        assertEquals(EpisodeStart.First, play.start)
        assertEquals(EpisodeNumber(1, 1), play.plan.episode)
        assertEquals(0L, play.plan.from)
        // The Episodes action says the season the Show starts in and how many Episodes it has.
        assertEquals(EpisodesPlace.NotStarted(season = 1, episodes = 3), content.action<TitleAction.Episodes>().place)
        // The picker lists the Episodes the Provider has, specials last, and opens on the first.
        val picker = content.episodes!!
        assertEquals(listOf(1, 2, 0), picker.seasons.map { it.number })
        assertEquals(listOf(3, 3, 1), picker.seasons.map { it.episodes.size })
        assertEquals(EpisodeNumber(1, 1), picker.next)
        assertTrue(picker.seasons.flatMap { it.episodes }.all { it.progress == null })
        // The Versions are those of the Episode Play starts.
        assertEquals(EpisodeNumber(1, 1), content.versions!!.episode)
        assertEquals("Pilot", content.versions!!.episodeName)
        assertEquals(play.plan.version, content.best)
    }

    @Test fun `a Show stopped in an Episode continues it, with Restart, and the picker opens on it`() = runBlocking {
        syncShow()
        playEpisode(1, 1, minutes = 48)
        playEpisode(1, 2, minutes = 20)

        val content = settled(bureau)

        // An Episode's place is kept with the Show: there is nothing to take out of Continue watching.
        assertEquals(listOf("PlayEpisode", "Restart", "Episodes", "Versions"), content.actionKinds())
        val play = content.play as TitleAction.PlayEpisode
        assertEquals(EpisodeNumber(1, 2), play.episode)
        assertNull(play.name)
        assertEquals(EpisodeStart.StoppedIn, play.start)
        assertEquals(20 * 60_000L, play.plan.from)
        assertEquals(0.4f, play.plan.progress)
        assertEquals(play.plan.fromStart(), content.action<TitleAction.Restart>().plan)
        assertEquals(EpisodesPlace.At(EpisodeNumber(1, 2)), content.action<TitleAction.Episodes>().place)
        val picker = content.episodes!!
        assertEquals(EpisodeNumber(1, 2), picker.next)
        val season = picker.seasons.first().episodes
        assertEquals(listOf(1f, 0.4f, null), season.map { it.progress })
        assertEquals(listOf(true, false, false), season.map { it.watched })
        assertEquals(EpisodeNumber(1, 2), content.versions!!.episode)
    }

    @Test fun `a Show whose last played Episode is Watched starts the next one, and the picker opens on it`() = runBlocking {
        syncShow()
        playEpisode(1, 1, minutes = 48)
        playEpisode(1, 2, minutes = 48)
        playEpisode(1, 3, minutes = 48)

        val content = settled(bureau)

        // After a season's last Episode comes the next season's first, from its start.
        assertEquals(listOf("PlayEpisode", "Episodes", "Versions"), content.actionKinds())
        val play = content.play as TitleAction.PlayEpisode
        assertEquals(EpisodeNumber(2, 1), play.episode)
        assertEquals(EpisodeStart.Next, play.start)
        assertEquals(0L, play.plan.from)
        assertEquals(EpisodesPlace.At(EpisodeNumber(2, 1)), content.action<TitleAction.Episodes>().place)
        assertEquals(EpisodeNumber(2, 1), content.episodes!!.next)
        assertEquals(EpisodeNumber(2, 1), content.versions!!.episode)
    }

    @Test fun `a Show the Provider has no Episode of has nothing to play and no Episodes or Versions action`() = runBlocking {
        syncShow(episodes = emptyList())

        val content = settled(bureau)

        assertEquals(listOf("PlayFromStart"), content.actionKinds())
        assertNull(content.play.plan)
        assertEquals(NothingToPlay.NoEpisodes, content.nothingToPlay)
        assertNull(content.versions)
        assertEquals(emptyList<Season>(), content.episodes!!.seasons)
    }

    @Test fun `a Show's page whose Episodes are still read has its actions waiting for them`() {
        val details = TitleDetails(bureau, "The Bureau", 2015, image = null, languages = emptyList(), otherLanguages = emptyList())

        val content = titlePageContent(details, plan = null, episodes = null, contentLanguages = english, checked = false)

        // Play starts nothing yet, the Episodes and Versions actions open nothing yet, and their wait is no reason to give.
        assertTrue(content.waitingForEpisodes)
        assertEquals(listOf(TitleAction.PlayFromStart(null), TitleAction.Episodes(null), TitleAction.Versions(null, null)), content.actions)
        assertNull(content.nothingToPlay)
        assertNull(content.versions)
        assertFalse(content.checked)
    }

    // Live state

    @Test fun `the page changes when playback stops, without being asked for again`() = runBlocking {
        syncMovie()
        val watched = Watched(this, movie)
        val before = watched.nextThat { it.checked }
        assertEquals(listOf("PlayFromStart", "Versions"), before.actionKinds())

        // The Viewer plays the Movie to half an hour in, then leaves the player.
        player.duration = 120 * 60_000L
        val session = library.playback(before.play.plan!!, player.engines)
        val playing = launch { session.play() }
        withTimeout(10.seconds) { session.state.first { it is PlaybackState.Playing } }
        player.position = 30 * 60_000L
        playing.cancelAndJoin()

        val after = watched.nextThat { it.play is TitleAction.Continue }
        assertEquals(listOf("Continue", "Restart", "RemoveFromContinueWatching", "Versions"), after.actionKinds())
        assertEquals(30 * 60_000L, after.play.plan!!.from)
        watched.leave()
    }

    @Test fun `the page changes when the Movie is taken out of Continue watching`() = runBlocking {
        syncMovie()
        library.saveProgress(movie, position = 30 * 60_000L, duration = 120 * 60_000L)
        val watched = Watched(this, movie)
        watched.nextThat { it.checked && it.play is TitleAction.Continue }

        library.removeFromContinueWatching(movie)

        val after = watched.nextThat { it.play is TitleAction.PlayFromStart }
        assertEquals(listOf("PlayFromStart", "Versions"), after.actionKinds())
        assertEquals(0L, after.play.plan!!.from)
        watched.leave()
    }

    @Test fun `the page changes when a Header read Confirms a Version, and names no quality until its Versions are checked`() = runBlocking {
        // Its name says 4K; its file, read while the page shows, holds a Full HD picture.
        syncMovie(first = "4K-EN - Movie (2025)")
        provider.movieFiles[1] = matroskaFile(listOf(FileTrack("audio", "eng", "A_EAC3", channels = 6)), width = 1920, height = 1080)
        provider.movieFiles[2] = matroskaFile(listOf(FileTrack("audio", "fre", "A_AC3")), width = 1920, height = 1080)
        assertFalse(library.versionsChecked(movie, english))
        val watched = Watched(this, movie)

        val handed = watched.until { it.checked }

        // Until the reads end, the page names no Version's quality: a guess they would then correct.
        handed.dropLast(1).forEach { assertNull(it.action<TitleAction.Versions>().playing) }
        // Once they have, the badges and the Versions action tell what the file holds: the TV plays it, so Play starts it.
        val checked = handed.last()
        assertFalse(checked.checking)
        assertEquals(Known(Quality.FullHD, Provenance.Confirmed), checked.best!!.attributes.quality)
        assertEquals(Known("en", Provenance.Confirmed), checked.best!!.attributes.language)
        assertEquals(checked.best, checked.action<TitleAction.Versions>().playing)
        assertEquals(checked.best, checked.play.plan!!.version)
        assertTrue(library.versionsChecked(movie, english))
        watched.leave()
    }

    @Test fun `a Show's page changes when a Header read Confirms an Episode's Version`() = runBlocking {
        syncShow(episodes = listOf(1 to 1))
        // The Provider's details say Full HD; the file holds 4K the TV can't display.
        provider.episodeFiles[100] = matroskaFile(listOf(FileTrack("audio", "eng", "A_AC3")), width = 3840, height = 2160)
        val watched = Watched(this, bureau)

        val checked = watched.nextThat { it.checked }

        val version = checked.versions!!.languages.single().versions.single()
        assertEquals(Known(Quality.UHD, Provenance.Confirmed), version.attributes.quality)
        // Nothing the TV can play is left: Play starts nothing, and the badges tell of the Show's best Version.
        assertNull(checked.play.plan)
        assertEquals(NothingToPlay.NoVersion, checked.nothingToPlay)
        assertEquals(checked.title.languages.first().versions.first(), checked.best)
        assertEquals(null, checked.action<TitleAction.Versions>().playing)
        watched.leave()
    }
}
