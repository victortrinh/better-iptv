package dev.betteriptv.app

import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import dev.betteriptv.app.ui.components.NuageIconButton
import dev.betteriptv.app.ui.components.NuageIcons
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
import dev.betteriptv.core.SubtitleOffer
import dev.betteriptv.core.Track
import kotlinx.coroutines.flow.MutableStateFlow
import dev.betteriptv.core.iso639
import dev.betteriptv.core.refreshRateFor
import dev.betteriptv.core.UpNext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
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
import androidx.compose.foundation.layout.wrapContentHeight
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
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusProperties
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
 * Leaving the screen stops playback and gives back the Viewer's connection. An Episode's overlay
 * has "Next Episode", and from its credits a card offers the next one, counting down to play it by
 * itself (see [UpNext]) when [settings] allow and it isn't the [inARow]th to start by itself with no
 * key pressed; the card stays when the Episode ends. Playing the next Episode calls [onPlay] with
 * how many have now started by themselves in a row; otherwise [onEnded] is called once the file
 * plays to its end.
 */
@Composable
fun PlayerScreen(
    library: Library,
    plan: PlayPlan,
    name: String,
    settings: ViewerSettings,
    deviceProfile: () -> DeviceProfile,
    onSubtitleStyle: (SubtitleStyle) -> Unit,
    inARow: Int,
    onPlay: (PlayPlan, inARow: Int) -> Unit,
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
    // Whether a key was pressed since this Episode started, and whether its credits are on screen
    // (still so once it ends); the Viewer can turn the next Episode's card away until they leave them.
    var touched by remember(session) { mutableStateOf(false) }
    var atCredits by remember(session) { mutableStateOf(false) }
    var dismissed by remember(session) { mutableStateOf(false) }
    var audioDelay by remember { mutableLongStateOf(0L) }
    var subtitleDelay by remember { mutableLongStateOf(0L) }
    val screen = remember { FocusRequester() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(session) {
        // Looked up as the Episode starts, so the overlay and the credits can offer it.
        val lookup = async { if (plan.episode == null) null else library.nextEpisodePlan(plan, withContext(Dispatchers.Default) { deviceProfile() }, settings.qualityCap) }
        launch { next = lookup.await() }
        session.play()
        if (lookup.await() == null) onEnded()
    }

    val player = when (val current = state) {
        is PlaybackState.Starting -> current.player
        is PlaybackState.Playing -> current.player
        PlaybackState.Failed, PlaybackState.Ended -> null
    } as? ControlledEngine
    val playing = state is PlaybackState.Playing
    val ended = state == PlaybackState.Ended
    LaunchedEffect(state) {
        // Starting between files keeps what was on screen: switching Version in the credits stays in them.
        when (val shown = state) {
            is PlaybackState.Playing -> atCredits = shown.credits
            PlaybackState.Ended -> atCredits = true
            PlaybackState.Failed -> atCredits = false
            is PlaybackState.Starting -> Unit
        }
        if (!atCredits) dismissed = false
    }
    // Once the Episode ends the next one stays offered, counting down only if the Viewer didn't turn it away.
    val upNext = next?.takeIf { atCredits && (ended || !dismissed) }
    val counting = upNext != null && !dismissed && UpNext.countsDown(settings.playNextAutomatically, inARow)
    var left by remember(session) { mutableIntStateOf(UpNext.COUNTDOWN.inWholeSeconds.toInt()) }
    LaunchedEffect(counting) {
        if (!counting) return@LaunchedEffect
        left = UpNext.COUNTDOWN.inWholeSeconds.toInt()
        while (left > 0) {
            delay(1.seconds)
            left--
        }
        upNext?.let { onPlay(it, if (touched) 1 else inARow + 1) }
    }
    val card = upNext?.takeIf { !ended && panel == Panel.None }
    LaunchedEffect(card != null) { if (card != null) overlay = false }
    LaunchedEffect(player, audioDelay, subtitleDelay) { player?.delay(audioDelay, subtitleDelay) }

    // Downloaded subtitles, for a file without subtitles in the Viewer's languages: searched for once per
    // session, when a file needs them, and shown automatically when the Track choice says so.
    val chosen by (player?.chosen ?: remember { MutableStateFlow(null) }).collectAsState()
    var offers by remember(session) { mutableStateOf<List<SubtitleOffer>?>(null) }
    var shownSubtitle by remember(session) { mutableStateOf<ShownSubtitle?>(null) }
    val downloads = remember(session) { HashMap<String, ShownSubtitle>() }
    val downloadLanguages = remember(player, chosen) {
        if (chosen == null) emptyList() else player?.tracks?.let { plan.downloadLanguages(it.subtitles, library.rememberedTracks()) }.orEmpty()
    }
    val showDownloaded: (SubtitleOffer) -> Unit = { offer ->
        scope.launch {
            val subtitle = downloads[offer.id] ?: library.downloadSubtitle(offer)?.let { file ->
                withContext(Dispatchers.Default) { ShownSubtitle.parse(offer, file) }.also { downloads[offer.id] = it }
            }
            if (subtitle != null) shownSubtitle = subtitle
        }
    }
    LaunchedEffect(session, downloadLanguages.isNotEmpty()) {
        if (downloadLanguages.isNotEmpty() && offers == null) offers = library.subtitleOffers(plan, plan.subtitleLanguages(library.rememberedTracks()))
    }
    LaunchedEffect(chosen, offers) {
        val choice = chosen ?: return@LaunchedEffect
        val language = choice.downloaded
        if (language == null) shownSubtitle = null
        else if (shownSubtitle?.offer?.language != language) offers?.firstOrNull { it.language == language }?.let(showDownloaded)
    }
    LaunchedEffect(player, settings.subtitleStyle) { player?.style(settings.subtitleStyle) }
    MatchRefreshRate(player)
    KeepScreenOn(player.takeIf { playing })
    LaunchedEffect(overlay, pressed, panel, player?.isPlaying) {
        // A paused picture keeps its overlay, so the Viewer sees where they are.
        if (overlay && panel == Panel.None && player?.isPlaying != false) {
            delay(OVERLAY_FOR)
            overlay = false
        }
    }
    // Not over a panel or the next Episode's card, whose focus it would take.
    val suggested = (state as? PlaybackState.Playing)?.lowerVersion?.takeIf { panel == Panel.None && card == null }
    // The screen holds focus whenever nothing on it asks for it, so the remote's keys still reach it.
    // It keeps it under the error too, until "Try other player" asks: focus handed over as the button
    // appears would arrive before the button can show it.
    val bare = panel == Panel.None && !overlay && suggested == null && upNext == null
    LaunchedEffect(bare) { if (bare) screen.requestFocus() }
    if (overlay && panel == Panel.None) BackHandler { overlay = false }

    Box(
        Modifier.fillMaxSize().background(Colors.ground).onPreviewKeyEvent { event ->
            if (event.type == KeyEventType.KeyDown) touched = true
            if (event.type != KeyEventType.KeyDown || panel != Panel.None || (ended && upNext != null)) return@onPreviewKeyEvent false
            pressed++
            when {
                event.key == Key.Menu -> panel = if (state is PlaybackState.Ended) Panel.None else Panel.Versions
                event.key == Key.MediaPlayPause -> player?.togglePause()
                // A Channel is live: there is nowhere to seek to.
                event.key == Key.MediaRewind -> if (!plan.live) player?.seek(-SEEK_STEP)
                event.key == Key.MediaFastForward -> if (!plan.live) player?.seek(SEEK_STEP)
                // With the overlay or a card up, its buttons take the keys.
                overlay || suggested != null || card != null || player == null -> return@onPreviewKeyEvent false
                else -> when (event.key) {
                    Key.DirectionLeft -> { if (!plan.live) player.seek(-SEEK_STEP); overlay = true }
                    Key.DirectionRight -> { if (!plan.live) player.seek(SEEK_STEP); overlay = true }
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
        if (player != null) shownSubtitle?.let { DownloadedSubtitles(player, it, subtitleDelay, settings.subtitleStyle) }

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
            PlaybackState.Ended -> upNext?.let { NextEpisode(it, left.takeIf { counting }, onPlay = { onPlay(it, 0) }) }
        }
        card?.let { UpNextCard(it, left.takeIf { counting }, onPlay = { onPlay(it, 0) }, onDismiss = { dismissed = true }) }

        if (overlay && panel == Panel.None && player != null && playing) {
            Overlay(
                name = name,
                episode = plan.episode,
                version = state.version,
                player = player,
                live = plan.live,
                onNext = next?.let { { onPlay(it, 0) } },
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
                downloads = offers.orEmpty().filter { it.language in downloadLanguages }.groupBy { it.language }.values.flatMap { it.take(DOWNLOADS_PER_LANGUAGE) },
                shownDownload = shownSubtitle?.offer,
                onDownloaded = { offer ->
                    player?.chooseSubtitles(null)
                    showDownloaded(offer)
                    scope.launch { library.rememberSubtitles(offer.language) }
                },
                onAudio = { index, track ->
                    player?.chooseAudio(index)
                    track.language?.let { scope.launch { library.rememberAudio(it) } }
                },
                onSubtitles = { index, track ->
                    shownSubtitle = null
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

/** How far the subtitle delay moves a press: a downloaded subtitle is often off by whole seconds, and finer is unnoticeable. */
private const val SUBTITLE_DELAY_STEP = 250L

/** How many downloaded subtitles are offered per language: another file is often the fix for one badly off. */
private const val DOWNLOADS_PER_LANGUAGE = 3

/** How long a Version stays named once it starts. */
private val NAMED_FOR = 5.seconds

/** How long the overlay stays without a key press while the picture plays. */
private val OVERLAY_FOR = 5.seconds

/** How long the buffering suggestion waits for the Viewer before stepping aside. */
private val SUGGESTED_FOR = 15.seconds

/**
 * Keeps the TV's screensaver away while the player screen shows, since the remote may go untouched
 * for a whole film, unless the Viewer paused the [player] that plays. Between files it stays on: letting
 * go then would start a screensaver held back since the last key press.
 */
@Composable
private fun KeepScreenOn(player: ControlledEngine?) {
    val view = LocalView.current
    val paused by produceState(false, player) {
        while (true) {
            value = player?.isPlaying == false
            delay(1.seconds)
        }
    }
    DisposableEffect(view, paused) {
        view.keepScreenOn = !paused
        onDispose { view.keepScreenOn = false }
    }
}

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
        }
        if (frameRate == null) {
            Log.i(PLAYER_TAG, "Frame rate unknown after $FRAME_RATE_WAIT: refresh rate left as it is")
            return@LaunchedEffect
        }
        val display = window.decorView.display ?: return@LaunchedEffect
        val current = display.mode
        val sameSize = display.supportedModes.filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
        val rate = refreshRateFor(frameRate, current.refreshRate, sameSize.map { it.refreshRate })
        if (rate == null) {
            Log.i(PLAYER_TAG, "$frameRate fps fits ${current.refreshRate} Hz, or none of ${sameSize.map { it.refreshRate }} Hz does: refresh rate left as it is")
            return@LaunchedEffect
        }
        val mode = sameSize.first { it.refreshRate == rate }
        Log.i(PLAYER_TAG, "$frameRate fps: switching from ${current.refreshRate} Hz to $rate Hz")
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
        // The rows' muted text is over a moving picture, so one veil lies under the whole list,
        // as high as the Versions need, a little wider than the rows and down past the screen's edge.
        Column(
            Modifier.align(Alignment.BottomStart).fillMaxWidth().fillMaxHeight(0.62f).wrapContentHeight(Alignment.Bottom)
                .padding(horizontal = Spacing.screenX - Spacing.m)
                .background(Colors.veil, RoundedCornerShape(topStart = Corners.card, topEnd = Corners.card))
                .padding(start = Spacing.m, end = Spacing.m, top = Spacing.m),
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
                Modifier.weight(1f, fill = false),
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
 * the transport (with "Next Episode" when there is one, [onNext]) and the panels' buttons at the
 * bottom. Play/Pause takes first focus. A [live] Channel has no scrubber and no jumps.
 */
@Composable
private fun Overlay(name: String, episode: EpisodeNumber?, version: Version?, player: ControlledEngine, live: Boolean, onNext: (() -> Unit)?, onVersions: () -> Unit, onTracks: () -> Unit) {
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
                    // A Channel has no badges: its label stands alone.
                    if (!live) {
                        BadgeRow(version.attributes.cardBadges())
                        Spacer(Modifier.width(Spacing.s))
                    }
                    Text(context.playLabel(version), style = Type.caption, color = Colors.ink, maxLines = 1)
                }
            }
        }
        PlayerControls(
            position = position,
            buffered = buffered,
            duration = player.duration,
            isPlaying = isPlaying,
            live = live,
            playFocus = play,
            onBack = { player.seek(-SEEK_STEP) },
            onPlayPause = player::togglePause,
            onForward = { player.seek(SEEK_STEP) },
            onNext = onNext,
            onVersions = onVersions,
            onTracks = onTracks,
            modifier = Modifier.align(Alignment.BottomStart),
        )
    }
}

/**
 * The bottom of the player overlay: elapsed time, the scrubber and the time left, then Back 10 s,
 * Play/Pause and Forward 10 s as round icons in the middle (then Next Episode, when there is one to
 * play, [onNext]), between Versions on the left and Audio and subtitles on the right.
 * The times sit in fixed slots in tabular digits, so the scrubber stays still as they tick (#70).
 * A [live] Channel has no scrubber and no jumps: Play/Pause stays alone in the middle.
 */
@Composable
internal fun PlayerControls(
    position: Long,
    buffered: Long,
    duration: Long?,
    isPlaying: Boolean,
    live: Boolean,
    playFocus: FocusRequester,
    onBack: () -> Unit,
    onPlayPause: () -> Unit,
    onForward: () -> Unit,
    onVersions: () -> Unit,
    onTracks: () -> Unit,
    modifier: Modifier = Modifier,
    onNext: (() -> Unit)? = null,
) {
    val time = Type.label.copy(fontFeatureSettings = "tnum")
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val slot = clockSlot(maxOf(duration ?: 0, position))
    val slotWidth = with(density) { measurer.measure("\u2212$slot", time).size.width.toDp() }
    Column(modifier.fillMaxWidth().padding(horizontal = Spacing.screenX, vertical = Spacing.screenY)) {
        if (!live) Row(verticalAlignment = Alignment.CenterVertically) {
            Text(clock(position), style = time, color = Colors.ink, textAlign = TextAlign.End, modifier = Modifier.width(slotWidth))
            Box(Modifier.weight(1f).padding(horizontal = 14.dp).height(6.dp)) {
                val shape = RoundedCornerShape(3.dp)
                fun share(at: Long) = if (duration == null) 0f else (at.toFloat() / duration).coerceIn(0f, 1f)
                Box(Modifier.fillMaxSize().background(Colors.ink.copy(alpha = 0.25f), shape))
                Box(Modifier.fillMaxWidth(share(buffered)).fillMaxHeight().background(Colors.ink.copy(alpha = 0.45f), shape))
                Box(Modifier.fillMaxWidth(share(position)).fillMaxHeight().background(Colors.ink, shape))
            }
            Text(duration?.let { "\u2212" + clock((it - position).coerceAtLeast(0)) } ?: "", style = time, color = Colors.inkMuted, modifier = Modifier.width(slotWidth))
        }
        Spacer(Modifier.height(Spacing.l))
        ControlsRow(
            start = { NuageButton(stringResource(R.string.versions_title), onClick = onVersions, icon = Icons.AutoMirrored.Filled.List) },
            transport = {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xl)) {
                    if (!live) NuageIconButton(NuageIcons.Replay10, stringResource(R.string.player_back), onBack, slot = PLAY_SIZE)
                    NuageIconButton(
                        if (isPlaying) NuageIcons.Pause else Icons.Filled.PlayArrow,
                        stringResource(if (isPlaying) R.string.player_pause else R.string.play),
                        onPlayPause,
                        Modifier.focusRequester(playFocus),
                        size = PLAY_SIZE,
                    )
                    if (!live) NuageIconButton(NuageIcons.Forward10, stringResource(R.string.player_forward), onForward, slot = PLAY_SIZE)
                    if (onNext != null) NuageIconButton(NuageIcons.SkipNext, stringResource(R.string.player_next), onNext, slot = PLAY_SIZE)
                }
            },
            end = { NuageButton(stringResource(R.string.player_tracks), onClick = onTracks, icon = NuageIcons.Subtitles) },
        )
    }
}

/** Play/Pause, a step bigger than the buttons beside it. */
private val PLAY_SIZE = 52.dp

/**
 * The transport centred on the screen, with [start] at the left and [end] at the right, level with
 * Play/Pause. Should a long label reach the middle, the transport gives way to keep [Spacing.xl]
 * from it instead of overlapping.
 */
@Composable
private fun ControlsRow(start: @Composable () -> Unit, transport: @Composable () -> Unit, end: @Composable () -> Unit) {
    Layout(contents = listOf(start, transport, end)) { (startMeasurables, transportMeasurables, endMeasurables), constraints ->
        val width = constraints.maxWidth
        val gap = Spacing.xl.roundToPx()
        val loose = constraints.copy(minWidth = 0)
        val left = startMeasurables.first().measure(loose)
        val right = endMeasurables.first().measure(loose)
        val middle = transportMeasurables.first().measure(loose.copy(maxWidth = (width - left.width - right.width - 2 * gap).coerceAtLeast(0)))
        val play = PLAY_SIZE.roundToPx()
        layout(width, maxOf(middle.height, left.height, right.height)) {
            left.place(0, (play - left.height) / 2)
            middle.place(((width - middle.width) / 2).coerceIn(left.width + gap, width - right.width - gap - middle.width), 0)
            right.place(width - right.width, (play - right.height) / 2)
        }
    }
}

/** `1:18:52`, or `23:41` under an hour. */
internal fun clock(millis: Long): String {
    val seconds = millis / 1000
    return if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60) else "%d:%02d".format(seconds / 60, seconds % 60)
}

/**
 * The widest [clock] of any time up to [longest], in zeros. In tabular digits every clock up to
 * [longest] fits in it, so a slot this wide never changes size as playback moves.
 */
internal fun clockSlot(longest: Long): String = clock(longest).replace(Regex("[0-9]"), "0")

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
    downloads: List<SubtitleOffer>,
    shownDownload: SubtitleOffer?,
    onDownloaded: (SubtitleOffer) -> Unit,
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
                SettingRow(stringResource(R.string.subtitles_off), if (tracks?.subtitlesOn == null && shownDownload == null) on else "", onClick = {
                    onSubtitles(null, null)
                    picks++
                })
            }
            tracks?.subtitles.orEmpty().forEachIndexed { index, track ->
                item {
                    SettingRow(context.trackName(track), if (index == tracks?.subtitlesOn && shownDownload == null) on else "", onClick = {
                        onSubtitles(index, track)
                        picks++
                    })
                }
            }
            downloads.forEach { offer ->
                item {
                    val detail = if (offer.hearingImpaired) stringResource(R.string.subtitle_hearing_impaired, offer.release) else offer.release
                    SettingRow(
                        stringResource(R.string.subtitle_downloaded, context.trackName(Track(offer.language))),
                        if (offer.id == shownDownload?.id) on else "",
                        onClick = {
                            onDownloaded(offer)
                            picks++
                        },
                        detail = detail.takeIf { it.isNotBlank() },
                    )
                }
            }
            item { PanelHeading(stringResource(R.string.subtitle_style)) }
            // libVLC reads the style only when it starts, and restarting it to show a change takes many seconds on the stick.
            if (player?.engine == Engine.Vlc) item { Text(stringResource(R.string.subtitle_style_vlc), style = Type.body, color = Colors.inkMuted) }
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
            item { DelayRow(R.string.audio_delay, audioDelay, DELAY_STEP, stringResource(R.string.delay_value, audioDelay), onAudioDelay) }
            item {
                val seconds = String.format(java.util.Locale.getDefault(), "%+.2f", subtitleDelay / 1000.0)
                DelayRow(R.string.subtitle_delay, subtitleDelay, SUBTITLE_DELAY_STEP, stringResource(R.string.delay_seconds, seconds), onSubtitleDelay)
            }
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
    Row(Modifier.keepsSideFocus(), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(label), style = Type.label, color = Colors.ink, modifier = Modifier.width(STYLE_LABEL))
        ChoiceRow(options.map { stringResource(it) }, selected, onSelect)
    }
}

