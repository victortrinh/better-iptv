package dev.betteriptv.app

import androidx.annotation.OptIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.extractor.text.ssa.SsaParser
import androidx.media3.extractor.text.subrip.SubripParser
import androidx.media3.extractor.text.webvtt.WebvttParser
import androidx.media3.ui.SubtitleView
import dev.betteriptv.core.DownloadedSubtitle
import dev.betteriptv.core.SubtitleOffer
import kotlinx.coroutines.delay

/**
 * A downloaded subtitle file ready to show: its [offer] and its cues. It is drawn over the picture by
 * the app rather than given to the engine, so it can come in while a file plays, on either engine,
 * without reopening the stream.
 */
@OptIn(UnstableApi::class)
internal class ShownSubtitle(val offer: SubtitleOffer, private val timed: List<CuesWithTiming>) {
    /** The cues showing [positionUs] into the film. */
    fun at(positionUs: Long): List<Cue> = timed.filter { positionUs >= it.startTimeUs && positionUs < it.end }.flatMap { it.cues }

    private val CuesWithTiming.end get() = if (durationUs == C.TIME_UNSET) startTimeUs + UNTIMED_CUE_US else endTimeUs

    companion object {
        /** Reads [file] with Media3's own parsers: SRT, WebVTT, or ASS without its styling. */
        fun parse(offer: SubtitleOffer, file: DownloadedSubtitle): ShownSubtitle {
            val parser: SubtitleParser = when (file.format) {
                "vtt" -> WebvttParser()
                "ass" -> SsaParser()
                else -> SubripParser()
            }
            val timed = ArrayList<CuesWithTiming>()
            parser.parse(file.text.toByteArray(), SubtitleParser.OutputOptions.allCues()) { timed += it }
            return ShownSubtitle(offer, timed.sortedBy { it.startTimeUs })
        }

        /** How long a cue the file gives no end shows for. */
        private const val UNTIMED_CUE_US = 4_000_000L
    }
}

/** Draws [subtitle] over [player]'s picture in the Viewer's [style], [delayMillis] later than it is timed. */
@OptIn(UnstableApi::class)
@Composable
internal fun DownloadedSubtitles(player: ControlledEngine, subtitle: ShownSubtitle, delayMillis: Long, style: SubtitleStyle) {
    val holder = remember { arrayOfNulls<SubtitleView>(1) }
    AndroidView(factory = { context -> SubtitleView(context).also { holder[0] = it } }, update = { it.draw(style) }, modifier = Modifier.fillMaxSize())
    LaunchedEffect(player, subtitle, delayMillis) {
        var shown: List<Cue>? = null
        while (true) {
            val cues = subtitle.at((player.position - delayMillis) * 1000)
            if (cues != shown) holder[0]?.setCues(cues)
            shown = cues
            delay(TICK_MILLIS)
        }
    }
}

/** How often the shown cues follow the playback position: well under a frame of reading time. */
private const val TICK_MILLIS = 50L
