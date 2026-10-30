package dev.betteriptv.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * How long a Version has to start before its next Backup is tried. It counts from the Provider's
 * latest answer instead, up to [ANSWERED_START_TIMEOUTS] times it in all.
 */
internal val START_TIMEOUT = 8.seconds

/**
 * VLC opens several requests one after another before its picture starts, ~1.5 s each through the
 * relay, so a working file can take ~12 s on the stick. Media3 does too for an MP4 whose index is
 * at its end: it reads the start, then the whole index from the end, then goes back for the picture.
 * While the Provider keeps answering, it may.
 */
private const val ANSWERED_START_TIMEOUTS = 4

/** Buffering this many times within [BUFFERING_WINDOW] brings the suggestion of a lower Version. */
private const val BUFFERING_REPEATS = 3
private val BUFFERING_WINDOW = 1.minutes

/** Where the Viewer is gets recorded this often while a file plays, so a crash or a pulled plug loses little. */
private val RECORD_EVERY = 15.seconds

/** How often an Episode's position is checked for its credits, so the next one is offered as they begin. */
private val CREDITS_EVERY = 1.seconds

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

    /** How long the file plays, in milliseconds, or null until the engine knows. */
    val duration: Long? get() = null

    /**
     * Plays the file at [location] from [from] milliseconds until it ends, reporting to [events] when
     * the picture starts and when it stalls. Throws [CantOpen] when this engine can't open or decode
     * the file, or an [IOException] when the stream fails. Cancelling stops playback; the session
     * [release]s the engine afterwards.
     */
    suspend fun play(location: String, from: Long, events: (PlayerEvent) -> Unit)

    /**
     * When the Provider last sent the engine some of the file, or null before it first did. Through a
     * relay, the relay knows instead.
     */
    val answeredAt: TimeSource.Monotonic.ValueTimeMark? get() = null

    /** What the engine found in the file once it opened it, so Playback Confirms the Version; null until then. */
    val seen: FileContents? get() = null

    /** Stops and lets go of the file; returns once the engine's request to the Provider is closed. */
    suspend fun release()
}

enum class PlayerEvent {
    /** The picture is playing for the first time since [PlayerEngine.play]. */
    Started,

    /** Having started, playing stalled to fill the buffer. */
    Buffering,
}

/** Thrown by a [PlayerEngine] that can't open or decode a file another engine might play. */
class CantOpen(message: String, cause: Throwable? = null) : Exception(message, cause)

/** What the Viewer sees of a [PlaybackSession]. */
sealed interface PlaybackState {
    /** The Version being opened or playing; null when nothing plays. */
    val version: Version?

    /**
     * [version] is being opened: it has a few seconds to start before its next Backup is tried.
     * [player] has the file once the Viewer's one connection is free; show its picture.
     */
    data class Starting(override val version: Version, val player: PlayerEngine? = null) : PlaybackState

    /**
     * [version] plays on [player]. After repeated buffering, [lowerVersion] is a lower Version in the
     * same language to suggest; the session never switches to it by itself. An Episode is in its
     * [credits] while its position is ([inCredits]), so the next one can be offered.
     */
    data class Playing(override val version: Version, val player: PlayerEngine, val lowerVersion: Version? = null, val credits: Boolean = false) : PlaybackState

    /**
     * Nothing plays: the Version asked for and its Backups are dead, or neither engine can open them.
     * The Viewer can try the other player or pick another Version.
     */
    data object Failed : PlaybackState {
        override val version: Version? get() = null
    }

    /** The file played to its end. */
    data object Ended : PlaybackState {
        override val version: Version? get() = null
    }
}

/**
 * Plays a [plan]'s Version. A Version Media3 can't open moves to the VLC engine; one that is dead,
 * breaks, or doesn't start within the start timeout gives way to its next Backup, at the same
 * position. The Viewer can switch Version, or move to the other engine with [tryOtherPlayer], partway
 * through. Every engine plays through the Viewer's one connection, which is given back only once the
 * engine has let go of the file. Playback starts where the plan starts, and where the Viewer is
 * gets recorded as it plays and when it stops, so Play resumes there. An Episode says when it is in
 * its credits.
 */