/** An audio or subtitle delay, made earlier or later a step at a time. */
@Composable
private fun DelayRow(label: Int, delay: Long, step: Long, shown: String, onChange: (Long) -> Unit) {
    Row(Modifier.keepsSideFocus(), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(label), style = Type.label, color = Colors.ink, modifier = Modifier.width(STYLE_LABEL))
        NuageButton(stringResource(R.string.delay_earlier), onClick = { onChange(delay - step) })
        Text(
            shown,
            style = Type.label,
            color = Colors.ink,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(96.dp),
        )
        NuageButton(stringResource(R.string.delay_later), onClick = { onChange(delay + step) })
    }
}

private val STYLE_LABEL = 200.dp

/** Left and Right stay in this row at its ends, instead of jumping to whatever lies beside it above or below. */
private fun Modifier.keepsSideFocus() = focusProperties {
    onExit = { if (requestedFocusDirection == FocusDirection.Left || requestedFocusDirection == FocusDirection.Right) cancelFocusChange() }
}.focusGroup()

/** A Track as the Viewer picks it: its language, then for audio its channels and format. */
private fun Context.trackName(track: Track): String {
    // A Track tagged `und` (undetermined) has no name in any language.
    val language = track.language?.let { iso639(it) ?: it }?.let(::languageName)?.takeIf { it.isNotEmpty() } ?: getString(R.string.track_unknown_language)
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

/**
 * Offered once an Episode ends: the [next] one, which one press plays, or which plays by itself in
 * [left] seconds when it counts down. Back leaves the player.
 */
@Composable
private fun NextEpisode(next: PlayPlan, left: Int?, onPlay: () -> Unit) {
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
            if (left != null) Text(stringResource(R.string.player_next_in, left), style = Type.body, color = Colors.inkMuted)
            NuageButton(stringResource(R.string.player_play_next), onPlay, Modifier.focusRequester(button), icon = Icons.Filled.PlayArrow)
        }
    }
}

