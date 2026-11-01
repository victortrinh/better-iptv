package dev.betteriptv.app

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.betteriptv.app.ui.theme.Spacing
import dev.betteriptv.core.Library
import dev.betteriptv.core.SyncStatus
import dev.betteriptv.core.TitleId
import dev.betteriptv.core.TitleKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What a Titles tab keeps while another screen shows: its [rows] (scroll positions and the rows
 * read last), and how many Titles it counted last, kept until it is read again.
 */
@Stable
class TitlesState {
    val rows = RowsState()
    var count by mutableStateOf(0)
}

/**
 * The Movies or Shows tab ([kind]), in rows like Home's ([RowsScreen]): the kind's Top 10 in the
 * Viewer's [region], its Trending this week, then New movies (or shows). Sync now and how the Sync
 * is going are in Settings; only a first Sync's progress, or why it failed, shows here, while
 * there are no Titles yet. [restoreFocus] is the card to focus on coming back, the one the
 * Viewer opened. [focusTab]: reached by picking its tab, which keeps focus.
 */
@Composable
fun TitlesScreen(
    library: Library,
    kind: TitleKind,
    contentLanguages: List<String>,
    appLanguage: String,
    region: String,
    state: TitlesState,
    restoreFocus: RowCard?,
    focusTab: Boolean,
    onTab: (Browse) -> Unit,
    onOpen: (RowCard, TitleId) -> Unit,
) {
    val status by library.syncStatus.collectAsStateWithLifecycle()
    val synced = status.last
    LaunchedEffect(synced, kind) { state.count = withContext(Dispatchers.IO) { library.titleCount(kind) } }
    val count = state.count
    // A first Sync leaves the tab empty for minutes: how far it is shows where the rows will be ([FirstSync]).
    val firstSync = count == 0 && (status is SyncStatus.Running || status is SyncStatus.Matching || status is SyncStatus.Failed)
    RowsScreen(
        library, contentLanguages, state.rows, restoreFocus, focusTab,
        reading = listOf(kind, contentLanguages, appLanguage, region),
        read = { library.tabRows(kind, contentLanguages, appLanguage, region) },
        empty = stringResource(R.string.home_empty),
        onOpen = onOpen,
        placeholder = if (firstSync) {
            { FirstSync(status) }
        } else null,
    ) { tab ->
        BrowseTabs(
            if (kind == TitleKind.Movie) Browse.Movies else Browse.Shows, onTab,
            Modifier.padding(start = Spacing.screenX, end = Spacing.screenX, top = Spacing.screenY, bottom = 16.dp),
            selectedFocus = tab,
        )
    }
}
