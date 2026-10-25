package dev.betteriptv.app

import android.view.View
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Text
import dev.betteriptv.app.ui.components.NuageButton
import dev.betteriptv.app.ui.theme.Colors
import dev.betteriptv.app.ui.theme.Corners
import dev.betteriptv.app.ui.theme.Spacing
import dev.betteriptv.app.ui.theme.Type
import dev.betteriptv.core.Engine
import dev.betteriptv.core.Library
import dev.betteriptv.core.PlayPlan
import dev.betteriptv.core.PlaybackState
import dev.betteriptv.core.PlayerEngine
import org.videolan.libvlc.util.VLCVideoLayout

/**
 * Plays a [plan]'s Version full screen through a Playback session: on Media3, or on the VLC engine
 * when Media3 can't open the file. The remote's Menu key offers "Try other player", which moves to
 * the other engine at the same position. Leaving the screen stops playback and gives back the
 * Viewer's connection; [onEnded] is called once the file plays to its end.
 */
@Composable
fun PlayerScreen(library: Library, plan: PlayPlan, onEnded: () -> Unit) {
    val context = LocalContext.current
    val session = remember(plan) { library.playback(plan, playerEngines(context, plan)) }
    val state by session.state.collectAsState()
    var menuOpen by remember(session) { mutableStateOf(false) }

    LaunchedEffect(session) {
        session.play()
        onEnded()
    }

    Box(
        Modifier.fillMaxSize().background(Colors.ground).onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown || menuOpen) return@onPreviewKeyEvent false
            val player = (state as? PlaybackState.Playing)?.player ?: return@onPreviewKeyEvent false
            when {
                event.key == Key.Menu -> menuOpen = true
                // The VLC engine has no controls of its own until the player overlay (#13).
                player is VlcEngine -> when (event.key) {
                    Key.DirectionCenter, Key.Enter, Key.MediaPlayPause -> player.togglePause()
                    Key.DirectionLeft, Key.MediaRewind -> player.seek(-SEEK_STEP)
                    Key.DirectionRight, Key.MediaFastForward -> player.seek(SEEK_STEP)
                    else -> return@onPreviewKeyEvent false
                }
                else -> return@onPreviewKeyEvent false
            }
            true
        },
    ) {
        when (val current = state) {
            is PlaybackState.Playing -> key(current.player) { EngineSurface(current.player, focused = !menuOpen) }
            PlaybackState.Failed -> Failed(onTryOtherPlayer = session::tryOtherPlayer)
            PlaybackState.Connecting, PlaybackState.Ended -> Unit
        }
        val playing = (state as? PlaybackState.Playing)?.player
        if (menuOpen && playing != null) {
            BackHandler { menuOpen = false }
            PlayerMenu(
                playing.engine,
                onTryOtherPlayer = {
                    menuOpen = false
                    session.tryOtherPlayer()
                },
                modifier = Modifier.align(Alignment.BottomEnd).padding(horizontal = Spacing.screenX, vertical = Spacing.screenY),
            )
        }
    }
}

private const val SEEK_STEP = 10_000L

/** The engine's picture, taking the remote's keys while [focused]. */
@Composable
private fun EngineSurface(player: PlayerEngine, focused: Boolean) {
    val takeFocus = { view: View -> if (focused && !view.hasFocus()) view.requestFocus() }
    when (player) {
        is Media3Engine -> Media3Surface(player, takeFocus)
        is VlcEngine -> AndroidView(
            factory = { context -> VLCVideoLayout(context).apply { isFocusable = true }.also(player::attach) },
            update = { takeFocus(it) },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun Media3Surface(player: Media3Engine, takeFocus: (View) -> Unit) {
    AndroidView(
        factory = { context ->
            PlayerView(context).apply {
                // The remote's keys reach the controls only while the view has focus.
                isFocusable = true
                isFocusableInTouchMode = true
                setShowSubtitleButton(true)
                this.player = player.player
            }
        },
        update = { takeFocus(it) },
        modifier = Modifier.fillMaxSize(),
    )
}

/** What the Menu key opens: which player is playing, and the other one. */
@Composable
private fun PlayerMenu(engine: Engine, onTryOtherPlayer: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(Corners.card)
    Column(
        // Glass carrying text over the picture: ground at 75% first, then glass.
        modifier.background(Colors.veil, shape).background(Colors.surface, shape).padding(Spacing.l),
        verticalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        Text(stringResource(R.string.player_engine, engineName(engine)), style = Type.caption, color = Colors.inkMuted)
        TryOtherPlayer(onTryOtherPlayer)
    }
}

@Composable
private fun Failed(onTryOtherPlayer: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.align(Alignment.Center).padding(Spacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.l),
        ) {
            Text(stringResource(R.string.player_failed), style = Type.body, color = Colors.error, textAlign = TextAlign.Center)
            TryOtherPlayer(onTryOtherPlayer)
        }
    }
}

/** The "Try other player" button, which takes focus as it appears. */
@Composable
private fun TryOtherPlayer(onClick: () -> Unit) {
    val focus = remember { FocusRequester() }
    NuageButton(stringResource(R.string.player_try_other), onClick, Modifier.focusRequester(focus))
    LaunchedEffect(Unit) { focus.requestFocus() }
}

@Composable
private fun engineName(engine: Engine) = stringResource(
    when (engine) {
        Engine.Media3 -> R.string.player_engine_media3
        Engine.Vlc -> R.string.player_engine_vlc
    },
)
