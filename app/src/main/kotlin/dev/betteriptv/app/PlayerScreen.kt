package dev.betteriptv.app

import android.app.Activity
import android.content.Context
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import dev.betteriptv.app.ui.components.BadgeRow
import dev.betteriptv.app.ui.components.ChoiceRow
import dev.betteriptv.app.ui.components.SettingRow
import dev.betteriptv.core.DeviceProfile
import dev.betteriptv.core.EpisodeNumber
import dev.betteriptv.core.Track
import dev.betteriptv.core.iso639
import dev.betteriptv.core.refreshRateFor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
import dev.betteriptv.core.Version
import kotlinx.coroutines.delay
import org.videolan.libvlc.util.VLCVideoLayout
import kotlin.time.Duration.Companion.seconds

/**
 * Plays a [plan]'s Version of the Title named [name] full screen through a Playback session, which
 * falls back to the VLC engine when Media3 can't open a file and to the Version's Backups when it is
 * dead. The Version playing is named when it starts; repeated buffering brings a one-tap suggestion
 * of a lower Version. Any key shows the player overlay (centre pauses once it shows; left and right
 * jump 10 s), with Versions and Audio and subtitles, where the Viewer picks Tracks, which are
 * remembered for the next file, styles subtitles ([settings]' style, changed through
 * [onSubtitleStyle]) and delays the sound or subtitles. The remote's Menu key opens the Versions
 * directly, with "Try other player". The TV switches to the film's refresh rate where it can.
 * Leaving the screen stops playback and gives back the Viewer's connection. When an Episode ends
 * the next one is offered, and choosing it calls [onPlay]; otherwise [onEnded] is called once the
 * file plays to its end.
 */
