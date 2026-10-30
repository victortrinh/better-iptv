package dev.betteriptv.app

import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import dev.betteriptv.core.CEA_608
import dev.betteriptv.core.CEA_708
import dev.betteriptv.core.CantOpen
import dev.betteriptv.core.DeviceProfile
import dev.betteriptv.core.Engine
import dev.betteriptv.core.FileContents
import dev.betteriptv.core.Hdr
import dev.betteriptv.core.MIN_FRAMES_FOR_RATE
import dev.betteriptv.core.PlayPlan
import dev.betteriptv.core.PlayerEngine
import dev.betteriptv.core.PlayerEvent
import dev.betteriptv.core.RememberedTracks
import dev.betteriptv.core.Track
import dev.betteriptv.core.TrackChoice
import dev.betteriptv.core.frameRateOf
import dev.betteriptv.core.vlcNamedTrack
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.interfaces.IVLCVout
import org.videolan.libvlc.util.VLCVideoLayout
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

internal const val PLAYER_TAG = "Player"

/**
 * Creates the engine a Playback session asks for, choosing a [plan]'s Tracks for this stick and TV
 * once the file is open, with what the Viewer last picked ([remembered], read as each file opens,
 * so a pick made while playing carries over to a Backup), and drawing subtitles in the [style] of
 * the moment.
 */
fun playerEngines(context: Context, plan: PlayPlan, remembered: () -> RememberedTracks, style: () -> SubtitleStyle): (Engine) -> PlayerEngine {
    val device = readDeviceProfile(context)
    return { engine ->
        when (engine) {
            Engine.Media3 -> Media3Engine(context, plan, device, remembered)
            Engine.Vlc -> VlcEngine(context, plan, device, remembered, style())
        }
    }
}

/**
 * A file's audio and subtitle Tracks the engine can play, and which are on: [audioOn] and
 * [subtitlesOn] index into them, null when none is.
 */
data class FileTracks(val audio: List<Track>, val subtitles: List<Track>, val audioOn: Int?, val subtitlesOn: Int?)

/** What the player overlay drives, on either engine. Used on the main thread only. */
sealed interface ControlledEngine : PlayerEngine {
    /** Whether it plays, or is paused. */
    val isPlaying: Boolean

    /** How far into the file it has loaded, in milliseconds. */
    val buffered: Long

    /** Pauses, or plays again. */
    fun togglePause()

    /** Jumps [by] milliseconds forward, or back when negative. */
    fun seek(by: Long)

    /** The file's Tracks; null until the engine has read them. */
    val tracks: FileTracks?

    /** Plays the audio Track at [index] of [tracks]. */
    fun chooseAudio(index: Int)

    /** Shows the subtitle Track at [index] of [tracks], or none when null. */
    fun chooseSubtitles(index: Int?)

    /** Plays the sound [audio] and shows the subtitles [subtitles] milliseconds later than the picture; negative is earlier. */
    fun delay(audio: Long, subtitles: Long)

    /** Draws subtitles without a style of their own in [style]. */
    fun style(style: SubtitleStyle)

    /** The film's frames a second, once the engine knows them. */
    val frameRate: Float?

    /** The plan's Track choice for the file, once the engine has read its Tracks: it says when downloaded subtitles show. */
    val chosen: StateFlow<TrackChoice?>
}

/** How long after a seek filling the buffer is taken for the seek's doing. */
private const val SEEK_GRACE_MILLIS = 2_000L

/** Media3 (ExoPlayer), the default engine. Used on the main thread only. */
@OptIn(UnstableApi::class)
class Media3Engine(context: Context, plan: PlayPlan, device: DeviceProfile, remembered: () -> RememberedTracks) : ControlledEngine {
    override val engine = Engine.Media3
    private val requests = OpenRequests()
    private val playing = buildPlayer(context, requests.counting(DefaultDataSource.Factory(context)))
    override val chosen = MutableStateFlow<TrackChoice?>(null)
    val player: ExoPlayer = playing.player.apply { addListener(TrackChooser(this, plan, device, remembered, { seen = it }) { chosen.value = it }) }
    /** The presentation times of the frames shown since playback started or the Viewer last seeked, on the playback thread. */
    private val frameTimes = ArrayList<Long>(MIN_FRAMES_FOR_RATE)
    @Volatile private var measuredFrameRate: Float? = null
    override val position: Long get() = player.currentPosition
    override val duration: Long? get() = player.duration.takeIf { it > 0 }
    override val answeredAt get() = requests.answeredAt
    override val isPlaying: Boolean get() = player.playWhenReady
    override val buffered: Long get() = player.bufferedPosition
    private var subtitleView: SubtitleView? = null
    private var style = SubtitleStyle()

