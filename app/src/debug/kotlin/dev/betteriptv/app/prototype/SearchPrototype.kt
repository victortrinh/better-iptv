package dev.betteriptv.app.prototype

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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import dev.betteriptv.app.ui.components.RemoteKeyboard
import dev.betteriptv.app.ui.components.TitleCard
import dev.betteriptv.app.ui.components.TopTabs
import dev.betteriptv.app.ui.theme.Cards
import dev.betteriptv.app.ui.theme.Colors
import dev.betteriptv.app.ui.theme.Corners
import dev.betteriptv.app.ui.theme.Type
import dev.betteriptv.app.ui.theme.Spacing

/** Search: a D-pad keyboard on the left, results updating on the right as each letter lands. */
@Composable
fun SearchPrototype() {
        var query by remember { mutableStateOf("the g") }
    val keyboard = remember { FocusRequester() }
    LaunchedEffect(Unit) { keyboard.requestFocus() }
    val results = listOf(SampleCatalog.general, SampleCatalog.goldRush, SampleCatalog.kid, SampleCatalog.phantom)
        .filter { it.name.lowercase().startsWith(query.lowercase()) }

    Ground {
        Column(Modifier.fillMaxSize().padding(horizontal = Spacing.screenX, vertical = Spacing.screenY)) {
            TopTabs(TABS, selected = 4, onSelect = {})
            Spacer(Modifier.height(24.dp))
            Row {
                Column(Modifier.width(290.dp)) {
                    Box(
                        Modifier.fillMaxWidth().height(44.dp).background(Colors.surface, RoundedCornerShape(Corners.control)).padding(horizontal = 14.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(query, style = Type.title, color = Colors.ink)
                            Box(Modifier.padding(start = 2.dp).width(2.dp).height(20.dp).background(Colors.focus))
                        }
                    }
                    Text(
                        "Accents and typos are fine: \"metropolis\", \"métropolis\" and \"metroplis\" all find it.",
                        style = Type.caption,
                        color = Colors.inkMuted,
                        modifier = Modifier.padding(top = 8.dp, bottom = 14.dp),
                    )
                    RemoteKeyboard(
                        onKey = { query += it },
                        onDelete = { query = query.dropLast(1) },
                        onSpace = { query += " " },
                        modifier = Modifier.focusRequester(keyboard),
                    )
                }
                Spacer(Modifier.width(40.dp))
                Column(Modifier.weight(1f)) {
                    Text("Movies · ${results.size}", style = Type.headline, color = Colors.ink)
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(Cards.gap)) {
                        results.forEach { title ->
                            TitleCard(painterResource(title.poster), title.name, title.badges, onClick = {})
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text("Shows · 0", style = Type.headline, color = Colors.ink)
                    Text("No Shows match \"$query\".", style = Type.body, color = Colors.inkMuted, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
    }
}