@Composable
fun PlayerScreen(
    library: Library,
    plan: PlayPlan,
    name: String,
    settings: ViewerSettings,
    deviceProfile: () -> DeviceProfile,
    onSubtitleStyle: (SubtitleStyle) -> Unit,
    onPlay: (PlayPlan) -> Unit,
    onEnded: () -> Unit,
) {
    val context = LocalContext.current
    val style by rememberUpdatedState(settings.subtitleStyle)
    val session = remember(plan) { library.playback(plan, playerEngines(context, plan, library::rememberedTracks) { style }) }
    val state by session.state.collectAsState()
    var panel by remember(session) { mutableStateOf(Panel.None) }
    var overlay by remember(session) { mutableStateOf(false) }
    // Each key press while the overlay shows keeps it up a while longer.
    var pressed by remember(session) { mutableIntStateOf(0) }
    var next by remember(session) { mutableStateOf<PlayPlan?>(null) }
    var audioDelay by remember { mutableLongStateOf(0L) }
    var subtitleDelay by remember { mutableLongStateOf(0L) }
    val screen = remember { FocusRequester() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(session) {
        session.play()
        next = if (plan.episode == null) null else library.nextEpisodePlan(plan, withContext(Dispatchers.Default) { deviceProfile() }, settings.qualityCap)
        if (next == null) onEnded()
    }

    val player = when (val current = state) {
        is PlaybackState.Starting -> current.player
        is PlaybackState.Playing -> current.player
        PlaybackState.Failed, PlaybackState.Ended -> null
    } as? ControlledEngine
    val playing = state is PlaybackState.Playing
    LaunchedEffect(player, audioDelay, subtitleDelay) { player?.delay(audioDelay, subtitleDelay) }
    LaunchedEffect(player, settings.subtitleStyle) { player?.style(settings.subtitleStyle) }
    MatchRefreshRate(player)
    LaunchedEffect(overlay, pressed, panel, player?.isPlaying) {
        // A paused picture keeps its overlay, so the Viewer sees where they are.
        if (overlay && panel == Panel.None && player?.isPlaying != false) {
            delay(OVERLAY_FOR)
            overlay = false
        }
    }
    // Not over a panel, whose focus it would take.
    val suggested = (state as? PlaybackState.Playing)?.lowerVersion?.takeIf { panel == Panel.None }
    // The screen holds focus whenever nothing on it asks for it, so the remote's keys still reach it.
    // It keeps it under the error too, until "Try other player" asks: focus handed over as the button
    // appears would arrive before the button can show it.
    val bare = panel == Panel.None && !overlay && suggested == null && next == null
    LaunchedEffect(bare) { if (bare) screen.requestFocus() }
    if (overlay && panel == Panel.None) BackHandler { overlay = false }

    Box(
        Modifier.fillMaxSize().background(Colors.ground).onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown || panel != Panel.None || next != null) return@onPreviewKeyEvent false
            pressed++
            when {
                event.key == Key.Menu -> panel = if (state is PlaybackState.Ended) Panel.None else Panel.Versions
                event.key == Key.MediaPlayPause -> player?.togglePause()
                event.key == Key.MediaRewind -> player?.seek(-SEEK_STEP)
                event.key == Key.MediaFastForward -> player?.seek(SEEK_STEP)
                // With the overlay up, its buttons take the keys.
                overlay || suggested != null || player == null -> return@onPreviewKeyEvent false
                else -> when (event.key) {
                    Key.DirectionLeft -> player.seek(-SEEK_STEP).also { overlay = true }
                    Key.DirectionRight -> player.seek(SEEK_STEP).also { overlay = true }
                    Key.DirectionCenter, Key.Enter, Key.DirectionUp, Key.DirectionDown -> overlay = true
                    else -> return@onPreviewKeyEvent false
                }
            }
            true
        },
    ) {
        // Beside the picture, not around it: Back would spend a press moving focus up to a focusable parent.
        Box(Modifier.focusRequester(screen).focusable())
        if (player != null) key(player) { EngineSurface(player) }

        when (val shown = state) {
            is PlaybackState.Starting -> if (!overlay) VersionLabel(stringResource(R.string.player_starting, context.playLabel(shown.version)))
            is PlaybackState.Playing -> {
                // Named for a few seconds each time a Version starts, so a fallback to a Backup is seen.
                var named by remember(shown.version, shown.player) { mutableStateOf(true) }
                LaunchedEffect(shown.version, shown.player) {
                    delay(NAMED_FOR)
                    named = false
                }
                if (named && !overlay) VersionLabel(context.playLabel(shown.version))
                suggested?.let { lower ->
                    BufferingSuggestion(lower, onSwitch = { session.switchTo(lower) }, onDismiss = session::dismissSuggestion)
                }
            }
            // Under the open Versions it would show through.
            PlaybackState.Failed -> if (panel == Panel.None) Failed(onTryOtherPlayer = session::tryOtherPlayer)
            PlaybackState.Ended -> next?.let { NextEpisode(it, onPlay = { onPlay(it) }) }
        }

        if (overlay && panel == Panel.None && player != null && playing) {
            Overlay(
                name = name,
                episode = plan.episode,
                version = state.version,
                player = player,
                onVersions = { panel = Panel.Versions },
                onTracks = { panel = Panel.Tracks },
            )
        }

        when (panel) {
            Panel.None -> Unit
            Panel.Versions -> VersionsPanel(
                plan.versions,
                state.version,
                engine = player?.engine,
                onTryOtherPlayer = {
                    panel = Panel.None
                    session.tryOtherPlayer()
                },
                onPick = { version ->
                    panel = Panel.None
                    session.switchTo(version)
                },
                onClose = { panel = Panel.None },
            )
            Panel.Tracks -> TracksPanel(
                player,
                settings.subtitleStyle,
                audioDelay,
                subtitleDelay,
                onAudio = { index, track ->
                    player?.chooseAudio(index)
                    track.language?.let { scope.launch { library.rememberAudio(it) } }
                },
                onSubtitles = { index, track ->
                    player?.chooseSubtitles(index)
                    if (track == null) scope.launch { library.rememberSubtitles(null) }
                    else track.language?.let { scope.launch { library.rememberSubtitles(it) } }
                },
                onStyle = onSubtitleStyle,
                onAudioDelay = { audioDelay = it },
                onSubtitleDelay = { subtitleDelay = it },
                onClose = { panel = Panel.None },
            )
        }
    }
}

/** What's open over the picture besides the overlay. */
private enum class Panel { None, Versions, Tracks }

private const val SEEK_STEP = 10_000L

/** How long a delay changes with each press, in milliseconds. */
private const val DELAY_STEP = 50L

/** How long a Version stays named once it starts. */
private val NAMED_FOR = 5.seconds

/** How long the overlay stays without a key press while the picture plays. */
private val OVERLAY_FOR = 5.seconds

