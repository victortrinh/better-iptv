package dev.betteriptv.app

import android.content.Context
import dev.betteriptv.core.BackgroundWork
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update

/**
 * Android's way of keeping the Core's work running when the app isn't in front: a foreground service
 * ([CatalogWorkService]) holds the process for as long as any work runs ([running]), so Fire OS
 * doesn't end a Sync or Matching when the Viewer switches to another app. The work itself stays
 * where the Core was called from.
 */
class AndroidBackgroundWork(private val context: Context, private val startService: (Context) -> Unit = CatalogWorkService::start) : BackgroundWork {
    private val _running = MutableStateFlow(0)

    /** How many Syncs and Matchings are running: [CatalogWorkService] stops itself once none is. */
    val running: StateFlow<Int> = _running.asStateFlow()

    override suspend fun <T> keepAlive(work: suspend () -> T): T {
        if (_running.getAndUpdate { it + 1 } == 0) startService(context)
        try {
            return work()
        } finally {
            _running.update { it - 1 }
        }
    }
}
