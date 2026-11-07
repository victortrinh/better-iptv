package dev.betteriptv.app

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import dev.betteriptv.app.ui.components.Completions
import dev.betteriptv.app.ui.components.KEYBOARD_WIDTH
import dev.betteriptv.app.ui.components.RemoteKeyboard
import dev.betteriptv.app.ui.components.TitleCard
import dev.betteriptv.app.ui.theme.Cards
import dev.betteriptv.app.ui.theme.Colors
import dev.betteriptv.app.ui.theme.Spacing
import dev.betteriptv.app.ui.theme.Type
import dev.betteriptv.core.Channel
import dev.betteriptv.core.Library
import dev.betteriptv.core.Replay
import dev.betteriptv.core.SearchCard
import dev.betteriptv.core.SearchResults
import dev.betteriptv.core.TitleId
import dev.betteriptv.core.TitleSummary
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Search, laid out as Netflix's: the [query] typed on a D-pad keyboard on the left, completions under
 * it, and to their right the query over one grid of cards that fills in as each letter lands, with no
 * row headings, nor names under the cards: the Titles found by name, best first whatever their kind,
 * then the genres' Titles, the Channels, the Sports replays and the people's Titles ([SearchResults.cards]). Each key
 * [onEdit]s the query as it is when the key lands, so keys pressed faster than the stick redraws
 * aren't lost. Under the keyboard, completions replace the query in one press and move focus to the
 * first card; before anything is typed they are genre shortcuts. A Title's card opens it ([onOpen]);
 * a Channel's watches it ([onWatch]), and its menu key adds or removes the Favourite; a Sports
 * replay's plays it ([onPlayReplay]). The tabs switch to the other browsing screens ([onTab]). The
 * keyboard takes focus on arrival, unless the screen was reached by picking its tab ([focusTab]).
 */
