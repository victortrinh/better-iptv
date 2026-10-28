package dev.betteriptv.core

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Live Channels through the Library: Clutter hidden at Sync, search, Favourites on the home screen, and watching. */
class ChannelsTest {
    @get:Rule val folder = TemporaryFolder()
    private val provider = FakeProvider()
    private lateinit var library: Library

    @Before fun logIn() = runBlocking {
        library = Library.open(folder.root.resolve("catalog.db").path, InMemoryCredentials())
        library.logIn(provider.login)
        Unit
    }

    @After fun tearDown() {
        library.close()
        provider.close()
    }

    private val english = listOf("en")

    private suspend fun channels(query: String) = library.search(query, english, "en").last().channels.map { it.name }

    private suspend fun channel(name: String) = library.search(name, english, "en").last().channels.first { it.name == name }

    private suspend fun favouritesRow() = library.homeRows(english, "en").filterIsInstance<HomeRow.Favourites>().singleOrNull()?.channels?.map { it.name }

    /** How a Provider names the start of a Game [ago] before now, in its `(2026-10-29 18:00:30)` style. */
    private fun startedAgo(ago: Duration) = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").format((Instant.now() - ago).atOffset(ZoneOffset.UTC))

    // Clutter

    @Test fun `separator rows, empty and finished Event slots, and radio are hidden`() = runBlocking {
        val onNow = "Arena: Jets vs Oilers (${startedAgo(Duration.ofHours(5))})"
        val later = "Arena: Bills vs Chiefs (${startedAgo(Duration.ofHours(-20))})"
        val next = "Next | Arena: Leafs vs Habs | 2026-01-02 | 01:00 (GMT)"
        provider.serveChannels(
            "####### ARENA PPV #######" to "US| ARENA PPV",
            "- NO EVENT STREAMING - | 8K EXCLUSIVE | US: ARENA PPV 25" to "US| ARENA PPV",
            "US (Arena 526) |  (2098-12-31 08:00:34)" to "US| ARENA PPV",
            "End | Arena: Rockets vs. Spurs | 2026-10-20 | 01:00 (GMT)" to "US| ARENA PPV",
            "Arena: Rockets vs. Spurs ENDED" to "US| ARENA PPV",
            "Arena: Lakers vs Celtics (${startedAgo(Duration.ofHours(7))})" to "US| ARENA PPV",
            onNow to "US| ARENA PPV",
            later to "US| ARENA PPV",
            next to "US| ARENA PPV",
            "Arena Hits" to "NL| RADIO LIVE",
            "UK: ARENA RADIO 2" to "UK| GENERAL",
            "T2: ARENA FM" to "NL| GENERAL",
            "T2: ARENA FM TV" to "NL| GENERAL",
            "US: ARENA SPORTS HD" to "US| SOCCER PPV",
            ":ARENA NO  95" to "NO| ARENA PPV",
        )
        library.sync()

        // An Event 5 hours in is still on, Sports replay hints mean nothing live, and a numbered slot may carry a Game.
        assertEquals(listOf(onNow, later, next, "T2: ARENA FM TV", "US: ARENA SPORTS HD", ":ARENA NO  95"), channels("arena"))
    }

    @Test fun `a Channel without an Event in its name is never finished`() = runBlocking {
        provider.serveChannels("US: 24/7 MARVEL 4" to "US| 24/7 PPV", "UK: TNT SPORTS EVENT 12" to "UK| TNT SPORT EVENT")
        library.sync()

        assertEquals(listOf("US: 24/7 MARVEL 4"), channels("marvel"))
        assertEquals(listOf("UK: TNT SPORTS EVENT 12"), channels("tnt"))
    }

    // Search

    @Test fun `Channels are found by name, ignoring accents, case and small typos`() = runBlocking {
        provider.serveChannels("CA FR: ICI RADIO-CANADA TÉLÉ MONTRÉAL HD" to "CA| GENERAL FR", "CA: SPORTSNET ONE HD" to "CA| SPORTS")
        library.sync()

        assertEquals(listOf("CA FR: ICI RADIO-CANADA TÉLÉ MONTRÉAL HD"), channels("montreal"))
        assertEquals(listOf("CA: SPORTSNET ONE HD"), channels("sportsent"))
        assertEquals(emptyList<String>(), channels("hbo"))
    }

    @Test fun `Channels come with Movies and Shows in one search`() = runBlocking {
        provider.serveMovies("EN - Sportsnet Story (2020)" to "EN - MOVIES")
        provider.serveChannels("CA: SPORTSNET ONE HD" to "CA| SPORTS")
        library.sync()

        val results = library.search("sportsnet", english, "en").last()
        assertEquals(listOf("Sportsnet Story"), results.movies.map { it.name })
        assertEquals(listOf("CA: SPORTSNET ONE HD"), results.channels.map { it.name })
    }

    @Test fun `Favourites lead equally close matches, the rest keep the Provider's order`() = runBlocking {
        provider.serveChannels("US: ESPN HD" to "US| SPORT", "US: ESPN 2 HD" to "US| SPORT", "US: ESPN NEWS" to "US| SPORT")
        library.sync()
        library.setFavourite(channel("US: ESPN NEWS").id, true)

        assertEquals(listOf("US: ESPN NEWS", "US: ESPN HD", "US: ESPN 2 HD"), channels("espn"))
        assertTrue(channel("US: ESPN NEWS").favourite)
        assertFalse(channel("US: ESPN HD").favourite)
    }

