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
import dev.betteriptv.core.LeagueReplay
import dev.betteriptv.core.Library
import dev.betteriptv.core.LiveCountry
import dev.betteriptv.core.SyncStep
import kotlinx.coroutines.delay
import java.time.Duration

/**
 * The Live tab, the sports hub's home (M3), in rows like Home's ([RowsScreen]): Live now, Today and
 * a row per League on ([leagues], in Settings' order) as Game cards, then More live events (the
 * Event channels no Game matched, on now or soon) and the Viewer's Favourites as Channel cards, then
 * the Channel browser's countries (the Channels row, the Viewer's [region] first, then their
 * Content languages' countries) as country cards named in the [appLanguage]; between More live
 * events and Favourites, Replays, the Leagues' Sports replays as Game cards, which OK plays
 * ([onPlayReplay]). The Games are asked for when the tab opens and again every [GAMES_REFRESH] while a Games row
 * or More live events is on screen (the Event channels it holds move with the clock and the Games),
 * the kept copy standing when the cloud fails. OK on a Game opens its page ([onOpenGame]); OK on a Channel watches it
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
    val noChannels = status.channelsAt == null
    val refreshes = gamesRefreshes(state)
    // Read again when a Sync lands Channels or a held catalog is applied, not only when Games land.
    val catalog by library.catalogChanges.collectAsStateWithLifecycle()
    RowsScreen(
        library, contentLanguages, appLanguage, state, restoreFocus, focusTab,
        reading = listOf(GamesReading(leagues, region, contentLanguages, refreshes), catalog),
        read = { library.liveRows(leagues, region, contentLanguages) },
        // Before a Sync there are no Channels to save; after one, the Viewer saves them from Search.
        empty = stringResource(if (noChannels) R.string.home_empty else R.string.live_empty),
        onOpen = { _, _, _ -> },
        onWatch = onWatch,
        onOpenGame = onOpenGame,
        onOpenCountry = onOpenCountry,
        onPlayReplay = onPlayReplay,
        placeholder = if (noChannels) {
            { SyncChecklist(status, SyncStep.Channels, onRetry) }
        } else null,
    ) { tab ->
        BrowseTabs(Browse.Live, onTab, Modifier.padding(start = Spacing.screenX, end = Spacing.screenX, top = Spacing.screenY, bottom = 16.dp), selectedFocus = tab, library = library)
    }
}

/** What the Live rows (and Home's, with its App language) are read for: the Leagues on, the Region and Content languages, and which refresh this is, so a change of any reads them again. */
internal data class GamesReading(val leagues: List<League>, val region: String, val contentLanguages: List<String>, val refresh: Int)

/**
 * How many times the Games have been due again since [state]'s rows first showed a Games row: bumped
 * every [GAMES_REFRESH] while one shows (Live's Game rows and More live events, Home's Live now), so a screen reading its
 * rows for it reads them (and asks the cloud) again; it stands still while no Games row shows.
 */
@Composable
internal fun gamesRefreshes(state: RowsState): Int {
    var refreshes by remember { mutableIntStateOf(0) }
    val gamesShown by remember(state) { derivedStateOf { state.rows?.any { it.hasGames } == true } }
    LaunchedEffect(gamesShown) {
        if (!gamesShown) return@LaunchedEffect
        while (true) {
            delay(GAMES_REFRESH.toMillis())
            refreshes++
        }
    }
    return refreshes
}

/** Whether a row holds Games, or the Event channels no Game matched, which the screen keeps reading again while they show. */
private val HomeRow.hasGames: Boolean get() = this is HomeRow.LiveNow || this is HomeRow.Today || this is HomeRow.LeagueGames || this is HomeRow.MoreLiveEvents

/** How often the Games are asked for again while a Games row shows (docs/roadmap.md, M3): the cloud reads a League's day every 5 minutes. */
internal val GAMES_REFRESH: Duration = Duration.ofMinutes(2)
