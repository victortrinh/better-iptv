package dev.betteriptv.app.prototype

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import dev.betteriptv.app.PlayerControls
import dev.betteriptv.app.SubtitleStyle
import dev.betteriptv.app.TrackCards
import dev.betteriptv.app.TrackOption
import dev.betteriptv.app.audioName
import dev.betteriptv.core.Track
import dev.betteriptv.app.R
import dev.betteriptv.app.ui.components.BadgeRow
import dev.betteriptv.app.ui.components.NuageButton
import dev.betteriptv.app.ui.theme.Cards
import dev.betteriptv.app.ui.theme.Colors
import dev.betteriptv.app.ui.theme.Corners
import dev.betteriptv.app.ui.theme.Type
import dev.betteriptv.app.ui.theme.Spacing
import kotlinx.coroutines.delay

/**
 * The player paused, with its overlay: where you are, which Version, and the controls. "Audio and
 * subtitles" opens its cards over the picture ([tracks] opens them at once).
 */
@Composable
fun PlayerPrototype(tracks: Boolean = false) {
    val title = SampleCatalog.general
    val play = remember { FocusRequester() }
    var cards by remember { mutableStateOf(tracks) }
    LaunchedEffect(cards) { if (!cards) play.requestFocus() }
    // Ticks like a playing film, so the time slots can be seen holding the scrubber still.
    var playing by remember { mutableStateOf(true) }
    var position by remember { mutableLongStateOf(3_599_000) }
    LaunchedEffect(playing) {
        while (playing) {
            delay(1_000)
            position += 1_000
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Image(painterResource(title.still), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.85f), 0.12f to Color.Black.copy(alpha = 0.6f), 0.28f to Color.Transparent, 0.5f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.85f))))

        Row(Modifier.align(Alignment.TopStart).fillMaxWidth().padding(horizontal = Spacing.screenX, vertical = Spacing.screenY), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(title.name, style = Type.headline, color = Colors.ink)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                    BadgeRow(SampleCatalog.versions[0].lines[0].badges)
                    Spacer(Modifier.width(8.dp))
                    Text("English 5.1 · Netflix", style = Type.caption, color = Colors.ink)
                }
            }
            // The one-tap suggestion when a stream keeps buffering; never an automatic switch.
            Row(
                Modifier.background(Colors.ground.copy(alpha = 0.75f), RoundedCornerShape(Corners.control)).background(Colors.surface, RoundedCornerShape(Corners.control)).padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text("Keeps buffering", style = Type.label, color = Colors.ink)
                    Text("1080p from Prime Video should be steadier", style = Type.caption, color = Colors.inkMuted)
                }
                Spacer(Modifier.width(12.dp))
                NuageButton("Switch", {})
            }
        }

        // Hidden under the cards, as in the player, so focus stays with them.
        if (!cards) PlayerControls(
            position = position,
            buffered = position + 900_000,
            duration = 4_732_000,
            isPlaying = playing,
            live = false,
            playFocus = play,
            onBack = { position = (position - 10_000).coerceAtLeast(0) },
            onPlayPause = { playing = !playing },
            onForward = { position += 10_000 },
            onVersions = {},
            onTracks = { cards = true },
            modifier = Modifier.align(Alignment.BottomStart),
        )
        if (cards) SampleTracks(onClose = { cards = false })
    }
}

/** Audio and subtitles for a film released in English, playing its French dub with English subtitles. */
@Composable
private fun SampleTracks(onClose: () -> Unit) {
    val context = LocalContext.current
    var audioOn by remember { mutableIntStateOf(1) }
    var subtitlesOn by remember { mutableIntStateOf(1) }
    var style by remember { mutableStateOf(SubtitleStyle()) }
    var audioDelay by remember { mutableLongStateOf(0) }
    var subtitleDelay by remember { mutableLongStateOf(0) }
    val audio = listOf(Track("eng", "audio/eac3", 6), Track("fre", "audio/ac3", 6), Track("fre", "audio/mp4a-latm", 2))
    val subtitles = listOf("Off", "English · SRT", "English · For the hard of hearing", "Français · SRT", "Español · SRT")
    TrackCards(
        audio = audio.mapIndexed { index, track -> TrackOption(context.audioName(track, original = index == 0), index == audioOn, { audioOn = index }, short = context.audioName(track, original = index == 0, withFormat = false)) },
        subtitles = subtitles.mapIndexed { index, name -> TrackOption(name, index == subtitlesOn, { subtitlesOn = index }) },
        style = style,
        styleNote = null,
        audioDelay = audioDelay,
        subtitleDelay = subtitleDelay,
        onStyle = { style = it },
        onAudioDelay = { audioDelay = it },
        onSubtitleDelay = { subtitleDelay = it },
        onClose = onClose,
    )
}
