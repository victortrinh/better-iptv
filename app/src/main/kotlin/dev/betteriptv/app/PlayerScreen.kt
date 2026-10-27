package dev.betteriptv.app

import android.view.View
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Text
import dev.betteriptv.app.ui.components.NuageButton
import dev.betteriptv.app.ui.components.VersionHeader
import dev.betteriptv.app.ui.components.VersionRow
import dev.betteriptv.app.ui.theme.Colors
import dev.betteriptv.app.ui.theme.Corners
import dev.betteriptv.app.ui.theme.Spacing
import dev.betteriptv.app.ui.theme.Type
import dev.betteriptv.core.Engine
import dev.betteriptv.core.Library
import dev.betteriptv.core.PlayPlan
import dev.betteriptv.core.PlaybackState
import dev.betteriptv.core.PlayerEngine
import dev.betteriptv.core.Version
import kotlinx.coroutines.delay
import org.videolan.libvlc.util.VLCVideoLayout
import kotlin.time.Duration.Companion.seconds

/**
 * Plays a [plan]'s Version full screen through a Playback session, which falls back to the VLC
 * engine when Media3 can't open a file and to the Version's Backups when it is dead. The Version
 * playing is named when it starts; repeated buffering brings a one-tap suggestion of a lower Version.
 * The remote's Menu key opens the Versions to switch to partway through, and "Try other player",
 * which moves to the other engine at the same position. Leaving the screen stops playback and gives
 * back the Viewer's connection; [onEnded] is called once the file plays to its end.
 */
@Composable
fun PlayerScreen(library: Library, plan: PlayPlan, onEnded: () -> Unit) {
    val context = LocalContext.current
    val session = remember(plan) { library.playback(plan, playerEngines(context, plan)) }
    val state by session.state.collectAsState()
    var menuOpen by remember(session) { mutableStateOf(false) }
    val screen = remember { FocusRequester() }

    LaunchedEffect(session) {
        session.play()
        onEnded()
    }

    val player = when (val current = state) {
        is PlaybackState.Starting -> current.player
        is PlaybackState.Playing -> current.player
        PlaybackState.Failed, PlaybackState.Ended -> null
    }
    // Not over the Versions, whose focus it would take.
    val suggested = (state as? PlaybackState.Playing)?.lowerVersion?.takeIf { !menuOpen }
    // With no picture to hold it, the screen keeps focus so the Menu key still reaches it. It keeps it
    // under the error too, until "Try other player" asks: focus handed over as the button appears
    // would arrive before the button can show it.
    val bare = player == null && !menuOpen
    LaunchedEffect(bare) { if (bare) screen.requestFocus() }

    Box(
        Modifier.fillMaxSize().background(Colors.ground).onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown || menuOpen) return@onPreviewKeyEvent false
            when {
                event.key == Key.Menu -> menuOpen = state !is PlaybackState.Ended
                // The VLC engine has no controls of its own until the player overlay (#13).
                player is VlcEngine && suggested == null -> when (event.key) {
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
        // Beside the picture, not around it: Back would spend a press moving focus up to a focusable parent.
        if (bare) Box(Modifier.focusRequester(screen).focusable())
        if (player != null) key(player) { EngineSurface(player, focused = !menuOpen && suggested == null) }

        when (val shown = state) {
            is PlaybackState.Starting -> VersionLabel(stringResource(R.string.player_starting, context.playLabel(shown.version)))
            is PlaybackState.Playing -> {
                // Named for a few seconds each time a Version starts, so a fallback to a Backup is seen.
                var named by remember(shown.version, shown.player) { mutableStateOf(true) }
                LaunchedEffect(shown.version, shown.player) {
                    delay(NAMED_FOR)
                    named = false
                }
                if (named) VersionLabel(context.playLabel(shown.version))
                suggested?.let { lower ->
                    BufferingSuggestion(lower, onSwitch = { session.switchTo(lower) }, onDismiss = session::dismissSuggestion)
                }
            }
            // Under the open Versions it would show through.
            PlaybackState.Failed -> if (!menuOpen) Failed(onTryOtherPlayer = session::tryOtherPlayer)
            PlaybackState.Ended -> Unit
        }

        if (menuOpen) {
            VersionsPanel(
                plan.versions,
                state.version,
                engine = player?.engine,
                onTryOtherPlayer = {
                    menuOpen = false
                    session.tryOtherPlayer()
                },
                onPick = { version ->
                    menuOpen = false
                    session.switchTo(version)
                },
                onClose = { menuOpen = false },
            )
        }
    }
}

private const val SEEK_STEP = 10_000L

/** How long a Version stays named once it starts. */
private val NAMED_FOR = 5.seconds

/** How long the buffering suggestion waits for the Viewer before stepping aside. */
private val SUGGESTED_FOR = 15.seconds

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
                player.showIn(this)
            }
        },
        update = { takeFocus(it) },
        modifier = Modifier.fillMaxSize(),
    )
}

