package dev.betteriptv.core

import kotlinx.coroutines.CancellationException
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
    private val prefixTable: PrefixTable,
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
     * Clutter and Sports replays are left out of the movie list, and Prefixes the Prefix table
     * doesn't know are reported in the finished Sync.
     */
    suspend fun sync(): SyncStatus = syncLock.withLock {
        withContext(Dispatchers.IO) {
            val last = _syncStatus.value.last
            _syncStatus.value = SyncStatus.Running(last)
            val started = System.nanoTime()
            _syncStatus.value = try {
                val client = XtreamClient(credentials.load() ?: throw ProviderException(WrongLogin), http)
                client.account()
                val unknownPrefixes = LinkedHashMap<String, UnknownPrefix>()
                store.replaceMovieListings(
                    fill = { staging ->
                        val categories = HashMap<String, String>()
                        client.movieCategories { id, name ->
                            categories[id] = name
                            staging.category(id, name)
                        }
                        client.movieListings { id, name, categoryId, added, tmdbId, image ->
                            val description = prefixTable.describe(name, categories[categoryId].orEmpty())
                            description.unknownPrefix?.let { prefix ->
                                unknownPrefixes.merge(prefix, UnknownPrefix(prefix, 1, name)) { seen, _ -> seen.copy(listingCount = seen.listingCount + 1) }
                            }
                            staging.movieListing(id, name, categoryId, added, tmdbId, image, hidden = description.hidden != null)
                        }
                    },
                    completed = { count ->
                        CompletedSync(
                            Instant.now(),
                            Duration.ofNanos(System.nanoTime() - started),
                            count,
                            unknownPrefixes.values.sortedWith(compareByDescending<UnknownPrefix> { it.listingCount }.thenBy { it.prefix }),
                        )
                    },
                )
                SyncStatus.Synced(store.lastSync()!!)
            } catch (e: ProviderException) {
                SyncStatus.Failed(e.problem, last)
            } catch (e: CancellationException) {
                _syncStatus.value = last?.let { SyncStatus.Synced(it) } ?: SyncStatus.NeverSynced
                throw e
            } catch (e: Exception) {
                // Anything else (a full disk, say) also leaves the previous catalog in place.
                SyncStatus.Failed(CatalogNotSaved(e.message ?: e.javaClass.simpleName), last)
            }
            _syncStatus.value
        }
    }

    /** How many Listings the movie list holds (Clutter and Sports replays aren't in it). */
    fun movieListingCount(): Int = store.movieListingCount()

    /** Movie Listings, newest first, from [offset] (0-based), with their Likely Version attributes. */
    fun movieListings(offset: Int, limit: Int): List<MovieListing> =
        store.movieListings(offset, limit) { name, category -> prefixTable.describe(name, category).attributes }

    /** How many Titles the movie list Matches its Listings to. */
    fun titleCount(): Int = store.titleCount()

    /**
     * Title cards, newest first (by each Title's newest Listing), from [offset] (0-based). Each card
     * shows its best Version in the Viewer's [contentLanguages] (BCP 47 tags, `en` matching `en-GB`),
     * or the best one overall when none is in them.
     */
    fun titles(offset: Int, limit: Int, contentLanguages: List<String>): List<TitleSummary> =
        store.titles(offset, limit).map { title ->
            val (best, name, image) = shown(candidates(title), contentLanguages)
            TitleSummary(title.id, name.name, name.year, image, best.attributes)
        }

    /** A Title with its Versions grouped by language, the Viewer's [contentLanguages] first; null when the catalog doesn't have it. */
    fun title(id: TitleId, contentLanguages: List<String>): TitleDetails? {
        val title = store.title(id) ?: return null
        val candidates = candidates(title)
        val (_, name, image) = shown(candidates, contentLanguages)
        val (languages, otherLanguages) = groupByLanguage(candidates, contentLanguages)
        return TitleDetails(id, name.name, name.year, image, languages, otherLanguages)
    }

    /** What a Title shows until cloud metadata arrives: its best Version's cleaned name, and the Provider's image from the best Version that has one. */
    private fun shown(candidates: List<Candidate>, contentLanguages: List<String>): Triple<Candidate, PrefixTable.CleanName, String?> {
        val ranked = preferred(candidates, contentLanguages)
        return Triple(ranked.first(), prefixTable.cleanName(ranked.first().listing.name), ranked.firstNotNullOfOrNull { it.listing.image })
    }

    private fun candidates(title: StoredTitle) = title.listings.map { Candidate(it, prefixTable.describe(it.name, it.category).attributes) }

    override fun close() = store.close()

    companion object {
        /** Opens the catalog at [databasePath], creating it if needed. */
        fun open(databasePath: String, credentials: CredentialStore, http: OkHttpClient = defaultHttp()): Library =
            Library(CatalogStore(databasePath), credentials, http, PrefixTable.bundled())

        private fun defaultHttp() = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}