    @Volatile override var seen: FileContents? = null
        private set

    init {
        // Media3 leaves the frame rate out of every MKV, so it's measured from the first frames shown.
        player.setVideoFrameMetadataListener { presentationTimeUs, _, _, _ ->
            if (measuredFrameRate != null) return@setVideoFrameMetadataListener
            val last = frameTimes.lastOrNull()
            if (last != null && (presentationTimeUs <= last || presentationTimeUs - last > 1_000_000)) frameTimes.clear()
            frameTimes += presentationTimeUs
            if (frameTimes.size >= MIN_FRAMES_FOR_RATE) measuredFrameRate = frameRateOf(frameTimes)
        }
    }

    /** Shows the player in a [view] made for it, ASS subtitles included. */
    fun showIn(view: PlayerView) {
        playing.showIn(view)
        subtitleView = view.subtitleView
        style(style)
    }

    override fun togglePause() {
        player.playWhenReady = !player.playWhenReady
    }

    override fun seek(by: Long) {
        val end = duration ?: Long.MAX_VALUE
        player.seekTo((player.currentPosition + by).coerceIn(0, end))
    }

    private fun groups(type: Int) = player.currentTracks.groups.filter { it.type == type && it.isSupported }

    override val tracks: FileTracks?
        get() {
            if (player.currentTracks.isEmpty) return null
            val audio = groups(C.TRACK_TYPE_AUDIO)
            val subtitles = groups(C.TRACK_TYPE_TEXT)
            return FileTracks(
                audio.map { it.getTrackFormat(0).let { format -> Track(format.language, format.sampleMimeType, format.channelCount.takeIf { count -> count != Format.NO_VALUE }) } },
                subtitles.map { it.getTrackFormat(0).let { format -> Track(format.language, format.sampleMimeType) } },
                audio.indexOfFirst { it.isSelected }.takeIf { it >= 0 },
                subtitles.indexOfFirst { it.isSelected }.takeIf { it >= 0 },
            )
        }

