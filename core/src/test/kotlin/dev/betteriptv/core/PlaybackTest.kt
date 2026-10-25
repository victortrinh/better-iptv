package dev.betteriptv.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/** A Playback session: falling back to Backups, the buffering suggestion, and switching Version mid-film. */
class PlaybackTest {
    @get:Rule val folder = TemporaryFolder()
    private val provider = FakeProvider()
    private val player = ScriptedPlayer(provider)
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

    @After fun neverOverTheConnectionLimit() {
        assertEquals("the Provider refused a stream", 0, provider.refusedStreams)
        assertFalse("a stream opened before the last one was closed", player.playedOverAnother)
    }

    private val movie = TitleId.Tmdb(TitleKind.Movie, 1)
    private val tv = DeviceProfile(maxHeight = 2160, hdr = emptySet())

    /** A 4K English Version with two Backups (2 and 3), and a lower English Version (4). */
    private suspend fun syncWithBackups() {
        provider.serveMovies(
            "4K-EN - Movie (2025)" to "4K MOVIES",
            "4K-EN - Movie (2025) " to "4K MOVIES",
            "4K-EN - Movie (2025)  " to "4K MOVIES",
            "EN - Movie (2025)" to "EN - MOVIES",
        )
        library.sync()
    }

    /** Starts a session for the Best playable Version; it runs until the test cancels it. */
    private suspend fun CoroutineScope.play(startTimeout: Duration = 300.milliseconds): Pair<PlaybackSession, Job> {
        val session = library.playback(library.playPlan(movie, listOf("en"), tv)!!, player, startTimeout)
        return session to launch { session.run() }
    }

    private suspend fun PlaybackSession.awaitPlaying(listingId: Long): PlaybackState.Playing = withTimeout(5_000) {
        state.first { it is PlaybackState.Playing && it.version.listingId == listingId } as PlaybackState.Playing
    }

    private suspend fun PlaybackSession.awaitState(predicate: (PlaybackState) -> Boolean) = withTimeout(5_000) { state.first(predicate) }

    // Backups

    @Test fun `a dead primary Version falls back to a Backup`() = runBlocking {
        syncWithBackups()
        provider.deadStreams += 1
        val (session, job) = play()

        session.awaitPlaying(2)
        assertEquals(listOf(1L, 2L), player.played.map { it.first })
        job.cancelAndJoin()
    }

    @Test fun `a Version that doesn't start in time is closed and the next Backup is tried`() = runBlocking {
        syncWithBackups()
        provider.slowStreams += 1
        provider.slowStreamDelayMillis = 2_000
        val (session, job) = play(startTimeout = 300.milliseconds)

        session.awaitPlaying(2)
        assertEquals(listOf(1L, 2L), player.played.map { it.first })
        job.cancelAndJoin()
    }

    @Test fun `a Version the player can't decode gives way to the next Backup`() = runBlocking {
        syncWithBackups()
        player.undecodable += setOf(1L, 2L)
        val (session, job) = play()

        session.awaitPlaying(3)
        job.cancelAndJoin()
    }

    @Test fun `while a Version is being tried, the Viewer sees which one`() = runBlocking {
        syncWithBackups()
        provider.slowStreams += 1
        val (session, job) = play(startTimeout = 2_000.milliseconds)

        val starting = session.awaitState { it is PlaybackState.Starting } as PlaybackState.Starting
        assertEquals(1L, starting.version.listingId)
        job.cancelAndJoin()
    }

    @Test fun `when every Version is dead the Viewer gets a clear error, and nothing is left open`() = runBlocking {
        syncWithBackups()
        provider.deadStreams += setOf(1L, 2L, 3L)
        val (session, job) = play()

        assertEquals(PlaybackState.Failed, session.awaitState { it == PlaybackState.Failed })
        // Backups are the same language and quality only: the lower Version isn't tried.
        assertEquals(listOf(1L, 2L, 3L), player.played.map { it.first })
        assertEquals(0, provider.openStreamCount)
        withTimeout(1_000) { library.connect(session.plan.version) }.close()
        job.cancelAndJoin()
    }

    @Test fun `when every Version is dead the Viewer can still pick another one`() = runBlocking {
        syncWithBackups()
        provider.deadStreams += setOf(1L, 2L, 3L)
        val (session, job) = play()
        session.awaitState { it == PlaybackState.Failed }

        session.switchTo(session.plan.versions.single { it.listingId == 4L })

        session.awaitPlaying(4)
        job.cancelAndJoin()
    }

