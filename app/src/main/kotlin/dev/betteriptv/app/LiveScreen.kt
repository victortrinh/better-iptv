package dev.betteriptv.app

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.betteriptv.app.ui.components.rememberHasMenuKey
import dev.betteriptv.app.ui.theme.Spacing
import dev.betteriptv.core.Channel
import dev.betteriptv.core.GameFeeds
import dev.betteriptv.core.League
import dev.betteriptv.core.LeagueReplay
import dev.betteriptv.core.Library
import dev.betteriptv.core.LiveCountry
import dev.betteriptv.core.LiveTab

/**
 * The Live tab, the sports hub's home (M3), in rows like Home's ([RowsScreen]): Live now, Today and
 * a row per League on ([leagues], in Settings' order) as Game cards, then More live events (the
 * Event channels no Game matched, on now or soon) and the Viewer's Favourites as Channel cards, then
 * the Channel browser's countries (the Channels row, the Viewer's [region] first, then their
 * Content languages' countries) as country cards named in the [appLanguage]; between More live
 * events and Favourites, Replays, the Leagues' Sports replays as Game cards, which OK plays
 * ([onPlayReplay]). The screen works out none of them: it asks the Core for the Live tab ([LiveTab],
 * [liveContent]) and draws the Rows it is handed, again each time the Core hands it new ones (a
 * catalog landing, a Profile switch, Games starting and ending while a Games row or More live events
 * shows). OK on a Game opens its page ([onOpenGame]); OK on a Channel watches it
 * ([onWatch]), and the menu key adds or removes the Favourite, focus moving to its neighbour; OK on
 * a country opens its Channels screen ([onOpenCountry]). [restoreFocus] and [focusTab] are as on [RowsScreen].
 */
@Composable
fun LiveScreen(
    library: Library,
    contentLanguages: List<String>,
    appLanguage: String,
    region: String,
    leagues: List<League>,
    state: RowsState,
    restoreFocus: RowCard?,
    focusTab: Boolean,
    onTab: (Browse) -> Unit,
    onWatch: (RowCard, Channel) -> Unit,
    onOpenGame: (RowCard, GameFeeds) -> Unit,
    onOpenCountry: (RowCard, LiveCountry) -> Unit,
    onPlayReplay: (RowCard, LeagueReplay) -> Unit = { _, _ -> },
    onRetry: () -> Unit = {},
) {
    val status by library.syncStatus.collectAsStateWithLifecycle()
    // Until a first Sync lands the Channels, its checklist shows where the rows will be, as on Home.
    val noChannels = showsChecklist(status)
    // The tab's content, from the Core: read again whenever the Core hands over new content.
    val live = remember(leagues, region, contentLanguages) { LiveTab(leagues, region, contentLanguages) }
    val content = liveContent(library, live)
    RowsScreen(
        library, contentLanguages, appLanguage, state, restoreFocus, focusTab,
        reading = listOf(live, content.changes),
        read = { content.read().rows },
        // Before a Sync there are no Channels to save; after one, the Viewer saves them from Search.
        empty = stringResource(if (noChannels) R.string.home_empty else if (rememberHasMenuKey()) R.string.live_empty else R.string.live_empty_hold),
        onOpen = { _, _ -> },
        onWatch = onWatch,
        onOpenGame = onOpenGame,
        onOpenCountry = onOpenCountry,
        onPlayReplay = onPlayReplay,
        placeholder = if (noChannels) {
            { SyncChecklist(status, onRetry) }
        } else null,
    ) { tab ->
        BrowseTabs(Browse.Live, onTab, Modifier.padding(start = Spacing.screenX, end = Spacing.screenX, top = Spacing.screenY, bottom = 16.dp), selectedFocus = tab, library = library)
    }
}
