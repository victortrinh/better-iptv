package dev.betteriptv.core

import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.selects.select
import java.io.IOException

/** The player engines a Version can play on: Media3 by default, VLC for the rare file Media3 can't open. */
enum class Engine {
    Media3,
    Vlc;

    val other: Engine get() = if (this == Media3) Vlc else Media3
}

/**
 * A player engine as the [PlaybackSession] drives it. The app implements one per [Engine]; tests
 * script one. A session creates a new one each time it starts a file and [release]s it afterwards.
 */
interface PlayerEngine {
    val engine: Engine

    /**
     * Whether the engine opens a new request for the file before closing the one it has, as VLC does
     * to read a file's index and to seek. The session then gives it the file through a relay on the
     * device, so the Provider still sees one stream.
     */
    val overlapsRequests: Boolean get() = false

    /** Where playback is in the file, in milliseconds; where it will start until it does. */
    val position: Long

    /**
     * Plays the file at [location] from [from] milliseconds until it ends. Throws [CantOpen] when
     * this engine can't open or decode the file, or an [IOException] when the stream fails.
     * Cancelling stops playback; the session [release]s the engine afterwards.
     */
    suspend fun play(location: String, from: Long)

    /** Stops and lets go of the file, closing the engine's request to the Provider. */
    fun release()
}

/** Thrown by a [PlayerEngine] that can't open or decode a file another engine might play. */
class CantOpen(message: String, cause: Throwable? = null) : Exception(message, cause)

sealed interface PlaybackState {
    /** Waiting for the Viewer's one connection. */
    data object Connecting : PlaybackState

    /** [player] has the file: show its picture. */
    data class Playing(val player: PlayerEngine) : PlaybackState

    /** Nothing plays: the stream failed, the last engine couldn't open the file, or the Viewer is logged out. */
    data object Failed : PlaybackState

    /** The file played to its end. */
    data object Ended : PlaybackState
}

/**
 * Plays a [plan]'s Version on Media3, moving to the VLC engine when Media3 can't open the file, and
 * between the two when the Viewer asks to [tryOtherPlayer]. Every engine plays through the
 * Viewer's one connection, which is given back only once the engine has let go of the file.
 */
class PlaybackSession internal constructor(
    private val library: Library,
    private val plan: PlayPlan,
    private val engines: (Engine) -> PlayerEngine,
) {
    private val _state = MutableStateFlow<PlaybackState>(PlaybackState.Connecting)
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private val switches = Channel<Unit>(Channel.CONFLATED)

    /** Moves playback to the other engine at the same position, or tries the other engine after a failure. */
    fun tryOtherPlayer() {
        switches.trySend(Unit)
    }

    /** Plays until the file ends; cancel it to stop. A failure waits for the Viewer to [tryOtherPlayer]. */
    suspend fun play() {
        var engine = Engine.Media3
        var from = 0L
        while (true) {
            _state.value = PlaybackState.Connecting
            when (val outcome = playOnce(engine, from)) {
                Outcome.Ended -> {
                    _state.value = PlaybackState.Ended
                    return
                }
                is Outcome.Switched -> from = outcome.position
                is Outcome.CantOpen -> {
                    from = outcome.position
                    // VLC is the last resort; failing there leaves the choice to the Viewer.
                    if (engine == Engine.Vlc) awaitSwitchAfterFailure()
                }
                Outcome.Failed -> awaitSwitchAfterFailure()
            }
            engine = engine.other
        }
    }

    private suspend fun awaitSwitchAfterFailure() {
        // A press made while the failed engine was still playing was meant for that engine.
        switches.tryReceive()
        _state.value = PlaybackState.Failed
        switches.receive()
    }

    private suspend fun playOnce(engine: Engine, from: Long): Outcome {
        val connection = try {
            library.connect(plan.version)
        } catch (e: IOException) {
            return Outcome.Failed
        }
        try {
            val player = engines(engine)
            val relay = if (player.overlapsRequests) library.relay(connection.location) else null
            try {
                // A press made before this engine started was meant for the one before it.
                switches.tryReceive()
                _state.value = PlaybackState.Playing(player)
                return coroutineScope {
                    val playing = async {
                        try {
                            player.play(relay?.location ?: connection.location, from)
                            Outcome.Ended
                        } catch (e: CantOpen) {
                            Outcome.CantOpen(player.position)
                        } catch (e: IOException) {
                            Outcome.Failed
                        }
                    }
                    select {
                        playing.onAwait { it }
                        switches.onReceive {
                            playing.cancel()
                            Outcome.Switched(player.position)
                        }
                    }
                }
            } finally {
                // The picture lets go of the engine before it is released.
                _state.value = PlaybackState.Connecting
                player.release()
                relay?.close()
            }
        } finally {
            // Releasing closes the engine's request to the Provider, so only then is the connection given back.
            connection.close()
        }
    }

    private sealed interface Outcome {
        data object Ended : Outcome
        data object Failed : Outcome
        data class CantOpen(val position: Long) : Outcome
        data class Switched(val position: Long) : Outcome
    }
}
