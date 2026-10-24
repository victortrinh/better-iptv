package dev.betteriptv.app

import android.content.Context
import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.tv.material3.Button
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import dev.betteriptv.core.CompletedSync
import dev.betteriptv.core.Library
import dev.betteriptv.core.MovieListing
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
    val catalog = status.last?.finishedAt
    val pages = remember(catalog) { MoviePages(library) }
    val count by produceState(initialValue = 0, catalog) { value = withContext(Dispatchers.IO) { library.movieCount() } }
    val context = LocalContext.current

    Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.movies_title), style = MaterialTheme.typography.headlineMedium)
            Text(pluralStringResource(R.plurals.movies_count, count, NumberFormat.getIntegerInstance().format(count)))
            Spacer(Modifier.weight(1f))
            Button(onClick = onSyncNow, enabled = status !is SyncStatus.Running) { Text(stringResource(R.string.sync_now)) }
        }
        Text(context.describe(status), style = MaterialTheme.typography.bodyMedium)
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(count) { index -> MovieRow(pages, index) }
        }
    }
}

@Composable
private fun MovieRow(pages: MoviePages, index: Int) {
    val movie = pages[index]
    if (movie == null) LaunchedEffect(index / MoviePages.SIZE) { pages.load(index / MoviePages.SIZE) }
    Surface(
        onClick = {},
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        modifier = Modifier.fillMaxWidth().height(40.dp),
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(movie?.name.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(movie?.category.orEmpty(), maxLines = 1, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Keeps a few pages of the movie list in memory around where the Viewer is. */
@Stable
private class MoviePages(private val library: Library) {
    private val pages = mutableStateMapOf<Int, List<MovieListing>>()
    private val loading = mutableSetOf<Int>()

    operator fun get(index: Int): MovieListing? = pages[index / SIZE]?.getOrNull(index % SIZE)

    suspend fun load(page: Int) {
        if (page in pages || !loading.add(page)) return
        val movies = withContext(Dispatchers.IO) { library.movies(page * SIZE, SIZE) }
        loading -= page
        pages[page] = movies
        if (pages.size > KEEP) pages.keys.maxBy { abs(it - page) }.let(pages::remove)
    }

    companion object {
        const val SIZE = 100
        const val KEEP = 10
    }
}

private fun Context.describe(status: SyncStatus): String = when (status) {
    SyncStatus.NeverSynced -> getString(R.string.sync_never)
    is SyncStatus.Running -> status.last?.let { getString(R.string.sync_running, ago(it)) } ?: getString(R.string.sync_first)
    is SyncStatus.Synced -> getString(R.string.sync_done, ago(status.last), String.format(Locale.getDefault(), "%.1f", status.last.duration.toMillis() / 1000.0))
    is SyncStatus.Failed -> status.last?.let { getString(R.string.sync_failed, describe(status.problem), ago(it)) }
        ?: getString(R.string.sync_failed_empty, describe(status.problem))
}

private fun Context.ago(sync: CompletedSync): String {
    val millis = sync.finishedAt.toEpochMilli()
    return if (System.currentTimeMillis() - millis < DateUtils.MINUTE_IN_MILLIS) getString(R.string.sync_just_now)
    else DateUtils.getRelativeTimeSpanString(millis).toString()
}
