package dev.betteriptv.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
            stringResource(R.string.home_title), stringResource(R.string.movies_title), stringResource(R.string.shows_title), stringResource(R.string.search_title),
            stringResource(R.string.settings_title),
        ),
        selected = selected.ordinal,
        onSelect = { onSelect(Browse.entries[it]) },
        modifier = modifier,
    )
}

/** A card on the home screen: which row it is in, and its Title's or Channel's [key]. */
data class HomeCard(val row: Int, val key: String)

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
 * to focus on arrival, the one the Viewer opened before coming back; the first card when null.
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
    LaunchedEffect(synced, contentLanguages, appLanguage) { state.rows = library.homeRows(contentLanguages, appLanguage) }
    val rows = state.rows
    // Where focus goes once a Favourite removed takes its card away.
    var afterRemoval by remember { mutableStateOf<HomeCard?>(null) }
    val focusOn = afterRemoval ?: restoreFocus ?: rows?.firstOrNull()?.let { HomeCard(0, it.keys.first()) }
    val scope = rememberCoroutineScope()
    val onFavourite = { rowIndex: Int, channels: List<Channel>, channel: Channel, favourite: Boolean ->
        scope.launch {
            library.setFavourite(channel.id, favourite)
            if (!favourite) {
                val at = channels.indexOf(channel)
                val neighbour = channels.getOrNull(at + 1) ?: channels.getOrNull(at - 1)
                // Without a neighbour the row goes, and focus with it to the row that takes its place.
                afterRemoval = HomeCard(rowIndex, neighbour?.let(::channelKey) ?: "")
            }
            state.rows = library.homeRows(contentLanguages, appLanguage)
            if (afterRemoval?.key == "") afterRemoval = state.rows?.getOrNull(rowIndex)?.let { HomeCard(rowIndex, it.keys.first()) }
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
        rows.orEmpty().forEachIndexed { rowIndex, row ->
            item(key = "row-$rowIndex", contentType = "row") {
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
                        val card = HomeCard(rowIndex, channelKey(channel))
                        val focus = remember { FocusRequester() }
                        if (card == focusOn) LaunchedEffect(Unit) { focus.requestFocus() }
                        ChannelCard(
                            channel,
                            onWatch = { onWatch(card, it) },
                            onFavourite = { it, favourite -> onFavourite(rowIndex, row.channels, it, favourite) },
                            modifier = Modifier.focusRequester(focus),
                        )
                    }
                    itemsIndexed(row.titles, key = { _, title -> title.id.toString() }) { index, title ->
                        val card = HomeCard(rowIndex, title.id.toString())
                        val focus = remember { FocusRequester() }
                        if (card == focusOn) LaunchedEffect(Unit) { focus.requestFocus() }
                        val resume = (row as? HomeRow.ContinueWatching)?.titles?.get(index)
                        TitleCard(
                            image = rememberArtwork(if (wide) resume?.still ?: title.image else title.image, card = true),
                            name = title.name,
                            badges = title.best.cardBadges(),
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
