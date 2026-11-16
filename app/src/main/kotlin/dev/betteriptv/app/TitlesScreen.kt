package dev.betteriptv.app

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.betteriptv.app.ui.theme.Spacing
import dev.betteriptv.core.FeaturedScreen
import dev.betteriptv.core.FeaturedTitle
import dev.betteriptv.core.Library
import dev.betteriptv.core.QualityCap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import dev.betteriptv.core.SyncStatus
import dev.betteriptv.core.TitleId
import dev.betteriptv.core.TitleKind
import dev.betteriptv.core.TitlesTab

/**
 * What a Titles tab keeps while another screen shows: its [rows] (scroll positions and the rows
 * read last), and how many Titles it counted last, kept until it is read again.
 */
@Stable
class TitlesState {
    val rows = RowsState()
}

/**
 * The Movies or Shows tab ([kind]), in rows like Home's ([RowsScreen]): the kind's Trending this
 * week, then New movies (or shows), then its genres. The screen works out none of them: it asks the
 * Core for the tab ([TitlesTab], [liveContent]) and draws the Rows it is handed. Sync now and how the Sync
 * is going are in Settings; a first Sync's checklist shows here only until its Channels land. While
 * its Head start is all the catalog holds (ADR 0019), the tab shows New movies (or shows) alone, and
 * with no Head start one line, that the movies and shows are on their way. Above the rows, a featured Title of the kind from Trending, else New, a
 * different one each time the tab is entered ([Library.featured]): its Play plays it ([onPlay]), its
 * Details opens it ([onOpen]). [restoreFocus] is the card to focus on coming back, the one the
 * Viewer opened. [focusTab]: reached by picking its tab, which keeps focus.
 */
@Composable
fun TitlesScreen(
    library: Library,
    kind: TitleKind,
    contentLanguages: List<String>,
    appLanguage: String,
    state: TitlesState,
    restoreFocus: RowCard?,
    focusTab: Boolean,
    onTab: (Browse) -> Unit,
    onOpen: (RowCard, TitleId) -> Unit,
    onPlay: (RowCard, FeaturedTitle) -> Unit,
    onRetry: () -> Unit,
    qualityCap: QualityCap,
    preferOriginalAudio: Boolean,
) {
    // The hero badges what its Play starts, for this TV ([Library.featured]), read as Play reads it.
    val context = LocalContext.current
    val device = suspend { withContext(Dispatchers.Default) { readDeviceProfile(context) } }
    val status by library.syncStatus.collectAsStateWithLifecycle()
    // A first Sync's checklist shows only until the Channels land, as on Home and Live (ADR 0019).
    val noChannels = showsChecklist(status)
    // From then until the Titles land, the tab shows what a Head start gave it: New movies (or shows)
    // and a featured Title. With no Head start it says, where the rows will be, that they are on their way.
    val titlesComing = !noChannels && status.last == null && status !is SyncStatus.Failed
    val onTheirWay = titlesOnTheirWay(status)
    val duringFirstSync = remember { status.last == null }
    // The tab's content, from the Core: handed over again when a catalog lands (a first Sync's Head
    // start too), a held catalog is applied, Matches merge Titles or the Profile switches.
    val tab = remember(kind, contentLanguages, appLanguage) { TitlesTab(kind, contentLanguages, appLanguage) }
    val content = liveContent(library, tab)
    RowsScreen(
        library, contentLanguages, appLanguage, state.rows, restoreFocus, focusTab,
        reading = listOf(tab, content.changes),
        read = { content.read().rows },
        empty = stringResource(if (onTheirWay) R.string.titles_on_their_way else R.string.home_empty),
        onOpen = onOpen,
        placeholder = if (noChannels) {
            { SyncChecklist(status, onRetry) }
        } else null,
        // Empty, and there so a tab the Head start gave no row isn't called empty while the Titles are
        // coming; without a Head start, [empty]'s line stands where the rows will be.
        trailing = if (titlesComing && !onTheirWay) {
            {}
        } else null,
        feature = { readDetails -> library.featured(if (kind == TitleKind.Movie) FeaturedScreen.Movies else FeaturedScreen.Shows, contentLanguages, appLanguage, device(), qualityCap, preferOriginalAudio, readDetails) },
        featureBadge = { id -> library.featuredBadge(id, contentLanguages, device(), qualityCap, preferOriginalAudio) },
        onPlayFeatured = onPlay,
        // The hero arrives with a first Sync's Head start, and with its Titles when it had none: a tab
        // that was up during the first Sync still asks as they land, the Sync by then finished.
        heroMayArrive = duringFirstSync,
    ) { tab ->
        BrowseTabs(
            if (kind == TitleKind.Movie) Browse.Movies else Browse.Shows, onTab,
            Modifier.padding(start = Spacing.screenX, end = Spacing.screenX, top = Spacing.screenY, bottom = 16.dp),
            selectedFocus = tab,
            library = library,
        )
    }
}
