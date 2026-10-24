package dev.betteriptv.app

import android.app.Application
import android.util.Log
import dev.betteriptv.core.Library
import dev.betteriptv.core.SyncStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant

class BetterIptvApp : Application() {
    lateinit var library: Library
        private set

    /** Outlives screens, so a Sync keeps going in the background while the Viewer browses. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var syncJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        library = Library.open(getDatabasePath("catalog.db").apply { parentFile?.mkdirs() }.path, KeystoreCredentialStore(this))
    }

    /** Starts a Sync unless one finished recently. The catalog on screen stays until the new one is ready. */
    fun syncIfStale() {
        val last = library.syncStatus.value.last
        if (last == null || Duration.between(last.finishedAt, Instant.now()) > STALE_AFTER) sync()
    }

    /** Starts a Sync unless one is already running. */
    fun sync() {
        if (syncJob?.isActive == true) return
        syncJob = scope.launch {
            // Only counts and timings: the login and stream locations never reach the log.
            when (val status = library.sync()) {
                is SyncStatus.Synced -> Log.i(TAG, "Synced ${status.last.movieCount} movie Listings in ${status.last.duration.toMillis()} ms")
                is SyncStatus.Failed -> Log.w(TAG, "Sync failed: ${status.problem}")
                else -> Unit
            }
        }
    }

    private companion object {
        const val TAG = "Sync"
        val STALE_AFTER: Duration = Duration.ofHours(6)
    }
}
