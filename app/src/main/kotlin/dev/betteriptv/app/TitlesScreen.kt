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
import androidx.tv.material3.Text
import dev.betteriptv.app.ui.theme.Colors
import dev.betteriptv.app.ui.theme.Type
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
 * week, then New movies (or shows), then its genres. Sync now and how the Sync
 * is going are in Settings; a first Sync's checklist shows here only until its Channels land. While
 * its Head start is all the catalog holds (ADR 0019), the tab shows New movies (or shows) alone, and
 * with no Head start a caption that the Titles are coming. Above the rows, a featured Title of the kind from Trending, else New, a
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
    // Read again when a Sync lands (a first Sync's Head start too), a held catalog is applied or Matches merge Titles.
    val catalog by library.catalogChanges.collectAsStateWithLifecycle()
    // A first Sync's checklist shows only until the Channels land, as on Home and Live (ADR 0019).
    val noChannels = showsChecklist(status)
    // From then until the Titles land, the tab shows what a Head start gave it: New movies (or shows)
    // and a featured Title. With no row to show (no Head start, or none of this kind), a caption says
    // the Titles are coming, as under Home's rows.
    val titlesComing = !noChannels && status.last == null && status !is SyncStatus.Failed
    val nothingYet = titlesComing && state.rows.rows?.isEmpty() == true
    val caption = stringResource(R.string.home_titles_pending)
    val duringFirstSync = remember { status.last == null }
    RowsScreen(
        library, contentLanguages, appLanguage, state.rows, restoreFocus, focusTab,
        reading = listOf(kind, contentLanguages, appLanguage, catalog),
        read = { library.tabRows(kind, contentLanguages, appLanguage) },
        empty = stringResource(R.string.home_empty),
        onOpen = onOpen,
        placeholder = if (noChannels) {
            { SyncChecklist(status, onRetry) }
        } else null,
        // Kept, though empty, while the Titles are coming: the tab isn't called empty in the moment a Head start lands.
        trailing = if (titlesComing) {
            {
                if (nothingYet) Text(caption, style = Type.caption, color = Colors.inkMuted, modifier = Modifier.padding(horizontal = Spacing.screenX, vertical = 12.dp))
            }
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
