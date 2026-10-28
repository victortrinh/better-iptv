package dev.betteriptv.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.ui.PlayerView
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegAudioRenderer
import io.github.peerless2012.ass.media.AssHandler
import io.github.peerless2012.ass.media.AssHandlerConfig
import io.github.peerless2012.ass.media.factory.AssRenderersFactory
import io.github.peerless2012.ass.media.kt.withAssMkvSupport
import io.github.peerless2012.ass.media.kt.withAssSupport
import io.github.peerless2012.ass.media.parser.AssSubtitleParserFactory
import io.github.peerless2012.ass.media.type.AssRenderType

/**
 * A Media3 player with the libass [ass] handler that draws its ASS subtitles, and the [delays] its
 * renderers play the sound and subtitles with.
 */
@OptIn(UnstableApi::class)
internal class Playing(val player: ExoPlayer, val ass: AssHandler, val delays: Delays) {
    /** Shows the player in a [view] made for it: ASS subtitles are drawn over the picture by libass, not by Media3's subtitle view. */
    fun showIn(view: PlayerView) {
        view.subtitleView?.withAssSupport(ass)
        view.player = player
    }

    fun release() {
        player.release()
        ass.release()
    }
}

/**
 * A player for every format the Provider's files come in: MKV, MP4, AVI and TS (Media3's own
 * extractors), Blu-ray DTS and TrueHD audio (FFmpeg), and SRT, WebVTT, PGS, VobSub, closed captions
 * (Media3's own decoders) and ASS (libass). Files are read through [dataSources].
 */
@OptIn(UnstableApi::class)
internal fun buildPlayer(context: Context, dataSources: DataSource.Factory = DefaultDataSource.Factory(context)): Playing {
    val delays = Delays()
    // libass draws on the stick's GPU; the size cap keeps a 4K TV from costing four times a 1080p one.
    val ass = AssHandler(AssRenderType.OVERLAY_OPEN_GL, AssHandlerConfig(maxRenderPixels = 1920 * 1080))
    try {
        val subtitleParsers = AssSubtitleParserFactory(ass)
        // Blu-ray rips in TS files carry DTS under the type Blu-ray gives it, which Media3 skips by default.
        val extractors = DefaultExtractorsFactory()
            .setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS)
            // Live channels carry closed captions in their video; without a caption format, Media3 drops them.
            .setTsSubtitleFormats(listOf(Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_CEA608).build()))
        val mediaSources = DefaultMediaSourceFactory(dataSources, extractors.withAssMkvSupport(subtitleParsers, ass))
            .setSubtitleParserFactory(subtitleParsers)
            .setLoadErrorHandlingPolicy(ProviderLoadErrorPolicy())
        val renderers = FfmpegAudioRenderersFactory(context, delays).setEnableDecoderFallback(true)
        val player = ExoPlayer.Builder(context)
            .setMediaSourceFactory(mediaSources)
            .setRenderersFactory(AssRenderersFactory(ass, renderers))
            .build()
        ass.init(player)
        return Playing(player, ass, delays)
    } catch (e: Throwable) {
        ass.release()
        throw e
    }
}

/**
 * Media3's retries, except for an answer the Provider won't change on a retry: a 4xx (a dead
 * Listing answers 404) or a refused login (511, 513). Such a Version fails at once, so its Backup
 * starts instead of waiting out about 3 s of retries. Timeouts and other 5xx answers can be passing
 * trouble, and are still retried; so are 403 and 429, which may be the Provider refusing a stream
 * over the Connection limit while it still counts the one Media3 closed to seek.
 */
@OptIn(UnstableApi::class)
internal class ProviderLoadErrorPolicy : DefaultLoadErrorHandlingPolicy() {
    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorInfo): Long {
        val answer = generateSequence<Throwable>(loadErrorInfo.exception) { it.cause }.filterIsInstance<InvalidResponseCodeException>().firstOrNull()
        return if (answer != null && answer.responseCode.isFinal()) C.TIME_UNSET else super.getRetryDelayMsFor(loadErrorInfo)
    }

    private fun Int.isFinal() = (this in 400..499 && this !in PASSING_4XX) || this == 511 || this == 513
}

