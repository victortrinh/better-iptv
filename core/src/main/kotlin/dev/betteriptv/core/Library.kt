package dev.betteriptv.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.Closeable
import java.io.IOException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.SortedMap
import java.util.TreeMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

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
    private val searchLock = Any()
    /** Bumped when a Sync or Matches change the catalog: the search index is rebuilt before the next search. */
    private val catalogGeneration = AtomicInteger()
    /** Bumped when metadata is cached: the index is rebuilt in the background, the old one serving meanwhile. */
    private val metadataGeneration = AtomicInteger()
    private val rebuildingSearch = AtomicBoolean()
    @Volatile private var searchIndex: BuiltIndex? = null
    private val connection = ConnectionGate()
    private val headerReader = HeaderReader(http)
    /** One Header read at a time, a details page's or Background reading's. */
    private val reads = Mutex()
    /** Details pages reading right now: Background reading waits for them. */
    private val detailsReading = AtomicInteger()
    @Volatile private var cardsOnScreen: List<TitleId> = emptyList()
    private val _confirmations = MutableSharedFlow<TitleId>(extraBufferCapacity = 64)

    /** The Titles whose card's Version Background reading just Confirmed, so their badges update. */
    val confirmations: SharedFlow<TitleId> = _confirmations.asSharedFlow()
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
            if (_syncStatus.value is SyncStatus.Synced) {
                catalogGeneration.incrementAndGet()
                matchByName()
                catalogGeneration.incrementAndGet()
            }
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
            // A batch can fail on its own (the cloud ran out of CPU time, say): its suspects are asked
            // again next Sync. Several in a row mean the cloud is down.
            var failedInARow = 0
            for (batch in questions.chunked(CloudClient.MAX_MATCH_BATCH)) {
                try {
                    store.saveMatchAnswers(cloud.match(batch).mapKeys { it.key.key }, now)
                    failedInARow = 0
                } catch (e: CloudException) {
                    if (++failedInARow == MATCH_FAILURES_IN_A_ROW) {
                        cloudRetryAt = now + CLOUD_RETRY_AFTER
                        break
                    }
                }
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

    /**
     * The languages the catalog has Versions in, as primary language subtags (`fr` for `fr-CA` too),
     * most Listings first: the choices for the Viewer's Content languages. Untagged Listings aren't counted.
     */
    suspend fun catalogLanguages(): List<String> = withContext(Dispatchers.IO) {
        val counts = HashMap<String, Int>()
        store.forEachListing { name, category ->
            prefixTable.describe(name, category).attributes.language?.value?.let { counts.merge(primaryLanguage(it), 1, Int::plus) }
        }
        counts.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).map { it.key }
    }

    /** How many Titles the movie or show list Matches its Listings to. */
    fun titleCount(kind: TitleKind = TitleKind.Movie): Int = store.titleCount(kind)

    /**
     * Movie or show cards, newest first (by each Title's newest Listing; a Show's is the one the
     * Provider changed last), from [offset] (0-based). Each card shows its best Version in the
     * Viewer's [contentLanguages] (BCP 47 tags, `en` matching `en-GB`), or the best one overall when
     * none is in them, and TMDB's name and poster in the [appLanguage].
     */
    suspend fun titles(offset: Int, limit: Int, contentLanguages: List<String>, appLanguage: String, kind: TitleKind = TitleKind.Movie): List<TitleSummary> = withContext(Dispatchers.IO) {
        summaries(store.titles(kind, offset, limit), contentLanguages, appLanguage)
    }

    /**
     * Movies, Shows and people whose names match [query], once it has two letters. Titles are found
     * by any Listing name of their Versions and any name TMDB has for them, people by name; accents
     * and case don't matter, and small typos are tolerated. Titles with Versions in the Viewer's
     * [contentLanguages] come first, then closer matches, then TMDB's most popular. A person's
     * Titles are those the Provider has. Cards show TMDB's name and poster in the [appLanguage].
     *
     * The first search after a Sync builds an index of the whole catalog; [prepareSearch] builds it ahead.
     */
    suspend fun search(query: String, contentLanguages: List<String>, appLanguage: String): SearchResults = withContext(Dispatchers.IO) {
        if (SearchIndex.words(query).sumOf { it.length } < SearchIndex.MIN_LETTERS) return@withContext SearchResults.NONE
        val index = searchIndex()
        val wanted = contentLanguages.map(::primaryLanguage).toSet()
        val byRank = compareBy<SearchIndex.Hit>(
            { index.titles[it.index].languages.none(wanted::contains) },
            { it.distance },
            { -index.titles[it.index].popularity },
            { it.index },
        )
        val titles = index.findTitles(query).sortedWith(byRank)
        val people = index.findPeople(query)
            .sortedWith(compareBy<SearchIndex.Hit>({ it.distance }, { person -> -index.personTitles[person.index].maxOf { index.titles[it].popularity } }))
            .take(PEOPLE_FOUND)
            .map { person -> index.people[person.index] to index.personTitles[person.index].map { SearchIndex.Hit(it, 0) }.sortedWith(byRank).take(TITLES_FOUND) }
        val shown = (TitleKind.entries.flatMap { kind -> titles.filter { index.titles[it.index].id.kind == kind }.take(TITLES_FOUND) } + people.flatMap { it.second })
            .map { index.titles[it.index].id }.distinct()
        val summaries = summaries(shown.mapNotNull(store::title), contentLanguages, appLanguage).associateBy { it.id }
        val found = { hits: List<SearchIndex.Hit> -> hits.mapNotNull { summaries[index.titles[it.index].id] } }
        SearchResults(
            movies = found(titles.filter { index.titles[it.index].id.kind == TitleKind.Movie }.take(TITLES_FOUND)),
            shows = found(titles.filter { index.titles[it.index].id.kind == TitleKind.Show }.take(TITLES_FOUND)),
            people = people.map { (name, hits) -> Person(name, found(hits)) },
        )
    }

    /** Builds the search index now, so the Viewer's first letters don't wait for it. */
    suspend fun prepareSearch() {
        withContext(Dispatchers.IO) { rebuildSearchIndex() }
    }

    private class BuiltIndex(val catalog: Int, val metadata: Int, val index: SearchIndex)

    /**
     * The search index of the current catalog. Metadata cached since it was built (often for the
     * results of the last search) only starts a rebuild in the background, so typing never waits on one.
     */
    private fun searchIndex(): SearchIndex {
        val built = searchIndex ?: return rebuildSearchIndex()
        if (built.catalog != catalogGeneration.get()) return rebuildSearchIndex()
        if (built.metadata != metadataGeneration.get() && rebuildingSearch.compareAndSet(false, true)) {
            thread(name = "search-index", isDaemon = true) {
                try {
                    rebuildSearchIndex()
                } catch (e: Exception) {
                    // The Library closed meanwhile; the next search tries again.
                } finally {
                    rebuildingSearch.set(false)
                }
            }
        }
        return built.index
    }

    private fun rebuildSearchIndex(): SearchIndex = synchronized(searchLock) {
        val catalog = catalogGeneration.get()
        val metadata = metadataGeneration.get()
        searchIndex?.takeIf { it.catalog == catalog && it.metadata == metadata }?.index
            ?: buildSearchIndex().also { searchIndex = BuiltIndex(catalog, metadata, it) }
    }

    /** Indexes every Title by its Versions' cleaned names and the metadata cached for it, in any language. */
    private fun buildSearchIndex(): SearchIndex {
        class Known(val names: List<String>, val cast: List<String>, val popularity: Double)
        val known = HashMap<TitleId, Known>()
        store.forEachMetadata { id, body -> TitleMetadata(body).let { known[id] = Known(it.searchNames, it.cast, it.popularity) } }
        val builder = SearchIndex.Builder()
        for (kind in TitleKind.entries) {
            store.forEachTitle(kind) { id, listings ->
                val metadata = known[id]
                val names = listings.mapTo(HashSet()) { prefixTable.cleanName(it.first).name } + metadata?.names.orEmpty()
                val languages = listings.mapNotNullTo(HashSet()) { (name, category) -> prefixTable.describe(name, category).attributes.language?.value?.let(::primaryLanguage) }
                builder.title(id, names, languages, metadata?.popularity ?: 0.0, metadata?.cast.orEmpty())
            }
        }
        return builder.build()
    }

    /** [titles]' cards: TMDB's name and poster in the [appLanguage], and the best Version in the Viewer's [contentLanguages]. */
    private fun summaries(titles: List<StoredTitle>, contentLanguages: List<String>, appLanguage: String): List<TitleSummary> {
        val metadata = metadata(titles.map { it.id }, appLanguage)
        return titles.map { title ->
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
     * [contentLanguages] (BCP 47 tags, in order of preference) and [device] within the Viewer's
     * [qualityCap], with its Backups. Null for a Show, when the catalog doesn't have the Title, or
     * when it has no Version the device can display other than Audio-description ones, which are
     * never picked automatically. A Title with nothing within the cap plays its lowest quality above
     * it. It starts where the Viewer stopped last time, unless they finished the Title.
     */
    suspend fun playPlan(id: TitleId, contentLanguages: List<String>, device: DeviceProfile, qualityCap: QualityCap = QualityCap.UHD): PlayPlan? = withContext(Dispatchers.IO) {
        if (id.kind != TitleKind.Movie) return@withContext null
        val title = store.title(id) ?: return@withContext null
        val remembered = rememberedTracks()
        val ranked = playable(candidates(title), rankingLanguages(remembered, contentLanguages), device, qualityCap)
        ranked.firstOrNull()?.let { PlayPlan(id, withBackups(it, ranked), contentLanguages, leadersWithBackups(ranked), resumeFrom(id), remembered = remembered) }
    }

    /**
     * What playing a Show's [episode] starts: its Best playable Version, chosen as a Movie's is, from
     * the Episodes [episodes] read before; it starts from the beginning. Null for a Movie, or when the
     * Show has no such Episode or no Version of it the device can display.
     */
    suspend fun episodePlayPlan(id: TitleId, episode: EpisodeNumber, contentLanguages: List<String>, device: DeviceProfile, qualityCap: QualityCap = QualityCap.UHD): PlayPlan? =
        withContext(Dispatchers.IO) { episodePlans(id, contentLanguages, device, qualityCap)?.get(episode)?.invoke() }

    /**
     * What to offer once [plan]'s Episode ends: the Show's next Episode the device can play, the next
     * season's first after a season's last, and specials after the last season. Null for a Movie, or
     * after the Show's last Episode.
     */
    suspend fun nextEpisodePlan(plan: PlayPlan, device: DeviceProfile, qualityCap: QualityCap = QualityCap.UHD): PlayPlan? = withContext(Dispatchers.IO) {
        val playing = plan.episode ?: return@withContext null
        val plans = episodePlans(plan.titleId, plan.contentLanguages, device, qualityCap) ?: return@withContext null
        plans.tailMap(playing).entries.asSequence().filter { it.key != playing }.firstNotNullOfOrNull { it.value() }
    }

    /** A Show's Episodes, in order, each with what playing it starts, from the show details kept on the device. */
    private fun episodePlans(id: TitleId, contentLanguages: List<String>, device: DeviceProfile, qualityCap: QualityCap): SortedMap<EpisodeNumber, () -> PlayPlan?>? {
        if (id.kind != TitleKind.Show) return null
        val title = store.title(id) ?: return null
        val kept = store.showDetails(title.listings.map { it.id })
        val remembered = rememberedTracks()
        val languages = rankingLanguages(remembered, contentLanguages)
        val byEpisode = episodeCandidates(title.listings.filter { it.id in kept }.map { candidates(it) to kept.getValue(it.id).episodes })
        val plans: SortedMap<EpisodeNumber, () -> PlayPlan?> = TreeMap(byEpisode.comparator())
        byEpisode.forEach { (episode, candidates) ->
            plans[episode] = {
                val ranked = playable(candidates, languages, device, qualityCap)
                ranked.firstOrNull()?.let { PlayPlan(id, withBackups(it, ranked), contentLanguages, leadersWithBackups(ranked), episode = episode, remembered = remembered) }
            }
        }
        return plans
    }

    /** The audio and subtitle languages the Viewer last picked in the player. */
    fun rememberedTracks(): RememberedTracks = remembered ?: run {
        val subtitles = store.rememberedTrack(SUBTITLES)
        RememberedTracks(store.rememberedTrack(AUDIO), subtitles?.takeIf { it != SUBTITLES_OFF }, subtitlesOff = subtitles == SUBTITLES_OFF).also { remembered = it }
    }

    /** Remembers that the Viewer picked audio in [language] (a BCP 47 tag), so it plays next time, and its Versions rank first. */
    suspend fun rememberAudio(language: String) = withContext(Dispatchers.IO) {
        store.rememberTrack(AUDIO, language)
        remembered = rememberedTracks().copy(audio = language)
    }

    /** Remembers that the Viewer picked subtitles in [language], or turned them off when null, so the next file starts the same. */
    suspend fun rememberSubtitles(language: String?) = withContext(Dispatchers.IO) {
        store.rememberTrack(SUBTITLES, language ?: SUBTITLES_OFF)
        remembered = rememberedTracks().copy(subtitles = language, subtitlesOff = language == null)
    }

    @Volatile private var remembered: RememberedTracks? = null

    /**
     * What playing the Version from Listing [listingId], chosen by the Viewer, starts, from where they
     * stopped last time; null for a Show or when the Title doesn't have it.
     */
    suspend fun chosenPlayPlan(id: TitleId, listingId: Long, contentLanguages: List<String>): PlayPlan? = withContext(Dispatchers.IO) {
        if (id.kind != TitleKind.Movie) return@withContext null
        val candidates = store.title(id)?.let(::candidates) ?: return@withContext null
        candidates.find { it.listing.id == listingId }?.let { chosen ->
            val version = withBackups(chosen, candidates)
            val remembered = rememberedTracks()
            // The chosen Version leads the Versions to switch to, whether or not it would have led its Backups.
            val others = leadersWithBackups(preferred(candidates, rankingLanguages(remembered, contentLanguages)).filter { it.equivalence != chosen.equivalence })
            PlayPlan(id, version, contentLanguages, listOf(version) + others, resumeFrom(id), remembered = remembered)
        }
    }

    /** Where Play starts in [id]: where the Viewer stopped, or the start once they finished it. */
    private fun resumeFrom(id: TitleId): Long = store.progress(id)?.takeUnless { it.watched }?.position ?: 0

    /**
     * Records that the Viewer is at [position] of [duration] (milliseconds, null when the player
     * doesn't know it) in [id]. In the last few minutes the Title counts as watched, and under a minute
     * in nothing is recorded, so a Title opened by mistake doesn't land in Continue watching.
     */
    internal fun saveProgress(id: TitleId, position: Long, duration: Long?) {
        val watched = duration != null && position >= duration - minOf((duration * WATCHED_SHARE_LEFT).toLong(), WATCHED_TIME_LEFT)
        if (!watched && position < MIN_RESUME_POSITION) return
        store.saveProgress(id, if (watched) 0 else position, duration, watched, Instant.now())
    }

    /** Records that the Viewer played [id] to its end. */
    internal fun finished(id: TitleId) = store.saveProgress(id, 0, null, watched = true, Instant.now())

    /**
     * The home screen's rows: Continue watching, then New on your service (Titles among the most
     * recently added, most popular on TMDB first), then one row per TMDB genre, its most common
     * first. Browse rows show only Titles with a Version in the Viewer's [contentLanguages], or an
     * Untagged one Likely in one; Continue watching shows whatever the Viewer started. Genres come
     * from the TMDB metadata of the newest Titles, so they need the cloud once a week. Rows with no
     * Titles are left out.
     */
    suspend fun homeRows(contentLanguages: List<String>, appLanguage: String): List<HomeRow> = withContext(Dispatchers.IO) {
        val resumes = store.inProgress(ROW_SIZE).mapNotNull { progress -> store.title(progress.id)?.let { progress to it } }
        val movies = store.titles(TitleKind.Movie, 0, HOME_MOVIES)
        val shows = store.titles(TitleKind.Show, 0, HOME_SHOWS)
        val metadata = metadata((resumes.map { it.second } + movies + shows).map { it.id }.distinct(), appLanguage)
        fun card(title: StoredTitle, candidates: List<Candidate> = candidates(title)): TitleSummary {
            val shown = shown(candidates, contentLanguages, metadata[title.id], appLanguage)
            return TitleSummary(title.id, shown.name, shown.year, shown.image, shown.best.attributes)
        }
        val popularFirst = compareByDescending<StoredTitle> { metadata[it.id]?.popularity ?: 0.0 }
        // The newest movies, then the newest shows, in the Viewer's languages.
        val browsable = listOf(movies, shows).map { titles ->
            titles.filter { inContentLanguages(candidates(it), contentLanguages, metadata[it.id]?.originalLanguage) }
        }
        val newest = (browsable[0].take(NEW_MOVIES) + browsable[1].take(NEW_SHOWS)).sortedWith(popularFirst)
        val byGenre = LinkedHashMap<String, MutableList<StoredTitle>>()
        browsable.flatten().forEach { title -> metadata[title.id]?.genres?.forEach { byGenre.getOrPut(it) { mutableListOf() } += title } }
        val genres = byGenre.entries.filter { it.value.size >= MIN_GENRE_SIZE }.sortedByDescending { it.value.size }.take(GENRE_ROWS)
        buildList {
            if (resumes.isNotEmpty()) {
                add(
                    HomeRow.ContinueWatching(
                        resumes.map { (progress, title) ->
                            val fraction = progress.duration?.takeIf { it > 0 }?.let { (progress.position.toFloat() / it).coerceIn(0f, 1f) }
                            InProgress(card(title), metadata[title.id]?.backdrop, fraction)
                        },
                    ),
                )
            }
            if (newest.isNotEmpty()) add(HomeRow.NewOnYourService(newest.take(ROW_SIZE).map { card(it) }))
            genres.forEach { (name, titles) -> add(HomeRow.Genre(name, titles.sortedWith(popularFirst).take(ROW_SIZE).map { card(it) })) }
        }
    }

    /**
     * Takes the Viewer's connection to play [version], waiting until the [Connection] holding it is
     * closed: a second stream is never opened while one is in use. Close it when playback ends. An
     * [episode]'s Version streams from the Provider's Shows, not its Movies.
     */
    suspend fun connect(version: Version, episode: Boolean = false): Connection {
        val location = withContext(Dispatchers.IO) {
            val login = credentials.load() ?: throw ProviderException(WrongLogin)
            val client = XtreamClient(login, http)
            // Most of the surveyed Provider's files are MKV.
            if (episode) client.episodeLocation(version.listingId, store.episodeContainer(version.listingId) ?: "mkv")
            else client.movieLocation(version.listingId, store.container(version.listingId) ?: "mkv")
        }
        return Connection(location, connection.lease())
    }

    /**
     * What a Movie's details page starts, without the page waiting for it: the bitrates from the
     * Provider's movie details of its Versions (API calls, never a stream), what Viewers of the same
     * Provider read before (from the cloud), then Header reads of the best unconfirmed Version in each
     * language, the Viewer's [contentLanguages] first, one at a time while the Viewer's connection is
     * free. Pressing Play takes the connection at once and ends the reads, and so does the Provider
     * refusing one. Calls [changed] each time a Version's attributes change, so the page shows them.
     * Results are kept on the device and shared through the cloud, so a Version is never read twice.
     */
    suspend fun readHeaders(id: TitleId, contentLanguages: List<String>, changed: suspend () -> Unit) = withContext(Dispatchers.IO) {
        if (id.kind != TitleKind.Movie) return@withContext
        detailsReading.incrementAndGet()
        try {
            readDetailsHeaders(id, contentLanguages, changed)
        } finally {
            detailsReading.decrementAndGet()
        }
    }

    private suspend fun readDetailsHeaders(id: TitleId, contentLanguages: List<String>, changed: suspend () -> Unit) {
        val title = store.title(id) ?: return
        val login = credentials.load() ?: return
        val client = XtreamClient(login, http)
        val ids = title.listings.map { it.id }
        val asked = store.movieBitrates(ids).keys
        val unasked = ids.filter { it !in asked }
        if (unasked.isNotEmpty()) {
            val gate = Semaphore(SHOW_DETAILS_AT_ONCE)
            val bitrates = coroutineScope {
                unasked.map { listing ->
                    async {
                        gate.withPermit {
                            try {
                                listing to client.movieBitrate(listing)
                            } catch (e: ProviderException) {
                                null
                            }
                        }
                    }
                }.awaitAll()
            }.filterNotNull().toMap()
            store.saveMovieBitrates(bitrates, Instant.now())
            if (bitrates.values.any { it != null }) changed()
        }
        val provider = providerKey(login)
        shareFileReads(provider)
        if (lookUpFileReads(provider, ids)) changed()
        // This page's Versions come before Background reading's.
        connection.cancelRead()
        for (version in unread(title, contentLanguages)) {
            val container = store.container(version.listingId) ?: "mkv"
            if (container !in HEADER_READABLE) continue
            val read = try {
                reads.withLock { connection.whileFree { Read(headerReader.read(client.movieLocation(version.listingId, container), container)) } }
            } catch (e: IOException) {
                // The Provider refused: no more reads for now, nor Background reading today.
                if (e is HeaderReadRefused) store.saveReadingDay(ReadingDay.of(store.readingDay(), LocalDate.now()).copy(refused = true).kept())
                null
            } ?: break
            store.saveFileRead(version.listingId, read.contents?.toJson(), Instant.now(), shared = false)
            if (read.contents != null) changed()
        }
        shareFileReads(provider)
    }

    private class Read(val contents: FileContents?)

    /** The Titles whose cards show right now, in the order Background reading should read them. */
    fun onScreen(ids: List<TitleId>) {
        cardsOnScreen = ids
    }

    /** The attributes of the Version [id]'s card shows, with what reads Confirmed; null when the catalog no longer has it. */
    fun cardBest(id: TitleId, contentLanguages: List<String>): VersionAttributes? =
        store.title(id)?.let { preferred(candidates(it), contentLanguages).firstOrNull()?.attributes }

    /**
     * Background reading at [pace], until cancelled: a Header read of the Version a Movie's card
     * shows, one at a time and only while the connection is free, so never during Playback nor while
     * a details page reads. Cards on screen come first ([onScreen]), then Continue watching, then the
     * newest Movies in the Viewer's [contentLanguages]. A Provider answering with an error stops it
     * until the next day, and one not answering pauses it for a quarter of an hour. Each read that
     * Confirms a card's Version is announced in [confirmations].
     */
    suspend fun readInBackground(contentLanguages: List<String>, pace: BackgroundPace) =
        readInBackground(contentLanguages, pace, Clock.systemDefaultZone(), OFFLINE_PAUSE)

    internal suspend fun readInBackground(contentLanguages: List<String>, pace: BackgroundPace, clock: Clock, offlinePause: Duration) = withContext(Dispatchers.IO) {
        var pausedUntil = Instant.MIN
        while (true) {
            delay(pace.gap)
            val day = ReadingDay.of(store.readingDay(), LocalDate.now(clock))
            if (day.refused || day.reads >= pace.perDay || clock.instant().isBefore(pausedUntil) || detailsReading.get() > 0) continue
            val login = credentials.load() ?: continue
            val next = nextBackgroundRead(contentLanguages) ?: continue
            if (!reads.tryLock()) continue
            val read = try {
                connection.whileFree { Read(headerReader.read(XtreamClient(login, http).movieLocation(next.listingId, next.container), next.container)) }
            } catch (e: HeaderReadRefused) {
                store.saveReadingDay(day.copy(refused = true).kept())
                continue
            } catch (e: IOException) {
                pausedUntil = clock.instant() + offlinePause
                continue
            } finally {
                reads.unlock()
            }
            // Null when Play or a details page took the connection.
            read ?: continue
            store.saveFileRead(next.listingId, read.contents?.toJson(), Instant.now(), shared = false)
            store.saveReadingDay(day.copy(reads = day.reads + 1).kept())
            if (read.contents != null) _confirmations.emit(next.id)
        }
    }

    private class BackgroundRead(val id: TitleId, val listingId: Long, val container: String)

    /** The first Movie, in Background reading's order, whose card shows a Version not yet read. */
    private fun nextBackgroundRead(contentLanguages: List<String>): BackgroundRead? {
        val newest = store.titles(TitleKind.Movie, 0, NEW_MOVIES).filter { inContentLanguages(candidates(it), contentLanguages, null) }.map { it.id }
        val order = cardsOnScreen.asSequence() + store.inProgress(ROW_SIZE).asSequence().map { it.id } + newest.asSequence()
        return order.filter { it.kind == TitleKind.Movie }.distinct().firstNotNullOfOrNull { id ->
            val title = store.title(id) ?: return@firstNotNullOfOrNull null
            val shown = preferred(candidates(title), contentLanguages).firstOrNull()?.listing ?: return@firstNotNullOfOrNull null
            val container = store.container(shown.id) ?: "mkv"
            if (container !in HEADER_READABLE || store.fileReads(listOf(shown.id)).containsKey(shown.id)) null
            else BackgroundRead(id, shown.id, container)
        }
    }

    /** The best Version not yet read in each of [title]'s languages, the Viewer's [contentLanguages] first. */
    private fun unread(title: StoredTitle, contentLanguages: List<String>): List<Version> {
        val read = store.fileReads(title.listings.map { it.id }).keys
        val (languages, otherLanguages) = groupByLanguage(candidates(title), contentLanguages)
        return (languages + otherLanguages).mapNotNull { group ->
            group.versions.flatMap { listOf(it) + it.backups }.firstOrNull { it.listingId !in read }
        }
    }

    /** Keeps what the cloud has of [listingIds]' file reads that the device lacks; whether there were any. */
    private fun lookUpFileReads(provider: String, listingIds: List<Long>): Boolean {
        if (cloud == null || Instant.now().isBefore(cloudRetryAt)) return false
        val kept = store.fileReads(listingIds).keys
        var found = false
        try {
            listingIds.filter { it !in kept }.chunked(CloudClient.MAX_BATCH).forEach { batch ->
                cloud.fileReads(provider, batch).forEach { (id, body) ->
                    store.saveFileRead(id, body, Instant.now(), shared = true)
                    found = true
                }
            }
        } catch (e: CloudException) {
            cloudRetryAt = Instant.now() + CLOUD_RETRY_AFTER
        }
        return found
    }

    /** Shares the file reads the cloud doesn't have yet; those it doesn't take are shared next time. */
    private fun shareFileReads(provider: String) {
        if (cloud == null || Instant.now().isBefore(cloudRetryAt)) return
        try {
            store.unsharedFileReads().entries.chunked(CloudClient.MAX_BATCH).forEach { batch ->
                val files = batch.associate { it.key to it.value }
                cloud.shareFileReads(provider, files)
                store.markFileReadsShared(files.keys)
            }
        } catch (e: CloudException) {
            cloudRetryAt = Instant.now() + CLOUD_RETRY_AFTER
        }
    }

    /**
     * Confirms what Playback of [version] found in its file. A Header read's bitrate and HDR format
     * stand where the player didn't report them. It is shared with the cloud on the next details page.
     */
    internal fun confirm(version: Version, seen: FileContents) {
        val before = store.fileReads(listOf(version.listingId))[version.listingId]
        val file = seen.copy(hdr = seen.hdr ?: before?.hdr, bitrate = seen.bitrate ?: before?.bitrate)
        store.saveFileRead(version.listingId, file.toJson(), Instant.now(), shared = false)
    }

    /**
     * A Playback session for [plan], playing on the player engines [engines] creates: it starts the
     * plan's Version, falling back to the VLC engine and to its Backups, and switches Version or
     * engine when the Viewer asks. Start it with [PlaybackSession.play].
     */
    fun playback(plan: PlayPlan, engines: (Engine) -> PlayerEngine): PlaybackSession = playback(plan, engines, START_TIMEOUT)

    internal fun playback(plan: PlayPlan, engines: (Engine) -> PlayerEngine, startTimeout: kotlin.time.Duration) =
        PlaybackSession(this, plan, engines, startTimeout)

    /** A relay on this device for the file at a [Connection]'s [location], one Provider stream at a time. */
    internal fun relay(location: String) = StreamRelay(location, http)

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
                    if (answer.found.isNotEmpty()) metadataGeneration.incrementAndGet()
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

    /** [title]'s Versions, a Movie's with what Header reads, Playback and the Provider's movie details Confirmed. */
    private fun candidates(title: StoredTitle): List<Candidate> {
        val plain = title.listings.map(::candidates)
        if (title.id.kind != TitleKind.Movie) return plain
        val ids = title.listings.map { it.id }
        val reads = store.fileReads(ids)
        val bitrates = store.movieBitrates(ids)
        return plain.map { candidate ->
            val id = candidate.listing.id
            val withBitrate = bitrates[id]?.let { candidate.attributes.copy(bitrate = Known(it, Provenance.Confirmed)) } ?: candidate.attributes
            Candidate(candidate.listing, reads[id]?.let(withBitrate::confirmedBy) ?: withBitrate)
        }
    }

    private fun candidates(listing: StoredListing) = Candidate(listing, prefixTable.describe(listing.name, listing.category).attributes)

    override fun close() = store.close()

    companion object {
        private val METADATA_FRESH_FOR: Duration = Duration.ofDays(7)
        private val CLOUD_RETRY_AFTER: Duration = Duration.ofMinutes(1)
        /** Background reading waits this long after the Provider didn't answer: the Wi-Fi may be down. */
        private val OFFLINE_PAUSE: Duration = Duration.ofMinutes(15)
        private const val MATCH_FAILURES_IN_A_ROW = 3

        /** The kinds of Track the Viewer's last picks are kept by, and what turning subtitles off is kept as. */
        private const val AUDIO = "audio"
        private const val SUBTITLES = "subtitles"
        private const val SUBTITLES_OFF = "off"
        /** The cloud's answers about a Listing are asked again after this, so new Titles on TMDB come through. */
        private val MATCH_FRESH_FOR: Duration = Duration.ofDays(30)
        /** A name TMDB found nothing for is asked about again sooner, but not every daily Sync: few Titles are added in a week. */
        private val MATCH_NOT_FOUND_FRESH_FOR: Duration = Duration.ofDays(7)
        private val SHOW_DETAILS_FRESH_FOR: Duration = Duration.ofDays(1)
        /** How many show details are asked of the Provider at once: GoT alone has 21 Versions. */
        private const val SHOW_DETAILS_AT_ONCE = 4
        /** A Title counts as watched with this share of it left, or [WATCHED_TIME_LEFT], whichever is less: the credits. */
        private const val WATCHED_SHARE_LEFT = 0.05
        private const val WATCHED_TIME_LEFT = 8 * 60_000L
        private const val MIN_RESUME_POSITION = 60_000L
        /** How many cards a home row holds. */
        private const val ROW_SIZE = 20
        /** The newest Titles the home rows are picked from: their metadata is asked of the cloud 50 at a time. */
        private const val HOME_MOVIES = 150
        private const val HOME_SHOWS = 50
        /** Of those, the ones New on your service is picked from. */
        private const val NEW_MOVIES = 40
        private const val NEW_SHOWS = 15
        private const val GENRE_ROWS = 8
        /** A genre with fewer Titles than this gets no row: a row of two looks broken. */
        private const val MIN_GENRE_SIZE = 5
        /** At most this many Movies and this many Shows per search, and Titles per person: two rows of cards. */
        private const val TITLES_FOUND = 40
        private const val PEOPLE_FOUND = 10

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
