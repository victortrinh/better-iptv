package dev.betteriptv.app

import android.content.Context
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Text
import dev.betteriptv.app.ui.theme.Colors
import dev.betteriptv.app.ui.theme.Spacing
import dev.betteriptv.app.ui.theme.Type
import dev.betteriptv.core.DeviceProfile
import dev.betteriptv.core.Library
import dev.betteriptv.core.PlayPlan
import dev.betteriptv.core.Track
import kotlinx.coroutines.awaitCancellation
import java.io.IOException

/**
 * Plays a [plan]'s Version full screen with Media3, holding the Viewer's one connection while it
 * plays. Its audio and subtitle Tracks are chosen by the Viewer's Content languages once the player
 * has read the file. Leaving the screen stops the player before the connection is given back.
 */
@Composable
fun PlayerScreen(library: Library, plan: PlayPlan) {
    val context = LocalContext.current
    var player by remember { mutableStateOf<Playing?>(null) }
    var failed by remember(plan) { mutableStateOf(false) }

    LaunchedEffect(plan) {
        val connection = try {
            library.connect(plan.version)
        } catch (e: IOException) {
            // Logged out, or the catalog couldn't be read: nothing to play.
            failed = true
            return@LaunchedEffect
        }
        var playing: Playing? = null
        try {
            playing = buildPlayer(context)
            val exo = playing.player
            exo.addListener(TrackChooser(exo, plan, readDeviceProfile(context)))
            exo.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) { failed = true }
            })
            exo.setMediaItem(MediaItem.fromUri(connection.location))
            exo.prepare()
            exo.playWhenReady = true
            player = playing
            awaitCancellation()
        } finally {
            player = null
            // Release closes the player's request to the Provider, so only then is the connection given back.
            playing?.release()
            connection.close()
        }
    }

    Box(Modifier.fillMaxSize().background(Colors.ground)) {
        // A new player needs a new view: the view's ASS overlay is tied to the player's handler.
        player?.let { playing -> key(playing) { PlayerSurface(context, playing) } }
        if (failed) {
            Text(
                stringResource(R.string.player_failed),
                style = Type.body,
                color = Colors.error,
                modifier = Modifier.align(Alignment.Center).padding(Spacing.xl),
            )
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun PlayerSurface(context: Context, playing: Playing) {
    AndroidView(
        factory = {
            PlayerView(context).apply {
                // The remote's keys reach the controls only while the view has focus.
                isFocusable = true
                isFocusableInTouchMode = true
                setShowSubtitleButton(true)
                playing.showIn(this)
                requestFocus()
            }
        },
        modifier = Modifier.fillMaxSize(),
    )
}

/** Applies the [plan]'s Track choice for [device] once, as soon as the player knows the file's Tracks. */
private class TrackChooser(private val player: ExoPlayer, private val plan: PlayPlan, private val device: DeviceProfile) : Player.Listener {
    private var chosen = false

    override fun onTracksChanged(tracks: Tracks) {
        if (chosen || tracks.isEmpty) return
        chosen = true
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
