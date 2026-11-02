package dev.betteriptv.app

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.betteriptv.app.ui.theme.Spacing
import dev.betteriptv.core.Channel
import dev.betteriptv.core.GameFeeds
import dev.betteriptv.core.HomeRow
import dev.betteriptv.core.League
import dev.betteriptv.core.Library
import dev.betteriptv.core.SyncStatus
import kotlinx.coroutines.delay
import java.time.Duration

/**
 * The Live tab, the sports hub's home (M3), in rows like Home's ([RowsScreen]): Live now, Today and
 * a row per League on ([leagues], in Settings' order) as Game cards, then the Viewer's Favourites as
 * Channel cards, until the hub's other rows (More live events, Replays, the Channels' countries)
 * join them. The Games are asked for when the tab opens and again every [GAMES_REFRESH] while a
 * Games row is on screen, the kept copy standing when the cloud fails. OK on a Game opens its page
 * ([onOpenGame]); OK on a Channel watches it ([onWatch]), and the menu key adds or removes the
 * Favourite, focus moving to its neighbour. [restoreFocus] and [focusTab] are as on [RowsScreen].
 */
@Composable
fun LiveScreen(
    library: Library,
    contentLanguages: List<String>,
    leagues: List<League>,
    state: RowsState,
    restoreFocus: RowCard?,
    focusTab: Boolean,
    onTab: (Browse) -> Unit,
    onWatch: (RowCard, Channel) -> Unit,
    onOpenGame: (RowCard, GameFeeds) -> Unit,
) {
    val status by library.syncStatus.collectAsStateWithLifecycle()
    // With no catalog stored yet, a first Sync's progress (or why it failed) shows where the rows will be, as on Home.
    val firstSync = status.last == null && (status is SyncStatus.Running || status is SyncStatus.Matching || status is SyncStatus.Failed)
    // Bumped every couple of minutes while a Games row shows, so the rows are read (and the cloud asked) again.
    var refreshes by remember { mutableIntStateOf(0) }
    val gamesShown by remember(state) { derivedStateOf { state.rows?.any { it.hasGames } == true } }
    LaunchedEffect(gamesShown) {
        if (!gamesShown) return@LaunchedEffect
        while (true) {
            delay(GAMES_REFRESH.toMillis())
            refreshes++
        }
    }
    RowsScreen(
        library, contentLanguages, state, restoreFocus, focusTab,
        reading = GamesReading(leagues, refreshes),
        read = { library.liveRows(leagues, contentLanguages) },
        // Before a Sync there are no Channels to save; after one, the Viewer saves them from Search.
        empty = stringResource(if (status.last == null) R.string.home_empty else R.string.live_empty),
        onOpen = { _, _ -> },
        onWatch = onWatch,
        onOpenGame = onOpenGame,
        placeholder = if (firstSync) {
            { FirstSync(status) }
        } else null,
    ) { tab ->
        BrowseTabs(Browse.Live, onTab, Modifier.padding(start = Spacing.screenX, end = Spacing.screenX, top = Spacing.screenY, bottom = 16.dp), selectedFocus = tab)
    }
}

/** What the Live rows are read for: the Leagues on, and which refresh this is, so a change of either reads them again. */
private data class GamesReading(val leagues: List<League>, val refresh: Int)

/** Whether a row holds Games, whose schedule the tab keeps asking for while they show. */
private val HomeRow.hasGames: Boolean get() = this is HomeRow.LiveNow || this is HomeRow.Today || this is HomeRow.LeagueGames

/** How often the Games are asked for again while a Games row shows (docs/roadmap.md, M3): the cloud reads a League's day every 5 minutes. */
internal val GAMES_REFRESH: Duration = Duration.ofMinutes(2)
