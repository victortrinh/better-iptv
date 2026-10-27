package dev.betteriptv.app

import android.content.Context
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
import androidx.media3.ui.PlayerView
import dev.betteriptv.core.CantOpen
import dev.betteriptv.core.DeviceProfile
import dev.betteriptv.core.Engine
import dev.betteriptv.core.FileContents
import dev.betteriptv.core.Hdr
import dev.betteriptv.core.PlayPlan
import dev.betteriptv.core.PlayerEngine
import dev.betteriptv.core.PlayerEvent
import dev.betteriptv.core.Track
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.util.VLCVideoLayout
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration.Companion.seconds

private const val PLAYER_TAG = "Player"

/**
 * Creates the engine a Playback session asks for, choosing a [plan]'s Tracks for this stick and TV
 * once the file is open.
 */
fun playerEngines(context: Context, plan: PlayPlan): (Engine) -> PlayerEngine {
    val device = readDeviceProfile(context)
    return { engine ->
        when (engine) {
            Engine.Media3 -> Media3Engine(context, plan, device)
            Engine.Vlc -> VlcEngine(context, plan, device)
        }
    }
}

/** How long after a seek filling the buffer is taken for the seek's doing. */
private const val SEEK_GRACE_MILLIS = 2_000L

/** Media3 (ExoPlayer), the default engine. Used on the main thread only. */
@OptIn(UnstableApi::class)
class Media3Engine(context: Context, plan: PlayPlan, device: DeviceProfile) : PlayerEngine {
    override val engine = Engine.Media3
    private val requests = OpenRequests()
    private val playing = buildPlayer(context, requests.counting(DefaultDataSource.Factory(context)))
    val player: ExoPlayer = playing.player.apply { addListener(TrackChooser(this, plan, device) { seen = it }) }
    override val position: Long get() = player.currentPosition
    override val duration: Long? get() = player.duration.takeIf { it > 0 }

    @Volatile override var seen: FileContents? = null
        private set

    /** Shows the player in a [view] made for it, ASS subtitles included. */
    fun showIn(view: PlayerView) = playing.showIn(view)

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

/** Counts the player's open requests to the Provider, so releasing can wait for the last one to close. */
@OptIn(UnstableApi::class)
private class OpenRequests {
    private val open = MutableStateFlow(0)

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
    private val onSeen: (FileContents) -> Unit,
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
            subtitles.map { Track(it.getTrackFormat(0).language) },
            device,
        )
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().apply {
            choice.audio?.let { setOverrideForType(TrackSelectionOverride(audio[it].mediaTrackGroup, 0)) }
            // Without a choice, a file's "default" subtitles stay off; forced ones (for foreign dialogue) still show.
            choice.subtitle?.let { setOverrideForType(TrackSelectionOverride(subtitles[it].mediaTrackGroup, 0)) }
                ?: setIgnoredTextSelectionFlags(C.SELECTION_FLAG_DEFAULT)
        }.build()
    }
}

/**
 * The VLC engine, for the rare file Media3 can't open. It decodes in software what the stick can't
 * in hardware. Its picture needs a [VLCVideoLayout]: playback starts once one is [attach]ed.
 * Used on the main thread only.
 */
class VlcEngine(context: Context, private val plan: PlayPlan, private val device: DeviceProfile) : PlayerEngine {
    override val engine = Engine.Vlc

    // To read a file's index and to seek, VLC opens a new request before closing the one it has.
    override val overlapsRequests = true
    private val vlc = LibVLC(context.applicationContext, arrayListOf("--http-reconnect"))
    private val player = MediaPlayer(vlc)
    private val attached = CompletableDeferred<Unit>()
    private var from = 0L
    override val position: Long get() = player.time.takeIf { it > 0 } ?: from
    override val duration: Long? get() = player.length.takeIf { it > 0 }

    fun attach(layout: VLCVideoLayout) {
        player.attachViews(layout, null, true, false)
        attached.complete(Unit)
    }

    /** Pauses, or plays again. */
    fun togglePause() = if (player.isPlaying) player.pause() else player.play()

    /** Jumps [by] milliseconds forward, or back when negative. */
    fun seek(by: Long) {
        player.setTime((player.time + by).coerceAtLeast(0))
    }

    override suspend fun play(location: String, from: Long, events: (PlayerEvent) -> Unit) {
        this.from = from
        attached.await()
        suspendCancellableCoroutine { continuation ->
            var tracksChosen = false
            var started = false
            player.setEventListener { event ->
                when (event.type) {
                    MediaPlayer.Event.EndReached -> if (continuation.isActive) continuation.resume(Unit)
                    // VLC doesn't say whether the file or the stream failed; it's the last resort either way.
                    MediaPlayer.Event.EncounteredError -> if (continuation.isActive) continuation.resumeWithException(CantOpen("VLC can't play the file"))
                    MediaPlayer.Event.Playing, MediaPlayer.Event.ESAdded -> {
                        if (!tracksChosen) tracksChosen = chooseTracks()
                        if (event.type == MediaPlayer.Event.Playing && !started) {
                            started = true
                            events(PlayerEvent.Started)
                        }
                    }
                }
            }
            val media = Media(vlc, Uri.parse(location))
            media.addOption(":start-time=${from / 1000.0}")
            player.media = media
            // The player keeps its own reference.
            media.release()
            player.play()
        }
    }

    /** Applies the [plan]'s Track choice; false while VLC doesn't know the file's audio Tracks yet. */
    private fun chooseTracks(): Boolean {
        val media = player.media ?: return false
        val tracks = try {
            (0 until media.trackCount).map(media::getTrack)
        } finally {
            media.release()
        }
        val audio = tracks.filter { it.type == IMedia.Track.Type.Audio }
        val subtitles = tracks.filter { it.type == IMedia.Track.Type.Text }
        if (audio.isEmpty()) return false
        // VLC decodes every format itself, so its Tracks' formats don't change the choice.
        val choice = plan.chooseTracks(audio.map { Track(it.language) }, subtitles.map { Track(it.language) }, device)
        choice.audio?.let { player.setAudioTrack(audio[it].id) }
        player.setSpuTrack(choice.subtitle?.let { subtitles[it].id } ?: -1)
        return true
    }

    override suspend fun release() {
        player.setEventListener(null)
        player.stop()
        player.detachViews()
        player.release()
        vlc.release()
    }
}

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
