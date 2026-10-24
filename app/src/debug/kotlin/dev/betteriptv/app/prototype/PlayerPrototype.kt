package dev.betteriptv.app.prototype

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import dev.betteriptv.app.R
import dev.betteriptv.app.ui.components.BadgeRow
import dev.betteriptv.app.ui.components.NuageButton
import dev.betteriptv.app.ui.theme.Cards
import dev.betteriptv.app.ui.theme.Colors
import dev.betteriptv.app.ui.theme.Corners
import dev.betteriptv.app.ui.theme.Type
import dev.betteriptv.app.ui.theme.Spacing

/** The player paused, with its overlay: where you are, which Version, and the controls. */
@Composable
fun PlayerPrototype() {
        val title = SampleCatalog.general
    val play = remember { FocusRequester() }
    LaunchedEffect(Unit) { play.requestFocus() }

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

        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = Spacing.screenX, vertical = Spacing.screenY)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("0:23:41", style = Type.label, color = Colors.ink)
                Box(Modifier.weight(1f).padding(horizontal = 14.dp).height(6.dp)) {
                    Box(Modifier.fillMaxSize().background(Colors.ink.copy(alpha = 0.25f), RoundedCornerShape(3.dp)))
                    Box(Modifier.fillMaxWidth(0.42f).height(6.dp).background(Colors.ink.copy(alpha = 0.45f), RoundedCornerShape(3.dp)))
                    Box(Modifier.fillMaxWidth(0.30f).height(6.dp).background(Colors.ink, RoundedCornerShape(3.dp)))
                }
                Text("1:18:52", style = Type.label, color = Colors.inkMuted)
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                NuageButton("Back 10 s", {})
                NuageButton("Play", {}, Modifier.focusRequester(play), icon = Icons.Filled.PlayArrow)
                NuageButton("Forward 10 s", {})
                Spacer(Modifier.weight(1f))
                NuageButton("Versions", {})
                NuageButton("Audio and subtitles", {})
            }
        }
    }
}
