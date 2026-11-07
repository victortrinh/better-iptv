package dev.betteriptv.core

import java.time.Duration
import java.time.Instant
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Where a Viewer stopped and what they Watched, kept alike on a household's TVs (#435): one
 * Profile's history syncs with the Account the device is signed in to.
 *
 * While signed in, each change the Viewer makes is queued ([record]) and the queue is pushed after
 * each stop ([pushSoon]); a push that fails stays queued for the next. Each push pulls the Account's
 * changes since the device's cursor, which wait ([CatalogStore.stagePulledHistory]) until they land
 * between screens ([land]), never under focus; a Title's page lands its own at once ([landTitle]).
 * Per Movie or Episode the latest change wins, and "Remove from Continue watching" is a change too.
 *
 * The first sync after signing in merges the device's history into the Account: the Profile it is
 * on becomes the one that syncs (until #440 syncs every Profile), all of its history is queued, and
 * the cursor starts from the beginning. Signing out ([signedOut]) takes that Profile's history off
 * the device. A device that never signs in never syncs, and keeps its history as before.
 */
internal class HistorySync(
    private val store: CatalogStore,
    private val cloud: CloudClient?,
    /** Whether the device is signed in to an Account, as it last knew. */
    private val signedIn: () -> Boolean,
    /** The Profile the device is on, and whether a Profile still exists. */
    private val currentProfile: () -> Long,
    private val profileExists: (Long) -> Boolean,
    /** The [providerKey] of the Provider logged in to, or null. */
    private val provider: () -> String?,
    /** Called when the cloud says the device is in no Account: it was signed out from another TV. */
    private val onSignedOut: () -> Unit,
) {
    private val lock = Any()
    private val pushes: ExecutorService = Executors.newSingleThreadExecutor { Thread(it, "library-history").apply { isDaemon = true } }
    private val pushQueued = AtomicBoolean()

    /** The Profile whose history syncs, or null before the first sync after signing in. */
    private val syncedProfile: Long? get() = store.historyState()?.profile

    /** Queues [change] to [profile]'s pushes, when that Profile's history syncs. */
    fun record(profile: Long, change: HistoryChange) {
        if (signedIn() && syncedProfile == profile) store.queueHistory(profile, change)
    }

    /** Syncs in the background soon, once however often it is asked meanwhile: after a stop, a removal, or signing in. */
    fun pushSoon() {
        if (cloud == null || !signedIn() || !pushQueued.compareAndSet(false, true)) return
        try {
            pushes.execute {
                pushQueued.set(false)
                sync()
            }
        } catch (e: RejectedExecutionException) {
            // Closing: the queue is pushed at the next launch.
        }
    }

    /**
     * Pushes what is queued and pulls the Account's changes since the cursor, and says whether the
     * cloud answered. Nothing without a cloud or before signing in; while the cloud can't be reached
     * the queue waits for the next.
     */
    fun sync(): Boolean = synchronized(lock) {
        val cloud = cloud ?: return false
        if (!signedIn()) return false
        val provider = provider()
        var state = store.historyState()?.takeIf { profileExists(it.profile) } ?: HistoryState(currentProfile(), 0, provider).also {
            // Signing in merges the device's history into the Account, the latest change winning.
            store.queueAllHistory(it.profile)
            store.saveHistoryState(it)
        }
        // On another Provider, the Listing-keyed history is another: everything is pulled again.
        if (state.provider != provider) state = state.copy(cursor = 0, provider = provider).also(store::saveHistoryState)
        store.forgetTombstones(Instant.now() - TOMBSTONES_KEPT_FOR)
        try {
            do {
                val queued = store.queuedHistory(state.profile, CloudClient.MAX_HISTORY_BATCH)
                // A Listing's progress names its Provider: it waits for a login.
                val pushed = queued.filter { it.id is TitleId.Tmdb || provider != null }
                val answer = cloud.sync(state.cursor, provider, pushed)
                store.stagePulledHistory(state.profile, answer.changes, answer.reset)
                store.unqueueHistory(state.profile, pushed)
                state = state.copy(cursor = answer.cursor)
                store.saveHistoryState(state)
            } while (queued.size == CloudClient.MAX_HISTORY_BATCH && pushed.isNotEmpty())
        } catch (e: CloudClient.NotSignedIn) {
            onSignedOut()
            return false
        } catch (e: CloudException) {
            return false
        }
        true
    }

    /** Lands the Account's changes pulled, as a held catalog lands (ADR 0011), and says whether the screens' history changed. */
    fun land(): Boolean {
        val profile = syncedProfile ?: return false
        if (!store.hasPulledHistory(profile)) return false
        return store.applyPulledHistory(profile)
    }

    /** Lands [id]'s pulled changes, so its page and Play continue from where another TV stopped. Says whether any did. */
    fun landTitle(profile: Long, id: TitleId): Boolean {
        if (profile != syncedProfile || !store.hasPulledHistory(profile, id)) return false
        return store.applyPulledHistory(profile, id)
    }

    /** Takes the synced Profile's history off the device, as signing out does: the catalog and the Provider login stay. */
    fun signedOut() = synchronized(lock) {
        val profile = syncedProfile ?: return
        store.forgetHistory(profile)
    }

    fun close() {
        pushes.shutdown()
        pushes.awaitTermination(CLOSE_WAIT_SECONDS, TimeUnit.SECONDS)
    }

    private companion object {
        /** The cloud keeps a removal as long (cloud/src/sync.ts). */
        val TOMBSTONES_KEPT_FOR: Duration = Duration.ofDays(30)
        const val CLOSE_WAIT_SECONDS = 5L
    }
}
