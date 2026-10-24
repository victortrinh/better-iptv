package dev.betteriptv.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.Closeable
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * The core's public interface: everything the screens show comes from here.
 *
 * Reads are blocking and cheap; call them off the main thread.
 */
class Library private constructor(
    private val store: CatalogStore,
    private val credentials: CredentialStore,
    private val http: OkHttpClient,
) : Closeable {
    private val syncLock = Mutex()
    private val _syncStatus = MutableStateFlow(store.lastSync()?.let { SyncStatus.Synced(it) } ?: SyncStatus.NeverSynced)
    val syncStatus: StateFlow<SyncStatus> = _syncStatus.asStateFlow()

    val isLoggedIn: Boolean get() = credentials.load() != null

    /** Checks [login] with the Provider and keeps it only if the Provider accepts it. */
    suspend fun logIn(login: ProviderLogin): LoginResult = withContext(Dispatchers.IO) {
        try {
            XtreamClient(login, http).account().also { credentials.save(login) }
        } catch (e: ProviderException) {
            e.problem
        }
    }

    /**
     * Downloads the Provider's movie Categories and Listings and replaces the catalog atomically.
     * Only `player_api` requests are made, never a stream, so a Sync never takes the Viewer's connection.
     */
    suspend fun sync(): SyncStatus = syncLock.withLock {
        withContext(Dispatchers.IO) {
            val last = _syncStatus.value.last
            _syncStatus.value = SyncStatus.Running(last)
            val started = System.nanoTime()
            _syncStatus.value = try {
                val client = XtreamClient(credentials.load() ?: throw ProviderException(WrongLogin), http)
                client.account()
                store.replaceMovies(
                    fill = { staging ->
                        client.api("get_vod_categories") { json ->
                            json.eachObject {
                                var id: String? = null
                                var name: String? = null
                                while (hasNext()) when (nextName()) {
                                    "category_id" -> id = nextScalar()
                                    "category_name" -> name = nextScalar()
                                    else -> skipValue()
                                }
                                if (id != null) staging.category(id, name.orEmpty())
                            }
                        }
                        client.api("get_vod_streams") { json ->
                            json.eachObject {
                                var id: Long? = null
                                var name: String? = null
                                var category: String? = null
                                var added: Long? = null
                                while (hasNext()) when (nextName()) {
                                    "stream_id" -> id = nextScalar()?.toLongOrNull()
                                    "name" -> name = nextScalar()
                                    "category_id" -> category = nextScalar()
                                    "added" -> added = nextScalar()?.toLongOrNull()
                                    else -> skipValue()
                                }
                                if (id != null && name != null) staging.movie(id, name, category, added)
                            }
                        }
                    },
                    meta = { count ->
                        val completed = CompletedSync(Instant.now(), Duration.ofNanos(System.nanoTime() - started), count)
                        mapOf(
                            LAST_SYNC_AT to completed.finishedAt.toString(),
                            LAST_SYNC_MILLIS to completed.duration.toMillis().toString(),
                            LAST_SYNC_MOVIES to completed.movieCount.toString(),
                        )
                    },
                )
                SyncStatus.Synced(store.lastSync()!!)
            } catch (e: ProviderException) {
                SyncStatus.Failed(e.problem, last)
            }
            _syncStatus.value
        }
    }

    /** How many movie Listings the catalog holds. */
    fun movieCount(): Int = store.movieCount()

    /** Movie Listings, newest first, from [offset] (0-based). */
    fun movies(offset: Int, limit: Int): List<MovieListing> = store.movies(offset, limit)

    override fun close() = store.close()

    companion object {
        private const val LAST_SYNC_AT = "last_sync_at"
        private const val LAST_SYNC_MILLIS = "last_sync_millis"
        private const val LAST_SYNC_MOVIES = "last_sync_movies"

        /** Opens the catalog at [databasePath], creating it if needed. */
        fun open(databasePath: String, credentials: CredentialStore, http: OkHttpClient = defaultHttp()): Library =
            Library(CatalogStore(databasePath), credentials, http)

        private fun defaultHttp() = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()

        private fun CatalogStore.lastSync(): CompletedSync? {
            val at = meta(LAST_SYNC_AT) ?: return null
            return CompletedSync(
                Instant.parse(at),
                Duration.ofMillis(meta(LAST_SYNC_MILLIS)!!.toLong()),
                meta(LAST_SYNC_MOVIES)!!.toInt(),
            )
        }
    }
}