    @Test fun `a Version that stops mid-film gives way to a Backup at the same position`() = runBlocking {
        syncWithBackups()
        val (session, job) = play()
        session.awaitPlaying(1)

        player.position = 42.minutes
        player.fail()

        session.awaitPlaying(2)
        assertEquals(2L to 42.minutes, player.played.last())
        job.cancelAndJoin()
    }

    @Test fun `ending the session closes the stream and gives the connection back`() = runBlocking {
        syncWithBackups()
        val (session, job) = play()
        session.awaitPlaying(1)
        job.cancelAndJoin()

        assertEquals(0, provider.openStreamCount)
        withTimeout(1_000) { library.connect(session.plan.version) }.close()
    }

    // Buffering

    @Test fun `repeated buffering suggests a lower Version, and never switches by itself`() = runBlocking {
        syncWithBackups()
        val (session, job) = play()
        session.awaitPlaying(1)

        repeat(3) { player.buffer() }

        val suggested = session.awaitState { it is PlaybackState.Playing && it.lowerVersion != null } as PlaybackState.Playing
        assertEquals(4L, suggested.lowerVersion!!.listingId)
        delay(500)
        assertEquals(listOf(1L), player.played.map { it.first })
        assertEquals(1L, (session.state.value as PlaybackState.Playing).version.listingId)
        job.cancelAndJoin()
    }

    @Test fun `buffering once or twice suggests nothing`() = runBlocking {
        syncWithBackups()
        val (session, job) = play()
        session.awaitPlaying(1)

        repeat(2) { player.buffer() }
        delay(300)

        assertNull((session.state.value as PlaybackState.Playing).lowerVersion)
        job.cancelAndJoin()
    }

    @Test fun `without a lower Version in the same language, buffering suggests nothing`() = runBlocking {
        provider.serveMovies(
            "EN - Movie (2025)" to "EN - MOVIES",
            "4K-FR - Le Film (2025)" to "4K MOVIES",
        )
        library.sync()
        val (session, job) = play()
        session.awaitPlaying(1)

        repeat(5) { player.buffer() }
        delay(300)

        assertNull((session.state.value as PlaybackState.Playing).lowerVersion)
        job.cancelAndJoin()
    }

    @Test fun `taking the suggestion switches to the lower Version at the same position`() = runBlocking {
        syncWithBackups()
        val (session, job) = play()
        session.awaitPlaying(1)
        player.position = 12.minutes
        repeat(3) { player.buffer() }
        val lower = (session.awaitState { it is PlaybackState.Playing && it.lowerVersion != null } as PlaybackState.Playing).lowerVersion!!

        session.switchTo(lower)

        session.awaitPlaying(4)
        assertEquals(4L to 12.minutes, player.played.last())
        job.cancelAndJoin()
    }

    // Switching Version

    @Test fun `a Versions switch changes Version mid-film and resumes at the same position`() = runBlocking {
        provider.serveMovies(
            "4K-EN - Movie (2025)" to "4K MOVIES",
            "4K-FR - Le Film (2025)" to "4K MOVIES",
        )
        library.sync()
        val (session, job) = play()
        session.awaitPlaying(1)
        player.position = 61.minutes
        val french = session.plan.versions.single { it.listingId == 2L }

        session.switchTo(french)

        session.awaitPlaying(2)
        assertEquals(listOf(1L to Duration.ZERO, 2L to 61.minutes), player.played.toList())
        job.cancelAndJoin()
    }

    @Test fun `a switched-to Version that is dead falls back to its own Backups`() = runBlocking {
        provider.serveMovies(
            "4K-EN - Movie (2025)" to "4K MOVIES",
            "FR - Le Film (2025)" to "FR - FILMS",
            "FR - Le Film (2025) " to "FR - FILMS",
        )
        library.sync()
        provider.deadStreams += 2
        val (session, job) = play()
        session.awaitPlaying(1)
        player.position = 5.minutes

        session.switchTo(session.plan.versions.single { it.listingId == 2L })

        session.awaitPlaying(3)
        assertEquals(3L to 5.minutes, player.played.last())
        job.cancelAndJoin()
    }

    @Test fun `the play plan offers every Version of the Title to switch to, the Viewer's languages first`() = runBlocking {
        provider.serveMovies(
            "FR - Le Film (2025)" to "FR - FILMS",
            "4K-EN - Movie (2025)" to "4K MOVIES",
            "4K-EN - Movie (2025) " to "4K MOVIES",
            "EN - Movie (2025)" to "EN - MOVIES",
        )
        library.sync()

        val plan = library.playPlan(movie, listOf("en"), tv)!!
        assertEquals(listOf(2L, 4L, 1L), plan.versions.map { it.listingId })
        assertEquals(listOf(3L), plan.versions.first().backups.map { it.listingId })
    }
}