/** How long the buffering suggestion waits for the Viewer before stepping aside. */
private val SUGGESTED_FOR = 15.seconds

/**
 * Switches the TV to the refresh rate that fits [player]'s film once its frame rate is known, where
 * the TV has one at the same resolution, and back to the TV's own when the player leaves.
 */
@Composable
private fun MatchRefreshRate(player: ControlledEngine?) {
    val window = (LocalView.current.context as? Activity)?.window ?: return
    DisposableEffect(window) {
        val before = window.attributes.preferredDisplayModeId
        onDispose { window.attributes = window.attributes.apply { preferredDisplayModeId = before } }
    }
    LaunchedEffect(player) {
        player ?: return@LaunchedEffect
        // Media3 and VLC learn the frame rate once they've read the file's header.
        val frameRate = withTimeoutOrNull(FRAME_RATE_WAIT) {
            var rate: Float? = null
            while (rate == null) {
                rate = player.frameRate
                if (rate == null) delay(250)
            }
            rate
        } ?: return@LaunchedEffect
        val display = window.decorView.display ?: return@LaunchedEffect
        val current = display.mode
        val sameSize = display.supportedModes.filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
        val rate = refreshRateFor(frameRate, current.refreshRate, sameSize.map { it.refreshRate }) ?: return@LaunchedEffect
        val mode = sameSize.first { it.refreshRate == rate }
        window.attributes = window.attributes.apply { preferredDisplayModeId = mode.modeId }
    }
}

private val FRAME_RATE_WAIT = 20.seconds

/** The engine's picture. The player overlay is the app's own, so Media3's controls stay hidden. */
@Composable
private fun EngineSurface(player: ControlledEngine) {
    when (player) {
        is Media3Engine -> Media3Surface(player)
        is VlcEngine -> AndroidView(
            factory = { context -> VLCVideoLayout(context).also(player::attach) },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun Media3Surface(player: Media3Engine) {
    AndroidView(
        factory = { context ->
            PlayerView(context).apply {
                useController = false
                isFocusable = false
                player.showIn(this)
            }
        },
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

/**
 * The player overlay: the Title, its Episode and the Version playing at the top; where playback is,
 * the transport and the panels' buttons at the bottom. Play/Pause takes first focus.
 */
@Composable
private fun Overlay(name: String, episode: EpisodeNumber?, version: Version?, player: ControlledEngine, onVersions: () -> Unit, onTracks: () -> Unit) {
    val context = LocalContext.current
    val play = remember { FocusRequester() }
    LaunchedEffect(Unit) { play.requestFocus() }
    // Read twice a second: the engines don't report their position as it moves.
    val now by produceState(Triple(player.position, player.buffered, player.isPlaying), player) {
        while (true) {
            value = Triple(player.position, player.buffered, player.isPlaying)
            delay(500)
        }
    }
    val (position, buffered, isPlaying) = now
    val duration = player.duration
    Box(Modifier.fillMaxSize().background(topScrim).background(bottomScrim)) {
        Column(Modifier.align(Alignment.TopStart).fillMaxWidth().padding(horizontal = Spacing.screenX, vertical = Spacing.screenY)) {
            Text(
                episode?.let { context.getString(R.string.player_episode, name, it.season, it.number) } ?: name,
                style = Type.headline,
                color = Colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (version != null) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                    BadgeRow(version.attributes.cardBadges())
                    Spacer(Modifier.width(Spacing.s))
                    Text(context.playLabel(version), style = Type.caption, color = Colors.ink, maxLines = 1)
                }
            }
        }
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = Spacing.screenX, vertical = Spacing.screenY)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(clock(position), style = Type.label, color = Colors.ink)
                Box(Modifier.weight(1f).padding(horizontal = 14.dp).height(6.dp)) {
                    val shape = RoundedCornerShape(3.dp)
                    fun share(at: Long) = if (duration == null) 0f else (at.toFloat() / duration).coerceIn(0f, 1f)
                    Box(Modifier.fillMaxSize().background(Colors.ink.copy(alpha = 0.25f), shape))
                    Box(Modifier.fillMaxWidth(share(buffered)).fillMaxHeight().background(Colors.ink.copy(alpha = 0.45f), shape))
                    Box(Modifier.fillMaxWidth(share(position)).fillMaxHeight().background(Colors.ink, shape))
                }
                Text(duration?.let { "-" + clock((it - position).coerceAtLeast(0)) } ?: "", style = Type.label, color = Colors.inkMuted)
            }
            Spacer(Modifier.height(Spacing.l))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.m), verticalAlignment = Alignment.CenterVertically) {
                NuageButton(stringResource(R.string.player_back), onClick = { player.seek(-SEEK_STEP) })
                NuageButton(
                    stringResource(if (isPlaying) R.string.player_pause else R.string.play),
                    onClick = player::togglePause,
                    modifier = Modifier.focusRequester(play),
                    icon = if (isPlaying) null else Icons.Filled.PlayArrow,
                )
                NuageButton(stringResource(R.string.player_forward), onClick = { player.seek(SEEK_STEP) })
                Spacer(Modifier.weight(1f))
                NuageButton(stringResource(R.string.versions_title), onClick = onVersions)
                NuageButton(stringResource(R.string.player_tracks), onClick = onTracks)
            }
        }
    }
}

