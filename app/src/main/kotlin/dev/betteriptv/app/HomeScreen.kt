package dev.betteriptv.app

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.betteriptv.app.ui.components.TopTab
import dev.betteriptv.app.ui.components.TopTabs
import dev.betteriptv.app.ui.theme.Spacing
import dev.betteriptv.core.Channel
import dev.betteriptv.core.GameFeeds
import dev.betteriptv.core.League
import dev.betteriptv.core.Library
import dev.betteriptv.core.SyncStatus
import dev.betteriptv.core.SyncStep
import dev.betteriptv.core.TitleId

/** The browsing screens, in tab order. */
enum class Browse { Home, Movies, Shows, Live, Search, Settings }

/**
 * The tabs across the top of the browsing screens; [selectedFocus] focuses the [selected] one. With
 * [library], the pill at their right end says while a Sync or Matching may still change the rows.
 */
@Composable
fun BrowseTabs(selected: Browse, onSelect: (Browse) -> Unit, modifier: Modifier = Modifier, selectedFocus: FocusRequester? = null, library: Library? = null) {
    TopTabs(
        listOf(
            TopTab(stringResource(R.string.home_title)),
            TopTab(stringResource(R.string.movies_title)),
            TopTab(stringResource(R.string.shows_title)),
            TopTab(stringResource(R.string.live_title)),
            TopTab(stringResource(R.string.search_title), Icons.Filled.Search),
            TopTab(stringResource(R.string.settings_title), Icons.Filled.Settings),
        ),
        selected = selected.ordinal,
        onSelect = { onSelect(Browse.entries[it]) },
        modifier = modifier,
        selectedFocus = selectedFocus,
    ) {
        if (library != null) CatalogActivityPill(library, Modifier.align(Alignment.CenterEnd))
    }
}

/**
 * The home screen's rows ([RowsScreen]): Continue watching (stills with how far the Viewer got),
 * Live now (the Games of the [leagues] on that are in progress with a Feed, as the Live tab's Game
 * cards; no row otherwise), the Top 10 movies and shows in the Viewer's [region], New on your
 * service, Trending this week, the Viewer's Favourites, then a row per genre. The Games are asked
 * for when Home opens and again every [GAMES_REFRESH] while Live now is on screen, as on Live, but
 * never hold the other rows: the kept Games show first, and the rows are read again once fresh ones
 * land ([Library.catalogChanges]). [restoreFocus] and [focusTab] are as on [RowsScreen]. A Title's card opens it ([onOpen]); a Game's
 * opens its page ([onOpenGame]); a Channel's watches it ([onWatch]), and its menu key adds or
 * removes the Favourite.
 */
@Composable
fun HomeScreen(
    library: Library,
    contentLanguages: List<String>,
    appLanguage: String,
    region: String,
    leagues: List<League>,
    state: RowsState,
    restoreFocus: RowCard?,
    focusTab: Boolean,
    onTab: (Browse) -> Unit,
    onOpen: (RowCard, TitleId) -> Unit,
    onWatch: (RowCard, Channel) -> Unit,
    onOpenGame: (RowCard, GameFeeds) -> Unit,
    onRetry: () -> Unit,
) {
    val status by library.syncStatus.collectAsStateWithLifecycle()
    // Until a first Sync lands the Channels, its checklist shows where the rows will be, as on Live, Movies and Shows.
    val noChannels = status.channelsAt == null
    // Once they have, the Title rows' places wait under the rows, captioned, until the Titles land; a failure then shows on Movies and Shows.
    val titlesComing = !noChannels && status.last == null && status !is SyncStatus.Failed
    val refreshes = gamesRefreshes(state)
    // Bumped when fresh Games land behind a read (and when a Sync lands a new catalog): the rows are read again.
    val catalog by library.catalogChanges.collectAsStateWithLifecycle()
    // Rows never move under focus: while a Favourite has it, the one row under where the Title rows land, a
    // catalog that lands waits to be read until focus leaves the row or Home is entered again.
    val focusedRow = state.focused?.row
    var read by remember { mutableIntStateOf(catalog) }
    LaunchedEffect(catalog, focusedRow) { if (focusedRow != FAVOURITES_ROW) read = catalog }
    val caption = stringResource(R.string.home_titles_pending)
    RowsScreen(
        library, contentLanguages, appLanguage, state, restoreFocus, focusTab,
        reading = listOf(GamesReading(leagues, region, contentLanguages, refreshes), appLanguage, read),
        read = { library.homeRows(contentLanguages, appLanguage, region, leagues) },
        empty = stringResource(R.string.home_empty),
        onOpen = onOpen,
        onWatch = onWatch,
        onOpenGame = onOpenGame,
        placeholder = if (noChannels) {
            { SyncChecklist(status, SyncStep.Channels, onRetry) }
        } else null,
        trailing = if (titlesComing) {
            { PlaceholderRows(caption) }
        } else null,
    ) { tab ->
        BrowseTabs(Browse.Home, onTab, Modifier.padding(start = Spacing.screenX, end = Spacing.screenX, top = Spacing.screenY, bottom = 16.dp), selectedFocus = tab, library = library)
    }
}