    override fun chooseAudio(index: Int) {
        val group = groups(C.TRACK_TYPE_AUDIO).getOrNull(index) ?: return
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0)).build()
    }

    override fun chooseSubtitles(index: Int?) {
        val group = index?.let { groups(C.TRACK_TYPE_TEXT).getOrNull(it) }
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().apply {
            setTrackTypeDisabled(C.TRACK_TYPE_TEXT, group == null)
            group?.let { setOverrideForType(TrackSelectionOverride(it.mediaTrackGroup, 0)) }
        }.build()
    }

    override fun delay(audio: Long, subtitles: Long) {
        playing.delays.audioUs = audio * 1000
        playing.delays.subtitlesUs = subtitles * 1000
    }

    override fun style(style: SubtitleStyle) {
        this.style = style
        subtitleView?.draw(style)
    }

    override val frameRate: Float?
        get() = groups(C.TRACK_TYPE_VIDEO).firstOrNull { it.isSelected }?.getTrackFormat(0)?.frameRate?.takeIf { it > 0 } ?: measuredFrameRate

    override suspend fun play(location: String, from: Long, events: (PlayerEvent) -> Unit) = suspendCancellableCoroutine { continuation ->
        player.addListener(object : Player.Listener {
            private var started = false
            private var seekedAt: Long? = null

            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_ENDED -> finish(null)
                    Player.STATE_READY -> if (!started) {
                        started = true
                        events(PlayerEvent.Started)
                    }
                    // Filling the buffer just after the Viewer seeks isn't the Version struggling.
                    Player.STATE_BUFFERING -> if (started && !justSeeked()) events(PlayerEvent.Buffering)
                }
            }

            private fun justSeeked() = seekedAt?.let { SystemClock.elapsedRealtime() - it < SEEK_GRACE_MILLIS } == true

            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK) seekedAt = SystemClock.elapsedRealtime()
            }

            override fun onPlayerError(error: PlaybackException) {
                finish(if (error.isTheFiles()) CantOpen("Media3 can't play the file", error) else IOException("the stream failed", error))
            }

            override fun onTracksChanged(tracks: Tracks) {
                // A picture or sound the stick has no decoder for raises no error: the file would just play
                // without it. One that seems beyond a decoder's reported capabilities is still tried, since
                // those reports are conservative; if decoding then fails, the error says so.
                val unplayable = listOf(C.TRACK_TYPE_VIDEO, C.TRACK_TYPE_AUDIO)
                    .any { tracks.containsType(it) && !tracks.isTypeSupported(it, /* allowExceedsCapabilities = */ true) }
                if (unplayable) return finish(CantOpen("Media3 has no decoder for the file's picture or sound"))
                // Every Version has a picture: none means Media3 didn't recognise its format and left it out.
                if (!tracks.isEmpty && !tracks.containsType(C.TRACK_TYPE_VIDEO)) finish(CantOpen("Media3 found no picture it can read"))
            }

            private fun finish(error: Throwable?) {
                // Why the engine gave up, never where the file is: its location holds the login.
                if (error != null) Log.w(PLAYER_TAG, "Media3 stopped: ${error.message} (${(error.cause as? PlaybackException)?.errorCodeName})")
                player.removeListener(this)
                if (!continuation.isActive) return
                if (error == null) continuation.resume(Unit) else continuation.resumeWithException(error)
            }
        })
        player.setMediaItem(MediaItem.fromUri(location), from)
        player.prepare()
        player.playWhenReady = true
    }

    override suspend fun release() {
        playing.release()
        // Releasing returns before the player's loading thread closes its request, and the Provider
        // would refuse the next file while it's open. A read stuck on a stalled Provider times out.
        withTimeoutOrNull(RELEASE_TIMEOUT) { requests.awaitNone() }
    }
}

/** Longer than Media3's own read timeout, so a request stuck on a stalled Provider closes first. */
private val RELEASE_TIMEOUT = 10.seconds

/**
 * Counts the player's open requests to the Provider, so releasing can wait for the last one to close,
 * and notes when the Provider last sent some of the file.
 */
@OptIn(UnstableApi::class)
private class OpenRequests {
    private val open = MutableStateFlow(0)

    /** Set on the loading thread as bytes come in, so a slow start still being read counts as alive. */
    @Volatile var answeredAt: TimeSource.Monotonic.ValueTimeMark? = null
        private set

    fun counting(upstream: DataSource.Factory) = DataSource.Factory { Counted(upstream.createDataSource()) }

    suspend fun awaitNone() {
        open.first { it == 0 }
    }

    private inner class Counted(private val upstream: DataSource) : DataSource by upstream {
        private var opened = false

        override fun open(dataSpec: DataSpec): Long {
            // Counted from the attempt: the player closes a source even when opening it fails.
            if (!opened) open.update { it + 1 }
            opened = true
            return upstream.open(dataSpec)
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            upstream.read(buffer, offset, length).also { if (it > 0) answeredAt = TimeSource.Monotonic.markNow() }

        override fun close() {
            try {
                upstream.close()
            } finally {
                if (opened) open.update { it - 1 }
                opened = false
            }
        }
    }
}

/**
 * Whether the error is about the file rather than the stream, so VLC may still play it: parsing
 * (3xxx), decoding (4xxx) and audio output (5xxx) errors, and the unspecified ones a file Media3
 * can't handle may raise.
 */
private fun PlaybackException.isTheFiles() = errorCode / 1000 in 3..5 ||
    errorCode == PlaybackException.ERROR_CODE_UNSPECIFIED || errorCode == PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK

/**
 * Applies the [plan]'s Track choice for [device] once, as soon as the player knows the file's
 * Tracks, and tells [onSeen] what the file holds, so Playback Confirms the Version.
 */
private class TrackChooser(
    private val player: ExoPlayer,
    private val plan: PlayPlan,
    private val device: DeviceProfile,
    private val remembered: () -> RememberedTracks,
    private val onSeen: (FileContents) -> Unit,
    private val onChosen: (TrackChoice) -> Unit,
) : Player.Listener {
    private var chosen = false

    override fun onTracksChanged(tracks: Tracks) {
        if (chosen || tracks.isEmpty) return
        chosen = true
        onSeen(tracks.contents())
        // Only Tracks the stick can play: choosing one it can't would leave the picture silent.
        val audio = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO && it.isSupported }
        val subtitles = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT && it.isSupported }
        val choice = plan.chooseTracks(
            audio.map { it.getTrackFormat(0).let { format -> Track(format.language, format.sampleMimeType) } },
            subtitles.map { it.getTrackFormat(0).let { format -> Track(format.language, format.sampleMimeType) } },
            device,
            remembered(),
        )
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().apply {
            choice.audio?.let { setOverrideForType(TrackSelectionOverride(audio[it].mediaTrackGroup, 0)) }
            // Without a choice, a file's "default" subtitles stay off; forced ones (for foreign dialogue) still show.
            choice.subtitle?.let { setOverrideForType(TrackSelectionOverride(subtitles[it].mediaTrackGroup, 0)) }
                ?: setIgnoredTextSelectionFlags(C.SELECTION_FLAG_DEFAULT)
        }.build()
        onChosen(choice)
    }
}

