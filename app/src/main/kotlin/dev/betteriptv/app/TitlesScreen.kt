package dev.betteriptv.app

import android.content.Context
import android.text.format.DateUtils
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import dev.betteriptv.app.ui.components.NuageButton
import dev.betteriptv.app.ui.components.TitleCard
import dev.betteriptv.app.ui.components.TopTabs
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs

/**
 * One card per movie or show Title ([kind], switched with the tabs), newest first, read from the
 * device database a page at a time, with TMDB's name and poster in the [appLanguage].
 * [restoreFocus] is the card to focus on arrival: the first, or the one the Viewer opened before coming back.
 */
@Composable
fun TitlesScreen(
    library: Library,
    kind: TitleKind,
    contentLanguages: List<String>,
    appLanguage: String,
    grid: LazyGridState,
    restoreFocus: Int,
    onSyncNow: () -> Unit,
    onKind: (TitleKind) -> Unit,
    onSettings: () -> Unit,
    onOpen: (index: Int, TitleId) -> Unit,
) {
    val status by library.syncStatus.collectAsStateWithLifecycle()
    // A finished Sync swaps in a new catalog, and Listings Matched by name afterwards regroup its
    // Titles: either way, drop the pages read before.
    val synced = status.last
    val scope = rememberCoroutineScope()
    val pages = remember(synced, kind, contentLanguages, appLanguage) { TitlePages(library, kind, contentLanguages, appLanguage, scope) }
    val count by produceState(initialValue = 0, synced, kind) { value = withContext(Dispatchers.IO) { library.titleCount(kind) } }
    val context = LocalContext.current

    Column(Modifier.fillMaxSize().background(Colors.ground)) {
        Box(Modifier.padding(start = Spacing.screenX, end = Spacing.screenX, top = Spacing.screenY)) {
            TopTabs(
                listOf(stringResource(R.string.movies_title), stringResource(R.string.shows_title), stringResource(R.string.settings_title)),
                selected = KINDS.indexOf(kind),
                onSelect = { KINDS.getOrNull(it)?.let(onKind) ?: onSettings() },
            )
            Row(Modifier.align(Alignment.CenterEnd), verticalAlignment = Alignment.CenterVertically) {
                Text(pluralStringResource(R.plurals.titles_count, count, NumberFormat.getIntegerInstance().format(count)), style = Type.body, color = Colors.inkMuted)
                Spacer(Modifier.width(Spacing.m))
                NuageButton(stringResource(R.string.sync_now), onSyncNow, enabled = status !is SyncStatus.Running)
            }
        }
        Text(
            context.describe(status),
            style = Type.caption,
            color = if (status is SyncStatus.Failed) Colors.error else Colors.inkMuted,
            modifier = Modifier.padding(start = Spacing.screenX, end = Spacing.screenX, top = Spacing.s),
        )
        // The margins are content padding, so a focused card grows into them instead of being clipped.
        LazyVerticalGrid(
            GridCells.FixedSize(Cards.posterWidth),
            Modifier.fillMaxSize(),
            state = grid,
            contentPadding = PaddingValues(start = Spacing.screenX, end = Spacing.screenX, top = Spacing.l, bottom = Spacing.screenY),
            horizontalArrangement = Arrangement.spacedBy(Cards.gap),
        ) {
            items(count) { index -> Card(pages, index, focusNow = index == restoreFocus, onOpen) }
        }
    }
}

@Composable
private fun Card(pages: TitlePages, index: Int, focusNow: Boolean, onOpen: (index: Int, TitleId) -> Unit) {
    val title = pages[index]
    if (title == null) LaunchedEffect(pages, index / TitlePages.PAGE_SIZE) { pages.load(index / TitlePages.PAGE_SIZE) }
    val focus = remember { FocusRequester() }
    if (focusNow) LaunchedEffect(Unit) { focus.requestFocus() }
    TitleCard(
        image = rememberArtwork(title?.image),
        name = title?.name.orEmpty(),
        badges = title?.best?.cardBadges().orEmpty(),
        onClick = { title?.let { onOpen(index, it.id) } },
        modifier = Modifier.focusRequester(focus).padding(vertical = 10.dp),
    )
}

/** Keeps a few pages of the Title list in memory around where the Viewer is. */
@Stable
private class TitlePages(
    private val library: Library,
    private val kind: TitleKind,
    private val contentLanguages: List<String>,
    private val appLanguage: String,
    private val scope: CoroutineScope,
) {
    private val pages = mutableStateMapOf<Int, List<TitleSummary>>()
    private val loading = mutableSetOf<Int>()

    operator fun get(index: Int): TitleSummary? = pages[index / PAGE_SIZE]?.getOrNull(index % PAGE_SIZE)

    /** Loads [page] in the screen's scope: a card scrolled away mustn't cancel it for the cards still showing. */
    fun load(page: Int) {
        if (page in pages || !loading.add(page)) return
        scope.launch {
            try {
                pages[page] = library.titles(page * PAGE_SIZE, PAGE_SIZE, contentLanguages, appLanguage, kind)
                if (pages.size > PAGES_KEPT) pages.keys.maxBy { abs(it - page) }.let(pages::remove)
            } finally {
                loading -= page
            }
        }
    }

    companion object {
        const val PAGE_SIZE = 60
        const val PAGES_KEPT = 10
    }
}

/** The tabs before Settings, in order. */
private val KINDS = listOf(TitleKind.Movie, TitleKind.Show)

private fun Context.describe(status: SyncStatus): String = when (status) {
    SyncStatus.NeverSynced -> getString(R.string.sync_never)
    is SyncStatus.Running -> status.last?.let { getString(R.string.sync_running, ago(it)) } ?: getString(R.string.sync_first)
    is SyncStatus.Synced -> getString(R.string.sync_done, ago(status.last), String.format(Locale.getDefault(), "%.1f", status.last.duration.toMillis() / 1000.0)) +
        unknownPrefixes(status.last)
    is SyncStatus.Failed -> status.last?.let { getString(R.string.sync_failed, describe(status.problem), ago(it)) }
        ?: getString(R.string.sync_failed_empty, describe(status.problem))
}

private fun Context.describe(problem: SyncProblem): String = when (problem) {
    is ProviderProblem -> describe(problem)
    is CatalogNotSaved -> getString(R.string.catalog_not_saved, problem.detail)
}

/** Names the Prefixes the Prefix table should learn, so they get added instead of guessed. */
private fun Context.unknownPrefixes(sync: CompletedSync): String {
    val prefixes = sync.unknownPrefixes.ifEmpty { return "" }
    return " " + resources.getQuantityString(R.plurals.sync_unknown_prefixes, prefixes.size, prefixes.joinToString(", ") { it.prefix })
}

private fun Context.ago(sync: CompletedSync): String {
    val millis = sync.finishedAt.toEpochMilli()
    return if (System.currentTimeMillis() - millis < DateUtils.MINUTE_IN_MILLIS) getString(R.string.sync_just_now)
    else DateUtils.getRelativeTimeSpanString(millis).toString()
}
