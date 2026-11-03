package dev.betteriptv.app

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import io.github.peerless2012.ass.media.kt.withAssSupport

/**
 * Media3's picture on the screen, and where its subtitles are drawn. Media3's own layout keeps its
 * subtitle view inside the picture's frame, so on a film wider than the screen they sat over the
 * picture instead of in the black bar under it (#292). Here text cues (SRT, WebVTT, MP4 timed text,
 * closed captions) go to a view over the whole [screen]: they sit the style's share of the screen's
 * height off its bottom edge, which is in the bar when there is one, and the same place as before on a
 * 16:9 film, where the frame is the screen. Bitmap cues (PGS, VobSub, DVB) are placed by the file on
 * its picture, so they stay in a view inside the frame, the [picture], and so does libass's ASS.
 * Downloaded subtitles are drawn over the screen too, by [DownloadedSubtitles].
 */
@OptIn(UnstableApi::class)
class Media3Picture(context: Context) : Player.Listener {
    val view: PlayerView = LayoutInflater.from(context).inflate(R.layout.media3_picture, null) as PlayerView
    private val frame: ViewGroup = view.findViewById(androidx.media3.ui.R.id.exo_content_frame)
    private val picture = SubtitleView(context).also(frame::addView)
    private val screen = SubtitleView(context).also(view::addView)

    /** Shows [playing] here, its ASS subtitles over the picture. */
    internal fun show(playing: Playing) {
        picture.withAssSupport(playing.ass)
        view.player = playing.player
        playing.player.addListener(this)
        show(playing.player.currentCues.cues)
    }

    override fun onCues(cueGroup: CueGroup) = show(cueGroup.cues)

    private fun show(cues: List<Cue>) {
        val (onPicture, onScreen) = split(cues)
        picture.setCues(onPicture)
        screen.setCues(onScreen)
    }

    /** Draws subtitles without a style of their own in [style]. */
    fun draw(style: SubtitleStyle) {
        picture.draw(style)
        screen.draw(style)
    }

    companion object {
        /** The cues drawn on the picture (bitmaps) and the ones drawn over the screen (text), in that order. */
        fun split(cues: List<Cue>): Pair<List<Cue>, List<Cue>> = cues.partition { it.bitmap != null }
    }
}