    // Favourites

    @Test fun `Favourites show as a home row, in the order they were added`() = runBlocking {
        provider.serveChannels("US: ESPN HD" to "US| SPORT", "CA: TSN 1" to "CA| SPORT", "UK: BBC ONE" to "UK| GENERAL")
        library.sync()
        assertEquals(null, favouritesRow())

        library.setFavourite(channel("UK: BBC ONE").id, true)
        library.setFavourite(channel("US: ESPN HD").id, true)

        assertEquals(listOf("UK: BBC ONE", "US: ESPN HD"), favouritesRow())
        // With nothing to continue, it's the first row.
        assertTrue(library.homeRows(english, "en").first() is HomeRow.Favourites)
    }

    @Test fun `Favourites follow Continue watching`() = runBlocking {
        provider.serveMovies("EN - Started (2020)" to "EN - MOVIES")
        provider.serveChannels("US: ESPN HD" to "US| SPORT")
        library.sync()
        library.setFavourite(channel("US: ESPN HD").id, true)
        library.saveProgress(TitleId.Tmdb(TitleKind.Movie, 1), position = 10 * 60_000L, duration = 100 * 60_000L)

        val rows = library.homeRows(english, "en")
        assertTrue(rows[0] is HomeRow.ContinueWatching)
        assertTrue(rows[1] is HomeRow.Favourites)
    }

    @Test fun `a Favourite removed leaves the home row`() = runBlocking {
        provider.serveChannels("US: ESPN HD" to "US| SPORT", "CA: TSN 1" to "CA| SPORT")
        library.sync()
        library.setFavourite(channel("US: ESPN HD").id, true)
        library.setFavourite(channel("CA: TSN 1").id, true)

        library.setFavourite(channel("US: ESPN HD").id, false)

        assertEquals(listOf("CA: TSN 1"), favouritesRow())
        assertFalse(channel("US: ESPN HD").favourite)
    }

    @Test fun `Favourites outlive Syncs, and are away only while the Provider drops the Channel`() = runBlocking {
        provider.serveChannels("US: ESPN HD" to "US| SPORT", "CA: TSN 1" to "CA| SPORT")
        library.sync()
        library.setFavourite(channel("CA: TSN 1").id, true)

        library.sync()
        assertEquals(listOf("CA: TSN 1"), favouritesRow())

        provider.serveChannels("US: ESPN HD" to "US| SPORT")
        library.sync()
        assertEquals(null, favouritesRow())

        provider.serveChannels("US: ESPN HD" to "US| SPORT", "CA: TSN 1" to "CA| SPORT")
        library.sync()
        assertEquals(listOf("CA: TSN 1"), favouritesRow())
    }

    // Watching

    private val player by lazy { ScriptedPlayer(provider) }

    private suspend fun watch(name: String): Pair<PlaybackSession, kotlinx.coroutines.Job> = kotlinx.coroutines.coroutineScope {
        val session = library.playback(library.channelPlayPlan(channel(name), english), player.engines)
        session to kotlinx.coroutines.GlobalScope.launch { session.play() }
    }

    private suspend fun PlaybackSession.awaitPlaying() = withTimeout(5_000) { state.first { it is PlaybackState.Playing } as PlaybackState.Playing }

    @Test fun `a Channel plays its MPEG-TS stream through the Viewer's one connection`() = runBlocking {
        provider.serveChannels("US: ESPN HD" to "US| SPORT")
        library.sync()
        val (session, job) = watch("US: ESPN HD")

        val playing = session.awaitPlaying()
        assertEquals("ts", playing.version.liveFormat)
        assertTrue(player.locations.single().contains("/live/") && player.locations.single().endsWith("/1.ts"))
        // While it plays, nothing else may take the connection.
        assertEquals(null, kotlinx.coroutines.withTimeoutOrNull(500) { library.connect(playing.version) })
        job.cancelAndJoin()

        assertEquals(0, provider.openStreamCount)
        withTimeout(2_000) { library.connect(playing.version) }.close()
    }

    @Test fun `when the Provider sends no MPEG-TS, the Channel plays as HLS`() = runBlocking {
        provider.serveChannels("US: ESPN HD" to "US| SPORT")
        provider.deadLiveFormats += "ts"
        library.sync()
        val (session, job) = watch("US: ESPN HD")

        assertEquals("m3u8", session.awaitPlaying().version.liveFormat)
        assertTrue(player.locations.last().endsWith("/1.m3u8"))
        assertEquals(0, provider.refusedStreams)
        job.cancelAndJoin()
    }

    @Test fun `watching a Channel leaves nothing to continue`() = runBlocking {
        provider.serveChannels("US: ESPN HD" to "US| SPORT")
        library.sync()
        val (session, job) = watch("US: ESPN HD")
        session.awaitPlaying()
        player.position = 30 * 60_000L
        job.cancelAndJoin()

        assertTrue(library.homeRows(english, "en").none { it is HomeRow.ContinueWatching })
    }
}