@Composable
fun SearchScreen(
    library: Library,
    query: String,
    onEdit: ((String) -> String) -> Unit,
    contentLanguages: List<String>,
    appLanguage: String,
    region: String,
    focusTab: Boolean,
    onTab: (Browse) -> Unit,
    onOpen: (TitleId) -> Unit,
    onWatch: (Channel) -> Unit,
    onPlayReplay: (Replay) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    // Bumped when a Sync lands (its Channels first), a held catalog is applied or Matches regroup the Titles.
    val catalog by library.catalogChanges.collectAsStateWithLifecycle()
    var results by remember { mutableStateOf(SearchResults.NONE) }
    // The query [results] answer: the one being typed until the index, if still being built, has them.
    var answered by remember { mutableStateOf("") }
    // Finishes the index at the Viewer's priority, if its build in the background hasn't, while they reach for the first letters.
    LaunchedEffect(catalog) { library.prepareSearch() }
    LaunchedEffect(query, catalog, contentLanguages, appLanguage, region) {
        // Letters typed in quick succession make one search.
        delay(TYPING_PAUSE_MS)
        // The device's own matches, then again with what TMDB's search adds.
        library.search(query, contentLanguages, appLanguage, region).collect {
            results = it
            answered = query
        }
    }
    val keyboard = remember { FocusRequester() }
    val tab = remember { FocusRequester() }
    val grid = remember { FocusRequester() }
    if (!focusTab) LaunchedEffect(Unit) { keyboard.requestFocus() }
    FocusTabOnArrival(focusTab, tab)
    // A completion was picked: focus goes to the first card once its query is answered.
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
        BrowseTabs(Browse.Search, { if (it != Browse.Search) onTab(it) }, Modifier.padding(horizontal = Spacing.screenX), selectedFocus = tab, library = library)
        Spacer(Modifier.height(24.dp))
        Row(Modifier.fillMaxSize()) {
            // Scrolls once focus goes down into the completions, so they all can be reached, and back
            // up once it returns to the keyboard, so the whole keyboard shows again.
            val column = rememberScrollState()
            Column(
                Modifier.padding(start = Spacing.screenX).width(KEYBOARD_WIDTH).verticalScroll(column)
                    // Right from Space or Delete, above the first row of cards, would reach the tabs
                    // before the grid: Right out of the column goes into the grid, to its last focused card.
                    .focusProperties { onExit = { if (requestedFocusDirection == FocusDirection.Right && !results.isEmpty) grid.requestFocus() } }
                    .focusGroup(),
            ) {
                RemoteKeyboard(
                    onKey = { key -> onEdit { it + key } },
                    onDelete = { onEdit { it.dropLast(1) } },
                    onSpace = { onEdit { if (it.isNotEmpty() && !it.endsWith(" ")) "$it " else it } },
                    modifier = Modifier.onFocusChanged { if (it.hasFocus) scope.launch { column.animateScrollTo(0) } },
                    entry = keyboard,
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
            Spacer(Modifier.width(COLUMN_GAP))
            Column(Modifier.weight(1f)) {
                Query(query)
                Results(query, results, searching = answered != query, tab, grid, firstResult, onOpen, onWatch, onPlayReplay) { channel, favourite ->
                    scope.launch {
                        library.setFavourite(channel.key, favourite)
                        results = results.copy(channels = results.channels.map { if (it.key == channel.key) it.copy(favourite = favourite) else it })
                    }
                }
            }
        }
    }
}

/**
 * The query over the cards, as on Netflix: the magnifier, then what is typed with the caret after
 * it, or, before anything is, what to type in its place, muted, with the hint under it.
 */
@Composable
private fun Query(query: String) {
    Row(Modifier.height(QUERY_HEIGHT).padding(end = Spacing.screenX), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.Search, contentDescription = null, tint = Colors.ink, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(Spacing.m))
        if (query.isEmpty()) {
            Text(stringResource(R.string.search_start), style = Type.title, color = Colors.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        } else {
            Text(query, style = Type.title, color = Colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Box(Modifier.padding(start = 2.dp).width(2.dp).height(18.dp).background(Colors.focus))
        }
    }
    if (query.isEmpty()) {
        Text(
            stringResource(R.string.search_hint),
            style = Type.caption,
            color = Colors.inkMuted,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = Spacing.s, end = Spacing.screenX),
        )
    }
}

@Composable
private fun Results(
    query: String,
    results: SearchResults,
    searching: Boolean,
    tab: FocusRequester,
    grid: FocusRequester,
    firstResult: FocusRequester,
    onOpen: (TitleId) -> Unit,
    onWatch: (Channel) -> Unit,
    onPlayReplay: (Replay) -> Unit,
    onFavourite: (Channel, Boolean) -> Unit,
) {
    if (results.isEmpty) {
        // Before anything is typed, the query line says what to.
        if (query.isBlank()) return
        val message = if (searching) stringResource(R.string.search_searching) else stringResource(R.string.search_none, query.trim())
        Text(message, style = Type.body, color = Colors.inkMuted, modifier = Modifier.padding(top = GRID_TOP))
        return
    }
    val cards = remember(results, query) { results.cards(query) }
    // Focusing [grid] enters it at the card that had focus; Back focuses the tab.
    ResultGrid(cards, wide = { it !is SearchCard.OfTitle }, keyOf = { it.key }, first = firstResult, modifier = Modifier.focusRequester(grid).backKeyToTab(tab)) { card, modifier ->
        when (card) {
            is SearchCard.OfTitle -> ResultTitle(card.title, onOpen, modifier)
            is SearchCard.OfChannel -> ChannelCard(card.channel, onWatch, onFavourite, modifier, named = false)
            is SearchCard.OfReplay -> ReplayCard(card.replay, onPlayReplay, modifier)
        }
    }
}

private val SearchCard.key: String
    get() = when (this) {
        is SearchCard.OfTitle -> title.id.toString()
        is SearchCard.OfChannel -> "channel/${channel.key}"
        is SearchCard.OfReplay -> "replay/${replay.listingId}"
    }

/**
 * A Title found: its poster alone, as on Netflix, with no name or badges under it when focused:
 * the art names it, and the details tell the rest. In the grid it doesn't grow into its backdrop
 * (#216): a cell can't widen.
 */
@Composable
private fun ResultTitle(title: TitleSummary, onOpen: (TitleId) -> Unit, modifier: Modifier) {
    TitleCard(rememberArtwork(title.image, card = true), title.name, emptyList(), onClick = { onOpen(title.id) }, modifier = modifier, named = false)
}

/**
 * Result [cards] as one grid: rows of [POSTER_COLUMNS] posters, or of [WIDE_COLUMNS] [wide] cards, in
 * a `LazyColumn`, so each run of one shape fills its rows. [keyOf] names a card; [first] goes on the
 * first card, for a picked completion. Search answers again once TMDB's search adds what it knows,
 * reordering the cards best first: so a new answer shows the grid from its top, unless focus is in
 * it, where moving the cards under the Viewer would be worse (#169). Focus still on the first card,
 * where picking a completion put it, moves to the new first card.
 */
@Composable
internal fun <T> ResultGrid(
    cards: List<T>,
    wide: (T) -> Boolean,
    keyOf: (T) -> Any,
    first: FocusRequester,
    modifier: Modifier = Modifier,
    card: @Composable (T, Modifier) -> Unit,
) {
    val rows = remember(cards) { gridRows(cards, wide) }
    val list = rememberLazyListState()
    // The focused card's place among the cards, or null when focus is elsewhere.
    var focused by remember { mutableStateOf<Int?>(null) }
    val refocus = remember(cards) {
        val at = focused
        if (at == null || at == 0) list.requestScrollToItem(0)
        at == 0
    }
    LaunchedEffect(cards) {
        if (!refocus || cards.isEmpty()) return@LaunchedEffect
        // The new first card is laid out in the frame these cards are composed in.
        withFrameNanos {}
        first.requestFocus()
    }
    // The gap under the query line and the margins are content padding: a list clips along its
    // scroll axis, so a focused card on the first or last row, lifted 12 dp, would be cut off at
    // the list's edge otherwise, and cards scroll under the bottom margin rather than stopping at it.
    LazyColumn(
        state = list,
        contentPadding = PaddingValues(top = GRID_TOP, end = Spacing.screenX, bottom = Spacing.screenY),
        verticalArrangement = Arrangement.spacedBy(ROW_GAP),
        modifier = modifier.focusRestorer().onFocusChanged { if (!it.hasFocus) focused = null },
    ) {
        items(rows, key = { row -> keyOf(cards[row.first]) }) { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(Cards.gap)) {
                for (i in row) {
                    key(keyOf(cards[i])) {
                        val at = Modifier.onFocusChanged { if (it.isFocused) focused = i }
                        card(cards[i], if (i == 0) at.focusRequester(first) else at)
                    }
                }
            }
        }
    }
}

