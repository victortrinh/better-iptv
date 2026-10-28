package dev.betteriptv.app

import android.app.Application
import android.util.Log
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import dev.betteriptv.core.AppUpdates
import dev.betteriptv.core.Library
import dev.betteriptv.core.SyncStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant

class BetterIptvApp : Application(), SingletonImageLoader.Factory {
    lateinit var library: Library
        private set
    lateinit var settings: ViewerSettingsStore
        private set

    /** The in-app updater (#21), checked on launch; null in builds that don't update themselves (debug, benchmark). */
    var updates: AppUpdates? = null
        private set

    /** Outlives screens, so a Sync keeps going in the background while the Viewer browses. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var syncJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        settings = ViewerSettingsStore(this)
        library = Library.open(
            getDatabasePath("catalog.db").apply { parentFile?.mkdirs() }.path,
            KeystoreCredentialStore(this),
            cloudAddress = BuildConfig.CLOUD_URL,
        )
        if (BuildConfig.UPDATE_URL.isNotEmpty()) {
            updates = AppUpdates(
                BuildConfig.UPDATE_URL,
                BuildConfig.VERSION_CODE.toLong(),
                cacheDir.resolve("updates"),
                AndroidApkInstaller(this),
            ).also { scope.launch { it.check() } }
        }
    }

    /** The Viewer confirmed the offered update: downloads and installs it, or first asks for the install permission. */
    fun update() {
        val updates = updates ?: return
        scope.launch { updates.update() }
    }

    /** Coil's defaults, with card artwork held back while cards scroll past (see [rememberArtwork]). */
    override fun newImageLoader(context: PlatformContext): ImageLoader {
        lateinit var loader: ImageLoader
        loader = ImageLoader.Builder(context)
            .components { add(SettleFirst(inMemory = { data -> loader.memoryCache?.keys?.any { it.key == data } == true })) }
            .build()
        return loader
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
                is SyncStatus.Synced -> Log.i(
                    TAG,
                    "Synced ${status.last.movieListingCount} movie and ${status.last.showListingCount} show Listings in ${status.last.duration.toMillis()} ms, ${status.last.rematchedListings} Matched by name",
                )
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
