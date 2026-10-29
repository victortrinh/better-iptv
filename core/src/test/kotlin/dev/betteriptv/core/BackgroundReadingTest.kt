package dev.betteriptv.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.time.Duration.Companion.milliseconds

/** Background reading: Header reads of the Versions the Viewer is about to see, slowly, while nothing plays. */
class BackgroundReadingTest {
    @get:Rule val folder = TemporaryFolder()
    private val provider = FakeProvider()
    private val cloud = FakeCloud()
    private val credentials = InMemoryCredentials()
    private lateinit var library: Library
    private val clock = TestClock(Instant.parse("2026-10-28T14:00:00Z"))
    private var reading: Job? = null

    @Before fun logIn() = runBlocking {
        library = open()
        library.logIn(provider.login)
        provider.showListOverride = "[]"
        Unit
    }

    @After fun tearDown() = runBlocking {
        reading?.cancelAndJoin()
        library.close()
        provider.close()
        cloud.close()
    }

    private fun open() = Library.open(folder.root.resolve("catalog.db").path, credentials, cloud.url)

    private val english = listOf("en")
    private val fullHdTv = DeviceProfile(maxHeight = 1080, hdr = emptySet())

    private fun movie(n: Long) = TitleId.Tmdb(TitleKind.Movie, n)

    /** Syncs [count] English MKV Movies, newest first: Listing n is Movie n, with a readable file. */
    private suspend fun syncMovies(count: Int) {
        provider.movieCategoriesOverride = """[{"category_id":"1","category_name":"EN - MOVIES"}]"""
        provider.movieListOverride = (1..count).joinToString(",", "[", "]") { n ->
            """{"stream_id":$n,"name":"EN - Movie $n (2025)","tmdb":"$n","category_id":"1","added":"${1_700_000_000 - n}","container_extension":"mkv"}"""
        }
        (1L..count).forEach { provider.movieFiles[it] = matroskaFile(listOf(FileTrack("audio", "eng", "A_AAC")), width = 3840, height = 2160) }
        library.sync()
    }

    /** Starts Background reading at a test pace: a few milliseconds between reads, [perDay] a day. */
    private fun startReading(perDay: Int = 100, on: Library = library) {
        reading = CoroutineScope(Dispatchers.Default).launch {
            on.readInBackground(english, BackgroundPace(gap = 20.milliseconds, perDay = perDay), clock, offlinePause = Duration.ofMinutes(15))
        }
    }

    /** The movie Listings read so far, in order. */
    private fun read() = provider.ranges.map { it.substringBefore(':').toLong() }

    private suspend fun awaitReads(count: Int) = withTimeout(10_000) { while (read().size < count) delay(10) }

    /** Lets Background reading run for a while, enough for many reads at the test pace. */
    private suspend fun idle() = delay(400)

    @Test fun `cards on screen are read first, then Continue watching, then new arrivals`() = runBlocking {
        syncMovies(5)
        library.saveProgress(movie(4), position = 10 * 60_000L, duration = 100 * 60_000L)
        library.onScreen(listOf(movie(5), movie(3)))

        startReading()
        awaitReads(5)

        assertEquals(listOf(5L, 3L, 4L, 1L, 2L), read())
        idle()
        // Everything is Confirmed: nothing is read twice.
        assertEquals(5, read().size)
    }

    @Test fun `cards that come on screen go ahead of the rest`() = runBlocking {
        syncMovies(30)
        startReading()
        awaitReads(1)

        library.onScreen(listOf(movie(25)))

        withTimeout(10_000) { while (25L !in read()) delay(10) }
        assertTrue("25 waited behind ${read()}", read().indexOf(25L) < 6)
    }

    @Test fun `nothing is read while a Version plays, and reading resumes after`() = runBlocking {
        syncMovies(3)
        val plan = library.playPlan(movie(1), english, fullHdTv)!!

        library.connect(plan.version).use { connection ->
            val playing = async(Dispatchers.IO) { provider.play(connection.location).use { it.read(ByteArray(1024)) } }
            startReading()
            idle()
            assertEquals(emptyList<Long>(), read())
            playing.await()
        }

        awaitReads(3)
        assertEquals(0, provider.refusedStreams)
    }

