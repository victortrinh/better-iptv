package dev.betteriptv.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlin.time.Duration

/**
 * A player engine that really opens each location on the [provider], as the stick's player does,
 * and starts once the first bytes arrive. A dead stream, or a Listing in [undecodable], fails; a
 * slow one doesn't start until its bytes come. Tests make it buffer on cue and move its [position].
 */
class ScriptedPlayer(private val provider: FakeProvider) : PlayerEngine {
    /** Listings whose files open but that the player can't decode. */
    val undecodable: MutableSet<Long> = ConcurrentHashMap.newKeySet()

    /** Every location played, with where it started from, in order. */
    val played = CopyOnWriteArrayList<Pair<Long, Duration>>()

    /** Set when the session asked to play while the player still held a file. */
    @Volatile var playedOverAnother = false

    @Volatile override var position: Duration = Duration.ZERO

    @Volatile private var current: Playing? = null

    private inner class Playing(val events: (PlayerEvent) -> Unit) {
        @Volatile var request: HttpURLConnection? = null
        @Volatile var stopped = false

        fun report(event: PlayerEvent) {
            if (!stopped) events(event)
        }
    }

    override fun play(location: String, from: Duration, events: (PlayerEvent) -> Unit) {
        if (current != null) playedOverAnother = true
        val listingId = location.substringAfterLast('/').substringBefore('.').toLong()
        played += listingId to from
        position = from
        val playing = Playing(events)
        current = playing
        thread(isDaemon = true) {
            try {
                val request = URI(location).toURL().openConnection() as HttpURLConnection
                playing.request = request
                if (playing.stopped) return@thread request.disconnect()
                if (request.responseCode != 200 || request.inputStream.read() < 0) return@thread playing.report(PlayerEvent.Failed)
                playing.report(if (listingId in undecodable) PlayerEvent.Failed else PlayerEvent.Started)
            } catch (_: Exception) {
                playing.report(PlayerEvent.Failed)
            }
        }
    }

    /** Reports that playing stalled to fill its buffer, as a slow Provider makes it. */
    fun buffer() = current?.report(PlayerEvent.Buffering)

    /** Reports that the file stopped mid-film, as a Provider dropping the stream makes it. */
    fun fail() = current?.report(PlayerEvent.Failed)

    override suspend fun stop() {
        val playing = current ?: return
        current = null
        playing.stopped = true
        withContext(Dispatchers.IO) { playing.request?.disconnect() }
        // The stick's player returns once its request is closed; wait until the Provider has seen it go.
        withTimeout(5_000) { while (provider.openStreamCount > 0) delay(10) }
    }
}