/** The grid's rows, as ranges of [cards]: runs of one shape, [POSTER_COLUMNS] posters or [WIDE_COLUMNS] [wide] cards to a row. */
private fun <T> gridRows(cards: List<T>, wide: (T) -> Boolean): List<IntRange> = buildList {
    var start = 0
    while (start < cards.size) {
        val shape = wide(cards[start])
        val columns = if (shape) WIDE_COLUMNS else POSTER_COLUMNS
        var end = start + 1
        while (end < cards.size && end - start < columns && wide(cards[end]) == shape) end++
        add(start until end)
        start = end
    }
}

/** Between the keyboard's column and the cards. */
private val COLUMN_GAP = Spacing.xxl

/** The query line's height: the magnifier and the caret centre in it. */
private val QUERY_HEIGHT = 32.dp

/** Between the query line and the cards, or the message in their place: more than a focused card's 12 dp lift. */
private val GRID_TOP = Spacing.l

/**
 * Posters to a row: beside the 200 dp keyboard column, 960 − 2 × 48 − 200 − 32 = 632 dp are left,
 * and four posters take 4 × 136 + 3 × 20 = 604 of them. Wide cards (Channels, Replays) go three to
 * a row: 3 × 196 + 2 × 20 = 628.
 */
private const val POSTER_COLUMNS = 4
private const val WIDE_COLUMNS = 3

/** Between the grid's rows, as between its columns: a focused card's growth doesn't touch its neighbours. */
private val ROW_GAP = Cards.gap

private const val TYPING_PAUSE_MS = 150L