/** `1:18:52`, or `23:41` under an hour. */
private fun clock(millis: Long): String {
    val seconds = millis / 1000
    return if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60) else "%d:%02d".format(seconds / 60, seconds % 60)
}

/**
 * Audio and subtitles, over the bottom of the picture: the file's audio Tracks and subtitle Tracks
 * (with Off), the one on marked; the subtitle style; and the audio and subtitle delays. The first
 * audio Track takes first focus.
 */
@Composable
private fun TracksPanel(
    player: ControlledEngine?,
    style: SubtitleStyle,
    audioDelay: Long,
    subtitleDelay: Long,
    onAudio: (Int, Track) -> Unit,
    onSubtitles: (Int?, Track?) -> Unit,
    onStyle: (SubtitleStyle) -> Unit,
    onAudioDelay: (Long) -> Unit,
    onSubtitleDelay: (Long) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    // Read again after each pick: the engine says which Track is on.
    var picks by remember { mutableIntStateOf(0) }
    val tracks = remember(player, picks) { player?.tracks }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.requestFocus() }
    BackHandler(onBack = onClose)
    val on = context.getString(R.string.player_playing)
    Box(Modifier.fillMaxSize().background(bottomScrim)) {
        LazyColumn(
            Modifier.align(Alignment.BottomStart).fillMaxWidth().fillMaxHeight(0.72f),
            verticalArrangement = Arrangement.spacedBy(Spacing.s),
            contentPadding = PaddingValues(start = Spacing.screenX, end = Spacing.screenX, bottom = Spacing.screenY),
        ) {
            item { PanelHeading(stringResource(R.string.tracks_audio), Modifier.focusRequester(first).focusable(tracks?.audio.isNullOrEmpty())) }
            tracks?.audio.orEmpty().forEachIndexed { index, track ->
                item {
                    SettingRow(context.trackName(track), if (index == tracks?.audioOn) on else "", onClick = {
                        onAudio(index, track)
                        picks++
                    }, modifier = if (index == 0) Modifier.focusRequester(first) else Modifier)
                }
            }
            item { PanelHeading(stringResource(R.string.tracks_subtitles)) }
            item {
                SettingRow(stringResource(R.string.subtitles_off), if (tracks?.subtitlesOn == null) on else "", onClick = {
                    onSubtitles(null, null)
                    picks++
                })
            }
            tracks?.subtitles.orEmpty().forEachIndexed { index, track ->
                item {
                    SettingRow(context.trackName(track), if (index == tracks?.subtitlesOn) on else "", onClick = {
                        onSubtitles(index, track)
                        picks++
                    })
                }
            }
            item { PanelHeading(stringResource(R.string.subtitle_style)) }
            item {
                StyleChoice(R.string.subtitle_size, listOf(R.string.subtitle_small, R.string.subtitle_medium, R.string.subtitle_large, R.string.subtitle_largest), style.size.ordinal) {
                    onStyle(style.copy(size = SubtitleSize.entries[it]))
                }
            }
            item {
                StyleChoice(R.string.subtitle_colour, listOf(R.string.subtitle_white, R.string.subtitle_yellow), style.colour.ordinal) {
                    onStyle(style.copy(colour = SubtitleColour.entries[it]))
                }
            }
            item {
                StyleChoice(R.string.subtitle_background, listOf(R.string.subtitle_outline, R.string.subtitle_shadow, R.string.subtitle_box), style.background.ordinal) {
                    onStyle(style.copy(background = SubtitleBackground.entries[it]))
                }
            }
            item {
                StyleChoice(R.string.subtitle_position, listOf(R.string.subtitle_bottom, R.string.subtitle_raised), style.position.ordinal) {
                    onStyle(style.copy(position = SubtitlePosition.entries[it]))
                }
            }
            item { PanelHeading(stringResource(R.string.delays)) }
            item { DelayRow(R.string.audio_delay, audioDelay, onAudioDelay) }
            item { DelayRow(R.string.subtitle_delay, subtitleDelay, onSubtitleDelay) }
        }
    }
}

