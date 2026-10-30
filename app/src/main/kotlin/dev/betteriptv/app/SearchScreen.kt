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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import dev.betteriptv.app.ui.components.Completions
import dev.betteriptv.app.ui.components.RemoteKeyboard
import dev.betteriptv.app.ui.components.TitleCard
import dev.betteriptv.app.ui.theme.Cards
import dev.betteriptv.app.ui.theme.Colors
import dev.betteriptv.app.ui.theme.Corners
import dev.betteriptv.app.ui.theme.Spacing
import dev.betteriptv.app.ui.theme.Type
import androidx.compose.runtime.rememberCoroutineScope
import dev.betteriptv.core.Channel
import dev.betteriptv.core.Library
import kotlinx.coroutines.launch
import dev.betteriptv.core.SearchResults
import dev.betteriptv.core.TitleId
import dev.betteriptv.core.TitleSummary
import kotlinx.coroutines.delay

/**
 * Search: the [query] typed on a D-pad keyboard on the left, results on the right as each letter
 * lands, grouped as Movies, Shows, genres, Channels and people (each genre and person with their
 * Titles the Provider has). Under the keyboard, completions replace the query in one press and
 * move focus to the first result; before anything is typed they are genre shortcuts. A Title's
 * card opens it ([onOpen]); a Channel's watches it ([onWatch]), and its menu key adds or removes
 * the Favourite. The tabs switch to the other browsing screens ([onTab]).
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
    onWatch: (Channel) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val status by library.syncStatus.collectAsStateWithLifecycle()
    var results by remember { mutableStateOf(SearchResults.NONE) }
    // The query [results] answer: the one being typed until the index, if still being built, has them.
    var answered by remember { mutableStateOf("") }
    // Finishes the index at the Viewer's priority, if its build in the background hasn't, while they reach for the first letters.
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
    val tab = remember { FocusRequester() }
    LaunchedEffect(Unit) { keyboard.requestFocus() }
    // A completion was picked: focus goes to the first result once its query is answered.
    val firstResult = remember { FocusRequester() }
    var toResults by remember { mutableStateOf(false) }
    LaunchedEffect(toResults, results, answered) {
        if (!toResults || answered != query || results.isEmpty) return@LaunchedEffect
        // The first card is laid out in the frame these results are composed in.
        withFrameNanos {}
        firstResult.requestFocus()
        // Last: it restarts this effect.
        toResults = false
    }

    Column(Modifier.fillMaxSize().background(Colors.ground).padding(top = Spacing.screenY)) {
        BrowseTabs(Browse.Search, { if (it != Browse.Search) onTab(it) }, Modifier.padding(horizontal = Spacing.screenX), selectedFocus = tab)
        Spacer(Modifier.height(24.dp))
        Row(Modifier.fillMaxSize()) {
            // Scrolls once focus goes down into the completions, so they all can be reached.
            Column(Modifier.padding(start = Spacing.screenX).width(290.dp).verticalScroll(rememberScrollState())) {
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
                Completions(
                    results.completions,
                    onPick = {
                        onQuery(it)
                        toResults = true
                    },
                    modifier = Modifier.padding(top = 14.dp, bottom = Spacing.screenY),
                )
            }
            Spacer(Modifier.width(40.dp))
            Results(library, contentLanguages, query, results, searching = answered != query, tab, firstResult, onOpen, onWatch) { channel, favourite ->
                scope.launch {
                    library.setFavourite(channel.id, favourite)
                    results = results.copy(channels = results.channels.map { if (it.id == channel.id) it.copy(favourite = favourite) else it })
                }
            }
        }
    }
}

@Composable
private fun Results(
    library: Library,
    contentLanguages: List<String>,
    query: String,
    results: SearchResults,
    searching: Boolean,
    tab: FocusRequester,
    firstResult: FocusRequester,
    onOpen: (TitleId) -> Unit,
    onWatch: (Channel) -> Unit,
    onFavourite: (Channel, Boolean) -> Unit,
) {
    if (results.isEmpty) {
        val message = when {
            query.isBlank() -> stringResource(R.string.search_start)
            searching -> stringResource(R.string.search_searching)
            else -> stringResource(R.string.search_none, query.trim())
        }
        Text(message, style = Type.body, color = Colors.inkMuted)
        return
    }
    // The first card of the first group takes focus when a completion is picked.
    val first = Modifier.focusRequester(firstResult)
    val firstGroup = when {
        results.movies.isNotEmpty() -> results.movies
        results.shows.isNotEmpty() -> results.shows
        results.genres.isNotEmpty() -> results.genres.first().titles
        results.channels.isNotEmpty() -> results.channels
        else -> results.people.first().titles
    }
    // The right margin is content padding, so cards scroll under the edge instead of being clipped.
    LazyColumn(Modifier.backToTab(tab), contentPadding = PaddingValues(end = Spacing.screenX, bottom = Spacing.screenY)) {
        if (results.movies.isNotEmpty()) item {
            Group(library, contentLanguages, stringResource(R.string.search_movies, results.movies.size), results.movies, onOpen, first.takeIf { firstGroup === results.movies })
        }
        if (results.shows.isNotEmpty()) item {
            Group(library, contentLanguages, stringResource(R.string.search_shows, results.shows.size), results.shows, onOpen, first.takeIf { firstGroup === results.shows })
        }
        items(results.genres, key = { "genre/${it.name}" }) { genre ->
            Group(library, contentLanguages, genre.name, genre.titles, onOpen, first.takeIf { firstGroup === genre.titles })
        }
        if (results.channels.isNotEmpty()) item {
            Column {
                Text(stringResource(R.string.search_channels, results.channels.size), style = Type.headline, color = Colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                // A wide card grows past the column's edge when focused: the row starts that much
                // further left, so the card isn't clipped, and its cards still line up with the heading.
                LazyRow(
                    contentPadding = PaddingValues(start = Spacing.m, top = 12.dp, bottom = 10.dp, end = Spacing.screenX),
                    horizontalArrangement = Arrangement.spacedBy(Cards.gap),
                    modifier = Modifier.offset(x = -Spacing.m).focusRestorer(),
                ) {
                    itemsIndexed(results.channels, key = { _, channel -> "channel/${channel.id}" }) { i, channel ->
                        ChannelCard(channel, onWatch, onFavourite, if (i == 0 && firstGroup === results.channels) first else Modifier)
                    }
                }
                Spacer(Modifier.height(14.dp))
            }
        }
        items(results.people, key = { "person/${it.name}" }) { person ->
            Group(library, contentLanguages, person.name, person.titles, onOpen, first.takeIf { firstGroup === person.titles })
        }
    }
}

/** A headline over a row of cards; [first] modifies the first card. */
@Composable
private fun Group(library: Library, contentLanguages: List<String>, heading: String, titles: List<TitleSummary>, onOpen: (TitleId) -> Unit, first: Modifier?) {
    Column {
        Text(heading, style = Type.headline, color = Colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        LazyRow(
            contentPadding = PaddingValues(top = 12.dp, bottom = 10.dp, end = Spacing.screenX),
            horizontalArrangement = Arrangement.spacedBy(Cards.gap),
            modifier = Modifier.focusRestorer(),
        ) {
            itemsIndexed(titles, key = { _, title -> title.id.toString() }) { i, title ->
                TitleCard(
                    rememberArtwork(title.image, card = true),
                    title.name,
                    cardBest(library, title, contentLanguages).cardBadges(),
                    onClick = { onOpen(title.id) },
                    modifier = first?.takeIf { i == 0 } ?: Modifier,
                )
            }
        }
        Spacer(Modifier.height(14.dp))
    }
}

private const val TYPING_PAUSE_MS = 150L
