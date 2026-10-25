package dev.betteriptv.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
 * Plain reads are blocking and cheap; call them off the main thread. Reads that may need Title
 * metadata from the cloud suspend.
 */
class Library private constructor(
    private val store: CatalogStore,
    private val credentials: CredentialStore,
    private val http: OkHttpClient,
    private val prefixTable: PrefixTable,
    private val cloud: CloudClient?,
) : Closeable {
    /** After the cloud fails, it isn't asked again until then, so pages don't each wait out a timeout. */
    @Volatile private var cloudRetryAt = Instant.MIN

    private val syncLock = Mutex()
    private val connection = ConnectionGate()
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
     * Downloads the Provider's movie and show Categories and Listings and replaces the catalog
     * atomically. Only `player_api` requests are made, never a stream, so a Sync never takes the
     * Viewer's connection. Clutter and Sports replays are left out of the lists, and Prefixes the
     * Prefix table doesn't know are reported in the finished Sync.
     *
     * Listings are Matched by their Provider's TMDB ID, except suspected Mismatches and Listings
     * without one, which are Matched by what the cloud answered about them before. Once the new
     * catalog shows, the cloud is asked about the rest, and the Titles are regrouped by its answers
     * before this returns (the status shows the Sync finished in the meantime).
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
                store.replaceListings(
                    fill = { staging ->
                        val movieCategories = HashMap<String, String>()
                        val showCategories = HashMap<String, String>()
                        val listing = { kind: TitleKind, id: Long, name: String, category: String, categoryId: String?, added: Long?, tmdbId: Long?, image: String?, container: String? ->
                            val description = prefixTable.describe(name, category)
                            description.unknownPrefix?.let { prefix ->
                                unknownPrefixes.merge(prefix, UnknownPrefix(prefix, 1, name)) { seen, _ -> seen.copy(listingCount = seen.listingCount + 1) }
                            }
                            staging.listing(id, name, categoryId, added, image, hidden = description.hidden != null, question(kind, name, description, tmdbId), container)
                        }
                        client.movieCategories { id, name ->
                            movieCategories[id] = name
                            staging.category(TitleKind.Movie, id, name)
                        }
                        client.movieListings { id, name, categoryId, added, tmdbId, image, container ->
                            listing(TitleKind.Movie, id, name, movieCategories[categoryId].orEmpty(), categoryId, added, tmdbId, image, container)
                        }
                        client.showCategories { id, name ->
                            showCategories[id] = name
                            staging.category(TitleKind.Show, id, name)
                        }
                        client.showListings { id, name, categoryId, modified, tmdbId, image ->
                            listing(TitleKind.Show, id, name, showCategories[categoryId].orEmpty(), categoryId, modified, tmdbId, image, null)
                        }
                    },
                    completed = { movieCount, showCount, rematched ->
                        CompletedSync(
                            Instant.now(),
                            Duration.ofNanos(System.nanoTime() - started),
                            movieCount,
                            unknownPrefixes.values.sortedWith(compareByDescending<UnknownPrefix> { it.listingCount }.thenBy { it.prefix }),
                            rematched,
                            showCount,
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
            if (_syncStatus.value is SyncStatus.Synced) matchByName()
            _syncStatus.value
        }
    }

    /** What the cloud is asked about a Listing of [kind] to Match it. */
    private fun question(kind: TitleKind, name: String, description: PrefixTable.Description, tmdbId: Long?): MatchQuestion {
        val cleaned = prefixTable.cleanName(name)
        return MatchQuestion(kind, cleaned.name, cleaned.year, description.attributes.language?.value?.let(::primaryLanguage), tmdbId)
    }

    /**
     * Asks the cloud about the stored catalog's suspects it hasn't answered for lately, and regroups
     * the Titles by its answers. Without the cloud, the Provider's IDs stand until the next Sync.
     */
    private fun matchByName() {
        if (cloud == null || Instant.now().isBefore(cloudRetryAt)) return
        try {
            val now = Instant.now()
            val questions = store.unansweredSuspects(now - MATCH_FRESH_FOR, now - MATCH_NOT_FOUND_FRESH_FOR)
                .map { question(it.kind, it.name, prefixTable.describe(it.name, it.category), it.providerTmdbId) }
                .distinctBy { it.key }
            if (questions.isEmpty()) return
            try {
                questions.chunked(CloudClient.MAX_MATCH_BATCH).forEach { batch ->
                    store.saveMatchAnswers(cloud.match(batch).mapKeys { it.key.key }, now)
                }
            } catch (e: CloudException) {
                cloudRetryAt = now + CLOUD_RETRY_AFTER
            }
            store.applyMatchAnswers()?.let { _syncStatus.value = SyncStatus.Synced(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The Sync itself finished: its catalog shows, Matched by the Provider's IDs.
        }
    }

    /** How many Listings the movie list holds (Clutter and Sports replays aren't in it). */
    fun movieListingCount(): Int = store.movieListingCount()

    /** Movie Listings, newest first, from [offset] (0-based), with their Likely Version attributes. */
    fun movieListings(offset: Int, limit: Int): List<MovieListing> =
        store.movieListings(offset, limit) { name, category -> prefixTable.describe(name, category).attributes }

    /** How many Titles the movie or show list Matches its Listings to. */
    fun titleCount(kind: TitleKind = TitleKind.Movie): Int = store.titleCount(kind)

    /**
     * Movie or show cards, newest first (by each Title's newest Listing; a Show's is the one the
     * Provider changed last), from [offset] (0-based). Each card shows its best Version in the
     * Viewer's [contentLanguages] (BCP 47 tags, `en` matching `en-GB`), or the best one overall when
     * none is in them, and TMDB's name and poster in the [appLanguage].
     */
    suspend fun titles(offset: Int, limit: Int, contentLanguages: List<String>, appLanguage: String, kind: TitleKind = TitleKind.Movie): List<TitleSummary> = withContext(Dispatchers.IO) {
        val titles = store.titles(kind, offset, limit)
        val metadata = metadata(titles.map { it.id }, appLanguage)
        titles.map { title ->
            val shown = shown(candidates(title), contentLanguages, metadata[title.id], appLanguage)
            TitleSummary(title.id, shown.name, shown.year, shown.image, shown.best.attributes)
        }
    }

    /** A Title with its Versions grouped by language, the Viewer's [contentLanguages] first; null when the catalog doesn't have it. */
    suspend fun title(id: TitleId, contentLanguages: List<String>, appLanguage: String): TitleDetails? = withContext(Dispatchers.IO) {
        val title = store.title(id) ?: return@withContext null
        val candidates = candidates(title)
        val metadata = metadata(listOf(id), appLanguage)[id]
        val shown = shown(candidates, contentLanguages, metadata, appLanguage)
        val (languages, otherLanguages) = groupByLanguage(candidates, contentLanguages)
        TitleDetails(
            id, shown.name, shown.year, shown.image, languages, otherLanguages,
            description = metadata?.description(appLanguage),
            genres = metadata?.genres.orEmpty(),
            runtimeMinutes = metadata?.runtimeMinutes,
            backdrop = metadata?.backdrop,
            cast = metadata?.cast.orEmpty(),
            fromTmdb = metadata != null,
        )
    }

    /**
     * A Show's Episodes, from the Provider's show details of each of its Versions (API calls, a
     * few at a time, never a stream), merged by season and episode number. Details are kept on the
     * device and asked again only once the Provider changes that Version's Show; when it can't be
     * reached, the details kept from before stand. Null for a Movie, or a Show the catalog doesn't have.
     */
    suspend fun episodes(id: TitleId, contentLanguages: List<String>): ShowEpisodes? = withContext(Dispatchers.IO) {
        if (id.kind != TitleKind.Show) return@withContext null
        val title = store.title(id) ?: return@withContext null
        val kept = store.showDetails(title.listings.map { it.id })
        val now = Instant.now()
        val read = kept.mapValuesTo(HashMap()) { it.value.episodes }
        // A Show the Provider gives no change time for is asked again daily.
        val stale = title.listings.filter { listing ->
            val details = kept[listing.id]
            details == null || details.modified != listing.added || (listing.added == null && details.fetchedAt.isBefore(now - SHOW_DETAILS_FRESH_FOR))
        }
        val login = credentials.load()
        if (stale.isNotEmpty() && login != null) {
            val client = XtreamClient(login, http)
            val gate = Semaphore(SHOW_DETAILS_AT_ONCE)
            coroutineScope {
                stale.map { listing ->
                    async {
                        gate.withPermit {
                            try {
                                listing to client.showDetails(listing.id)
                            } catch (e: ProviderException) {
                                null
                            }
                        }
                    }
                }.awaitAll()
            }.filterNotNull().forEach { (listing, episodes) ->
                store.saveShowDetails(listing.id, listing.added, episodes, now)
                read[listing.id] = episodes
            }
        }
        ShowEpisodes(
            mergeEpisodes(title.listings.filter { it.id in read }.map { candidates(it) to read.getValue(it.id) }, contentLanguages),
            unreadVersions = title.listings.count { it.id !in read },
        )
    }

    /**
     * What pressing Play on a Movie starts: the Best playable Version for the Viewer's
     * [contentLanguages] (BCP 47 tags, in order of preference) and [device], with its Backups. Null
     * for a Show, when the catalog doesn't have the Title, or when it has no Version the device can
     * display other than Audio-description ones, which are never picked automatically.
     */
    suspend fun playPlan(id: TitleId, contentLanguages: List<String>, device: DeviceProfile): PlayPlan? = withContext(Dispatchers.IO) {
        if (id.kind != TitleKind.Movie) return@withContext null
        val title = store.title(id) ?: return@withContext null
        val ranked = playable(candidates(title), contentLanguages, device)
        ranked.firstOrNull()?.let { PlayPlan(id, withBackups(it, ranked), contentLanguages) }
    }

    /** What playing the Version from Listing [listingId], chosen by the Viewer, starts; null for a Show or when the Title doesn't have it. */
    suspend fun chosenPlayPlan(id: TitleId, listingId: Long, contentLanguages: List<String>): PlayPlan? = withContext(Dispatchers.IO) {
        if (id.kind != TitleKind.Movie) return@withContext null
        val candidates = store.title(id)?.let(::candidates) ?: return@withContext null
        candidates.find { it.listing.id == listingId }?.let { PlayPlan(id, withBackups(it, candidates), contentLanguages) }
    }

    /**
     * Takes the Viewer's connection to play [version], waiting until the [Connection] holding it is
     * closed: a second stream is never opened while one is in use. Close it when playback ends.
     */
    suspend fun connect(version: Version): Connection {
        val location = withContext(Dispatchers.IO) {
            val login = credentials.load() ?: throw ProviderException(WrongLogin)
            // Most of the surveyed Provider's movies are MKV files.
            XtreamClient(login, http).movieLocation(version.listingId, store.container(version.listingId) ?: "mkv")
        }
        return Connection(location, connection.lease())
    }

    private class Shown(val best: Candidate, val name: String, val year: Int?, val image: String?)

    /**
     * What a Title's card and header show: TMDB's name, year and poster, or without them the best
     * Version's cleaned name and the Provider's image from the best Version that has one.
     */
    private fun shown(candidates: List<Candidate>, contentLanguages: List<String>, metadata: TitleMetadata?, appLanguage: String): Shown {
        val ranked = preferred(candidates, contentLanguages)
        val cleaned = prefixTable.cleanName(ranked.first().listing.name)
        return Shown(
            ranked.first(),
            metadata?.name(appLanguage) ?: cleaned.name,
            metadata?.year ?: cleaned.year,
            metadata?.poster ?: ranked.firstNotNullOfOrNull { it.listing.image },
        )
    }

    /**
     * The TMDB metadata of [ids] in [appLanguage]'s language, from the device's cache, asking the
     * cloud for what it lacks or has kept for over a week. Without the cloud the cache is all there is.
     */
    private fun metadata(ids: List<TitleId>, appLanguage: String): Map<TitleId, TitleMetadata> {
        val language = primaryLanguage(appLanguage)
        val tmdbIds = ids.filterIsInstance<TitleId.Tmdb>()
        val cached = store.metadata(tmdbIds, language).toMutableMap()
        val now = Instant.now()
        val wanted = tmdbIds.filter { cached[it]?.fetchedAt?.isAfter(now - METADATA_FRESH_FOR) != true }
        if (cloud != null && wanted.isNotEmpty() && now.isAfter(cloudRetryAt)) {
            try {
                wanted.chunked(CloudClient.MAX_BATCH).forEach { batch ->
                    val answer = cloud.metadata(language, batch)
                    store.saveMetadata(language, answer.found, answer.notFound, now)
                    answer.found.forEach { (id, body) -> cached[id] = StoredMetadata(body, now) }
                    answer.notFound.forEach { cached[it] = StoredMetadata(null, now) }
                }
            } catch (e: CloudException) {
                // The cards show what the Provider says until the cloud is back.
                cloudRetryAt = now + CLOUD_RETRY_AFTER
            }
        }
        return buildMap { cached.forEach { (id, stored) -> stored.body?.let { put(id, TitleMetadata(it)) } } }
    }

    private fun candidates(title: StoredTitle) = title.listings.map(::candidates)

    private fun candidates(listing: StoredListing) = Candidate(listing, prefixTable.describe(listing.name, listing.category).attributes)

    override fun close() = store.close()

    companion object {
        private val METADATA_FRESH_FOR: Duration = Duration.ofDays(7)
        private val CLOUD_RETRY_AFTER: Duration = Duration.ofMinutes(1)
        /** The cloud's answers about a Listing are asked again after this, so new Titles on TMDB come through. */
        private val MATCH_FRESH_FOR: Duration = Duration.ofDays(30)
        /** A name TMDB found nothing for is asked about again sooner, but not every daily Sync: few Titles are added in a week. */
        private val MATCH_NOT_FOUND_FRESH_FOR: Duration = Duration.ofDays(7)
        private val SHOW_DETAILS_FRESH_FOR: Duration = Duration.ofDays(1)
        /** How many show details are asked of the Provider at once: GoT alone has 21 Versions. */
        private const val SHOW_DETAILS_AT_ONCE = 4

        /**
         * Opens the catalog at [databasePath], creating it if needed. Title metadata comes from the
         * cloud at [cloudAddress]; without one, Titles show what the Provider says about them.
         */
        fun open(databasePath: String, credentials: CredentialStore, cloudAddress: String? = null, http: OkHttpClient = defaultHttp()): Library {
            val store = CatalogStore(databasePath)
            return Library(store, credentials, http, PrefixTable.bundled(), cloudAddress?.let { CloudClient(it, http, store) })
        }

        private fun defaultHttp() = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}
