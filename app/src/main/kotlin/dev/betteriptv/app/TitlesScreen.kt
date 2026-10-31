package dev.betteriptv.app

import android.content.Context
import android.icu.text.DisplayContext
import android.icu.text.RelativeDateTimeFormatter
import android.icu.text.RelativeDateTimeFormatter.RelativeUnit
import android.icu.util.ULocale
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import dev.betteriptv.app.ui.components.NuageButton
import dev.betteriptv.app.ui.components.TitleCard
import dev.betteriptv.app.ui.theme.Cards
import dev.betteriptv.app.ui.theme.Colors
import dev.betteriptv.app.ui.theme.Spacing
import dev.betteriptv.app.ui.theme.Type
import dev.betteriptv.core.CatalogNotSaved
import dev.betteriptv.core.CompletedSync
import dev.betteriptv.core.Library
import dev.betteriptv.core.ProviderProblem
import dev.betteriptv.core.SyncProblem
import dev.betteriptv.core.SyncStatus
import dev.betteriptv.core.TitleId
import dev.betteriptv.core.TitleKind
import dev.betteriptv.core.TitleSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import java.time.Duration
import java.time.Instant
import java.util.Locale

/**
 * What a Titles tab keeps while another screen shows: its scroll position, and how many Titles it
 * counted last. The count shows until it is read again, so coming back from a Title doesn't lay
 * the grid out empty, which would put it back at its top (#116).
 */
@Stable
class TitlesState {
    val grid = LazyGridState()
    var count by mutableStateOf(0)
}

/**
 * One card per movie or show Title ([kind], picked with the tabs), newest first, read from the
 * device database a page at a time, with TMDB's name and poster in the [appLanguage].
 * [restoreFocus] is the card to focus on arrival: the first, or the one the Viewer opened before coming back.
 * [focusTab]: reached by picking its tab, which keeps focus.
 */
@Composable
fun TitlesScreen(
    library: Library,
    kind: TitleKind,
    contentLanguages: List<String>,
    appLanguage: String,
    state: TitlesState,
    restoreFocus: Int,
    focusTab: Boolean,
    onSyncNow: () -> Unit,
    onTab: (Browse) -> Unit,
    onOpen: (index: Int, TitleId) -> Unit,
) {
    val status by library.syncStatus.collectAsStateWithLifecycle()
    // A finished Sync swaps in a new catalog, and Listings Matched by name afterwards regroup its
    // Titles: either way, drop the pages read before.
    val synced = status.last
    val scope = rememberCoroutineScope()
    val pages = remember(synced, kind, contentLanguages, appLanguage) {
        TitlePages(scope) { offset, limit -> library.titles(offset, limit, contentLanguages, appLanguage, kind) }
    }
    val grid = state.grid
    LaunchedEffect(synced, kind) { state.count = withContext(Dispatchers.IO) { library.titleCount(kind) } }
    val count = state.count
    LaunchedEffect(pages, count) {
        snapshotFlow { grid.layoutInfo.visibleItemsInfo.let { (it.firstOrNull()?.index ?: 0) to (it.lastOrNull()?.index ?: 0) } }
            .collect { (first, last) -> pages.showing(first, last, count) }
    }
    val focus = remember(grid) { GridFocus(grid, scope) }
    val context = LocalContext.current
    val tab = remember { FocusRequester() }
    FocusTabOnArrival(focusTab, tab)

    Column(Modifier.fillMaxSize().background(Colors.ground)) {
        Box(Modifier.padding(start = Spacing.screenX, end = Spacing.screenX, top = Spacing.screenY)) {
            BrowseTabs(if (kind == TitleKind.Movie) Browse.Movies else Browse.Shows, onTab, selectedFocus = tab)
            Row(Modifier.align(Alignment.CenterEnd), verticalAlignment = Alignment.CenterVertically) {
                Text(pluralStringResource(R.plurals.titles_count, count, NumberFormat.getIntegerInstance().format(count)), style = Type.body, color = Colors.inkMuted)
                Spacer(Modifier.width(Spacing.m))
                NuageButton(stringResource(R.string.sync_now), onSyncNow, enabled = status !is SyncStatus.Running && status !is SyncStatus.Matching)
            }
        }
        val described = context.describe(status)
        Text(
            described,
            style = Type.caption,
            color = if (status is SyncStatus.Failed) Colors.error else Colors.inkMuted,
            modifier = Modifier.padding(start = Spacing.screenX, end = Spacing.screenX, top = Spacing.s),
        )
        // A first Sync leaves the grid empty for minutes: say how far it is where the cards will be, readable from the couch.
        if (count == 0 && (status is SyncStatus.Running || status is SyncStatus.Matching)) {
            Box(Modifier.fillMaxSize().padding(horizontal = Spacing.screenX, vertical = Spacing.screenY), contentAlignment = Alignment.Center) {
                Text(described, style = Type.body, color = Colors.inkMuted, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = EMPTY_GRID_TEXT_WIDTH))
            }
            return@Column
        }
        // The margins are content padding, so a focused card grows into them instead of being clipped.
        // The columns share the width between them, so the grid ends at the right margin like it starts at the left.
        LazyVerticalGrid(
            GridCells.Adaptive(Cards.posterWidth),
            Modifier.fillMaxSize().onPreviewKeyEvent { focus.onKey(it, count) }.entersFromAbove(focus).backKeyToTab(tab),
            state = grid,
            contentPadding = PaddingValues(start = Spacing.screenX, end = Spacing.screenX, top = Spacing.l, bottom = Spacing.screenY),
            horizontalArrangement = Arrangement.spacedBy(Cards.gap),
        ) {
            items(count) { index -> Card(library, contentLanguages, pages, focus, index, focusNow = index == restoreFocus, onOpen) }
        }
    }
}

