package dev.betteriptv.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList

/** The Playback session: which player engine plays a Version, and moving between Media3 and VLC. */
class PlaybackTest {
    @get:Rule val folder = TemporaryFolder()
    private val provider = FakeProvider()
    private lateinit var library: Library
    private lateinit var plan: PlayPlan

    @Before fun setUp() = runBlocking {
        library = Library.open(folder.root.resolve("catalog.db").path, InMemoryCredentials())
        library.logIn(provider.login)
        provider.movieCategoriesOverride = """[{"category_id":"1","category_name":"EN - MOVIES"}]"""
        provider.movieListOverride =
            """[{"stream_id":1,"name":"EN - Movie (2025)","tmdb":"1","category_id":"1","added":"1700000000","container_extension":"mkv"}]"""
        library.sync()
        plan = library.playPlan(TitleId.Tmdb(TitleKind.Movie, 1), listOf("en"), DeviceProfile(maxHeight = 1080, hdr = emptySet()))!!
    }

    @After fun tearDown() {
        library.close()
        provider.close()
    }

    /** What each engine does with the next file it's given, by engine. */
    private val scripts = mutableMapOf(Engine.Media3 to Script.Plays, Engine.Vlc to Script.Plays)

    /** Every engine the session created, in order. */
    private val created = CopyOnWriteArrayList<ScriptedEngine>()

    private fun session() = library.playback(plan) { engine -> ScriptedEngine(engine, scripts.getValue(engine)).also(created::add) }

    private suspend fun PlaybackSession.awaitPlaying(engine: Engine, count: Int): ScriptedEngine = withTimeout(5_000) {
        state.first { it is PlaybackState.Playing && it.player.engine == engine && created.size == count }
        created.last().also { it.opened.first { opened -> opened } }
    }

    @Test fun `a Version plays on Media3 first`() = runBlocking {
        val session = session()
        val job = launch { session.play() }

        session.awaitPlaying(Engine.Media3, count = 1)
        assertEquals(listOf(Engine.Media3), created.map { it.engine })
        job.cancel()
    }

    @Test fun `when Media3 can't open a Version, the VLC engine takes over`() = runBlocking {
        scripts[Engine.Media3] = Script.CantOpen
        val session = session()
        val job = launch { session.play() }

        val vlc = session.awaitPlaying(Engine.Vlc, count = 2)
        assertEquals(0L, vlc.startedFrom)
        // Media3 let go of the file before VLC opened it, so the Provider never refused a stream.
        assertTrue(created.first().released)
        assertEquals(0, provider.refusedStreams)
        assertEquals(2, provider.streamRequests)
        job.cancel()
    }

    @Test fun `when the VLC engine can't open it either, playback fails`() = runBlocking {
        scripts[Engine.Media3] = Script.CantOpen
        scripts[Engine.Vlc] = Script.CantOpen
        val session = session()
        val job = launch { session.play() }

        withTimeout(5_000) { session.state.first { it is PlaybackState.Failed } }
        assertEquals(listOf(Engine.Media3, Engine.Vlc), created.map { it.engine })
        assertTrue(created.all { it.released })
        job.cancel()
    }

    @Test fun `a broken stream fails playback without trying the other player`() = runBlocking {
        scripts[Engine.Media3] = Script.Breaks
        val session = session()
        val job = launch { session.play() }

        withTimeout(5_000) { session.state.first { it is PlaybackState.Failed } }
        assertEquals(listOf(Engine.Media3), created.map { it.engine })
        job.cancel()
    }

    @Test fun `Try other player switches engines and resumes at the same position`() = runBlocking {
        val session = session()
        val job = launch { session.play() }
        val media3 = session.awaitPlaying(Engine.Media3, count = 1)
        media3.position = 42 * 60_000L

        session.tryOtherPlayer()
        val vlc = session.awaitPlaying(Engine.Vlc, count = 2)
        assertEquals(42 * 60_000L, vlc.startedFrom)
        assertTrue(media3.released)

        // And back again.
        vlc.position = 43 * 60_000L
        session.tryOtherPlayer()
        assertEquals(43 * 60_000L, session.awaitPlaying(Engine.Media3, count = 3).startedFrom)
        assertEquals(0, provider.refusedStreams)
        job.cancel()
    }

