package dev.betteriptv.app.prototype

import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.ui.PlayerView
import dev.betteriptv.app.Playing
import dev.betteriptv.app.buildPlayer
import dev.betteriptv.app.readDeviceProfile

/**
 * Plays a sample file with the app's player, in debug builds only, to check a format on the stick
 * without the Provider. It logs (tag `Samples`) each Track, the audio output DeviceProfile.audioOutput
 * predicts, the path Media3 actually took, and subtitle cues.
 *
 *     adb shell am start -n dev.betteriptv.debug/dev.betteriptv.app.prototype.SampleActivity \
 *         -d http://192.168.4.20:8000/dts.mkv --ei audio 0 --ei subtitle 1
 *
 * `audio` and `subtitle` pick Tracks by index among the file's audio and subtitle Tracks; `sidecar`
 * adds a subtitle file beside it (WebVTT, SRT or ASS, by its extension), as downloaded subtitles will be.
 */
@OptIn(UnstableApi::class)
class SampleActivity : ComponentActivity() {
    private var playing: Playing? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val device = readDeviceProfile(this)
        Log.i(TAG, "passthrough=${device.audioPassthrough} decoders=${device.audioDecoders}")
        val audioIndex = intent.getIntExtra("audio", -1)
        val subtitleIndex = intent.getIntExtra("subtitle", -1)
        val playing = buildPlayer(this).also { playing = it }
        val player = playing.player
        player.addListener(object : Player.Listener {
            private var chosen = false

            override fun onTracksChanged(tracks: Tracks) {
                if (chosen || tracks.isEmpty) return
                chosen = true
                val audio = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
                val subtitles = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
                audio.forEachIndexed { i, group ->
                    val format = group.getTrackFormat(0)
                    Log.i(TAG, "audio $i ${format.describe()} supported=${group.isSupported} predicted=${device.audioOutput(format.sampleMimeType)}")
                }
                subtitles.forEachIndexed { i, group ->
                    Log.i(TAG, "subtitle $i ${group.getTrackFormat(0).describe()} supported=${group.isSupported}")
                }
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().apply {
                    audio.getOrNull(audioIndex)?.let { setOverrideForType(TrackSelectionOverride(it.mediaTrackGroup, 0)) }
                    subtitles.getOrNull(subtitleIndex)?.let { setOverrideForType(TrackSelectionOverride(it.mediaTrackGroup, 0)) }
                }.build()
            }

            override fun onCues(cueGroup: CueGroup) {
                cueGroup.cues.forEach { Log.i(TAG, "cue text=${it.text} bitmap=${it.bitmap?.let { b -> "${b.width}x${b.height}" }}") }
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG, "error ${error.errorCodeName}", error)
            }
        })
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onAudioDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
                Log.i(TAG, "audio decoder $decoderName")
            }

            override fun onAudioTrackInitialized(eventTime: AnalyticsListener.EventTime, audioTrackConfig: AudioSink.AudioTrackConfig) {
                val path = if (Util.isEncodingLinearPcm(audioTrackConfig.encoding)) "PCM" else "passthrough"
                Log.i(TAG, "audio track $path encoding=${audioTrackConfig.encoding} channels=${audioTrackConfig.channelConfig}")
            }

            override fun onAudioInputFormatChanged(eventTime: AnalyticsListener.EventTime, format: Format, decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?) {
                Log.i(TAG, "audio format ${format.describe()}")
            }

            override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
                Log.i(TAG, "video decoder $decoderName")
            }
        })
        setContentView(PlayerView(this).apply {
            useController = false
            playing.showIn(this)
        })
        val file = intent.data ?: return finish().also { Log.e(TAG, "no sample: start with -d <file>") }
        val sidecars = listOfNotNull(intent.getStringExtra("sidecar")).map { uri ->
            val type = when (uri.substringAfterLast('.')) {
                "vtt" -> MimeTypes.TEXT_VTT
                "ass", "ssa" -> MimeTypes.TEXT_SSA
                else -> MimeTypes.APPLICATION_SUBRIP
            }
            MediaItem.SubtitleConfiguration.Builder(Uri.parse(uri)).setMimeType(type).build()
        }
        player.setMediaItem(MediaItem.Builder().setUri(file).setSubtitleConfigurations(sidecars).build())
        // Samples are short: looping keeps them on screen for a screenshot.
        player.repeatMode = Player.REPEAT_MODE_ALL
        player.prepare()
        player.playWhenReady = true
    }

    override fun onDestroy() {
        playing?.release()
        super.onDestroy()
    }

    private fun Format.describe() = "$sampleMimeType codecs=$codecs lang=$language channels=$channelCount label=$label"

    private companion object {
        const val TAG = "Samples"
    }
}
