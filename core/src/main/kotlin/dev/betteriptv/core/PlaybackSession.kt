package dev.betteriptv.core

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** How long a Version has to start before the next Backup is tried. */
internal val START_TIMEOUT = 8.seconds

/** Buffering this many times within [BUFFERING_WINDOW] brings the suggestion of a lower Version. */
private const val BUFFERING_REPEATS = 3
private val BUFFERING_WINDOW = 1.minutes

/**
 * The player that decodes and shows a Version's file: Media3 on the stick, a scripted one in tests.
 * A [PlaybackSession] calls it from one coroutine; it reports [PlayerEvent]s from any thread.
 */
interface PlayerEngine {
    /** How far into the playing file the picture is. */
    val position: Duration

    /** Opens the file at [location] and plays it from [from], reporting what happens to [events]. */
    fun play(location: String, from: Duration, events: (PlayerEvent) -> Unit)

    /** Stops and lets go of the file; returns once the player's request to the Provider is closed. */
    suspend fun stop()
}

enum class PlayerEvent {
    /** The picture is playing for the first time since [PlayerEngine.play]. */
    Started,

    /** Having started, playing stalled to fill the buffer. */
    Buffering,

    /** The file couldn't be opened or decoded, or stopped coming. */
    Failed,
}

/** What the Viewer sees of a [PlaybackSession]. */
sealed interface PlaybackState {
    /** The Version being opened or playing; null when none would start. */
    val version: Version?

    /** [version] is being opened; it has a few seconds to start before its next Backup is tried. */
    data class Starting(override val version: Version) : PlaybackState

    /**
     * [version] is playing. After repeated buffering, [lowerVersion] is a lower Version in the same
     * language to suggest; the session never switches to it by itself.
     */
    data class Playing(override val version: Version, val lowerVersion: Version? = null) : PlaybackState

    /** No Version would start: the one asked for and all its Backups are dead. Switching tries another. */
    data object Failed : PlaybackState {
        override val version: Version? get() = null
    }
}

/**
 * Drives a [PlayerEngine] from a [plan]. A Version that fails, or doesn't start within the start
 * timeout, is closed and its next Backup is tried; one that stops partway through gives way to a Backup at
 * the same position. The failing stream is always closed, and the connection given back, before
 * the next one opens.
 */
class PlaybackSession internal constructor(
    val plan: PlayPlan,
    private val engine: PlayerEngine,
    private val connect: suspend (Version) -> Connection,
    private val startTimeout: Duration,
) {
    private val _state = MutableStateFlow<PlaybackState>(PlaybackState.Starting(plan.version))
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private sealed interface Signal
    private class Event(val attempt: Int, val event: PlayerEvent) : Signal
    private class Switch(val version: Version) : Signal
    private data object Dismiss : Signal

    private val signals = Channel<Signal>(Channel.UNLIMITED)
    private var connection: Connection? = null
    private var attempts = 0

    /** Where the next Version starts from: where the last one got to once it had started. */
    private var resumeAt = Duration.ZERO

    /**
     * Plays until cancelled; the stream is closed and the connection given back when it is. When no
     * Version will start ([PlaybackState.Failed]), it waits for the Viewer to switch to another.
     */
    suspend fun run() {
        var queue = plan.version.withBackups
        try {
            while (true) {
                while (queue.isNotEmpty()) {
                    val version = queue.first()
                    queue = queue.drop(1)
                    _state.value = PlaybackState.Starting(version)
                    val switch = play(version)
                    stop()
                    if (switch != null) queue = switch.version.withBackups
                }
                _state.value = PlaybackState.Failed
                val switch = awaitSwitch()
                queue = switch.version.withBackups
            }
        } finally {
            withContext(NonCancellable) { stop() }
        }
    }

    /** Switches to [version] (and its Backups, should it be dead) at the current position. */
    fun switchTo(version: Version) {
        signals.trySend(Switch(version))
    }

    /** Takes back the suggestion of a lower Version; it comes again only if buffering keeps repeating. */
    fun dismissSuggestion() {
        signals.trySend(Dismiss)
    }

    /**
     * Plays [version] until it fails, doesn't start in time, or the Viewer switches; returns the
     * switch, if that's what ended it.
     */
    private suspend fun play(version: Version): Switch? {
        // Looking up where the file plays from counts against its time to start.
        val opened = TimeSource.Monotonic.markNow()
        connection = try {
            connect(version)
        } catch (e: IOException) {
            // Logged out, or the catalog couldn't be read: this Version can't be opened.
            return null
        }
        val attempt = ++attempts
        engine.play(connection!!.location, resumeAt) { signals.trySend(Event(attempt, it)) }
        var started = false
        val stalls = ArrayDeque<TimeMark>()
        while (true) {
            val signal = if (started) signals.receive() else withTimeoutOrNull(startTimeout - opened.elapsedNow()) { signals.receive() }
            when (signal) {
                null -> return null
                is Switch -> if (signal.version.listingId != version.listingId) {
                    if (started) resumeAt = engine.position
                    return signal
                }
                Dismiss -> (_state.value as? PlaybackState.Playing)?.let {
                    stalls.clear()
                    _state.value = it.copy(lowerVersion = null)
                }
                is Event -> if (signal.attempt == attempt) when (signal.event) {
                    PlayerEvent.Started -> if (!started) {
                        started = true
                        _state.value = PlaybackState.Playing(version)
                    }
                    PlayerEvent.Failed -> {
                        if (started) resumeAt = engine.position
                        return null
                    }
                    PlayerEvent.Buffering -> if (started) {
                        stalls.addLast(TimeSource.Monotonic.markNow())
                        while (stalls.first().elapsedNow() > BUFFERING_WINDOW) stalls.removeFirst()
                        val playing = _state.value as PlaybackState.Playing
                        if (stalls.size >= BUFFERING_REPEATS && playing.lowerVersion == null) {
                            lowerVersion(version, plan.versions)?.let { _state.value = playing.copy(lowerVersion = it) }
                        }
                    }
                }
            }
        }
    }

    /** Waits for the Viewer to switch Version, ignoring what's left of the players that failed. */
    private suspend fun awaitSwitch(): Switch {
        while (true) (signals.receive() as? Switch)?.let { return it }
    }

    /** Closes the playing file, then gives the connection back. */
    private suspend fun stop() {
        val connection = connection ?: return
        this.connection = null
        try {
            engine.stop()
        } finally {
            connection.close()
        }
    }
}

/** [this] Version, then its Backups: what is tried, in order, to play it. */
private val Version.withBackups get() = listOf(this) + backups
