package dev.betteriptv.app

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.ui.PlayerView
import io.github.peerless2012.ass.media.AssHandler
import io.github.peerless2012.ass.media.AssHandlerConfig
import io.github.peerless2012.ass.media.factory.AssRenderersFactory
import io.github.peerless2012.ass.media.kt.withAssMkvSupport
import io.github.peerless2012.ass.media.kt.withAssSupport
import io.github.peerless2012.ass.media.parser.AssSubtitleParserFactory
import io.github.peerless2012.ass.media.type.AssRenderType

/** A Media3 player with the libass [ass] handler that draws its ASS subtitles. */
@OptIn(UnstableApi::class)
internal class Playing(val player: ExoPlayer, val ass: AssHandler) {
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
 * (Media3's own decoders) and ASS (libass).
 */
@OptIn(UnstableApi::class)
internal fun buildPlayer(context: Context): Playing {
    // libass draws on the stick's GPU; the size cap keeps a 4K TV from costing four times a 1080p one.
    val ass = AssHandler(AssRenderType.OVERLAY_OPEN_GL, AssHandlerConfig(maxRenderPixels = 1920 * 1080))
    try {
        val subtitleParsers = AssSubtitleParserFactory(ass)
        // Blu-ray rips in TS files carry DTS under the type Blu-ray gives it, which Media3 skips by default.
        val extractors = DefaultExtractorsFactory()
            .setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS)
            // Live channels carry closed captions in their video; without a caption format, Media3 drops them.
            .setTsSubtitleFormats(listOf(Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_CEA608).build()))
        val mediaSources = DefaultMediaSourceFactory(DefaultDataSource.Factory(context), extractors.withAssMkvSupport(subtitleParsers, ass))
            .setSubtitleParserFactory(subtitleParsers)
        // The stick's own audio path comes first, so a format is passed through, else decoded by the stick,
        // and only then by FFmpeg: the order DeviceProfile.audioOutput assumes.
        val renderers = DefaultRenderersFactory(context)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true)
        val player = ExoPlayer.Builder(context)
            .setMediaSourceFactory(mediaSources)
            .setRenderersFactory(AssRenderersFactory(ass, renderers))
            .build()
        ass.init(player)
        return Playing(player, ass)
    } catch (e: Throwable) {
        ass.release()
        throw e
    }
}
