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
import dev.betteriptv.core.CantOpen
import dev.betteriptv.core.DeviceProfile
import dev.betteriptv.core.Engine
import dev.betteriptv.core.FileContents
import dev.betteriptv.core.Hdr
import dev.betteriptv.core.PlayPlan
import dev.betteriptv.core.PlayerEngine
import dev.betteriptv.core.PlayerEvent
import dev.betteriptv.core.RememberedTracks
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
}

/** How long after a seek filling the buffer is taken for the seek's doing. */
private const val SEEK_GRACE_MILLIS = 2_000L

/** Media3 (ExoPlayer), the default engine. Used on the main thread only. */
@OptIn(UnstableApi::class)
class Media3Engine(context: Context, plan: PlayPlan, device: DeviceProfile, remembered: () -> RememberedTracks) : ControlledEngine {
    override val engine = Engine.Media3
    private val requests = OpenRequests()
    private val playing = buildPlayer(context, requests.counting(DefaultDataSource.Factory(context)))
    val player: ExoPlayer = playing.player.apply { addListener(TrackChooser(this, plan, device, remembered) { seen = it }) }
    override val position: Long get() = player.currentPosition
    override val duration: Long? get() = player.duration.takeIf { it > 0 }
    override val isPlaying: Boolean get() = player.playWhenReady
    override val buffered: Long get() = player.bufferedPosition
    private var subtitleView: SubtitleView? = null
    private var style = SubtitleStyle()

    @Volatile override var seen: FileContents? = null
        private set

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
        subtitleView?.apply {
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
    }

    override val frameRate: Float?
        get() = groups(C.TRACK_TYPE_VIDEO).firstOrNull { it.isSelected }?.getTrackFormat(0)?.frameRate?.takeIf { it > 0 }

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
    private val remembered: () -> RememberedTracks,
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
            remembered(),
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
    private val attached = CompletableDeferred<Unit>()
    private var from = 0L
    override val position: Long get() = player.time.takeIf { it > 0 } ?: from
    override val duration: Long? get() = player.length.takeIf { it > 0 }

    fun attach(layout: VLCVideoLayout) {
        player.attachViews(layout, null, true, false)
        attached.complete(Unit)
    }

    override val isPlaying: Boolean get() = player.isPlaying
    override val buffered: Long get() = position

    override fun togglePause() = if (player.isPlaying) player.pause() else player.play()

    override fun seek(by: Long) {
        player.setTime((player.time + by).coerceAtLeast(0))
    }

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
            val all = mediaTracks()
            val audio = all.filter { it.type == IMedia.Track.Type.Audio }
            if (audio.isEmpty()) return null
            val subtitles = all.filter { it.type == IMedia.Track.Type.Text }
            return FileTracks(
                audio.map { Track(it.language, channels = (it as? IMedia.AudioTrack)?.channels?.takeIf { count -> count > 0 }) },
                subtitles.map { Track(it.language) },
                audio.indexOfFirst { it.id == player.audioTrack }.takeIf { it >= 0 },
                subtitles.indexOfFirst { it.id == player.spuTrack }.takeIf { it >= 0 },
            )
        }

    override fun chooseAudio(index: Int) {
        mediaTracks().filter { it.type == IMedia.Track.Type.Audio }.getOrNull(index)?.let { player.setAudioTrack(it.id) }
    }

    override fun chooseSubtitles(index: Int?) {
        player.setSpuTrack(index?.let { mediaTracks().filter { it.type == IMedia.Track.Type.Text }.getOrNull(it)?.id } ?: -1)
    }

    override fun delay(audio: Long, subtitles: Long) {
        player.setAudioDelay(audio * 1000)
        player.setSpuDelay(subtitles * 1000)
    }

    // VLC draws subtitles with the options it was created with.
    override fun style(style: SubtitleStyle) = Unit

    override val frameRate: Float?
        get() = mediaTracks().filterIsInstance<IMedia.VideoTrack>().firstOrNull()
            ?.let { if (it.frameRateDen > 0) it.frameRateNum.toFloat() / it.frameRateDen else null }?.takeIf { it > 0 }

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
        val tracks = mediaTracks()
        val audio = tracks.filter { it.type == IMedia.Track.Type.Audio }
        val subtitles = tracks.filter { it.type == IMedia.Track.Type.Text }
        if (audio.isEmpty()) return false
        // VLC decodes every format itself, so its Tracks' formats don't change the choice.
        val choice = plan.chooseTracks(audio.map { Track(it.language) }, subtitles.map { Track(it.language) }, device, remembered())
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
