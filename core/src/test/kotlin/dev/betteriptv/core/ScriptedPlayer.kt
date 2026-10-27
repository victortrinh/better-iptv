package dev.betteriptv.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * Player engines that really open each location on the [provider], as the stick's do, and start once
 * the first bytes arrive. A dead stream fails; a Listing in [undecodable] is one neither engine can
 * open; a slow one doesn't start until its bytes come. Tests make the engine playing buffer or break
 * on cue, and move its [position].
 */
class ScriptedPlayer(private val provider: FakeProvider) {
    /** Listings whose files open but that no engine can decode. */
    val undecodable: MutableSet<Long> = ConcurrentHashMap.newKeySet()

    /** Every location played, with where it started from in milliseconds, in order. */
    val played = CopyOnWriteArrayList<Pair<Long, Long>>()

    /** Every location played, in order. */
    val locations = CopyOnWriteArrayList<String>()

    /** Set when the session asked an engine to play while another still held a file. */
    @Volatile var playedOverAnother = false

    @Volatile private var current: Scripted? = null

    /** Where the engine playing is in the file, in milliseconds. */
    var position: Long
        get() = current?.position ?: 0
        set(value) {
            current!!.position = value
        }

    val engines: (Engine) -> PlayerEngine = { Scripted(it) }

    /** Reports that playing stalled to fill its buffer, as a slow Provider makes it. */
    fun buffer() = current?.report(PlayerEvent.Buffering)

    /** Breaks the stream partway through, as a Provider dropping the connection does. */
    fun fail() = current?.failure?.complete(IOException("scripted: the stream broke"))

    /** Plays the file to its end. */
    fun end() = current?.failure?.complete(null)

    private inner class Scripted(override val engine: Engine) : PlayerEngine {
        @Volatile override var position = 0L
        @Volatile private var events: ((PlayerEvent) -> Unit)? = null
        @Volatile private var request: HttpURLConnection? = null
        @Volatile private var released = false
        /** Completes with how playing stops: a failure, or null for the file's end. */
        val failure = CompletableDeferred<Throwable?>()

        override suspend fun play(location: String, from: Long, events: (PlayerEvent) -> Unit) {
            if (current != null) playedOverAnother = true
            current = this
            val listingId = location.substringAfterLast('/').substringBefore('.').toLong()
            played += listingId to from
            locations += location
            position = from
            // Opened on a thread of its own, so a slow stream doesn't hold up cancelling.
            val opened = CompletableDeferred<Unit>()
            thread(isDaemon = true) {
                try {
                    val request = URI(location).toURL().openConnection() as HttpURLConnection
                    this.request = request
                    if (released) return@thread request.disconnect()
                    if (request.responseCode != 200 || request.inputStream.read() < 0) throw IOException("the stream is dead")
                    opened.complete(Unit)
                } catch (e: Exception) {
                    opened.completeExceptionally(e as? IOException ?: IOException(e))
                }
            }
            opened.await()
            if (listingId in undecodable) throw CantOpen("scripted: $engine can't decode the file")
            this.events = events
            events(PlayerEvent.Started)
            failure.await()?.let { throw it }
        }

        fun report(event: PlayerEvent) {
            if (!released) events?.invoke(event)
        }

        override suspend fun release() {
            released = true
            if (current == this) current = null
            withContext(Dispatchers.IO) { request?.disconnect() }
            // The stick's engines return once their request is closed; wait until the Provider has seen it go.
            withTimeout(5_000) { while (provider.openStreamCount > 0) delay(10) }
        }
    }
}
