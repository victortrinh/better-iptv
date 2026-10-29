package dev.betteriptv.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import dev.betteriptv.app.ui.components.TitleCard
import dev.betteriptv.app.ui.components.TopTab
import dev.betteriptv.app.ui.components.TopTabs
import dev.betteriptv.app.ui.theme.Cards
import dev.betteriptv.app.ui.theme.Colors
import dev.betteriptv.app.ui.theme.Spacing
import dev.betteriptv.app.ui.theme.Type
import androidx.compose.runtime.rememberCoroutineScope
import dev.betteriptv.core.Channel
import dev.betteriptv.core.HomeRow
import kotlinx.coroutines.launch
import dev.betteriptv.core.Library
import dev.betteriptv.core.TitleId
import dev.betteriptv.core.TitleSummary

/** The browsing screens, in tab order. */
enum class Browse { Home, Movies, Shows, Search, Settings }

/** The tabs across the top of the browsing screens. */
@Composable
fun BrowseTabs(selected: Browse, onSelect: (Browse) -> Unit, modifier: Modifier = Modifier) {
    TopTabs(
        listOf(
            TopTab(stringResource(R.string.home_title)),
            TopTab(stringResource(R.string.movies_title)),
            TopTab(stringResource(R.string.shows_title)),
            TopTab(stringResource(R.string.search_title), Icons.Filled.Search),
            TopTab(stringResource(R.string.settings_title), Icons.Filled.Settings),
        ),
        selected = selected.ordinal,
        onSelect = { onSelect(Browse.entries[it]) },
        modifier = modifier,
    )
}

/**
 * A card on the home screen: the [row] it is in, by the row's key (not its place, which changes as
 * Continue watching and Favourites come and go), and its Title's or Channel's [key].
 */
data class HomeCard(val row: String, val key: String)

/**
 * Where focus goes for [card], a card of the rows [before], once the screen shows [after] (each row's
 * key to its cards' keys, in screen order): the card itself; else the card now in its place in the
 * same row, the one after it or the last; else the first card of the row now where its row was.
 * Null when there are no cards.
 */
fun restoredCard(card: HomeCard, before: Map<String, List<String>>, after: Map<String, List<String>>): HomeCard? {
    val cards = after[card.row]
    if (cards != null && card.key in cards) return card
    if (!cards.isNullOrEmpty()) {
        val at = before[card.row]?.indexOf(card.key) ?: -1
        return HomeCard(card.row, cards[at.coerceIn(0, cards.lastIndex)])
    }
    val rows = after.filterValues { it.isNotEmpty() }.keys.toList()
    if (rows.isEmpty()) return null
    val at = before.keys.indexOf(card.row).coerceIn(0, rows.lastIndex)
    return HomeCard(rows[at], after.getValue(rows[at]).first())
}

/**
 * What the home screen keeps while another screen shows: its scroll position, and the rows read
 * last, which show until they are read again so coming back doesn't blank the screen.
 */
@Stable
class HomeState {
    val list = LazyListState()
    var rows by mutableStateOf<List<HomeRow>?>(null)
}

/**
 * The home screen's rows: Continue watching (stills with how far the Viewer got), the Viewer's
 * Favourites, New on your service, then a row per genre. They are read again whenever the screen
 * shows, so a Title just played moves to the front of Continue watching. [restoreFocus] is the card
 * to focus on arrival, the one the Viewer opened before coming back, or the card now in its place
 * when it has gone; the first card when null.
 * A Title's card opens it ([onOpen]); a Channel's watches it ([onWatch]), and its menu key adds or
 * removes the Favourite.
 */