/** The player's scrim over the top of the picture: 85% ground, 60% by 12% of the screen, then clear. */
private val topScrim = Brush.verticalGradient(0f to Colors.ground.copy(alpha = 0.85f), 0.12f to Colors.ground.copy(alpha = 0.6f), 0.28f to Colors.ground.copy(alpha = 0f))

/** The player's scrim over the bottom of the picture, 85% ground at the edge. */
private val bottomScrim = Brush.verticalGradient(0f to Colors.ground.copy(alpha = 0f), 1f to Colors.ground.copy(alpha = 0.85f))

/** Names a Version over the top of the picture, with the key that opens the others. */
@Composable
private fun VersionLabel(text: String) {
    Row(
        Modifier.fillMaxSize().background(topScrim).padding(horizontal = Spacing.screenX, vertical = Spacing.screenY),
        verticalAlignment = Alignment.Top,
    ) {
        Text(text, style = Type.label, color = Colors.ink, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
        Spacer(Modifier.width(Spacing.m))
        Text(stringResource(R.string.player_versions_hint), style = Type.caption, color = Colors.inkMuted)
    }
}

/**
 * The one-tap suggestion of a [lower] Version after repeated buffering. It takes focus so one press
 * switches; Back, or waiting, dismisses it. Nothing switches without the Viewer.
 */
@Composable
private fun BufferingSuggestion(lower: Version, onSwitch: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val button = remember { FocusRequester() }
    LaunchedEffect(lower) {
        button.requestFocus()
        delay(SUGGESTED_FOR)
        onDismiss()
    }
    BackHandler(onBack = onDismiss)
    val shape = RoundedCornerShape(Corners.control)
    Box(Modifier.fillMaxSize().padding(horizontal = Spacing.screenX, vertical = Spacing.screenY)) {
        Row(
            // Glass carrying text over the picture: ground at 75% first, then glass.
            Modifier.align(Alignment.TopEnd)
                .background(Colors.veil, shape)
                .background(Colors.surface, shape)
                .padding(start = Spacing.l, end = Spacing.s, top = Spacing.s, bottom = Spacing.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(stringResource(R.string.player_buffering), style = Type.label, color = Colors.ink)
                Text(stringResource(R.string.player_buffering_lower, context.playLabel(lower)), style = Type.caption, color = Colors.inkMuted)
            }
            Spacer(Modifier.width(Spacing.m))
            NuageButton(stringResource(R.string.player_switch), onClick = onSwitch, modifier = Modifier.focusRequester(button))
        }
    }
}

/**
 * What the Menu key opens over the bottom of the picture: the player playing and "Try other player",
 * then the Title's Versions; picking one switches to it. The one [playing], or the one whose Backup
 * is playing, is marked, and picking it just closes the list. With an [engine] playing, "Try other
 * player" takes first focus; without one, the first Version does.
 */
@Composable
private fun VersionsPanel(
    versions: List<Version>,
    playing: Version?,
    engine: Engine?,
    onTryOtherPlayer: () -> Unit,
    onPick: (Version) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val first = remember { FocusRequester() }
    if (engine == null) LaunchedEffect(Unit) { first.requestFocus() }
    BackHandler(onBack = onClose)
    Box(Modifier.fillMaxSize().background(bottomScrim)) {
        Column(
            Modifier.align(Alignment.BottomStart).fillMaxWidth().fillMaxHeight(0.62f).padding(horizontal = Spacing.screenX),
        ) {
            Row(Modifier.fillMaxWidth().padding(bottom = Spacing.s), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.versions_title), style = Type.headline, color = Colors.ink, modifier = Modifier.weight(1f))
                if (engine != null) {
                    Text(stringResource(R.string.player_engine, engineName(engine)), style = Type.caption, color = Colors.inkMuted)
                    Spacer(Modifier.width(Spacing.m))
                    TryOtherPlayer(onTryOtherPlayer)
                }
            }
            VersionHeader(Modifier.padding(bottom = 2.dp))
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(Spacing.s),
                contentPadding = PaddingValues(bottom = Spacing.screenY),
            ) {
                items(versions, key = { it.listingId }) { version ->
                    val isPlaying = playing != null && (version.listingId == playing.listingId || version.backups.any { it.listingId == playing.listingId })
                    val line = context.versionLine(version)
                    VersionRow(
                        if (isPlaying) line.copy(note = context.getString(R.string.player_playing), noteIsCaution = false) else line,
                        onClick = { if (isPlaying) onClose() else onPick(version) },
                        modifier = if (version == versions.first()) Modifier.focusRequester(first) else Modifier,
                    )
                }
            }
        }
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