@Composable
private fun PanelHeading(text: String, modifier: Modifier = Modifier) {
    Text(text, style = Type.headline, color = Colors.ink, modifier = modifier.padding(top = Spacing.m, bottom = 2.dp))
}

/** A subtitle style setting: its label in a column of its own, the choices beside it. */
@Composable
private fun StyleChoice(label: Int, options: List<Int>, selected: Int, onSelect: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(label), style = Type.label, color = Colors.ink, modifier = Modifier.width(STYLE_LABEL))
        ChoiceRow(options.map { stringResource(it) }, selected, onSelect)
    }
}

/** An audio or subtitle delay, made earlier or later a step at a time. */
@Composable
private fun DelayRow(label: Int, delay: Long, onChange: (Long) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(label), style = Type.label, color = Colors.ink, modifier = Modifier.width(STYLE_LABEL))
        NuageButton(stringResource(R.string.delay_earlier), onClick = { onChange(delay - DELAY_STEP) })
        Text(
            stringResource(R.string.delay_value, delay),
            style = Type.label,
            color = Colors.ink,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(96.dp),
        )
        NuageButton(stringResource(R.string.delay_later), onClick = { onChange(delay + DELAY_STEP) })
    }
}

private val STYLE_LABEL = 200.dp

/** A Track as the Viewer picks it: its language, then for audio its channels and format. */
private fun Context.trackName(track: Track): String {
    val language = track.language?.let { iso639(it) ?: it }?.let(::languageName) ?: getString(R.string.track_unknown_language)
    val channels = when (val count = track.channels) {
        null -> null
        1 -> getString(R.string.track_mono)
        2 -> getString(R.string.track_stereo)
        else -> "${count - 1}.1"
    }
    return listOfNotNull(language, channels, track.mimeType?.let(::formatName)).joinToString(" · ")
}

/** A Track's format as people know it: `audio/true-hd` is TrueHD. Null for one without a common name. */
private fun formatName(mimeType: String): String? = when (mimeType) {
    "audio/mp4a-latm" -> "AAC"
    "audio/ac3" -> "Dolby Digital"
    "audio/eac3" -> "Dolby Digital Plus"
    "audio/eac3-joc" -> "Dolby Atmos"
    "audio/true-hd" -> "TrueHD"
    "audio/vnd.dts" -> "DTS"
    "audio/vnd.dts.hd" -> "DTS-HD"
    "audio/mpeg" -> "MP3"
    "audio/flac" -> "FLAC"
    "audio/opus" -> "Opus"
    "application/x-subrip" -> "SRT"
    "text/x-ssa" -> "ASS"
    "text/vtt" -> "WebVTT"
    "application/pgs" -> "PGS"
    "application/vobsub" -> "VobSub"
    else -> null
}

/** Offered once an Episode ends: the [next] one, which one press plays. Back leaves the player. */
@Composable
private fun NextEpisode(next: PlayPlan, onPlay: () -> Unit) {
    val button = remember { FocusRequester() }
    LaunchedEffect(next) { button.requestFocus() }
    val episode = next.episode ?: return
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.align(Alignment.Center).padding(Spacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.l),
        ) {
            Text(stringResource(R.string.player_next_episode, episode.season, episode.number), style = Type.headline, color = Colors.ink, textAlign = TextAlign.Center)
            NuageButton(stringResource(R.string.player_play_next), onPlay, Modifier.focusRequester(button), icon = Icons.Filled.PlayArrow)
        }
    }
}