    @Test fun `a refusal stops reading until the next day, even after the app restarts`() = runBlocking {
        syncMovies(5)
        provider.refuseRangesAfter = 1

        startReading()
        withTimeout(10_000) { while (provider.streamRequests < 2) delay(10) }
        idle()
        // One read, then the refused one: nothing more today.
        assertEquals(listOf(1L), read())
        assertEquals(2, provider.streamRequests)

        reading!!.cancelAndJoin()
        library.close()
        library = open()
        startReading()
        idle()
        assertEquals(2, provider.streamRequests)

        provider.refuseRangesAfter = null
        clock.now = Instant.parse("2026-10-29T00:00:01Z")
        awaitReads(5)
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), read())
    }

    @Test fun `a dead Version is passed over, not taken for a refusal`() = runBlocking {
        syncMovies(3)
        provider.deadStatus = 400
        provider.deadStreams += 1L

        startReading()
        awaitReads(2)
        idle()

        assertEquals(listOf(2L, 3L), read())
        // Asked once: it stays unread, never asked again.
        assertEquals(3, provider.streamRequests)
    }

    @Test fun `a dead Version on a details page doesn't stop its other Versions' reads`() = runBlocking {
        provider.movieCategoriesOverride = """[{"category_id":"1","category_name":"EN - MOVIES"},{"category_id":"2","category_name":"FR - FILMS"}]"""
        provider.movieListOverride = """[
            {"stream_id":1,"name":"EN - Movie (2025)","tmdb":"7","category_id":"1","added":"1700000000","container_extension":"mkv"},
            {"stream_id":2,"name":"FR - Movie (2025)","tmdb":"7","category_id":"2","added":"1700000000","container_extension":"mkv"}
        ]"""
        provider.movieFiles[2] = matroskaFile(listOf(FileTrack("audio", "fre", "A_AAC")), width = 1920, height = 1080)
        library.sync()
        provider.deadStatus = 405
        provider.deadStreams += 1L

        library.readHeaders(movie(7), english) {}

        assertEquals(listOf(2L), read())
    }

    @Test fun `a Provider that doesn't answer pauses reading for a while, not for the day`() = runBlocking {
        syncMovies(3)
        provider.unansweredFiles = true

        startReading()
        withTimeout(10_000) { while (provider.streamRequests == 0) delay(10) }
        // OkHttp tries a dropped request twice.
        delay(100)
        val tried = provider.streamRequests
        idle()
        assertEquals(tried, provider.streamRequests)

        provider.unansweredFiles = false
        clock.now += Duration.ofMinutes(16)
        awaitReads(3)
        assertEquals(listOf(1L, 2L, 3L), read())
    }

    @Test fun `reading stops for the day after the day's reads`() = runBlocking {
        syncMovies(5)

        startReading(perDay = 2)
        awaitReads(2)
        idle()
        assertEquals(2, read().size)

        clock.now = Instant.parse("2026-10-29T00:00:01Z")
        awaitReads(4)
        idle()
        assertEquals(4, read().size)
    }

    @Test fun `a read that Confirms a Version updates its card`() = runBlocking {
        syncMovies(1)
        // Its Prefix says nothing of the picture.
        assertEquals(null, library.cardBest(movie(1), english)!!.quality)
        val confirmed = async { library.confirmations.first() }

        startReading()

        assertEquals(movie(1), withTimeout(10_000) { confirmed.await() })
        assertEquals(Known(Quality.UHD, Provenance.Confirmed), library.cardBest(movie(1), english)!!.quality)
    }

    @Test fun `the details page's reads come before Background reading`() = runBlocking {
        syncMovies(3)
        provider.slowFiles = true
        startReading()
        withTimeout(10_000) { while (provider.openStreamCount == 0) delay(10) }

        library.readHeaders(movie(3), english) {}

        assertTrue("Movie 3 wasn't read by its details page: ${read()}", 3L in read())
        assertEquals(0, provider.refusedStreams)
    }

    /** A clock the test moves by hand, in UTC. */
    private class TestClock(@Volatile var now: Instant) : Clock() {
        override fun instant(): Instant = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
    }
}
