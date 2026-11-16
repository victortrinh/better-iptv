package dev.betteriptv.core

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.ClassRule
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/*
 * The tests that need real sockets by nature, on the JVM only (#580): Android's relay is a local server
 * that reads the Provider with OkHttp, and gzip is OkHttp's doing. Each runs against the fake Provider
 * as a real local server (OverSockets), through Android's own network (okHttpNetwork). The playback
 * tests are the shared ones, run a second time: what they assert of a fake relay holds of the real one.
 */

/** [PlaybackTest] through Android's relay ([LocalStreamRelay]) and OkHttp, on real sockets. */
class PlaybackOverSocketsTest : PlaybackTest() {
    override val transport: FakeTransport get() = OverSockets

    companion object {
        @ClassRule @JvmField val sockets = RealSockets()
    }
}

/** [BackupsTest] through Android's relay ([LocalStreamRelay]) and OkHttp, on real sockets. */
class BackupsOverSocketsTest : BackupsTest() {
    override val transport: FakeTransport get() = OverSockets

    companion object {
        @ClassRule @JvmField val sockets = RealSockets()
    }
}

/** The fake Provider holds its Connection limit as a local server too, which is how an emulator's app reaches it. */
class FakeProviderOverSocketsTest : FakeProviderTest() {
    override val transport: FakeTransport get() = OverSockets

    companion object {
        @ClassRule @JvmField val sockets = RealSockets()
    }
}

/** Sync through Android's network: what only OkHttp does for it. */
class SyncOverSocketsTest : SocketTest() {
    @get:Rule val folder = TemporaryFolder()
    private val provider = FakeProvider(OverSockets)
    private val library by lazy { Library.open(folder.root.resolve("catalog.db").path, InMemoryCredentials(), network = OverSockets.network()) }

    @After fun tearDown() {
        library.close()
        provider.close()
    }

    // SyncTest's test of the same name, with the one thing it can't see without OkHttp: the catalog is asked for gzipped.
    @Test fun `Sync never opens a stream, so it works while one is playing`() = runBlocking {
        library.logIn(provider.login)
        provider.openStream(1107).use { playing ->
            playing.read(ByteArray(1024))

            val status = library.sync()

            assertTrue(status is SyncStatus.Synced)
        }
        assertEquals(1, provider.streamRequests)
        assertEquals(0, provider.refusedStreams)
        assertEquals(0, provider.requestsWithoutGzip)
    }
}
