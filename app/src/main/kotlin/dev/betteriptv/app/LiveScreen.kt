package dev.betteriptv.app

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.betteriptv.app.ui.theme.Spacing
import dev.betteriptv.core.Channel
import dev.betteriptv.core.Library
import dev.betteriptv.core.LiveCountry
import dev.betteriptv.core.SyncStatus

/**
 * The Live tab, the sports hub's home (M3), in rows like Home's ([RowsScreen]): the Viewer's
 * Favourites as Channel cards, then the Channel browser's countries (the Channels row, the Viewer's
 * [region] first, then their Content languages' countries) as country cards named in the
 * [appLanguage], until the hub's rows
 * (Live now, Today, a row per League, More live events, Replays) take their places before them. OK
 * on a Channel's card watches it ([onWatch]), and the menu key adds or removes the Favourite, focus
 * moving to its neighbour; OK on a country opens its Channels screen ([onOpenCountry]).
 * [restoreFocus] and [focusTab] are as on [RowsScreen].
 */
@Composable
fun LiveScreen(
    library: Library,
    contentLanguages: List<String>,
    appLanguage: String,
    region: String,
    state: RowsState,
    restoreFocus: RowCard?,
    focusTab: Boolean,
    onTab: (Browse) -> Unit,
    onWatch: (RowCard, Channel) -> Unit,
    onOpenCountry: (RowCard, LiveCountry) -> Unit,
) {
    val status by library.syncStatus.collectAsStateWithLifecycle()
    // With no catalog stored yet, a first Sync's progress (or why it failed) shows where the rows will be, as on Home.
    val firstSync = status.last == null && (status is SyncStatus.Running || status is SyncStatus.Matching || status is SyncStatus.Failed)
    RowsScreen(
        library, contentLanguages, appLanguage, state, restoreFocus, focusTab,
        reading = listOf(region, contentLanguages),
        read = { library.liveRows(region, contentLanguages) },
        // Before a Sync there are no Channels to save; after one, the Viewer saves them from Search.
        empty = stringResource(if (status.last == null) R.string.home_empty else R.string.live_empty),
        onOpen = { _, _ -> },
        onWatch = onWatch,
        onOpenCountry = onOpenCountry,
        placeholder = if (firstSync) {
            { FirstSync(status) }
        } else null,
    ) { tab ->
        BrowseTabs(Browse.Live, onTab, Modifier.padding(start = Spacing.screenX, end = Spacing.screenX, top = Spacing.screenY, bottom = 16.dp), selectedFocus = tab)
    }
}
