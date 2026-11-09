package dev.betteriptv.app

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
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
import dev.betteriptv.core.SyncStep
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
 * is going are in Settings; only a first Sync's checklist, or why it failed, shows here, while
 * there are no Titles yet. Above the rows, a featured Title of the kind from Trending, else New, a
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
    // Read again when a Sync lands, a held catalog is applied or Matches regroup the Titles.
    val catalog by library.catalogChanges.collectAsStateWithLifecycle()
    // A first Sync leaves the tab empty for minutes: its checklist, from Movies on, shows where the rows
    // will be until the Titles land, and a failure after the Channels landed shows here with Retry.
    val noTitles = status.last == null
    RowsScreen(
        library, contentLanguages, appLanguage, state.rows, restoreFocus, focusTab,
        reading = listOf(kind, contentLanguages, appLanguage, catalog),
        read = { library.tabRows(kind, contentLanguages, appLanguage) },
        empty = stringResource(R.string.home_empty),
        onOpen = onOpen,
        placeholder = if (noTitles) {
            { SyncChecklist(status, SyncStep.Movies, onRetry) }
        } else null,
        feature = { readDetails -> library.featured(if (kind == TitleKind.Movie) FeaturedScreen.Movies else FeaturedScreen.Shows, contentLanguages, appLanguage, device(), qualityCap, preferOriginalAudio, readDetails) },
        featureBadge = { id -> library.featuredBadge(id, contentLanguages, device(), qualityCap, preferOriginalAudio) },
        onPlayFeatured = onPlay,
    ) { tab ->
        BrowseTabs(
            if (kind == TitleKind.Movie) Browse.Movies else Browse.Shows, onTab,
            Modifier.padding(start = Spacing.screenX, end = Spacing.screenX, top = Spacing.screenY, bottom = 16.dp),
            selectedFocus = tab,
            library = library,
        )
    }
}
