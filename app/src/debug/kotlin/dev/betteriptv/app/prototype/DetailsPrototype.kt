package dev.betteriptv.app.prototype

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import dev.betteriptv.app.ui.components.BadgeRow
import dev.betteriptv.app.ui.components.CertaintyLegend
import dev.betteriptv.app.ui.components.ActionRow
import dev.betteriptv.app.ui.components.PrimaryAction
import dev.betteriptv.app.ui.components.SettingRow
import dev.betteriptv.app.ui.components.VersionHeader
import dev.betteriptv.app.ui.components.VersionRow
import dev.betteriptv.app.ui.theme.Cards
import dev.betteriptv.app.ui.theme.Colors
import dev.betteriptv.app.ui.theme.Corners
import dev.betteriptv.app.ui.theme.Type
import dev.betteriptv.app.ui.theme.Spacing

/** A Title's details: what it is, its actions led by Play, and every Version grouped by language. */
@Composable
fun DetailsPrototype(focusVersions: Boolean) {
        val title = SampleCatalog.general
    val play = remember { FocusRequester() }
    val firstVersion = remember { FocusRequester() }
    LaunchedEffect(Unit) { (if (focusVersions) firstVersion else play).requestFocus() }

    Ground {
        Backdrop(title.still)
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = Spacing.screenX, vertical = Spacing.screenY),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Column(Modifier.width(470.dp).padding(top = 24.dp, bottom = 20.dp)) {
                    Text(title.name, style = Type.display, color = Colors.ink)
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${title.year} · ${title.runtime} · ${title.genres}", style = Type.body, color = Colors.inkMuted)
                        Spacer(Modifier.width(12.dp))
                        BadgeRow(title.badges)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(title.description, style = Type.body, color = Colors.ink, maxLines = 3)
                    Text(title.people, style = Type.body, color = Colors.inkMuted, modifier = Modifier.padding(top = 4.dp))
                    Spacer(Modifier.height(18.dp))
                    // A Movie the Viewer stopped in: Play names where it continues, the other actions stack under it.
                    Column(Modifier.width(300.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        PrimaryAction("Continue from 1:02:13", Icons.Filled.PlayArrow, {}, Modifier.focusRequester(play), progress = 0.55f)
                        ActionRow("Restart", Icons.Filled.Refresh, {})
                        ActionRow("Remove from Continue watching", Icons.Filled.Close, {})
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("English · 4K · HDR10 · Netflix", style = Type.label, color = Colors.inkMuted)
                }
            }
            item {
                Row(Modifier.padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Versions", style = Type.headline, color = Colors.ink)
                    Spacer(Modifier.weight(1f))
                    CertaintyLegend()
                }
            }
            item { VersionHeader(Modifier.padding(bottom = 2.dp)) }
            SampleCatalog.versions.forEachIndexed { groupIndex, group ->
                item {
                    Text(
                        "${group.language} · ${group.lines.size}",
                        style = Type.title,
                        color = Colors.ink,
                        modifier = Modifier.padding(top = if (groupIndex == 0) 0.dp else 12.dp, bottom = 2.dp),
                    )
                }
                items(group.lines.size) { index ->
                    VersionRow(
                        group.lines[index],
                        onClick = {},
                        modifier = if (groupIndex == 0 && index == 0) Modifier.focusRequester(firstVersion) else Modifier,
                    )
                }
            }
            item {
                SettingRow(SampleCatalog.otherLanguages, "Show 4", {}, Modifier.padding(top = 12.dp))
            }
        }
    }
}
