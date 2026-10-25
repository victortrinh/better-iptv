package dev.betteriptv.app

import android.content.Context
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
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
import dev.betteriptv.app.ui.components.NuageButton
import dev.betteriptv.app.ui.components.VersionHeader
import dev.betteriptv.app.ui.components.VersionRow
import dev.betteriptv.app.ui.theme.Colors
import dev.betteriptv.app.ui.theme.Corners
import dev.betteriptv.app.ui.theme.Spacing
import dev.betteriptv.app.ui.theme.Type
import dev.betteriptv.core.Library
import dev.betteriptv.core.PlayPlan
import dev.betteriptv.core.PlaybackState
import dev.betteriptv.core.PlayerEngine
import dev.betteriptv.core.PlayerEvent
import dev.betteriptv.core.Track
import dev.betteriptv.core.Version
import kotlinx.coroutines.delay
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Plays a [plan] full screen with Media3 through a Playback session, which falls back to Backups by
 * itself. The Version playing is named when it starts; repeated buffering brings a one-tap
 * suggestion of a lower Version; the remote's menu key opens the Versions to switch to mid-film.
 * Leaving the screen stops the player before the connection is given back.
 */
@Composable
fun PlayerScreen(library: Library, plan: PlayPlan) {
    val context = LocalContext.current
    val engine = remember(plan) { Media3Engine(context, plan) }
    val session = remember(plan) { library.playback(plan, engine) }
    LaunchedEffect(session) { session.run() }
    val state by session.state.collectAsState()
    var playerView by remember { mutableStateOf<PlayerView?>(null) }
    var choosing by remember(plan) { mutableStateOf(false) }
    val untagged = stringResource(R.string.language_untagged)

    Box(Modifier.fillMaxSize().background(Colors.ground)) {
        PlayerSurface(context, engine.player, onView = { playerView = it }, onVersions = { choosing = true })

        when (val shown = state) {
            is PlaybackState.Starting -> VersionLabel(stringResource(R.string.player_starting, shown.version.attributes.playLabel(untagged)))
            is PlaybackState.Playing -> {
                // Named for a few seconds each time a Version starts, so a fallback to a Backup is seen.
                var named by remember(shown.version) { mutableStateOf(true) }
                LaunchedEffect(shown.version) {
                    delay(NAMED_FOR)
                    named = false
                }
                if (named) VersionLabel(shown.version.attributes.playLabel(untagged))
                shown.lowerVersion?.let { lower ->
                    BufferingSuggestion(lower, untagged, onSwitch = { session.switchTo(lower) }, onDismissed = { playerView?.requestFocus() })
                }
            }
            PlaybackState.Failed -> Text(
                stringResource(R.string.player_failed),
                style = Type.body,
                color = Colors.error,
                modifier = Modifier.align(Alignment.Center).padding(Spacing.xl),
            )
        }

        if (choosing) {
            val playing = (state as? PlaybackState.Playing)?.version ?: (state as? PlaybackState.Starting)?.version
            VersionsPanel(
                plan.versions,
                playing,
                onPick = { version ->
                    choosing = false
                    session.switchTo(version)
                    playerView?.requestFocus()
                },
                onClose = {
                    choosing = false
                    playerView?.requestFocus()
                },
            )
        }
    }
}

/** How long a Version stays named once it starts. */
private val NAMED_FOR = 5.seconds

/** How long the buffering suggestion waits for the Viewer before stepping aside. */
private val SUGGESTED_FOR = 15.seconds

@OptIn(UnstableApi::class)
@Composable
private fun PlayerSurface(context: Context, player: ExoPlayer?, onView: (PlayerView) -> Unit, onVersions: () -> Unit) {
    AndroidView(
        factory = {
            PlayerView(context).apply {
                // The remote's keys reach the controls only while the view has focus.
                isFocusable = true
                isFocusableInTouchMode = true
                setShowSubtitleButton(true)
                // The menu key isn't one the controls use; it opens the Versions.
                setOnKeyListener { _, keyCode, event ->
                    (keyCode == KeyEvent.KEYCODE_MENU && event.action == KeyEvent.ACTION_UP).also { if (it) onVersions() }
                }
                requestFocus()
                onView(this)
            }
        },
        // Each Version gets its own player; the view stays, and keeps focus, across them.
        update = { it.player = player },
        modifier = Modifier.fillMaxSize(),
    )
}

/** Names a Version over the top of the picture, with the key that opens the others. */
@Composable
private fun VersionLabel(text: String) {
    Row(
        Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Colors.ground.copy(alpha = 0.85f), Colors.ground.copy(alpha = 0f))))
            .padding(horizontal = Spacing.screenX, vertical = Spacing.screenY),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = Type.label, color = Colors.ink, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
        Spacer(Modifier.width(Spacing.m))
        Text(stringResource(R.string.player_versions_hint), style = Type.caption, color = Colors.inkMuted)
    }
}

/**
 * The one-tap suggestion of a [lower] Version after repeated buffering. It takes focus so one press
 * switches; Back, or waiting, dismisses it and hands focus back to the player. Nothing switches
 * without the Viewer.
 */
