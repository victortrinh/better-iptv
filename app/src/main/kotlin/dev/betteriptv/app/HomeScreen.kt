package dev.betteriptv.app

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.betteriptv.app.ui.components.TopTab
import dev.betteriptv.app.ui.components.TopTabs
import dev.betteriptv.app.ui.theme.Spacing
import dev.betteriptv.core.Channel
import dev.betteriptv.core.Library
import dev.betteriptv.core.SyncStatus
import dev.betteriptv.core.TitleId

/** The browsing screens, in tab order. */
enum class Browse { Home, Movies, Shows, Live, Search, Settings }

/** The tabs across the top of the browsing screens; [selectedFocus] focuses the [selected] one. */
@Composable
fun BrowseTabs(selected: Browse, onSelect: (Browse) -> Unit, modifier: Modifier = Modifier, selectedFocus: FocusRequester? = null) {
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
    )
}

/**
 * The home screen's rows ([RowsScreen]): Continue watching (stills with how far the Viewer got), the
 * Top 10 movies and shows in the Viewer's [region], New on your service, Trending this week, the
 * Viewer's Favourites, then a row per genre. [restoreFocus] and [focusTab] are as on [RowsScreen].
 * A Title's card opens it ([onOpen]); a Channel's watches it ([onWatch]), and its menu key adds or
 * removes the Favourite.
 */
@Composable
fun HomeScreen(
    library: Library,
    contentLanguages: List<String>,
    appLanguage: String,
    region: String,
    state: RowsState,
    restoreFocus: RowCard?,
    focusTab: Boolean,
    onTab: (Browse) -> Unit,
    onOpen: (RowCard, TitleId) -> Unit,
    onWatch: (RowCard, Channel) -> Unit,
) {
    val status by library.syncStatus.collectAsStateWithLifecycle()
    // With no catalog stored yet, a first Sync's progress (or why it failed) shows where the rows will be, as on Movies and Shows.
    val firstSync = status.last == null && (status is SyncStatus.Running || status is SyncStatus.Matching || status is SyncStatus.Failed)
    RowsScreen(
        library, contentLanguages, state, restoreFocus, focusTab,
        reading = listOf(contentLanguages, appLanguage, region),
        read = { library.homeRows(contentLanguages, appLanguage, region) },
        empty = stringResource(R.string.home_empty),
        onOpen = onOpen,
        onWatch = onWatch,
        placeholder = if (firstSync) {
            { FirstSync(status) }
        } else null,
    ) { tab ->
        BrowseTabs(Browse.Home, onTab, Modifier.padding(start = Spacing.screenX, end = Spacing.screenX, top = Spacing.screenY, bottom = 16.dp), selectedFocus = tab)
    }
}
