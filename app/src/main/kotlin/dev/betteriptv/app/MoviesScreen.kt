package dev.betteriptv.app

import android.content.Context
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import dev.betteriptv.app.ui.components.FocusSurface
import dev.betteriptv.app.ui.components.FocusWeight
import dev.betteriptv.app.ui.components.NuageButton
import dev.betteriptv.app.ui.components.Wordmark
import dev.betteriptv.app.ui.theme.Colors
import dev.betteriptv.app.ui.theme.Spacing
import dev.betteriptv.app.ui.theme.Type
import dev.betteriptv.core.CatalogNotSaved
import dev.betteriptv.core.CompletedSync
import dev.betteriptv.core.Library
import dev.betteriptv.core.MovieListing
import dev.betteriptv.core.ProviderProblem
import dev.betteriptv.core.SyncProblem
import dev.betteriptv.core.SyncStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs

/** Every movie Listing, newest first, read from the device database a page at a time. */
@Composable
fun MoviesScreen(library: Library, onSyncNow: () -> Unit) {
    val status by library.syncStatus.collectAsStateWithLifecycle()
    // A finished Sync swaps in a new catalog: drop the pages read from the old one.
    val syncedAt = status.last?.finishedAt
    val pages = remember(syncedAt) { MovieListingPages(library) }
    val count by produceState(initialValue = 0, syncedAt) { value = withContext(Dispatchers.IO) { library.movieListingCount() } }
    val context = LocalContext.current

    Column(Modifier.fillMaxSize().background(Colors.ground).padding(horizontal = Spacing.screenX, vertical = Spacing.screenY)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Wordmark()
            Spacer(Modifier.width(Spacing.xl))
            Text(stringResource(R.string.movies_title), style = Type.headline, color = Colors.ink)
            Spacer(Modifier.width(Spacing.m))
            Text(pluralStringResource(R.plurals.movies_count, count, NumberFormat.getIntegerInstance().format(count)), style = Type.body, color = Colors.inkMuted)
            Spacer(Modifier.weight(1f))
            NuageButton(stringResource(R.string.sync_now), onSyncNow, enabled = status !is SyncStatus.Running)
        }
        Text(
            context.describe(status),
            style = Type.caption,
            color = if (status is SyncStatus.Failed) Colors.error else Colors.inkMuted,
            modifier = Modifier.padding(top = Spacing.s, bottom = Spacing.l),
        )
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            items(count) { index -> MovieRow(pages, index) }
        }
    }
}

@Composable
private fun MovieRow(pages: MovieListingPages, index: Int) {
    val movie = pages[index]
    if (movie == null) LaunchedEffect(index / MovieListingPages.PAGE_SIZE) { pages.load(index / MovieListingPages.PAGE_SIZE) }
    FocusSurface(
        onClick = {},
        modifier = Modifier.fillMaxWidth().height(40.dp),
        weight = FocusWeight.Row,
        container = Colors.surface,
        focusedContainer = Colors.focus,
    ) { focused ->
        Row(Modifier.fillMaxSize().padding(horizontal = Spacing.l), verticalAlignment = Alignment.CenterVertically) {
            Text(
                movie?.name.orEmpty(),
                style = Type.label,
                color = if (focused) Colors.onFocus else Colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(Spacing.l))
            Text(movie?.category.orEmpty(), style = Type.caption, color = if (focused) Colors.onFocus else Colors.inkMuted, maxLines = 1)
        }
    }
}

/** Keeps a few pages of the movie list in memory around where the Viewer is. */
@Stable
private class MovieListingPages(private val library: Library) {
    private val pages = mutableStateMapOf<Int, List<MovieListing>>()
    private val loading = mutableSetOf<Int>()

    operator fun get(index: Int): MovieListing? = pages[index / PAGE_SIZE]?.getOrNull(index % PAGE_SIZE)

    suspend fun load(page: Int) {
        if (page in pages || !loading.add(page)) return
        val movies = withContext(Dispatchers.IO) { library.movieListings(page * PAGE_SIZE, PAGE_SIZE) }
        loading -= page
        pages[page] = movies
        if (pages.size > PAGES_KEPT) pages.keys.maxBy { abs(it - page) }.let(pages::remove)
    }

    companion object {
        const val PAGE_SIZE = 100
        const val PAGES_KEPT = 10
    }
}

private fun Context.describe(status: SyncStatus): String = when (status) {
    SyncStatus.NeverSynced -> getString(R.string.sync_never)
    is SyncStatus.Running -> status.last?.let { getString(R.string.sync_running, ago(it)) } ?: getString(R.string.sync_first)
    is SyncStatus.Synced -> getString(R.string.sync_done, ago(status.last), String.format(Locale.getDefault(), "%.1f", status.last.duration.toMillis() / 1000.0))
    is SyncStatus.Failed -> status.last?.let { getString(R.string.sync_failed, describe(status.problem), ago(it)) }
        ?: getString(R.string.sync_failed_empty, describe(status.problem))
}

private fun Context.describe(problem: SyncProblem): String = when (problem) {
    is ProviderProblem -> describe(problem)
    is CatalogNotSaved -> getString(R.string.catalog_not_saved, problem.detail)
}

private fun Context.ago(sync: CompletedSync): String {
    val millis = sync.finishedAt.toEpochMilli()
    return if (System.currentTimeMillis() - millis < DateUtils.MINUTE_IN_MILLIS) getString(R.string.sync_just_now)
    else DateUtils.getRelativeTimeSpanString(millis).toString()
}