@Composable
private fun BufferingSuggestion(lower: Version, untagged: String, onSwitch: () -> Unit, onDismissed: () -> Unit) {
    var dismissed by remember(lower) { mutableStateOf(false) }
    if (dismissed) return
    val dismiss = {
        dismissed = true
        onDismissed()
    }
    val button = remember { FocusRequester() }
    LaunchedEffect(lower) {
        button.requestFocus()
        delay(SUGGESTED_FOR)
        dismiss()
    }
    BackHandler(onBack = dismiss)
    Box(Modifier.fillMaxSize().padding(horizontal = Spacing.screenX, vertical = Spacing.screenY)) {
        // Glass carrying text over a busy picture lays the ground under it first.
        Row(
            Modifier.align(Alignment.TopEnd)
                .background(Colors.ground.copy(alpha = 0.75f), RoundedCornerShape(Corners.control))
                .background(Colors.surface, RoundedCornerShape(Corners.control))
                .padding(start = Spacing.l, end = Spacing.s, top = Spacing.s, bottom = Spacing.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(stringResource(R.string.player_buffering), style = Type.label, color = Colors.ink)
                Text(stringResource(R.string.player_buffering_lower, lower.attributes.playLabel(untagged)), style = Type.caption, color = Colors.inkMuted)
            }
            Spacer(Modifier.width(Spacing.m))
            NuageButton(stringResource(R.string.player_switch), onClick = onSwitch, modifier = Modifier.focusRequester(button))
        }
    }
}

/** The Title's Versions over the bottom of the picture, the [playing] one marked; picking one switches to it. */
@Composable
private fun VersionsPanel(versions: List<Version>, playing: Version?, onPick: (Version) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.requestFocus() }
    BackHandler(onBack = onClose)
    Box(Modifier.fillMaxSize().background(Colors.ground.copy(alpha = 0.6f))) {
        Column(
            Modifier.align(Alignment.BottomStart).fillMaxWidth().fillMaxHeight(0.62f)
                .background(Brush.verticalGradient(listOf(Colors.ground.copy(alpha = 0f), Colors.ground.copy(alpha = 0.85f))))
                .padding(horizontal = Spacing.screenX),
        ) {
            Text(stringResource(R.string.versions_title), style = Type.headline, color = Colors.ink, modifier = Modifier.padding(bottom = Spacing.s))
            VersionHeader(Modifier.padding(bottom = 2.dp))
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(Spacing.s),
                contentPadding = PaddingValues(bottom = Spacing.screenY),
            ) {
                items(versions, key = { it.listingId }) { version ->
                    val isPlaying = version.listingId == playing?.listingId
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

/**
 * The Playback session's player engine on the stick: a new Media3 player for each file, so releasing
 * it closes its request to the Provider before the next file opens. [player] is the one showing.
 */
private class Media3Engine(private val context: Context, private val plan: PlayPlan) : PlayerEngine {
    var player by mutableStateOf<ExoPlayer?>(null)
        private set

    override val position: Duration get() = player?.currentPosition?.milliseconds ?: Duration.ZERO

    override fun play(location: String, from: Duration, events: (PlayerEvent) -> Unit) {
        val exo = ExoPlayer.Builder(context).build()
        exo.addListener(TrackChooser(exo, plan))
        exo.addListener(object : Player.Listener {
            private var started = false
            private var seeking = false

            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_READY -> {
                        seeking = false
                        if (!started) {
                            started = true
                            events(PlayerEvent.Started)
                        }
                    }
                    // Filling the buffer after the Viewer seeks isn't the stream struggling.
                    Player.STATE_BUFFERING -> if (started && !seeking) events(PlayerEvent.Buffering)
                }
            }

            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK) seeking = true
            }

            override fun onPlayerError(error: PlaybackException) = events(PlayerEvent.Failed)
        })
        exo.setMediaItem(MediaItem.fromUri(location), from.inWholeMilliseconds)
        exo.prepare()
        exo.playWhenReady = true
        player = exo
    }

    override suspend fun stop() {
        val exo = player ?: return
        player = null
        exo.release()
    }
}

/** Applies the [plan]'s Track choice once, as soon as the player knows the file's Tracks. */
private class TrackChooser(private val player: ExoPlayer, private val plan: PlayPlan) : Player.Listener {
    private var chosen = false

    override fun onTracksChanged(tracks: Tracks) {
        if (chosen || tracks.isEmpty) return
        chosen = true
        // Only Tracks the stick can play: choosing one it can't would leave the picture silent.
        val audio = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO && it.isSupported }
        val subtitles = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT && it.isSupported }
        val choice = plan.chooseTracks(
            audio.map { Track(it.getTrackFormat(0).language) },
            subtitles.map { Track(it.getTrackFormat(0).language) },
        )
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().apply {
            choice.audio?.let { setOverrideForType(TrackSelectionOverride(audio[it].mediaTrackGroup, 0)) }
            // Without a choice, a file's "default" subtitles stay off; forced ones (for foreign dialogue) still show.
            choice.subtitle?.let { setOverrideForType(TrackSelectionOverride(subtitles[it].mediaTrackGroup, 0)) }
                ?: setIgnoredTextSelectionFlags(C.SELECTION_FLAG_DEFAULT)
        }.build()
    }
}