/**
 * Offered over an Episode's credits, bottom right: the [next] one, with "Play now", which takes
 * focus, and "Watch credits", which turns it away, as Back does. When it counts down, it says it
 * plays by itself in [left] seconds.
 */
@Composable
private fun UpNextCard(next: PlayPlan, left: Int?, onPlay: () -> Unit, onDismiss: () -> Unit) {
    val episode = next.episode ?: return
    val button = remember { FocusRequester() }
    LaunchedEffect(next) { button.requestFocus() }
    BackHandler(onBack = onDismiss)
    val shape = RoundedCornerShape(Corners.card)
    Box(Modifier.fillMaxSize().background(bottomScrim).padding(horizontal = Spacing.screenX, vertical = Spacing.screenY)) {
        Column(
            // Glass carrying text over the picture: ground at 75% first, then glass.
            Modifier.align(Alignment.BottomEnd)
                .background(Colors.veil, shape)
                .background(Colors.surface, shape)
                .padding(Spacing.l),
        ) {
            Text(stringResource(R.string.player_next_episode, episode.season, episode.number), style = Type.label, color = Colors.ink)
            if (left != null) Text(stringResource(R.string.player_next_in, left), style = Type.caption, color = Colors.inkMuted)
            Spacer(Modifier.height(Spacing.m))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
                NuageButton(stringResource(R.string.player_play_now), onPlay, Modifier.focusRequester(button), icon = Icons.Filled.PlayArrow)
                NuageButton(stringResource(R.string.player_watch_credits), onDismiss)
            }
        }
    }
}