/**
 * The VLC engine, for the rare file Media3 can't open. It decodes in software what the stick can't
 * in hardware. Its picture needs a [VLCVideoLayout]: playback starts once one is [attach]ed.
 * Driven from the main thread, but LibVLC can hold the main thread for half a minute while its own
 * threads open a stream, so every call into the player is made on a thread of its own, and what the
 * overlay reads is what LibVLC last reported.
 */
class VlcEngine(
    context: Context,
    private val plan: PlayPlan,
    private val device: DeviceProfile,
    private val remembered: () -> RememberedTracks,
    style: SubtitleStyle,
) : ControlledEngine {
    override val engine = Engine.Vlc

    // To read a file's index and to seek, VLC opens a new request before closing the one it has.
    override val overlapsRequests = true
    private val vlc = LibVLC(context.applicationContext, arrayListOf("--http-reconnect") + style.vlcOptions())
    private val player = MediaPlayer(vlc)
    private val calls = Executors.newSingleThreadExecutor { Thread(it, "vlc-calls") }
    private val onVlc = calls.asCoroutineDispatcher()
    private val attached = CompletableDeferred<Unit>()
    @Volatile private var from = 0L

    // The picture can still read the engine for a frame after the session releases it (downloaded
    // subtitles follow its position every 50 ms): it answers with where it stopped instead.
    @Volatile private var released = false

    // What LibVLC last reported, read by the overlay.
    @Volatile private var time: Long? = null
    @Volatile private var timeAt = TimeSource.Monotonic.markNow()
    @Volatile private var length: Long? = null
    @Volatile private var playing = false
    // Until LibVLC says otherwise.
    @Volatile private var pausable = true
    @Volatile private var shown = false
    @Volatile private var known = emptyList<IMedia.Track>()
    @Volatile private var audioTracks = emptyList<VlcTrack>()
    @Volatile private var subtitleTracks = emptyList<VlcTrack>()
    @Volatile private var audioId = -1
    @Volatile private var spuId = -1

    /** Whether the Viewer wants it playing: a pause asked for before the picture shows waits for it. */
    @Volatile private var wanted = true

    // Between LibVLC's reports of where it is, the position moves on with the clock, so downloaded subtitles keep time.
    override val position: Long
        get() {
            val at = time ?: return from
            if (released || !playing) return at
            return at + timeAt.elapsedNow().inWholeMilliseconds.coerceAtMost(MAX_TIME_AHEAD)
        }
    override val duration: Long? get() = length

    fun attach(layout: VLCVideoLayout) {
        // VLC sometimes fails to make its picture (#173): when its surfaces come and go says whether they were there.
        player.vlcVout.addCallback(object : IVLCVout.Callback {
            override fun onSurfacesCreated(vout: IVLCVout) {
                Log.i(PLAYER_TAG, "VLC's surfaces are ready")
            }
            override fun onSurfacesDestroyed(vout: IVLCVout) {
                Log.i(PLAYER_TAG, "VLC's surfaces are gone")
            }
        })
        player.attachViews(layout, null, true, false)
        attached.complete(Unit)
    }

    override val isPlaying: Boolean get() = !released && wanted
    override val buffered: Long get() = position

    /** Calls into LibVLC on its thread, unless the engine is released by then. */
    private fun onVlcThread(call: () -> Unit) {
        if (released) return
        try {
            calls.execute { if (!released) call() }
        } catch (_: RejectedExecutionException) {
            // Released meanwhile.
        }
    }

    override fun togglePause() {
        // A stream VLC can't pause, such as some live ones, plays on.
        if (released || (shown && !pausable)) return
        wanted = !wanted
        onVlcThread(::applyPause)
    }

    /** Plays or pauses as the Viewer wants: pausing waits for a picture, and is skipped for a stream VLC can't pause, which it would stop. */
    private fun applyPause() {
        if (wanted) {
            if (!player.isPlaying) player.play()
        } else if (player.isPlaying && shown && pausable) {
            player.pause()
        }
    }

    @Volatile override var seen: FileContents? = null
        private set

    override fun seek(by: Long) {
        if (released) return
        val to = (position + by).coerceAtLeast(0)
        time = to
        timeAt = TimeSource.Monotonic.markNow()
        onVlcThread { player.setTime(to) }
    }

    /** The file's Tracks as LibVLC knows them; on its thread. */
    private fun mediaTracks(): List<IMedia.Track> {
        val media = player.media ?: return emptyList()
        return try {
            (0 until media.trackCount).map(media::getTrack)
        } finally {
            media.release()
        }
    }

    override val tracks: FileTracks?
        get() {
            val audio = audioTracks
            if (audio.isEmpty()) return null
            val subtitles = subtitleTracks
            return FileTracks(
                audio.map { it.track },
                subtitles.map { it.track },
                audio.indexOfFirst { it.id == audioId }.takeIf { it >= 0 },
                subtitles.indexOfFirst { it.id == spuId }.takeIf { it >= 0 },
            )
        }

    override fun chooseAudio(index: Int) {
        val id = audioTracks.getOrNull(index)?.id ?: return
        audioId = id
        onVlcThread { player.setAudioTrack(id) }
    }

    override fun chooseSubtitles(index: Int?) {
        val id = index?.let { subtitleTracks.getOrNull(it)?.id } ?: -1
        spuId = id
        onVlcThread { player.setSpuTrack(id) }
    }

    override fun delay(audio: Long, subtitles: Long) = onVlcThread {
        player.setAudioDelay(audio * 1000)
        player.setSpuDelay(subtitles * 1000)
    }

    // VLC draws subtitles with the options it was created with.
    override fun style(style: SubtitleStyle) = Unit

    override val chosen = MutableStateFlow<TrackChoice?>(null)

    override val frameRate: Float?
        get() = known.filterIsInstance<IMedia.VideoTrack>().firstOrNull()
            ?.let { if (it.frameRateDen > 0) it.frameRateNum.toFloat() / it.frameRateDen else null }?.takeIf { it > 0 }

    override suspend fun play(location: String, from: Long, events: (PlayerEvent) -> Unit) {
        this.from = from
        attached.await()
        suspendCancellableCoroutine { continuation ->
            var tracksChosen = false
            var started = false
            // On the main thread: it only notes what LibVLC reports, and leaves calls into it to its thread.
            val listener = MediaPlayer.EventListener { event ->
                when (event.type) {
                    MediaPlayer.Event.EndReached -> if (continuation.isActive) continuation.resume(Unit)
                    // VLC doesn't say whether the file or the stream failed; it's the last resort either way.
                    MediaPlayer.Event.EncounteredError -> if (continuation.isActive) continuation.resumeWithException(CantOpen("VLC can't play the file"))
                    MediaPlayer.Event.TimeChanged -> {
                        time = event.timeChanged
                        timeAt = TimeSource.Monotonic.markNow()
                    }
                    MediaPlayer.Event.LengthChanged -> length = event.lengthChanged.takeIf { it > 0 }
                    MediaPlayer.Event.PausableChanged -> pausable = event.pausable
                    MediaPlayer.Event.Paused, MediaPlayer.Event.Stopped -> playing = false
                    MediaPlayer.Event.Vout -> if (event.voutCount > 0 && !shown) {
                        Log.i(PLAYER_TAG, "VLC shows its picture")
                        shown = true
                        // A pause asked for before the picture showed.
                        onVlcThread(::applyPause)
                    }
                    MediaPlayer.Event.ESSelected, MediaPlayer.Event.ESDeleted -> onVlcThread(::readTracks)
                }
                if (event.type == MediaPlayer.Event.Playing) {
                    playing = true
                    if (!started) {
                        started = true
                        Log.i(PLAYER_TAG, "VLC plays")
                        events(PlayerEvent.Started)
                    }
                }
                if (event.type == MediaPlayer.Event.Playing || event.type == MediaPlayer.Event.ESAdded) onVlcThread {
                    readTracks()
                    if (!tracksChosen) tracksChosen = chooseTracks()
                }
            }
            onVlcThread {
                player.setEventListener(listener)
                val media = Media(vlc, Uri.parse(location))
                media.addOption(":start-time=${from / 1000.0}")
                player.media = media
                // The player keeps its own reference.
                media.release()
                player.play()
            }
        }
    }

    /** Notes the file's Tracks and which are on, for the overlay; on LibVLC's thread. */
    private fun readTracks() {
        val media = mediaTracks()
        known = media
        val audio = media.filter { it.type == IMedia.Track.Type.Audio }
        if (audio.isNotEmpty()) {
            audioTracks = audio.map { VlcTrack(it.id, Track(it.language, channels = (it as? IMedia.AudioTrack)?.channels?.takeIf { count -> count > 0 })) }
            subtitleTracks = media.filter { it.type == IMedia.Track.Type.Text }.map { VlcTrack(it.id, vlcSubtitles(it)) }
        } else {
            // A live Channel's stream: LibVLC lists its sound and closed captions only as the player's, by name.
            audioTracks = player.audioTracks.named()
            subtitleTracks = player.spuTracks.named()
        }
        audioId = player.audioTrack
        spuId = player.spuTrack
    }

    private fun Array<MediaPlayer.TrackDescription>?.named() =
        orEmpty().filter { it.id >= 0 }.mapNotNull { description -> vlcNamedTrack(description.name)?.let { VlcTrack(description.id, it) } }

    /**
     * Applies the [plan]'s Track choice, and keeps what the file holds so Playback Confirms the
     * Version; false while VLC doesn't know the file's audio Tracks yet, or a live Channel's closed
     * captions the Viewer last picked: VLC finds those in the picture a moment after the sound.
     * On LibVLC's thread.
     */
    private fun chooseTracks(): Boolean {
        val audio = audioTracks
        val subtitles = subtitleTracks
        if (audio.isEmpty()) return false
        seen = vlcContents(known)
        val remembered = remembered()
        // VLC decodes every format itself, so its Tracks' formats don't change the choice.
        val choice = plan.chooseTracks(audio.map { it.track }, subtitles.map { it.track }, device, remembered)
        choice.audio?.let {
            player.setAudioTrack(audio[it].id)
            audioId = audio[it].id
        }
        spuId = choice.subtitle?.let { subtitles[it].id } ?: -1
        player.setSpuTrack(spuId)
        chosen.value = choice
        return !(plan.live && remembered.closedCaptions && choice.subtitle == null)
    }

    override suspend fun release() {
        from = position
        released = true
        // Stopping waits for LibVLC's threads, which can take as long as a stalled stream.
        withContext(onVlc) {
            player.setEventListener(null)
            player.stop()
        }
        player.detachViews()
        withContext(onVlc) {
            player.release()
            vlc.release()
        }
        calls.shutdown()
    }
}

