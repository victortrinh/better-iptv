package dev.betteriptv.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import dev.betteriptv.app.ui.components.RemoteKeyboard
import dev.betteriptv.app.ui.components.TitleCard
import dev.betteriptv.app.ui.theme.Cards
import dev.betteriptv.app.ui.theme.Colors
import dev.betteriptv.app.ui.theme.Corners
import dev.betteriptv.app.ui.theme.Spacing
import dev.betteriptv.app.ui.theme.Type
import dev.betteriptv.core.Library
import dev.betteriptv.core.SearchResults
import dev.betteriptv.core.TitleId
import dev.betteriptv.core.TitleSummary
import kotlinx.coroutines.delay

/**
 * Search: the [query] typed on a D-pad keyboard on the left, results on the right as each letter
 * lands, grouped as Movies, Shows and people (each person with their Titles the Provider has).
 * The tabs switch to the other browsing screens ([onTab]).
 */
@Composable
fun SearchScreen(
    library: Library,
    query: String,
    onQuery: (String) -> Unit,
    contentLanguages: List<String>,
    appLanguage: String,
    onTab: (Browse) -> Unit,
    onOpen: (TitleId) -> Unit,
) {
    val status by library.syncStatus.collectAsStateWithLifecycle()
    var results by remember { mutableStateOf(SearchResults.NONE) }
    // The query [results] answer: the one being typed until the index, built on first use, has them.
    var answered by remember { mutableStateOf("") }
    // Builds the index while the Viewer reaches for the first letters.
    LaunchedEffect(status.last) { library.prepareSearch() }
    LaunchedEffect(query, status.last, contentLanguages, appLanguage) {
        // Letters typed in quick succession make one search.
        delay(TYPING_PAUSE_MS)
        // The device's own matches, then again with what TMDB's search adds.
        library.search(query, contentLanguages, appLanguage).collect {
            results = it
            answered = query
        }
    }
    val keyboard = remember { FocusRequester() }
    LaunchedEffect(Unit) { keyboard.requestFocus() }

    Column(Modifier.fillMaxSize().background(Colors.ground).padding(top = Spacing.screenY)) {
        BrowseTabs(Browse.Search, { if (it != Browse.Search) onTab(it) }, Modifier.padding(horizontal = Spacing.screenX))
        Spacer(Modifier.height(24.dp))
        Row(Modifier.fillMaxSize()) {
            Column(Modifier.padding(start = Spacing.screenX).width(290.dp)) {
                Box(
                    Modifier.fillMaxWidth().height(44.dp).background(Colors.surface, RoundedCornerShape(Corners.control)).padding(horizontal = 14.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(query, style = Type.title, color = Colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        Box(Modifier.padding(start = 2.dp).width(2.dp).height(20.dp).background(Colors.focus))
                    }
                }
                Text(
                    stringResource(R.string.search_hint),
                    style = Type.caption,
                    color = Colors.inkMuted,
                    modifier = Modifier.padding(top = 8.dp, bottom = 14.dp),
                )
                RemoteKeyboard(
                    onKey = { onQuery(query + it) },
                    onDelete = { onQuery(query.dropLast(1)) },
                    onSpace = { if (query.isNotEmpty() && !query.endsWith(" ")) onQuery("$query ") },
                    modifier = Modifier.focusRequester(keyboard),
                )
            }
            Spacer(Modifier.width(40.dp))
            Results(library, contentLanguages, query, results, searching = answered != query, onOpen)
        }
    }
}

@Composable
private fun Results(library: Library, contentLanguages: List<String>, query: String, results: SearchResults, searching: Boolean, onOpen: (TitleId) -> Unit) {
    if (results == SearchResults.NONE) {
        val message = when {
            query.isBlank() -> stringResource(R.string.search_start)
            searching -> stringResource(R.string.search_searching)
            else -> stringResource(R.string.search_none, query.trim())
        }
        Text(message, style = Type.body, color = Colors.inkMuted)
        return
    }
    // The right margin is content padding, so cards scroll under the edge instead of being clipped.
    LazyColumn(contentPadding = PaddingValues(end = Spacing.screenX, bottom = Spacing.screenY)) {
        if (results.movies.isNotEmpty()) item { Group(library, contentLanguages, stringResource(R.string.search_movies, results.movies.size), results.movies, onOpen) }
        if (results.shows.isNotEmpty()) item { Group(library, contentLanguages, stringResource(R.string.search_shows, results.shows.size), results.shows, onOpen) }
        items(results.people, key = { "person/${it.name}" }) { person -> Group(library, contentLanguages, person.name, person.titles, onOpen) }
    }
}

/** A headline over a row of cards. */
@Composable
private fun Group(library: Library, contentLanguages: List<String>, heading: String, titles: List<TitleSummary>, onOpen: (TitleId) -> Unit) {
    Column {
        Text(heading, style = Type.headline, color = Colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        LazyRow(
            contentPadding = PaddingValues(top = 12.dp, bottom = 10.dp, end = Spacing.screenX),
            horizontalArrangement = Arrangement.spacedBy(Cards.gap),
        ) {
            items(titles, key = { it.id.toString() }) { title ->
                TitleCard(rememberArtwork(title.image, card = true), title.name, cardBest(library, title, contentLanguages).cardBadges(), onClick = { onOpen(title.id) })
            }
        }
        Spacer(Modifier.height(14.dp))
    }
}

private const val TYPING_PAUSE_MS = 150L