class PlaybackSession internal constructor(
    private val library: Library,
    val plan: PlayPlan,
    private val engines: (Engine) -> PlayerEngine,
    private val startTimeout: Duration,
) {
    private val _state = MutableStateFlow<PlaybackState>(PlaybackState.Starting(plan.version))
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private sealed interface Signal
    private class Switch(val version: Version) : Signal
    private data object OtherPlayer : Signal
    private data object Dismiss : Signal

    private val signals = Channel<Signal>(Channel.UNLIMITED)

    /** Where the next file starts from: where the last one got to. */
    private var resumeAt = plan.from

    /** Switches to [version] (and its Backups, should it be dead) at the current position. */
    fun switchTo(version: Version) {
        signals.trySend(Switch(version))
    }

    /** Moves playback to the other engine at the same position, or tries the other engine after a failure. */
    fun tryOtherPlayer() {
        signals.trySend(OtherPlayer)
    }

    /** Takes back the suggestion of a lower Version; it comes again only if buffering keeps repeating. */
    fun dismissSuggestion() {
        signals.trySend(Dismiss)
    }

    /** Plays until the file ends; cancel it to stop. A failure waits for the Viewer to try again. */
    suspend fun play() {
        // The Version the Viewer asked for; trying again after a failure starts from it.
        var asked = plan.version
        var queue = asked.withBackups
        var engine = Engine.Media3
        // The engine last tried: trying the other player after a failure means the other one from it.
        var tried = engine
        while (true) {
            while (queue.isNotEmpty()) {
                tried = engine
                when (val outcome = attempt(queue.first(), engine)) {
                    Outcome.Ended -> {
                        // An Episode's progress isn't kept yet: it would count as the Show's.
                        plan.resumable?.let { withContext(Dispatchers.IO) { library.finished(it) } }
                        _state.value = PlaybackState.Ended
                        return
                    }
                    // VLC is the last resort for a file; failing there moves on to the next Backup.
                    Outcome.CantOpen -> if (engine == Engine.Media3) engine = Engine.Vlc else {
                        queue = queue.drop(1)
                        engine = Engine.Media3
                    }
                    Outcome.Dead -> {
                        queue = queue.drop(1)
                        engine = Engine.Media3
                    }
                    Outcome.OtherPlayer -> engine = engine.other
                    is Outcome.Switched -> {
                        asked = outcome.version
                        queue = asked.withBackups
                        engine = Engine.Media3
                    }
                }
            }
            _state.value = PlaybackState.Failed
            when (val choice = awaitChoice()) {
                is Switch -> {
                    asked = choice.version
                    engine = Engine.Media3
                }
                else -> engine = tried.other
            }
            queue = asked.withBackups
        }
    }

    /**
     * Plays [version] on [engine] until it ends, fails, doesn't start in time, or the Viewer moves
     * elsewhere; returns which.
     */
    private suspend fun attempt(version: Version, engine: Engine): Outcome {
        _state.value = PlaybackState.Starting(version)
        val connection = try {
            library.connect(version, episode = plan.episode != null)
        } catch (e: IOException) {
            // Logged out, or the catalog couldn't be read: this Version can't be opened.
            return Outcome.Dead
        }
        // Waiting for the Viewer's connection isn't the Version's doing: its time to start runs from here.
        val opened = TimeSource.Monotonic.markNow()
        try {
            val player = engines(engine)
            // A live stream has no index to read and nowhere to seek, and an HLS playlist's segments
            // are fetched beside it, which the relay's one location can't serve: VLC plays it as it is.
            val relay = if (player.overlapsRequests && !plan.live) library.relay(connection.location) else null
            try {
                _state.value = PlaybackState.Starting(version, player)
                return watch(version, player, relay?.location ?: connection.location, opened, relay)
            } finally {
                resumeAt = player.position
                // A quick write, made even while the Viewer leaving cancels playback.
                plan.resumable?.let { library.saveProgress(it, resumeAt, player.duration) }
                // The picture lets go of the engine before it is released.
                _state.value = PlaybackState.Starting(version)
                // A Channel's stream changes as it goes: there is no file to Confirm.
                if (!plan.live) player.seen?.let { library.confirm(version, it) }
                withContext(NonCancellable) {
                    player.release()
                    relay?.close()
                }
            }
        } finally {
            // Releasing closes the engine's request to the Provider, so only then is the connection given back.
            connection.close()
        }
    }

    /**
     * Returns once [player], given the file at [opened], has had its time to start: the start timeout
     * since the Provider last answered it (through a [relay], answered the relay), up to the limit.
     */
    private suspend fun startDeadline(player: PlayerEngine, opened: TimeSource.Monotonic.ValueTimeMark, relay: StreamRelay?) {
        val limit = opened + startTimeout * ANSWERED_START_TIMEOUTS
        var from = opened
        while (true) {
            delay(minOf(from + startTimeout, limit) - TimeSource.Monotonic.markNow())
            val answered = if (relay != null) relay.answeredAt else player.answeredAt
            if (answered == null || answered <= from || limit.hasPassedNow()) return
            from = answered
        }
    }

    /**
     * Watches [player] play [version] until something ends it, keeping [state] up to date meanwhile.
     * Unless it has started by its [startDeadline], it is [Outcome.Dead].
     */
    private suspend fun watch(
        version: Version,
        player: PlayerEngine,
        location: String,
        opened: TimeSource.Monotonic.ValueTimeMark,
        relay: StreamRelay?,
    ): Outcome = coroutineScope {
        val events = Channel<PlayerEvent>(Channel.UNLIMITED)
        val playing = async {
            try {
                player.play(location, resumeAt) { events.trySend(it) }
                Outcome.Ended
            } catch (e: CantOpen) {
                Outcome.CantOpen
            } catch (e: IOException) {
                Outcome.Dead
            }
        }
        val deadline = async { startDeadline(player, opened, relay) }
        val resumable = plan.resumable
        if (resumable != null) launch {
            while (true) {
                delay(RECORD_EVERY)
                val position = player.position
                val duration = player.duration
                withContext(Dispatchers.IO) { library.saveProgress(resumable, position, duration) }
            }
        }
        if (plan.episode != null) launch {
            while (true) {
                delay(CREDITS_EVERY)
                val shown = _state.value as? PlaybackState.Playing ?: continue
                val credits = inCredits(player.position, player.duration)
                if (credits != shown.credits) _state.value = shown.copy(credits = credits)
            }
        }
        var started = false
        val stalls = ArrayDeque<TimeMark>()
        var outcome: Outcome? = null
        while (outcome == null) {
            outcome = select {
                playing.onAwait { it }
                if (!started) deadline.onAwait { Outcome.Dead }
                events.onReceive { event ->
                    when (event) {
                        PlayerEvent.Started -> if (!started) {
                            started = true
                            _state.value = PlaybackState.Playing(version, player)
                        }
                        PlayerEvent.Buffering -> if (started) {
                            stalls.addLast(TimeSource.Monotonic.markNow())
                            while (stalls.first().elapsedNow() > BUFFERING_WINDOW) stalls.removeFirst()
                            val shown = _state.value as PlaybackState.Playing
                            if (stalls.size >= BUFFERING_REPEATS && shown.lowerVersion == null) {
                                // What the player found in the file tells a Movie's quality the Prefix seldom does.
                                val known = player.seen?.let { version.copy(attributes = version.attributes.confirmedBy(it)) } ?: version
                                lowerVersion(known, plan.versions)?.let { _state.value = shown.copy(lowerVersion = it) }
                            }
                        }
                    }
                    null
                }
                signals.onReceive { signal ->
                    when (signal) {
                        is Switch -> if (signal.version.listingId != version.listingId) Outcome.Switched(signal.version) else null
                        OtherPlayer -> Outcome.OtherPlayer
                        Dismiss -> {
                            (_state.value as? PlaybackState.Playing)?.let {
                                stalls.clear()
                                _state.value = it.copy(lowerVersion = null)
                            }
                            null
                        }
                    }
                }
            }
        }
        coroutineContext.cancelChildren()
        outcome
    }

    /** Waits for the Viewer to switch Version or try the other player, ignoring stale dismissals. */
    private suspend fun awaitChoice(): Signal {
        while (true) signals.receive().takeIf { it != Dismiss }?.let { return it }
    }

    private sealed interface Outcome {
        data object Ended : Outcome
        data object CantOpen : Outcome
        data object Dead : Outcome
        data object OtherPlayer : Outcome
        data class Switched(val version: Version) : Outcome
    }
}

/** [this] Version, then its Backups: what is tried, in order, to play it. */
private val Version.withBackups get() = listOf(this) + backups