/** A Track VLC plays, by the id its player knows it by. */
private class VlcTrack(val id: Int, val track: Track)

/** How far the position moves on with the clock past LibVLC's last report: no further than its reports come. */
private const val MAX_TIME_AHEAD = 1_000L

/** What a file holds, from every Track Media3 found in it, playable or not. */
private fun Tracks.contents(): FileContents {
    fun formats(type: Int) = groups.filter { it.type == type }.map { it.getTrackFormat(0) }
    val video = formats(C.TRACK_TYPE_VIDEO).firstOrNull()
    return FileContents(
        audio = formats(C.TRACK_TYPE_AUDIO).map { Track(it.language, it.sampleMimeType, it.channelCount.takeIf { count -> count != Format.NO_VALUE }) },
        subtitles = formats(C.TRACK_TYPE_TEXT).map { Track(it.language, it.sampleMimeType) },
        width = video?.width?.takeIf { it != Format.NO_VALUE },
        height = video?.height?.takeIf { it != Format.NO_VALUE },
        hdr = when {
            video == null -> null
            video.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION -> Hdr.DolbyVision
            video.colorInfo?.colorTransfer == C.COLOR_TRANSFER_ST2084 -> Hdr.HDR10
            else -> null
        },
    )
}

/**
 * What a file holds, from every Track VLC found in it. VLC names no HDR format, so a Header read's
 * stands, and knows no bitrate for a file.
 */
