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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

class BetterIptvApp : Application(), SingletonImageLoader.Factory {
    lateinit var library: Library
        private set
    lateinit var settings: ViewerSettingsStore
        private set

    /** The in-app updater (#21), checked on launch and again when the app comes back to the front; null in builds that don't update themselves (debug, benchmark). */
    var updates: AppUpdates? = null
        private set

    /** Outlives screens, so a Sync keeps going in the background while the Viewer browses. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _catalogJobs = MutableStateFlow(0)

    /** How many Syncs and Matchings are running: [CatalogWorkService] keeps the process alive while any is. */
    val catalogJobs: StateFlow<Int> = _catalogJobs.asStateFlow()

    /** Whether this launch has picked up Matching a closed app left unfinished ([Library.resumeMatching]). */
    private val matchingResumed = AtomicBoolean()

    override fun onCreate() {
        super.onCreate()
        settings = ViewerSettingsStore(this)
        library = Library.open(
            getDatabasePath("catalog.db").apply { parentFile?.mkdirs() }.path,
            KeystoreCredentialStore(this),
            cloudAddress = BuildConfig.CLOUD_URL,
        )
        // The first search mustn't wait for the index (over a minute on the stick, #106), nor Home's first rows for its build.
        scope.launch {
            delay(SEARCH_INDEX_AFTER)
            library.prepareSearchInBackground()
        }
        // VLC's first picture after an install mustn't wait 41 s for its font list (#410).
        scope.launch {
            delay(VLC_FONTS_AFTER)
            // Not under a picture that plays: it would take the decoders' share of the stick.
            while (playerOpen) delay(VLC_FONTS_AFTER)
            VlcFonts.buildIfMissing(this@BetterIptvApp)
        }
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

    /**
     * Whether the player is open: a held catalog isn't applied and no Sync starts while it is, so
     * nothing moves under what the Viewer watches. Set by the root screen.
     */
    @Volatile var playerOpen = false

    /**
     * The app came to the front (launch or resume): a held Sync's catalog lands now, between screens,
     * or else a Sync starts if the last one is stale. Either Matches the catalog after; with neither,
     * the first time, Matching a closed app left unfinished resumes. Nothing while the player is
     * open or before login. Updates are checked again too, unless checked recently: the process
     * lives for days on a stick, so the launch check alone misses every build released since.
     */
    fun onResumed() {
        if (playerOpen) return
        updates?.let { scope.launch { it.checkIfStale(RECHECK_UPDATES_AFTER) } }
        if (!library.isLoggedIn) return
        when {
            library.hasPendingCatalog -> applyPendingCatalog()
            syncIfStale() -> Unit
            matchingResumed.compareAndSet(false, true) -> scope.launch { keepingAlive { library.resumeMatching() } }
        }
    }

    /** Lands a held Sync's catalog ([Library.applyPendingCatalog]), on entering a top-level screen; never while the player is open. */
    fun applyPendingCatalog() {
        if (playerOpen || !library.hasPendingCatalog) return
        scope.launch { keepingAlive { library.applyPendingCatalog() } }
    }

    /**
     * Starts a Sync unless one finished recently or waits to be applied, and says whether it did. The
     * catalog on screen stays until the new one lands.
     */
    fun syncIfStale(): Boolean {
        val status = library.syncStatus.value
        if (status is SyncStatus.Ready) return false
        val last = status.last
        if (last != null && Duration.between(last.finishedAt, Instant.now()) <= STALE_AFTER) return false
        sync()
        return true
    }

    /**
     * Starts a Sync unless one is running. A later Sync's catalog is held for [applyPendingCatalog],
     * so rows never move under focus; Matching after a Sync doesn't hold the next one up.
     */
    fun sync() {
        if (library.syncStatus.value is SyncStatus.Running) return
        scope.launch {
            // Only counts and timings: the login and stream locations never reach the log.
            when (val status = keepingAlive { library.sync(hold = true) }) {
                is SyncStatus.Synced -> Log.i(
                    TAG,
                    "Synced ${status.last.movieListingCount} movie and ${status.last.showListingCount} show Listings in ${status.last.duration.toMillis()} ms, ${status.last.rematchedListings} Matched by name",
                )
                is SyncStatus.Ready -> Log.i(TAG, "Synced; the catalog waits to be applied")
                is SyncStatus.Failed -> Log.w(TAG, "Sync failed at ${status.step}: ${status.problem}")
                else -> Unit
            }
        }
    }

    /** Runs [work] with [CatalogWorkService] keeping the process alive, so switching apps doesn't end it. */
    private suspend fun <T> keepingAlive(work: suspend () -> T): T {
        if (_catalogJobs.getAndUpdate { it + 1 } == 0) CatalogWorkService.start(this)
        try {
            return work()
        } finally {
            _catalogJobs.update { it - 1 }
        }
    }

    private companion object {
        const val TAG = "Sync"
        val STALE_AFTER: Duration = Duration.ofHours(6)
        // CI releases within minutes of a merge, and the manifest is one small read.
        val RECHECK_UPDATES_AFTER: Duration = Duration.ofMinutes(15)
        const val SEARCH_INDEX_AFTER = 5_000L
        // After Home's cold start and the search index have had the stick to themselves.
        const val VLC_FONTS_AFTER = 20_000L
    }
}