/** The 4xx answers that can change on a retry: refused for now, timed out, too many requests. */
private val PASSING_4XX = setOf(403, 408, 429)

/** How much later than the picture the Viewer wants the sound and the subtitles, in microseconds; negative is earlier. */
internal class Delays {
    @Volatile var audioUs = 0L
    @Volatile var subtitlesUs = 0L
}

/**
 * Media3's renderers with NextLib's FFmpeg audio decoder after the stick's own audio path, so a
 * format is passed through, else decoded by the stick, and only then by FFmpeg: the order
 * DeviceProfile.audioOutput assumes. NextLib's own factory also adds FFmpeg video and text
 * renderers, which the app doesn't want. Sound and subtitles play with the Viewer's [delays].
 */
@OptIn(UnstableApi::class)
private class FfmpegAudioRenderersFactory(context: Context, private val delays: Delays) : DefaultRenderersFactory(context) {
    override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioOutputPlaybackParams: Boolean): AudioSink? =
        super.buildAudioSink(context, enableFloatOutput, enableAudioOutputPlaybackParams)?.let { DelayedAudioSink(it, delays) }

    override fun buildTextRenderers(context: Context, output: TextOutput, outputLooper: Looper, extensionRendererMode: Int, out: ArrayList<Renderer>) {
        val built = ArrayList<Renderer>()
        super.buildTextRenderers(context, output, outputLooper, extensionRendererMode, built)
        built.mapTo(out) { DelayedRenderer(it, delays) }
    }

    override fun buildAudioRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        audioSink: AudioSink,
        eventHandler: Handler,
        eventListener: AudioRendererEventListener,
        out: ArrayList<Renderer>,
    ) {
        super.buildAudioRenderers(context, extensionRendererMode, mediaCodecSelector, enableDecoderFallback, audioSink, eventHandler, eventListener, out)
        out.add(FfmpegAudioRenderer(eventHandler, eventListener, audioSink))
    }
}

/**
 * The audio sink, its clock moved by the audio delay. The picture follows the sound's clock, so
 * reporting the sound [Delays.audioUs] further on shows the picture that much earlier against it.
 */
@OptIn(UnstableApi::class)
private class DelayedAudioSink(sink: AudioSink, private val delays: Delays) : ForwardingAudioSink(sink) {
    override fun getCurrentPositionUs(sourceEnded: Boolean): Long =
        super.getCurrentPositionUs(sourceEnded).let { if (it == AudioSink.CURRENT_POSITION_NOT_SET) it else it + delays.audioUs }
}

/** A text renderer that shows each subtitle [Delays.subtitlesUs] later, by rendering that far behind the playback position. */
@OptIn(UnstableApi::class)
private class DelayedRenderer(private val renderer: Renderer, private val delays: Delays) : Renderer by renderer {
    override fun render(positionUs: Long, elapsedRealtimeUs: Long) = renderer.render(positionUs - delays.subtitlesUs, elapsedRealtimeUs)

    // Kotlin doesn't delegate the interface's Java default methods.
    override fun getDurationToProgressUs(positionUs: Long, elapsedRealtimeUs: Long) = renderer.getDurationToProgressUs(positionUs, elapsedRealtimeUs)
    override fun supportsResetPositionWithoutKeyFrameReset(positionUs: Long) = renderer.supportsResetPositionWithoutKeyFrameReset(positionUs)
    override fun setPlaybackSpeed(currentPlaybackSpeed: Float, targetPlaybackSpeed: Float) = renderer.setPlaybackSpeed(currentPlaybackSpeed, targetPlaybackSpeed)
    override fun enableMayRenderStartOfStream() = renderer.enableMayRenderStartOfStream()
    override fun release() = renderer.release()
}
