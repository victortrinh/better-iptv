package dev.betteriptv.app.prototype

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import dev.betteriptv.app.ResultGrid
import dev.betteriptv.app.ui.components.Completions
import dev.betteriptv.app.ui.components.KEYBOARD_WIDTH
import dev.betteriptv.app.ui.components.RemoteKeyboard
import dev.betteriptv.app.ui.components.TitleCard
import dev.betteriptv.app.ui.components.TopTabs
import dev.betteriptv.app.ui.theme.Colors
import dev.betteriptv.app.ui.theme.Spacing
import dev.betteriptv.app.ui.theme.Type
import kotlinx.coroutines.delay

/**
 * Search, as Netflix lays it out: a D-pad keyboard on the left, completions under it, and the query
 * over one grid of cards on the right, updating as each letter lands.
 */
@Composable
fun SearchPrototype() {
    var query by remember { mutableStateOf("the g") }
    val keyboard = remember { FocusRequester() }
    LaunchedEffect(Unit) { keyboard.requestFocus() }
    val firstResult = remember { FocusRequester() }
    val grid = remember { FocusRequester() }
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
        Column(Modifier.fillMaxSize().padding(start = Spacing.screenX, top = Spacing.screenY)) {
            TopTabs(TABS, selected = 4, onSelect = {}, modifier = Modifier.padding(end = Spacing.screenX))
            Spacer(Modifier.height(24.dp))
            Row {
                Column(
                    Modifier.width(KEYBOARD_WIDTH).verticalScroll(rememberScrollState())
                        // Right from Space or Delete, above the cards, goes into the grid rather than up to the tabs.
                        .focusProperties { onExit = { if (requestedFocusDirection == FocusDirection.Right && shown.isNotEmpty()) grid.requestFocus() } }
                        .focusGroup(),
                ) {
                    RemoteKeyboard(
                        onKey = { query += it },
                        onDelete = { query = query.dropLast(1) },
                        onSpace = { query += " " },
                        entry = keyboard,
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
                Spacer(Modifier.width(Spacing.xxl))
                Column(Modifier.weight(1f)) {
                    Row(Modifier.height(32.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Search, contentDescription = null, tint = Colors.ink, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(Spacing.m))
                        Text(query, style = Type.title, color = Colors.ink)
                        Box(Modifier.padding(start = 2.dp).width(2.dp).height(18.dp).background(Colors.focus))
                    }
                    if (shown.isEmpty()) {
                        Text("Nothing matches \"$query\".", style = Type.body, color = Colors.inkMuted, modifier = Modifier.padding(top = Spacing.l))
                    } else {
                        ResultGrid(shown, wide = { false }, keyOf = { it.name }, first = firstResult, modifier = Modifier.padding(top = Spacing.l).focusRequester(grid)) { title, modifier ->
                            TitleCard(painterResource(title.poster), title.name, emptyList(), onClick = {}, modifier = modifier, named = false)
                        }
                    }
                }
            }
        }
    }
}

/** As TMDB's search takes on the stick: about a second after the device's own matches. */
private const val SECOND_ANSWER_MS = 1500L
