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
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.focus.onFocusChanged
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
import dev.betteriptv.core.SearchGroup
import dev.betteriptv.core.SearchResults
import dev.betteriptv.core.TitleId
import dev.betteriptv.core.TitleSummary
import kotlinx.coroutines.delay

/**
 * Search: the [query] typed on a D-pad keyboard on the left, results on the right as each letter
 * lands, grouped as Movies, Shows, genres, Channels and people (each genre and person with their
 * Titles the Provider has). Each key [onEdit]s the query as it is when the key lands, so keys
 * pressed faster than the stick redraws aren't lost. Under the keyboard, completions replace the
 * query in one press and move focus to the first result; before anything is typed they are genre
 * shortcuts. A Title's card opens it ([onOpen]); a Channel's watches it ([onWatch]), and its menu
 * key adds or removes the Favourite. The tabs switch to the other browsing screens ([onTab]). The
 * keyboard takes focus on arrival, unless the screen was reached by picking its tab ([focusTab]).
 */
@Composable
fun SearchScreen(
    library: Library,
    query: String,
    onEdit: ((String) -> String) -> Unit,
    contentLanguages: List<String>,
    appLanguage: String,
    focusTab: Boolean,
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
    if (!focusTab) LaunchedEffect(Unit) { keyboard.requestFocus() }
    FocusTabOnArrival(focusTab, tab)
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
            // Scrolls once focus goes down into the completions, so they all can be reached, and back
            // up once it returns to the keyboard, so the query shows again.
            val column = rememberScrollState()
            Column(Modifier.padding(start = Spacing.screenX).width(290.dp).verticalScroll(column)) {
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
                    onKey = { key -> onEdit { it + key } },
                    onDelete = { onEdit { it.dropLast(1) } },
                    onSpace = { onEdit { if (it.isNotEmpty() && !it.endsWith(" ")) "$it " else it } },
                    modifier = Modifier.focusRequester(keyboard).onFocusChanged { if (it.hasFocus) scope.launch { column.animateScrollTo(0) } },
                )
                Completions(
                    results.completions,
                    onPick = { completion ->
                        onEdit { completion }
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
    val groups = results.groups(query)
    // A new answer shows the results from their top, unless focus is in them.
    val column = rememberLazyListState()
    var inResults by remember { mutableStateOf(false) }
    remember(groups) { if (!inResults) column.requestScrollToItem(0) }
    // The right margin is content padding, so cards scroll under the edge instead of being clipped.
    LazyColumn(
        Modifier.backToTab(tab).onFocusChanged { inResults = it.hasFocus },
        state = column,
        contentPadding = PaddingValues(end = Spacing.screenX, bottom = Spacing.screenY),
    ) {
        itemsIndexed(groups, key = { _, group -> group.key }) { i, group ->
            // The first card of the first group takes focus when a completion is picked.
            val first = Modifier.focusRequester(firstResult).takeIf { i == 0 }
            when (group) {
                is SearchGroup.Movies -> Group(library, contentLanguages, stringResource(R.string.search_movies, group.titles.size), group.titles, onOpen, first)
                is SearchGroup.Shows -> Group(library, contentLanguages, stringResource(R.string.search_shows, group.titles.size), group.titles, onOpen, first)
                is SearchGroup.OfGenre -> Group(library, contentLanguages, group.genre.name, group.genre.titles, onOpen, first)
                is SearchGroup.OfPerson -> Group(library, contentLanguages, group.person.name, group.person.titles, onOpen, first)
                is SearchGroup.Channels -> Column {
                    Text(stringResource(R.string.search_channels, group.channels.size), style = Type.headline, color = Colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    // A wide card grows past the column's edge when focused: the row starts that much
                    // further left, so the card isn't clipped, and its cards still line up with the heading.
                    ResultRow(group.channels, key = { "channel/${it.id}" }, first) { channel, modifier ->
                        ChannelCard(channel, onWatch, onFavourite, modifier)
                    }
                    Spacer(Modifier.height(14.dp))
                }
            }
        }
    }
}

private val SearchGroup.key: String
    get() = when (this) {
        is SearchGroup.Movies -> "movies"
        is SearchGroup.Shows -> "shows"
        is SearchGroup.OfGenre -> "genre/${genre.name}"
        is SearchGroup.Channels -> "channels"
        is SearchGroup.OfPerson -> "person/${person.name}"
    }

/** A headline over a row of cards; [first] modifies the first card. */
@Composable
private fun Group(library: Library, contentLanguages: List<String>, heading: String, titles: List<TitleSummary>, onOpen: (TitleId) -> Unit, first: Modifier?) {
    Column {
        Text(heading, style = Type.headline, color = Colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        // A focused card grows past the column's edge: the row starts that much further left, as
        // Channels' does, so the card isn't clipped, and its cards still line up with the heading.
        ResultRow(titles, key = { it.id.toString() }, first) { title, modifier ->
            TitleCard(
                rememberArtwork(title.image, card = true),
                title.name,
                cardBest(library, title, contentLanguages).cardBadges(),
                onClick = { onOpen(title.id) },
                modifier = modifier,
            )
        }
        Spacer(Modifier.height(14.dp))
    }
}

/**
 * A row of result [items], `m` further left so a focused card isn't clipped; [first] modifies the
 * first card. Search answers again once TMDB's search adds what it knows, reordering the row best
 * first, but a `LazyRow` follows the card that was first: so a new answer shows the row from its
 * start, unless focus is in it, where moving the row under the Viewer would be worse (#169). Focus
 * still on the row's first card (where picking a completion put it) moves to the new first card.
 */
@Composable
internal fun <T> ResultRow(items: List<T>, key: (T) -> Any, first: Modifier?, card: @Composable (T, Modifier) -> Unit) {
    val row = rememberLazyListState()
    // The focused card's place in the row, or null when focus is elsewhere.
    var focused by remember { mutableStateOf<Int?>(null) }
    val firstCard = remember { FocusRequester() }
    val refocus = remember(items) {
        val at = focused
        if (at == null || at == 0) row.requestScrollToItem(0)
        at == 0
    }
    LaunchedEffect(items) {
        if (!refocus) return@LaunchedEffect
        // The new first card is laid out in the frame these items are composed in.
        withFrameNanos {}
        firstCard.requestFocus()
    }
    LazyRow(
        state = row,
        contentPadding = PaddingValues(start = Spacing.m, top = 12.dp, bottom = 10.dp, end = Spacing.screenX),
        horizontalArrangement = Arrangement.spacedBy(Cards.gap),
        modifier = Modifier.offset(x = -Spacing.m).focusRestorer().onFocusChanged { if (!it.hasFocus) focused = null },
    ) {
        itemsIndexed(items, key = { _, item -> key(item) }) { i, item ->
            val modifier = Modifier.onFocusChanged { if (it.isFocused) focused = i }
            card(item, if (i == 0) modifier.focusRequester(firstCard).then(first ?: Modifier) else modifier)
        }
    }
}

private const val TYPING_PAUSE_MS = 150L