    @Test fun `after a failure, Try other player tries again on the other engine`() = runBlocking {
        scripts[Engine.Media3] = Script.Breaks
        val session = session()
        val job = launch { session.play() }
        withTimeout(5_000) { session.state.first { it is PlaybackState.Failed } }

        session.tryOtherPlayer()
        session.awaitPlaying(Engine.Vlc, count = 2)
        job.cancel()
    }

    @Test fun `a file that plays to the end ends the session`() = runBlocking {
        scripts[Engine.Media3] = Script.Ends
        val session = session()

        withTimeout(5_000) { session.play() }
        assertEquals(PlaybackState.Ended, session.state.value)
        assertTrue(created.single().released)
    }

    @Test fun `the VLC engine waits its turn at the same connection gate`() = runBlocking {
        val session = session()
        val job = launch { session.play() }
        val media3 = session.awaitPlaying(Engine.Media3, count = 1)
        // Something else queues for the Viewer's one connection while Media3 holds it.
        val other = async { library.connect(plan.version) }
        delay(100)

        media3.failure.complete(CantOpen("scripted: Media3 can't decode the file"))
        val held = withTimeout(2_000) { other.await() }
        delay(300)
        // Waiting for the connection: no engine has the file yet.
        assertEquals(PlaybackState.Starting(plan.version), session.state.value)
        assertEquals(1, created.size)

        held.close()
        session.awaitPlaying(Engine.Vlc, count = 2)
        assertEquals(0, provider.refusedStreams)
        job.cancel()
        job.join()
        // Leaving playback gives the connection back.
        withTimeout(1_000) { library.connect(plan.version) }.close()
    }

    @Test fun `an engine that overlaps its requests, like VLC, still uses one Provider stream and never sees the login`() = runBlocking {
        val session = library.playback(plan) { engine -> ScriptedEngine(engine, Script.Plays, overlapsRequests = true).also(created::add) }
        val job = launch { session.play() }

        val engine = session.awaitPlaying(Engine.Media3, count = 1)
        assertEquals(0, provider.refusedStreams)
        assertEquals(2, provider.streamRequests)
        assertFalse(provider.username in engine.location!!)
        assertFalse(provider.password in engine.location!!)
        job.cancel()
        job.join()
        withTimeout(2_000) { library.connect(plan.version) }.close()
    }

    private val device = DeviceProfile(maxHeight = 1080, hdr = emptySet())

    private suspend fun nextPlan() = library.playPlan(plan.titleId!!, listOf("en"), device)!!

    /** Plays [plan] until it reaches [position] of [duration], then leaves, as the Viewer pressing Back would. */
    private suspend fun stopAt(position: Long, duration: Long? = null) = coroutineScope {
        created.clear()
        val session = session()
        val job = launch { session.play() }
        val engine = session.awaitPlaying(Engine.Media3, count = 1)
        engine.position = position
        engine.duration = duration
        job.cancel()
        job.join()
    }

    @Test fun `Play resumes where the Viewer stopped`() = runBlocking {
        stopAt(42 * 60_000L)

        plan = nextPlan()
        assertEquals(42 * 60_000L, plan.from)
        created.clear()
        val session = session()
        val job = launch { session.play() }
        assertEquals(42 * 60_000L, session.awaitPlaying(Engine.Media3, count = 1).startedFrom)
        job.cancel()
    }

    @Test fun `Play resumes from the Version the Viewer chose too`() = runBlocking {
        stopAt(42 * 60_000L)

        assertEquals(42 * 60_000L, library.chosenPlayPlan(plan.titleId!!, plan.version.listingId, listOf("en"))!!.from)
    }

    @Test fun `stopping in the first minute doesn't record a resume point`() = runBlocking {
        stopAt(30_000L)

        assertEquals(0L, nextPlan().from)
    }

    @Test fun `stopping in the credits marks the Title watched, so Play starts over`() = runBlocking {
        stopAt(115 * 60_000L, duration = 120 * 60_000L)

        assertEquals(0L, nextPlan().from)
        assertTrue(library.homeRows(listOf("en"), "en").none { it is HomeRow.ContinueWatching })
    }