@Composable
private fun Card(
    library: Library,
    contentLanguages: List<String>,
    pages: TitlePages<TitleSummary>,
    gridFocus: GridFocus,
    index: Int,
    focusNow: Boolean,
    onOpen: (index: Int, TitleId) -> Unit,
) {
    val title = pages[index]
    val focus = remember(index) { gridFocus.card(index) }.requester
    if (focusNow) LaunchedEffect(Unit) { focus.requestFocus() }
    TitleCard(
        image = rememberArtwork(title?.image, card = true),
        name = title?.name.orEmpty(),
        badges = title?.let { cardBest(library, it, contentLanguages) }?.cardBadges().orEmpty(),
        onClick = { title?.let { onOpen(index, it.id) } },
        modifier = Modifier.focusRequester(focus).onFocusChanged { if (it.hasFocus) gridFocus.focused(index) }.padding(vertical = 10.dp),
    )
}

private fun Context.describe(status: SyncStatus): String = when (status) {
    SyncStatus.NeverSynced -> getString(R.string.sync_never)
    is SyncStatus.Running -> running(status)
    is SyncStatus.Synced -> synced(status.last)
    is SyncStatus.Matching -> synced(status.last) + " " + (
        status.of?.let { resources.getQuantityString(R.plurals.sync_matching, it, count(status.asked), count(it)) }
            ?: getString(R.string.sync_matching_start)
        )
    is SyncStatus.Failed -> status.last?.let { getString(R.string.sync_failed, describe(status.problem), ago(it)) }
        ?: getString(R.string.sync_failed_empty, describe(status.problem))
}

/** A first Sync counts the Listings read; a later one tells how far it is by the last one's counts. */
private fun Context.running(status: SyncStatus.Running): String {
    val last = status.last
    if (last == null) {
        if (status.moviesRead == 0 && status.showsRead == 0) return getString(R.string.sync_first)
        val movies = resources.getQuantityString(R.plurals.sync_movies_read, status.moviesRead, count(status.moviesRead))
        val read = if (status.showsRead == 0) movies
        else getString(R.string.sync_movies_and_shows, movies, resources.getQuantityString(R.plurals.sync_shows_read, status.showsRead, count(status.showsRead)))
        return getString(R.string.sync_first_progress, read)
    }
    val fraction = status.fraction ?: return getString(R.string.sync_running, ago(last))
    return getString(R.string.sync_running_progress, NumberFormat.getPercentInstance().format(fraction), ago(last))
}

private fun Context.synced(last: CompletedSync): String =
    getString(R.string.sync_done, ago(last), String.format(Locale.getDefault(), "%.1f", last.duration.toMillis() / 1000.0)) + unknownPrefixes(last)

private fun count(n: Int): String = NumberFormat.getIntegerInstance().format(n)

private fun Context.describe(problem: SyncProblem): String = when (problem) {
    is ProviderProblem -> describe(problem)
    is CatalogNotSaved -> getString(R.string.catalog_not_saved, problem.detail)
}

/** Names the Prefixes the Prefix table should learn, so they get added instead of guessed. */
private fun Context.unknownPrefixes(sync: CompletedSync): String {
    val prefixes = sync.unknownPrefixes.ifEmpty { return "" }
    return " " + resources.getQuantityString(R.plurals.sync_unknown_prefixes, prefixes.size, prefixes.joinToString(", ") { it.prefix })
}

private fun Context.ago(sync: CompletedSync): String = ago(sync.finishedAt, Instant.now())

/**
 * How long ago a Sync finished, worded to sit mid-sentence: "il y a 5 minutes", not the
 * capitalised "Il y a 5 minutes" DateUtils gives for the start of one.
 */
internal fun Context.ago(then: Instant, now: Instant): String {
    val elapsed = Duration.between(then, now)
    val (amount, unit) = when {
        elapsed < Duration.ofMinutes(1) -> return getString(R.string.sync_just_now)
        elapsed < Duration.ofHours(1) -> elapsed.toMinutes() to RelativeUnit.MINUTES
        elapsed < Duration.ofDays(1) -> elapsed.toHours() to RelativeUnit.HOURS
        elapsed < Duration.ofDays(7) -> elapsed.toDays() to RelativeUnit.DAYS
        elapsed < Duration.ofDays(30) -> elapsed.toDays() / 7 to RelativeUnit.WEEKS
        elapsed < Duration.ofDays(365) -> elapsed.toDays() / 30 to RelativeUnit.MONTHS
        else -> elapsed.toDays() / 365 to RelativeUnit.YEARS
    }
    val formatter = RelativeDateTimeFormatter.getInstance(
        ULocale.forLocale(resources.configuration.locales[0]), null,
        RelativeDateTimeFormatter.Style.LONG, DisplayContext.CAPITALIZATION_FOR_MIDDLE_OF_SENTENCE,
    )
    return formatter.format(amount.toDouble(), RelativeDateTimeFormatter.Direction.LAST, unit)
}

/** Wide enough for the French first-Sync line on two lines at body size. */
private val EMPTY_GRID_TEXT_WIDTH = 440.dp
