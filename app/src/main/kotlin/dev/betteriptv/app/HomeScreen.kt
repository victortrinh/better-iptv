package dev.betteriptv.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import dev.betteriptv.app.ui.components.TopTab
import dev.betteriptv.app.ui.components.TopTabs
import dev.betteriptv.app.ui.theme.Colors
import dev.betteriptv.app.ui.theme.Spacing
import dev.betteriptv.app.ui.theme.Type
import dev.betteriptv.core.Channel
import dev.betteriptv.core.FeaturedScreen
import dev.betteriptv.core.FeaturedTitle
import dev.betteriptv.core.GameFeeds
import dev.betteriptv.core.League
import dev.betteriptv.core.Library
import dev.betteriptv.core.QualityCap
import dev.betteriptv.core.Screen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import dev.betteriptv.core.SyncStatus
import dev.betteriptv.core.TitleId

/** The browsing screens, in tab order. */
enum class Browse { Home, Movies, Shows, Live, Search, Settings }

/**
 * The tabs across the top of the browsing screens; [selectedFocus] focuses the [selected] one. With
 * [library], the pill at their right end says while a Sync or Matching may still change the rows.
 * With two Profiles or more the profile chip ([LocalProfileChip]) sits after it, at the very end.
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
        TabsEnd(LocalProfileChip.current, pill = library?.let { { CatalogActivityPill(it) } })
    }
}

/**
 * The home screen's rows ([RowsScreen]): Continue watching (stills with how far the Viewer got),
 * Live now (the Games of the [leagues] on that are in progress with a Feed, as the Live tab's Game
 * cards; no row otherwise), Trending this week, New on your service, the Viewer's Favourites,
 * then a row per genre. The screen works out none of them: it asks the Core for Home
 * ([Screen.Home], [liveContent]) and draws the Rows it is handed, again each time the Core hands it
 * new ones (a catalog landing, a Profile switch, fresh Games while Live now shows). While a first
 * Sync's Head start is all the catalog holds (ADR 0019), those are Continue watching, Live now, New
 * on your service and Favourites, with no Trending or genre row yet. Above the rows, a featured Title from Trending, else New, a
 * different one each time Home is entered ([Library.featured]): its Play plays it ([onPlay]), its
 * Details opens it ([onOpen]). [restoreFocus] and [focusTab] are as on [RowsScreen]. A Title's card opens it ([onOpen]); a Game's
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
    onPlay: (RowCard, FeaturedTitle) -> Unit,
    onRetry: () -> Unit,
    qualityCap: QualityCap,
    preferOriginalAudio: Boolean,
) {
    // The hero badges what its Play starts, for this TV ([Library.featured]), read as Play reads it.
    val context = LocalContext.current
    val device = suspend { withContext(Dispatchers.Default) { readDeviceProfile(context) } }
    val status by library.syncStatus.collectAsStateWithLifecycle()
    // Until a first Sync lands the Channels, its checklist shows where the rows will be, as on Live, Movies and Shows.
    val noChannels = showsChecklist(status)
    // Once they have, the Titles are coming until they land, and the Catalog pill says so (#562); a failure then shows in Settings.
    val titlesComing = !noChannels && status.last == null && status !is SyncStatus.Failed
    val onTheirWay = titlesOnTheirWay(status)
    // Home's content, from the Core: new content is read at once, so the rows around the focused one
    // may come and go, but the focused row itself waits for focus to leave it ([RowsLayout]).
    val home = remember(contentLanguages, appLanguage, region, leagues) { Screen.Home(contentLanguages, appLanguage, region, leagues) }
    val content = liveContent(library, home)
    // The first Sync's sign-in card (#438): under the checklist, then under the rows until the Titles land.
    val scope = rememberCoroutineScope()
    val duringFirstSync = remember { status.last == null }
    val signIn = firstSyncSignIn(
        // Not offered until Accounts can send their Magic link (#458).
        offered = BuildConfig.ACCOUNTS && library.offersSignInCard.value,
        firstSync = status.last == null,
        start = library::startSignIn,
        check = library::checkSignIn,
        onDismiss = { scope.launch { library.dismissSignInCard() } },
    )
    RowsScreen(
        library, contentLanguages, appLanguage, state, restoreFocus, focusTab,
        reading = listOf(home, content.changes),
        read = { content.read().rows },
        empty = stringResource(R.string.home_empty),
        onOpen = onOpen,
        onWatch = onWatch,
        onOpenGame = onOpenGame,
        placeholder = if (noChannels) {
            { SyncChecklist(status, onRetry, card = signIn?.let { { SignInCard(it) } }) }
        } else null,
        // Kept, though empty without a card: the Titles' rows are a read away, and Home isn't called empty meanwhile.
        trailing = if (titlesComing) {
            {
                Column(Modifier.padding(horizontal = Spacing.screenX)) {
                    // A Home with no row at all and no Head start says its movies and shows are on their way, where the rows will be.
                    if (onTheirWay && state.rows?.isEmpty() == true) Text(stringResource(R.string.titles_on_their_way), style = Type.body, color = Colors.inkMuted)
                    signIn?.let { SignInCard(it, Modifier.padding(top = Spacing.m)) }
                }
            }
        } else null,
        feature = { readDetails -> library.featured(FeaturedScreen.Home, contentLanguages, appLanguage, device(), qualityCap, preferOriginalAudio, readDetails) },
        featureBadge = { id -> library.featuredBadge(id, contentLanguages, device(), qualityCap, preferOriginalAudio) },
        onPlayFeatured = onPlay,
        // The hero arrives with a first Sync's Head start, and with its Titles when it had none: a Home
        // that was up during the first Sync still asks as they land, the Sync by then finished.
        heroMayArrive = duringFirstSync,
    ) { tab ->
        BrowseTabs(Browse.Home, onTab, Modifier.padding(start = Spacing.screenX, end = Spacing.screenX, top = Spacing.screenY, bottom = 16.dp), selectedFocus = tab, library = library)
    }
}