    @Test fun `stopping as a film's credits start, 10 minutes from the end, marks it watched`() = runBlocking {
        stopAt(110 * 60_000L, duration = 120 * 60_000L)

        assertEquals(0L, nextPlan().from)
    }

    @Test fun `stopping just before the last 10 minutes keeps the resume point`() = runBlocking {
        stopAt(109 * 60_000L, duration = 120 * 60_000L)

        assertEquals(109 * 60_000L, nextPlan().from)
    }

    @Test fun `a short film counts as watched in its last tenth`() = runBlocking {
        stopAt(27 * 60_000L, duration = 30 * 60_000L)
        assertEquals(0L, nextPlan().from)

        stopAt(26 * 60_000L, duration = 30 * 60_000L)
        assertEquals(26 * 60_000L, nextPlan().from)
    }

    @Test fun `removing a Movie from Continue watching forgets where the Viewer stopped`() = runBlocking {
        stopAt(42 * 60_000L, duration = 120 * 60_000L)

        library.removeFromContinueWatching(plan.titleId!!)

        assertEquals(0L, nextPlan().from)
        assertTrue(library.homeRows(listOf("en"), "en").none { it is HomeRow.ContinueWatching })
    }

    @Test fun `Restart plays from the beginning, and keeps the old resume point until the first minute passes`() = runBlocking {
        stopAt(42 * 60_000L, duration = 120 * 60_000L)
        plan = nextPlan().fromStart()

        stopAt(30_000L, duration = 120 * 60_000L)
        assertEquals(0L, created.single().startedFrom)
        assertEquals(42 * 60_000L, nextPlan().from)

        plan = nextPlan().fromStart()
        stopAt(5 * 60_000L, duration = 120 * 60_000L)
        assertEquals(5 * 60_000L, nextPlan().from)
    }

    @Test fun `playing to the end marks the Title watched`() = runBlocking {
        stopAt(42 * 60_000L)
        plan = nextPlan()
        scripts[Engine.Media3] = Script.Ends

        session().play()

        assertEquals(0L, nextPlan().from)
        assertTrue(library.homeRows(listOf("en"), "en").none { it is HomeRow.ContinueWatching })
    }

    private enum class Script { Plays, CantOpen, Breaks, Ends }

    /**
     * A player engine that opens the stream like a real one would, then does what its [script] says.
     * One that plays fails on cue when its [failure] is completed.
     */
    private inner class ScriptedEngine(
        override val engine: Engine,
        private val script: Script,
        override val overlapsRequests: Boolean = false,
    ) : PlayerEngine {
        @Volatile override var position = 0L
        @Volatile override var duration: Long? = null
        @Volatile var startedFrom: Long? = null
        @Volatile var released = false
        val opened = MutableStateFlow(false)
        val failure = CompletableDeferred<Throwable>()
        @Volatile var location: String? = null
        private val streams = CopyOnWriteArrayList<InputStream>()

        override suspend fun play(location: String, from: Long, events: (PlayerEvent) -> Unit) {
            startedFrom = from
            position = from
            this.location = location
            withContext(Dispatchers.IO) {
                streams += provider.play(location).also { it.readNBytes(1024) }
                // Like VLC reading the file's index at its end: a second request before closing the first.
                if (overlapsRequests) streams += ranged(location, "bytes=1000000-").also { it.readNBytes(1024) }
            }
            opened.value = true
            if (script != Script.CantOpen) events(PlayerEvent.Started)
            when (script) {
                Script.Plays -> throw failure.await()
                Script.CantOpen -> throw CantOpen("scripted: $engine can't open the file")
                Script.Breaks -> throw IOException("scripted: the stream broke")
                Script.Ends -> return
            }
        }

        override suspend fun release() {
            released = true
            streams.forEach(InputStream::close)
        }
    }

    private fun ranged(location: String, range: String): InputStream {
        val connection = URI(location).toURL().openConnection() as HttpURLConnection
        connection.setRequestProperty("Range", range)
        if (connection.responseCode !in 200..299) throw IllegalStateException("stream refused: HTTP ${connection.responseCode}")
        return connection.inputStream
    }
}