internal fun vlcContents(tracks: List<IMedia.Track>): FileContents {
    val video = tracks.filterIsInstance<IMedia.VideoTrack>().firstOrNull()
    return FileContents(
        audio = tracks.filter { it.type == IMedia.Track.Type.Audio }
            .map { Track(it.language, VLC_AUDIO_CODECS[it.codec?.trim()], (it as? IMedia.AudioTrack)?.channels?.takeIf { count -> count > 0 }) },
        subtitles = tracks.filter { it.type == IMedia.Track.Type.Text }.map(::vlcSubtitles),
        width = video?.width?.takeIf { it > 0 },
        height = video?.height?.takeIf { it > 0 },
    )
}

/** A VLC subtitle Track, its closed captions known by their fourcc. */
private fun vlcSubtitles(track: IMedia.Track) = Track(track.language, VLC_CAPTION_CODECS[track.codec?.trim()])

private val VLC_CAPTION_CODECS = mapOf("c608" to CEA_608, "c708" to CEA_708)

/** Media3's MIME types by VLC audio fourcc, as VLC names them, with trailing spaces trimmed. */
private val VLC_AUDIO_CODECS = mapOf(
    "mp4a" to "audio/mp4a-latm", "a52" to "audio/ac3", "eac3" to "audio/eac3", "trhd" to "audio/true-hd", "mlp" to "audio/true-hd",
    "dts" to "audio/vnd.dts", "Opus" to "audio/opus", "flac" to "audio/flac", "mpga" to "audio/mpeg", "vorb" to "audio/vorbis",
)