@Composable
fun HomeScreen(
    library: Library,
    contentLanguages: List<String>,
    appLanguage: String,
    state: HomeState,
    restoreFocus: HomeCard?,
    onTab: (Browse) -> Unit,
    onOpen: (HomeCard, TitleId) -> Unit,
    onWatch: (HomeCard, Channel) -> Unit,
) {
    val status by library.syncStatus.collectAsStateWithLifecycle()
    val synced = status.last
    // The card to focus, until it takes focus: asked for once, so a row composed again as the
    // Viewer scrolls back to it doesn't take focus back. The rows kept from last time show first,
    // so the card is checked again once they are read: the Title opened may have left Continue
    // watching meanwhile.
    var pending by remember(restoreFocus) { mutableStateOf(restoreFocus) }
    var focused by remember(restoreFocus) { mutableStateOf(false) }
    var checked by remember(restoreFocus) { mutableStateOf(false) }
    LaunchedEffect(synced, contentLanguages, appLanguage) {
        val before = state.rows
        state.rows = library.homeRows(contentLanguages, appLanguage)
        if (!checked && restoreFocus != null && before != null) {
            val restored = restoredCard(restoreFocus, before.cards, state.rows.orEmpty().cards)
            if (restored != restoreFocus) pending = restored
        }
        checked = true
    }
    val rows = state.rows
    val focusOn = pending ?: rows?.firstOrNull()?.takeIf { !focused }?.let { HomeCard(it.key, it.keys.first()) }
    val onFocused = {
        pending = null
        focused = true
    }
    val scope = rememberCoroutineScope()
    val onFavourite = { row: HomeRow, channel: Channel, favourite: Boolean ->
        scope.launch {
            library.setFavourite(channel.id, favourite)
            val before = state.rows.orEmpty()
            state.rows = library.homeRows(contentLanguages, appLanguage)
            // Focus goes to its neighbour, or to the row that takes its place.
            if (!favourite) pending = restoredCard(HomeCard(row.key, channelKey(channel)), before.cards, state.rows.orEmpty().cards)
        }
        Unit
    }

    // The margins are content padding, so a focused card grows into them instead of being clipped.
    LazyColumn(
        Modifier.fillMaxSize().background(Colors.ground),
        state = state.list,
        contentPadding = PaddingValues(bottom = Spacing.screenY),
    ) {
        item(key = "tabs") {
            BrowseTabs(Browse.Home, onTab, Modifier.padding(horizontal = Spacing.screenX, vertical = Spacing.screenY))
        }
        if (rows?.isEmpty() == true) {
            item(key = "empty") {
                Text(stringResource(R.string.home_empty), style = Type.body, color = Colors.inkMuted, modifier = Modifier.padding(horizontal = Spacing.screenX))
            }
        }
        rows.orEmpty().forEach { row ->
            item(key = "row-${row.key}", contentType = "row") {
                Text(
                    row.heading(),
                    style = Type.headline,
                    color = Colors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = Spacing.screenX, end = Spacing.screenX, bottom = 12.dp),
                )
                val wide = row is HomeRow.ContinueWatching
                LazyRow(
                    contentPadding = PaddingValues(horizontal = Spacing.screenX, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(Cards.gap),
                    modifier = Modifier.padding(bottom = 14.dp),
                ) {
                    if (row is HomeRow.Favourites) itemsIndexed(row.channels, key = { _, channel -> channelKey(channel) }) { _, channel ->
                        val card = HomeCard(row.key, channelKey(channel))
                        val focus = remember { FocusRequester() }
                        if (card == focusOn) LaunchedEffect(Unit) {
                            focus.requestFocus()
                            onFocused()
                        }
                        ChannelCard(
                            channel,
                            onWatch = { onWatch(card, it) },
                            onFavourite = { it, favourite -> onFavourite(row, it, favourite) },
                            modifier = Modifier.focusRequester(focus),
                        )
                    }
                    itemsIndexed(row.titles, key = { _, title -> title.id.toString() }) { index, title ->
                        val card = HomeCard(row.key, title.id.toString())
                        val focus = remember { FocusRequester() }
                        if (card == focusOn) LaunchedEffect(Unit) {
                            focus.requestFocus()
                            onFocused()
                        }
                        val resume = (row as? HomeRow.ContinueWatching)?.titles?.get(index)
                        TitleCard(
                            image = rememberArtwork(if (wide) resume?.still ?: title.image else title.image, card = true),
                            name = title.name,
                            badges = cardBest(library, title, contentLanguages).cardBadges(),
                            onClick = { onOpen(card, title.id) },
                            modifier = Modifier.focusRequester(focus),
                            wide = wide,
                            progress = resume?.progress,
                        )
                    }
                }
            }
        }
    }
}

private val HomeRow.titles: List<TitleSummary>
    get() = when (this) {
        is HomeRow.ContinueWatching -> titles.map { it.title }
        is HomeRow.Favourites -> emptyList()
        is HomeRow.NewOnYourService -> titles
        is HomeRow.Genre -> titles
    }

/** A row's key, as [HomeCard]s hold it: the same row keeps it when rows before it come or go. */
private val HomeRow.key: String
    get() = when (this) {
        is HomeRow.ContinueWatching -> "continue"
        is HomeRow.Favourites -> "favourites"
        is HomeRow.NewOnYourService -> "new"
        is HomeRow.Genre -> "genre/$name"
    }

/** Each row's key to its cards' keys, in screen order, for [restoredCard]. */
private val List<HomeRow>.cards: Map<String, List<String>>
    get() = associate { it.key to it.keys }

/** The keys of a row's cards, as [HomeCard]s hold them. */
private val HomeRow.keys: List<String>
    get() = if (this is HomeRow.Favourites) channels.map(::channelKey) else titles.map { it.id.toString() }

private fun channelKey(channel: Channel) = "channel/${channel.id}"

@Composable
private fun HomeRow.heading(): String = when (this) {
    is HomeRow.ContinueWatching -> stringResource(R.string.row_continue_watching)
    is HomeRow.Favourites -> stringResource(R.string.row_favourites)
    is HomeRow.NewOnYourService -> stringResource(R.string.row_new)
    is HomeRow.Genre -> name
}
