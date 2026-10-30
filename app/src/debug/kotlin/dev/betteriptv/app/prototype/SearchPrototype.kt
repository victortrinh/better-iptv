package dev.betteriptv.app.prototype

import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.withFrameNanos
import dev.betteriptv.app.ResultRow
import dev.betteriptv.app.ui.components.Completions
import kotlinx.coroutines.delay
import androidx.tv.material3.Text
import dev.betteriptv.app.ui.components.RemoteKeyboard
import dev.betteriptv.app.ui.components.TitleCard
import dev.betteriptv.app.ui.components.TopTabs
import dev.betteriptv.app.ui.theme.Colors
import dev.betteriptv.app.ui.theme.Corners
import dev.betteriptv.app.ui.theme.Type
import dev.betteriptv.app.ui.theme.Spacing

/**
 * Search: a D-pad keyboard on the left, completions under it, results updating on the right as
 * each letter lands.
 */
@Composable
fun SearchPrototype() {
    var query by remember { mutableStateOf("the g") }
    val keyboard = remember { FocusRequester() }
    LaunchedEffect(Unit) { keyboard.requestFocus() }
    val firstResult = remember { FocusRequester() }
    var toResults by remember { mutableStateOf(false) }
    val titles = listOf(
        SampleCatalog.general, SampleCatalog.goldRush, SampleCatalog.kid, SampleCatalog.phantom,
        SampleCatalog.metropolis, SampleCatalog.safetyLast, SampleCatalog.sherlockJr, SampleCatalog.caligari,
    )
    val typed = query.trim().lowercase()
    val genres = titles.flatMap { it.genres.split(" · ") }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }
    val starts = { name: String -> name.lowercase().split(" ").any { it.startsWith(typed.substringAfterLast(' ')) } && name.lowercase().contains(typed) }
    val results = titles.filter { starts(it.name) || it.genres.split(" · ").any { genre -> genre.equals(typed, ignoreCase = true) } }
    // As the search index offers them: Title names, then people, then genres; genre shortcuts before typing.
    val completions = when {
        typed.isEmpty() -> genres
        typed.length < 2 -> emptyList()
        else -> (
            results.map { it.name }.take(4) +
                titles.flatMap { it.people.split(", ") }.distinct().filter(starts).take(2) +
                genres.filter(starts).take(1)
            ).filter { !it.equals(typed, ignoreCase = true) }.take(7)
    }
    // Search answers twice: the device's own matches, then reordered once TMDB's search adds what it knows.
    var shown by remember { mutableStateOf(results) }
    LaunchedEffect(query) {
        shown = results
        delay(SECOND_ANSWER_MS)
        shown = results.reversed()
    }
    LaunchedEffect(toResults, query) {
        if (!toResults || results.isEmpty()) return@LaunchedEffect
        withFrameNanos {}
        firstResult.requestFocus()
        toResults = false
    }

    Ground {
        Column(Modifier.fillMaxSize().padding(horizontal = Spacing.screenX, vertical = Spacing.screenY)) {
            TopTabs(TABS, selected = 4, onSelect = {})
            Spacer(Modifier.height(24.dp))
            Row {
                Column(Modifier.width(290.dp).verticalScroll(rememberScrollState())) {
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
                    Completions(
                        completions,
                        onPick = {
                            query = it
                            toResults = true
                        },
                        modifier = Modifier.padding(top = 14.dp, bottom = Spacing.screenY),
                    )
                }
                Spacer(Modifier.width(40.dp))
                Column(Modifier.weight(1f)) {
                    Text("Movies · ${shown.size}", style = Type.headline, color = Colors.ink)
                    ResultRow(shown, key = { it.name }, Modifier.focusRequester(firstResult)) { title, modifier ->
                        TitleCard(painterResource(title.poster), title.name, title.badges, onClick = {}, modifier = modifier)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text("Shows · 0", style = Type.headline, color = Colors.ink)
                    Text("No Shows match \"$query\".", style = Type.body, color = Colors.inkMuted, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
    }
}

/** As TMDB's search takes on the stick: about a second after the device's own matches. */
private const val SECOND_ANSWER_MS = 1500L