/** Draws subtitles without a style of their own in the Viewer's [style]: embedded ones, and downloaded ones over either engine. */
@OptIn(UnstableApi::class)
internal fun SubtitleView.draw(style: SubtitleStyle) {
    val (background, edge) = when (style.background) {
        SubtitleBackground.Outline -> Color.TRANSPARENT to CaptionStyleCompat.EDGE_TYPE_OUTLINE
        SubtitleBackground.Shadow -> Color.TRANSPARENT to CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW
        SubtitleBackground.Box -> SUBTITLE_BOX to CaptionStyleCompat.EDGE_TYPE_NONE
    }
    setStyle(CaptionStyleCompat(style.colour.argb, background, Color.TRANSPARENT, edge, Color.BLACK, null))
    // The Viewer's size and colour win over the file's own.
    setApplyEmbeddedFontSizes(false)
    setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * style.size.scale)
    setBottomPaddingFraction(style.position.fromBottom)
}

/** The dark box behind subtitles, at 70%. */
private const val SUBTITLE_BOX = 0xB3000000.toInt()

/** VLC's options for drawing subtitles in this style. */
private fun SubtitleStyle.vlcOptions(): List<String> = listOfNotNull(
    "--sub-text-scale=${(size.scale * 100).toInt()}",
    "--freetype-color=${colour.argb and 0xFFFFFF}",
    "--sub-margin=${(position.fromBottom * 400).toInt()}",
    when (background) {
        SubtitleBackground.Outline -> "--freetype-outline-thickness=4"
        SubtitleBackground.Shadow -> "--freetype-shadow-opacity=200"
        SubtitleBackground.Box -> "--freetype-background-opacity=180"
    },
)
