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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import dev.betteriptv.app.ui.components.ChoiceRow
import dev.betteriptv.app.ui.components.SettingRow
import dev.betteriptv.app.ui.components.TopTabs
import dev.betteriptv.app.ui.theme.Nuage
import dev.betteriptv.app.ui.theme.Spacing

@Composable
fun SettingsPrototype() {
    val colors = Nuage.colors
    var qualityCap by remember { mutableIntStateOf(0) }
    var reading by remember { mutableIntStateOf(1) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.requestFocus() }

    Ground(null) {
        Column(Modifier.fillMaxSize().padding(top = Spacing.screenY)) {
            TopTabs(TABS, selected = 5, onSelect = {}, modifier = Modifier.padding(horizontal = Spacing.screenX))
            LazyColumn(
                contentPadding = PaddingValues(horizontal = Spacing.screenX, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.width(760.dp),
            ) {
                item { Heading("Languages") }
                item { SettingRow("App language", "English", {}, Modifier.focusRequester(first), detail = "Menus, titles and descriptions") }
                item { SettingRow("Content languages", "English, Français", {}, detail = "Rows only show Titles with a Version in these") }
                item { Heading("Playback") }
                item {
                    Labelled("Quality cap", "The highest quality Play picks. Lower it if streams stutter.") {
                        ChoiceRow(listOf("4K", "1080p", "720p"), qualityCap, { qualityCap = it })
                    }
                }
                item {
                    Labelled("Background reading", "Reads what's inside Versions while nothing is playing. Gentle stops at the first sign of Provider pushback.") {
                        ChoiceRow(listOf("Off", "Gentle", "Normal"), reading, { reading = it })
                    }
                }
                item { SettingRow("Subtitles", "Large, white on shadow", {}) }
                item { Heading("Provider") }
                item { SettingRow("Subscription", "Expires 14 March 2027", {}, detail = "1 stream at a time") }
            }
        }
    }
}

@Composable
private fun Heading(text: String) {
    Text(text, style = Nuage.type.headline, color = Nuage.colors.ink, modifier = Modifier.padding(top = 14.dp, bottom = 2.dp))
}

@Composable
private fun Labelled(label: String, detail: String, content: @Composable () -> Unit) {
    Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        Column(Modifier.width(320.dp)) {
            Text(label, style = Nuage.type.label, color = Nuage.colors.ink)
            Text(detail, style = Nuage.type.caption, color = Nuage.colors.inkMuted)
        }
        Spacer(Modifier.width(16.dp))
        content()
    }
    Spacer(Modifier.height(2.dp))
}
