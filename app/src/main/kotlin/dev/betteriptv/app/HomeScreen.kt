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
import dev.betteriptv.core.HomeRow
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

/** A card on the home screen: which row it is in, and its Title. */
data class HomeCard(val row: Int, val id: TitleId)

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
 * The home screen's rows: Continue watching (stills with how far the Viewer got), New on your
 * service, then a row per genre. They are read again whenever the screen shows, so a Title just
 * played moves to the front of Continue watching. [restoreFocus] is the card to focus on arrival,
 * the one the Viewer opened before coming back; the first card when null.
 */
@Composable
fun HomeScreen(
    library: Library,
    contentLanguages: List<String>,
    appLanguage: String,
    state: HomeState,
    restoreFocus: HomeCard?,
    onTab: (Browse) -> Unit,
    onOpen: (HomeCard) -> Unit,
) {
    val status by library.syncStatus.collectAsStateWithLifecycle()
    val synced = status.last
    LaunchedEffect(synced, contentLanguages, appLanguage) { state.rows = library.homeRows(contentLanguages, appLanguage) }
    val rows = state.rows
    val focusOn = restoreFocus ?: rows?.firstOrNull()?.let { HomeCard(0, it.titles.first().id) }

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
                    itemsIndexed(row.titles, key = { _, title -> title.id.toString() }) { index, title ->
                        val card = HomeCard(rowIndex, title.id)
                        val focus = remember { FocusRequester() }
                        if (card == focusOn) LaunchedEffect(Unit) { focus.requestFocus() }
                        val resume = (row as? HomeRow.ContinueWatching)?.titles?.get(index)
                        TitleCard(
                            image = rememberArtwork(if (wide) resume?.still ?: title.image else title.image),
                            name = title.name,
                            badges = title.best.cardBadges(),
                            onClick = { onOpen(card) },
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
        is HomeRow.NewOnYourService -> titles
        is HomeRow.Genre -> titles
    }

@Composable
private fun HomeRow.heading(): String = when (this) {
    is HomeRow.ContinueWatching -> stringResource(R.string.row_continue_watching)
    is HomeRow.NewOnYourService -> stringResource(R.string.row_new)
    is HomeRow.Genre -> name
}
