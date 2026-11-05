package dev.betteriptv.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
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
import java.time.ZoneId
import java.util.SortedMap
import java.util.TreeMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
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
    /** The same for TMDB's search, which fails on its own when TMDB is rate-limiting: cached metadata still comes through. */
    @Volatile private var searchRetryAt = Instant.MIN

    private val syncLock = Mutex()
    private val searchLock = Any()
    /** Bumped when a Sync or Matches change the catalog: the search index is rebuilt in the background, the old one serving meanwhile. */
    private val catalogGeneration = AtomicInteger()
    private val _catalogChanges = MutableStateFlow(0)
    private val catalogChangeCount = AtomicInteger()

    /**
     * Bumped once a Sync's new catalog is in place, again when Matches regroup its Titles, and when
     * fresh Games land behind a read ([games]): screens read their Title counts and rows again when
     * it changes (#271, #256). Not the catalog's generation itself, which the search index follows.
     */
    val catalogChanges: StateFlow<Int> = _catalogChanges.asStateFlow()
    /** Bumped when metadata is cached: the index is rebuilt in the background, the old one serving meanwhile. */
    private val metadataGeneration = AtomicInteger()
    private val rebuildingSearch = AtomicBoolean()
    /** The low-priority thread building the search index in the background, while it runs. */
    @Volatile private var searchBuilder: Thread? = null
    @Volatile private var searchIndex: BuiltIndex? = null
    private val connection = ConnectionGate()
    private val headerReader = HeaderReader(http)
    /** One Header read at a time, a details page's or Background reading's. */
    private val reads = Mutex()
    /** Details pages reading right now: Background reading waits for them. */
    private val detailsReading = AtomicInteger()
    @Volatile private var cardsOnScreen: List<TitleId> = emptyList()
    /**
     * Saves the cloud's Charts and metadata answers one at a time, in order, apart from the read
     * that asked for them: a Sync holds the catalog's writer while it downloads, for minutes on the
     * Fire TV Stick, and Home's rows mustn't wait for it (#213). Until its save lands, an answer is
     * read from [unsavedCharts] or [unsavedMetadata] as if it were stored.
     */
    private val saves = Executors.newSingleThreadExecutor { Thread(it, "library-saves").apply { isDaemon = true } }
    /** The thread asking the cloud for Games behind a read that doesn't wait for them ([games]). */
    private val gamesFetches = Executors.newSingleThreadExecutor { Thread(it, "library-games").apply { isDaemon = true } }
    private val gamesFetching = AtomicBoolean()
    /**
     * The guide cache (#261): each Channel's programmes from the Provider's per-Channel guide call, by
     * Channel key, kept until the last of them ends ([GuideEntry.until]). In memory only: programmes
     * are over within hours, and the next launch asks again for what is on screen.
     */
    private val guideCache = ConcurrentHashMap<String, GuideEntry>()
    /** The Channels the guide is wanted for: the Channel rows on screen, then the Favourites, in that order. */
    @Volatile private var guideOnScreen: List<Channel> = emptyList()
    @Volatile private var guideFavourites: List<Channel> = emptyList()
    /** When a guide call last got no answer, by Channel key: asked again after [GUIDE_RETRY_AFTER], not at every focus. */
    private val guideUnanswered = ConcurrentHashMap<String, Instant>()
    /** The thread making guide calls, one at a time, behind the screens that want them. */
    private val guideFetches = Executors.newSingleThreadExecutor { Thread(it, "library-guide").apply { isDaemon = true } }
    /** Whether a guide round is queued and not yet started: another want then needs no second one. */
    private val guideQueued = AtomicBoolean()
    private val _guideChanges = MutableStateFlow(0)

    /** Bumped each time a guide call lands: screens showing what is on read [guide] again. */
    val guideChanges: StateFlow<Int> = _guideChanges.asStateFlow()
    /** The guide's clock: today, for the day a refusal stops it, and now, for the programme on. Tests move it. */
    @Volatile internal var guideClock: Clock = Clock.systemDefaultZone()
    private val unsavedCharts = ConcurrentHashMap<Pair<String, String>, StoredCharts>()
    private val unsavedGames = ConcurrentHashMap<String, StoredGames>()
    /** When the cloud last answered a request for each League, listing it or not: one it has no copy of yet waits as a kept copy does (#307). */
    private val gamesAnsweredAt = ConcurrentHashMap<String, Instant>()
    private val unsavedMetadata = ConcurrentHashMap<Pair<String, TitleId.Tmdb>, StoredMetadata>()
    /** Whether a Sync is replacing the catalog, holding its writer: saves then don't wait for it. */
    @Volatile private var syncWriting = false
    private val _confirmations = MutableSharedFlow<TitleId>(extraBufferCapacity = 64)

    /** The Titles whose card's Version Background reading just Confirmed, so their badges update. */
    val confirmations: SharedFlow<TitleId> = _confirmations.asSharedFlow()
    private val _syncStatus = MutableStateFlow(openingStatus())
    val syncStatus: StateFlow<SyncStatus> = _syncStatus.asStateFlow()
    private val _matchingStatus = MutableStateFlow<MatchingStatus?>(null)

    /** The cloud being asked about the catalog's suspects after a Sync's catalog lands, or null while it isn't. */
    val matchingStatus: StateFlow<MatchingStatus?> = _matchingStatus.asStateFlow()

    /** Matching runs under this, apart from [syncLock]: Sync now needn't wait for the Titles to regroup. */
    private val matchLock = Mutex()

    /** Whether a held Sync's catalog waits to be swapped in by [applyPendingCatalog]. */
    val hasPendingCatalog: Boolean get() = _syncStatus.value is SyncStatus.Ready

    /**
     * The status as the stored catalog says: a held Sync's catalog waits as [SyncStatus.Ready], unless
     * none shows yet (the app closed between a first Sync's two swaps), when it lands here and now.
     */
    private fun openingStatus(): SyncStatus {
        val last = store.lastSync()
        val pendingAt = store.pendingAt()
        return when {
            pendingAt != null && last != null -> SyncStatus.Ready(last, pendingAt)
            pendingAt != null -> SyncStatus.Synced(store.applyPending()!!)
            last != null -> SyncStatus.Synced(last)
            else -> SyncStatus.NeverSynced(store.channelsSyncedAt())
        }
    }

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
     * Downloads the Provider's catalog and replaces the device's copy, in two stages. The Channels
     * stage (live Categories and Listings) comes first; the Titles stage (movie and show Categories
     * and Listings) after, with the suspects Matched by the answers kept. Only `player_api` requests
     * are made, never a stream, so a Sync never takes the Viewer's connection. Clutter and Sports
     * replays are left out of the lists, and Prefixes the Prefix table doesn't know are reported in
     * the finished Sync.
     *
     * A first Sync swaps each stage in as it lands, so Live and Home open on the Channels within
     * seconds while the Titles are still read. A later Sync computes both behind the catalog on
     * screen, then either swaps them in together (the default), or with [hold] keeps them staged as
     * [SyncStatus.Ready] for [applyPendingCatalog], which the app calls between screens so rows never
     * move under focus; a Sync started meanwhile discards what was held.
     *
     * Once a catalog lands, the cloud is asked about its suspects and the Titles regroup by its
     * answers, under [matchingStatus], apart from this status: the Sync itself is finished, and another
     * may start meanwhile. This returns once that is done too.
     */
    suspend fun sync(hold: Boolean = false): SyncStatus {
        val landed = syncLock.withLock { withContext(Dispatchers.IO) { syncUnderLock(hold) } }
        if (landed is SyncStatus.Synced) afterCatalogLanded()
        return _syncStatus.value
    }

    private fun syncUnderLock(hold: Boolean): SyncStatus {
        val last = _syncStatus.value.last
        var channelsAt = _syncStatus.value.channelsAt
        var step = SyncStep.Channels
        var entriesRead = 0
        val running = { _syncStatus.value = SyncStatus.Running(last, channelsAt, step, entriesRead) }
        running()
        val started = System.nanoTime()
        val finished = try {
            val client = XtreamClient(credentials.load() ?: throw ProviderException(WrongLogin), http)
            client.account()
            store.discardPending()
            val unknownPrefixes = LinkedHashMap<String, UnknownPrefix>()
            // A Sync holds the writer from here until the new catalog is in place: reads don't wait on it to save (#213).
            syncWriting = true
            try {
                val channelCount = store.stageChannels { staging ->
                    val liveCategories = LinkedHashMap<String, String>()
                    client.liveCategories { id, name -> liveCategories[id] = name }
                    val now = Instant.now()
                    // Per Category, how many Listings it has and how many are Event channels or empty Event slots.
                    val tallies = HashMap<String?, IntArray>()
                    client.liveListings { id, name, categoryId, image, guideId ->
                        val category = liveCategories[categoryId].orEmpty()
                        val start = eventStart(name, now, prefixTable.eventTimeZone)
                        val listing = prefixTable.describeLive(name, category)
                        val tally = tallies.getOrPut(categoryId) { IntArray(2) }
                        tally[0]++
                        if (listing == PrefixTable.LiveListing.EmptyEventSlot || isEventChannel(name, start)) tally[1]++
                        if (listing == PrefixTable.LiveListing.Channel) {
                            val channel = prefixTable.channelName(name)
                            staging.channel(id, name, image, start, categoryId, guideId, channel.key, channel.name, channel.country, channel.quality, channel.hevc)
                            entriesRead++
                            running()
                        }
                    }
                    if (tallies.isEmpty()) requireListings(liveCategories, "live")
                    liveCategories.forEach { (id, name) ->
                        val tally = tallies[id]
                        val kind = when {
                            prefixTable.isClutterCategory(name) -> CategoryKind.Clutter
                            tally != null && tally[1] * 2 > tally[0] -> CategoryKind.Event
                            else -> CategoryKind.Regular
                        }
                        staging.liveCategory(id, name, normalisedCategoryName(name), categoryCountry(name), kind)
                    }
                }
                if (last == null) {
                    // The first stage shows at once: Live and Home have their Channels while the Titles are read.
                    val at = Instant.now()
                    store.swapChannels(at)
                    channelsAt = at
                    catalogChanged()
                }
                step = SyncStep.Movies
                running()
                val pending = store.stageTitles(
                    fill = { staging ->
                        val movieCategories = HashMap<String, String>()
                        val showCategories = HashMap<String, String>()
                        val listing = { kind: TitleKind, id: Long, name: String, category: String, categoryId: String?, added: Long?, tmdbId: Long?, image: String?, container: String? ->
                            val description = prefixTable.describe(name, category)
                            description.unknownPrefix?.let { prefix ->
                                unknownPrefixes.merge(prefix, UnknownPrefix(prefix, 1, name)) { seen, _ -> seen.copy(listingCount = seen.listingCount + 1) }
                            }
                            val hidden = description.hidden != null
                            staging.listing(id, name, categoryId, added, image, hidden, question(kind, name, description, tmdbId), container)
                            // A Sports replay is kept apart, with what its name says: its TMDB ID and poster are wrong and ignored.
                            if (description.hidden == PrefixTable.Hidden.SportsReplay && kind == TitleKind.Movie) {
                                staging.replay(id, name, category, added, container, ReplayNames.parse(name))
                            }
                            if (!hidden) {
                                entriesRead++
                                running()
                            }
                        }
                        client.movieCategories { id, name ->
                            movieCategories[id] = name
                            staging.category(TitleKind.Movie, id, name)
                        }
                        var movieListings = 0
                        client.movieListings { id, name, categoryId, added, tmdbId, image, container ->
                            movieListings++
                            listing(TitleKind.Movie, id, name, movieCategories[categoryId].orEmpty(), categoryId, added, tmdbId, image, container)
                        }
                        if (movieListings == 0) requireListings(movieCategories, "movie")
                        step = SyncStep.Shows
                        running()
                        client.showCategories { id, name ->
                            showCategories[id] = name
                            staging.category(TitleKind.Show, id, name)
                        }
                        var showListings = 0
                        client.showListings { id, name, categoryId, modified, tmdbId, image ->
                            showListings++
                            listing(TitleKind.Show, id, name, showCategories[categoryId].orEmpty(), categoryId, modified, tmdbId, image, null)
                        }
                        if (showListings == 0) requireListings(showCategories, "show")
                    },
                    onFilled = {
                        step = SyncStep.Saving
                        running()
                    },
                    completed = { movieCount, showCount, rematched ->
                        CompletedSync(
                            Instant.now(),
                            Duration.ofNanos(System.nanoTime() - started),
                            movieCount,
                            unknownPrefixes.values.sortedWith(compareByDescending<UnknownPrefix> { it.listingCount }.thenBy { it.prefix }),
                            rematched,
                            showCount,
                            channelCount,
                        )
                    },
                )
                when {
                    last == null -> SyncStatus.Synced(store.swapTitles())
                    hold -> SyncStatus.Ready(last, pending.finishedAt)
                    else -> SyncStatus.Synced(store.applyPending()!!)
                }
            } finally {
                syncWriting = false
            }
        } catch (e: ProviderException) {
            SyncStatus.Failed(e.problem, last, channelsAt, step)
        } catch (e: CancellationException) {
            // A first Sync stopped short keeps the Channels it landed, as a reopened app does.
            _syncStatus.value = last?.let { SyncStatus.Synced(it) } ?: SyncStatus.NeverSynced(channelsAt)
            throw e
        } catch (e: Exception) {
            // Anything else (a full disk, say) also leaves the previous catalog in place.
            SyncStatus.Failed(CatalogNotSaved(e.message ?: e.javaClass.simpleName), last, channelsAt, step)
        }
        if (finished is SyncStatus.Failed) runCatching { store.discardPending() }
        // The new catalog shows from here: screens count its Titles again before the status changes (#271).
        if (finished is SyncStatus.Synced) catalogChanged()
        _syncStatus.value = finished
        if (finished is SyncStatus.Synced) prepareSearchInBackground()
        return finished
    }

    /**
     * Fails the Sync when the Provider sent an empty [kind] list beside [categories] of that kind: it once
     * answered both title lists with `[]`, and the empty catalog stood as a finished Sync that nothing
     * retried. A Provider with no Categories of a kind has none of it, and its empty list is right.
     */
    private fun requireListings(categories: Map<String, String>, kind: String) {
        if (categories.isNotEmpty()) throw ProviderException(Unreachable("It sent an empty $kind list. Try again in a moment."))
    }

    /**
     * Swaps a held Sync's catalog in ([SyncStatus.Ready]), then Matches its suspects as a Sync does
     * once its catalog lands. Returns false at once, without waiting, when none is held or a Sync is
     * running: the next screen entry asks again.
     */
    suspend fun applyPendingCatalog(): Boolean {
        if (!syncLock.tryLock()) return false
        val applied = try {
            withContext(Dispatchers.IO) {
                if (_syncStatus.value !is SyncStatus.Ready) return@withContext false
                syncWriting = true
                val sync = try {
                    store.applyPending()
                } finally {
                    syncWriting = false
                }
                if (sync == null) {
                    _syncStatus.value = store.lastSync()?.let { SyncStatus.Synced(it) } ?: SyncStatus.NeverSynced(store.channelsSyncedAt())
                    return@withContext false
                }
                catalogChanged()
                _syncStatus.value = SyncStatus.Synced(sync)
                prepareSearchInBackground()
                true
            }
        } finally {
            syncLock.unlock()
        }
        if (applied) afterCatalogLanded()
        return applied
    }

    private fun catalogChanged() {
        catalogGeneration.incrementAndGet()
        changed()
    }

    /** Tells the screens to read again ([catalogChanges]), without a new catalog generation. */
    private fun changed() {
        _catalogChanges.value = catalogChangeCount.incrementAndGet()
    }

    /**
     * Once a catalog lands: reports the Prefixes the Sync didn't know, then asks the cloud about the
     * catalog's suspects and regroups the Titles by its answers, under [matchingStatus] from start to
     * end whatever happens, so the status never stays at Matching (#271).
     */
    private suspend fun afterCatalogLanded() = matchLock.withLock {
        withContext(Dispatchers.IO) {
            try {
                if (!canAskCloud()) return@withContext
                _matchingStatus.value = MatchingStatus(0, null)
                val sync = store.lastSync() ?: return@withContext
                reportUnknownPrefixes(sync)
                matchByName()
            } finally {
                _matchingStatus.value = null
            }
        }
    }

    /**
     * Picks up Matching the app closed on (or the cloud didn't answer): asks the cloud about the
     * stored catalog's suspects it hasn't answered for lately and regroups the Titles by the answers
     * kept, without waiting for the next Sync. Asks nothing, and shows no [matchingStatus], when
     * every suspect has its answer.
     */
    suspend fun resumeMatching() = matchLock.withLock {
        withContext(Dispatchers.IO) {
            try {
                if (store.lastSync() != null) matchByName()
            } finally {
                _matchingStatus.value = null
            }
        }
    }

    /**
     * Tells the cloud which Prefixes the Sync found that the Prefix table doesn't know, so they get
     * added to it. The Viewer never sees them; a Sync that can't report them leaves it to the next.
     */
    private fun reportUnknownPrefixes(sync: CompletedSync) {
        if (cloud == null || sync.unknownPrefixes.isEmpty() || !canAskCloud()) return
        val login = credentials.load() ?: return
        try {
            cloud.reportUnknownPrefixes(providerKey(login), sync.unknownPrefixes.take(CloudClient.MAX_PREFIX_BATCH))
        } catch (e: CloudException) {
            cloudRetryAt = Instant.now() + CLOUD_RETRY_AFTER
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
     * When no batch has been answered for [MATCH_STALL_AFTER], the rest wait for the next Sync too.
     * A Sync may land a new catalog meanwhile: the answers are kept by question, so they regroup
     * whichever catalog shows when they are applied.
     */
    private fun matchByName() {
        if (cloud == null || !canAskCloud()) return
        try {
            val now = Instant.now()
            val questions = store.unansweredSuspects(now - MATCH_FRESH_FOR, now - MATCH_NOT_FOUND_FRESH_FOR)
                .map { question(it.kind, it.name, prefixTable.describe(it.name, it.category), it.providerTmdbId) }
                .distinctBy { it.key }
            // A batch can fail on its own (the cloud ran out of CPU time, say): its suspects are asked
            // again next Sync. Several in a row mean the cloud is down.
            var failedInARow = 0
            var asked = 0
            var answeredAt = matchClock.instant()
            if (questions.isNotEmpty()) _matchingStatus.value = MatchingStatus(asked, questions.size)
            for (batch in questions.chunked(CloudClient.MAX_MATCH_BATCH)) {
                if (Duration.between(answeredAt, matchClock.instant()) >= MATCH_STALL_AFTER) break
                try {
                    store.saveMatchAnswers(cloud.match(batch).mapKeys { it.key.key }, now)
                    failedInARow = 0
                    answeredAt = matchClock.instant()
                } catch (e: CloudException) {
                    if (++failedInARow == MATCH_FAILURES_IN_A_ROW) {
                        cloudRetryAt = now + CLOUD_RETRY_AFTER
                        break
                    }
                }
                asked += batch.size
                _matchingStatus.value = MatchingStatus(asked, questions.size)
            }
            // Answers saved before the app closed are applied even when nothing is left to ask; with
            // nothing asked and no Listing moved, the screens needn't read again.
            (store.applyMatchAnswers() ?: store.lastSync()?.takeIf { questions.isNotEmpty() })?.let { regrouped ->
                // The regrouped Sync replaces the one showing, not a Sync running or held meanwhile.
                _syncStatus.update { if (it is SyncStatus.Synced) SyncStatus.Synced(regrouped) else it }
                catalogChanged()
                prepareSearchInBackground()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The Sync itself finished: its catalog shows, Matched by the Provider's IDs.
        }
    }

    /** What Matching times its batches by: tests move it on to stall Matching without waiting. */
    @Volatile internal var matchClock: Clock = Clock.systemUTC()

    private fun canAskCloud() = cloud != null && !Instant.now().isBefore(cloudRetryAt)

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
     * and case don't matter, and small typos are tolerated; a year after a name finds that year's
     * Title. Titles with Versions in the Viewer's [contentLanguages] come first, then closer matches
     * (a whole name, then whole words, then words being typed, then typos), then TMDB's most
     * popular. A person's
     * Titles are those the Provider has. Cards show TMDB's name and poster in the [appLanguage].
     *
     * The device's own matches come first, from the Listing names and the metadata it has cached.
     * Then, for three letters or more, TMDB's search (through the cloud, ADR 0009) adds Titles the
     * device has no metadata for and every Title of the people it finds, and the results come again.
     * TMDB's search also finds loose matches, so its Titles rank as words being typed, after the
     * device's whole-word matches.
     * Without the cloud the first results are all there are.
     *
     * Channels are found by their names (country tag and name, without the Provider's Tier tags and
     * quality words) the same way, each Channel once whatever its variants, Favourites first among
     * equally close matches, then in the Provider's order; finished Event channels aren't. TMDB's
     * genres are found like people, with their Titles.
     *
     * The results offer completions: the names of the best Titles, people and genres found. Before
     * anything is typed, they are the genres with the most Titles in the Viewer's languages. Genres
     * are named in the App language and found by their names in any ([Genres]).
     *
     * The search index of the whole catalog is built in the background after a Sync and by
     * [prepareSearchInBackground]; a search before the first one waits for it, and one during or
     * after a Sync answers from the index built before it until the new one is.
     */
    fun search(query: String, contentLanguages: List<String>, appLanguage: String, region: String = DEFAULT_REGION): Flow<SearchResults> = flow {
        val letters = SearchIndex.words(query).sumOf { it.length }
        if (letters == 0) return@flow emit(SearchResults.NONE.copy(completions = genreShortcuts(searchIndex(), contentLanguages, appLanguage)))
        if (letters < SearchIndex.MIN_LETTERS) return@flow emit(SearchResults.NONE)
        val index = searchIndex()
        val titles = index.findTitles(query).associateTo(HashMap()) { it.index to it.distance }
        val people = index.findPeople(query).map { FoundPerson(index.person(it.index), it.distance, index.personTitles(it.index).toSet()) }
        val channels = foundChannels(index, query)
        val replays = foundReplays(index, query)
        val ranks = topRanks(region, contentLanguages, appLanguage)
        emit(searchResults(index, query, titles, people, emptyMap(), contentLanguages, appLanguage, ranks).copy(channels = channels, replays = replays))

        if (cloud == null || letters < TMDB_SEARCH_LETTERS) return@flow
        // The screen searches once typing pauses; TMDB is asked only once it has paused a little longer.
        delay(TMDB_SEARCH_PAUSE_MS)
        val found = tmdbSearch(query) ?: return@flow
        val popularity = HashMap<Int, Double>()
        for ((id, score) in found.titles) {
            val position = index.position(id) ?: continue
            // As a Title's word being typed: a Title's words are a step after its whole name.
            titles[position] = minOf(titles[position] ?: Int.MAX_VALUE, SearchIndex.STARTED_WORD + 1)
            popularity[position] = score
        }
        val online = found.people.map { (name, ids) -> FoundPerson(name, 0, ids.mapNotNullTo(HashSet(), index::position)) }
        val merged = (people + online).groupBy { SearchIndex.words(it.name) }.values.map { same ->
            FoundPerson(same.first().name, same.minOf { it.distance }, same.flatMapTo(HashSet()) { it.titles })
        }
        emit(searchResults(index, query, titles, merged, popularity, contentLanguages, appLanguage, ranks).copy(channels = channels, replays = replays))
    }.flowOn(Dispatchers.IO)

    /** The Sports replays whose names match [query], closest first, then the newest by the date in the name, then in the Provider's order: every one, a highlights show as well as a game. */
    private fun foundReplays(index: SearchIndex, query: String): List<Replay> {
        val hits = index.findReplays(query).take(REPLAYS_FOUND)
        val replays = store.replays(hits.map { index.replays[it.index] })
        return hits.mapNotNull { replays[index.replays[it.index]] }
    }

    /** The Channels on now whose names match [query], closest first, then Favourites, then in the Provider's order. */
    private fun foundChannels(index: SearchIndex, query: String): List<Channel> {
        val hits = index.findChannels(query).sortedWith(compareBy<SearchIndex.Hit>({ it.distance }, { it.index })).take(CHANNELS_FOUND * 4)
        // Finished Event channels drop out, so a few more are read than are shown.
        val on = store.channels(hits.map { index.channels[it.index] }, Instant.now() - EVENT_FINISHED_AFTER)
        return hits.mapNotNull { hit -> on[index.channels[hit.index]]?.let { hit to it } }
            .sortedWith(compareBy({ it.first.distance }, { !it.second.favourite }, { it.first.index }))
            .map { it.second }
            .take(CHANNELS_FOUND)
    }

    /** The Viewer's Favourites that are on, in the order they were added. */
    suspend fun favourites(): List<Channel> = withContext(Dispatchers.IO) { store.favourites(Instant.now() - EVENT_FINISHED_AFTER) }

    /** Saves the Channel keyed [channel] ([Channel.key]) as a Favourite, or removes it when [favourite] is false. */
    suspend fun setFavourite(channel: String, favourite: Boolean) = withContext(Dispatchers.IO) { store.setFavourite(channel, favourite, Instant.now()) }

    /** The live Categories the last Sync stored, in the Provider's order, with their normalised names and kinds. */
    suspend fun liveCategories(): List<LiveCategory> = withContext(Dispatchers.IO) { store.liveCategories() }

    /**
     * The Channel browser's countries (the Channels row on Live), from the regular live Categories'
     * country codes: the Viewer's [region] first, then their [contentLanguages]' countries, then the
     * rest by Channel count, then the Prefix table's aliased codes (`AR` for Arabic) by Channel count
     * ([orderedCountries]). Clutter and Event Categories, and Categories whose names carry no
     * country code or one that is neither a country nor aliased, add none ([browserCountry]).
     */
    suspend fun liveCountries(region: String, contentLanguages: List<String>): List<LiveCountry> = withContext(Dispatchers.IO) {
        orderedCountries(store.liveCountries(Instant.now() - EVENT_FINISHED_AFTER, ::browserCountry), region, contentLanguages)
    }

    private fun browserCountry(country: String) = browserCountry(country, prefixTable::countryAlias)

    /**
     * The Categories the Channels screen lists for the country coded [country] ([LiveCountry.code]):
     * regular ones only (no Clutter or Event Categories), named without the country code and tags
     * (the code's language kept: `SPORTS · FR`), those sharing a name merged, each with its Channel
     * count. Genre Categories come first, in the order of the Prefix table's genre list (News,
     * Sports, Entertainment, …), then the Provider's service buckets (`AT&T`, `PRIME`, `TUBI`) by
     * Channel count; ties keep the Provider's order.
     */
    suspend fun browsedCategories(country: String): List<BrowsedCategory> = withContext(Dispatchers.IO) {
        val categories = store.browsedCategories(country, Instant.now() - EVENT_FINISHED_AFTER, ::browserCountry)
        val genres = categories.associateWith { prefixTable.genreRank(it.name) }
        categories.sortedWith(compareBy<BrowsedCategory, Int?>(nullsLast()) { genres[it] }.thenByDescending { if (genres[it] == null) it.channels else 0 })
    }

    /**
     * The Channels of a browsed [category], in the Provider's order, whole: a Channel with variants in
     * two Categories is listed in both, and watching it tries every variant. Finished Event channels
     * are left out, as everywhere.
     */
    suspend fun categoryChannels(category: BrowsedCategory): List<Channel> = withContext(Dispatchers.IO) {
        store.categoryChannels(category.categoryIds, Instant.now() - EVENT_FINISHED_AFTER)
    }

    /**
     * What watching [channel] starts: its best variant for the Viewer's [device] within the
     * [qualityCap] ([rankedVariants]), as MPEG-TS, then the same stream as HLS, for a Provider or a
     * player that does one and not the other, then the next variant the same way, as a chain of
     * Backups the failure policy walks. The chain stops after [CHAIN_VARIANTS] variants: one that
     * answers but never starts costs a start timeout each way, so a Channel dead at the Provider
     * fails in under a minute rather than walking eight variants. The plan's Versions are every
     * variant in rank order, named as the Provider lists them and each with the chain from it as
     * its Backups, so picking one from the player's list goes on from it the same way.
     */
    fun channelPlayPlan(channel: Channel, contentLanguages: List<String>, device: DeviceProfile, qualityCap: QualityCap = QualityCap.UHD): PlayPlan {
        val ranked = rankedVariants(channel.variants, device, qualityCap)
        fun chain(from: Int) = ranked.subList(from, minOf(ranked.size, from + CHAIN_VARIANTS)).flatMap { variant ->
            val attributes = VersionAttributes(quality = variant.quality?.let { Known(it, Provenance.Likely) })
            listOf("ts", "m3u8").map { Version(variant.id, variant.name, attributes, liveFormat = it) }
        }
        val versions = ranked.indices.map { chain(it).let { chain -> chain.first().copy(backups = chain.drop(1)) } }
        return PlayPlan(null, versions.first(), contentLanguages, versions, remembered = rememberedTracks())
    }

    /**
     * What the Provider's guide says [channel] is showing: the programme on now and the next one,
     * from the guide cache, or null while the cache has nothing for it: the Channel has no guide id,
     * its guide hasn't been asked for or answered yet (see [guideOnScreen]), the guide listed nothing
     * from now on, or the Provider refused a call today. Cheap: read on the main thread by the
     * Channels screen's rows, the info under a focused Favourite and the player's top scrim, again
     * whenever [guideChanges] moves.
     */
    fun guide(channel: Channel): NowNext? {
        val now = guideClock.instant()
        return guideCache[channel.key]?.takeIf { it.until.isAfter(now) }?.at(now)?.takeUnless { it.isEmpty }
    }

    /**
     * The Channels whose rows are on screen right now (the Channels screen's, or the one the player
     * plays): their guides are asked for, one call at a time, behind the screen, those with a guide
     * id and nothing cached first, in this order, then the Favourites' (asked for whenever Home's or
     * Live's rows are read). A Channel the guide doesn't cover (no guide id) is never asked about;
     * one whose programmes are cached isn't asked again until the last of them ends; one the guide
     * listed nothing for isn't asked again for [GUIDE_EMPTY_KEPT_FOR]. At the first call the Provider
     * refuses (any answer that isn't a guide, a 429 or a 503 alike, as a Header read counts them) no
     * more are made until the next day, as Background reading stops after a refused Header read; a
     * call that gets no answer at all is tried again after [GUIDE_RETRY_AFTER]. Each answer bumps
     * [guideChanges].
     */
    fun guideOnScreen(channels: List<Channel>) = wantGuide(onScreen = channels)

    private fun wantGuide(onScreen: List<Channel>? = null, favourites: List<Channel>? = null) {
        if (onScreen != null) guideOnScreen = onScreen
        if (favourites != null) guideFavourites = favourites
        if (!guideQueued.compareAndSet(false, true)) return
        try {
            guideFetches.execute {
                guideQueued.set(false)
                fetchGuides()
            }
        } catch (e: RejectedExecutionException) {
            // Closed: nothing wants the guide any more.
        }
    }

    /** One round of guide calls, on the guide thread: every Channel wanted and not cached, in order, until none is left or the Provider refuses. */
    private fun fetchGuides() {
        while (true) {
            val now = guideClock.instant()
            val day = ReadingDay.of(store.guideDay(), LocalDate.now(guideClock))
            if (day.refused) return
            val login = credentials.load() ?: return
            val wanted = (guideOnScreen + guideFavourites).distinctBy { it.key }
            val next = wanted.firstOrNull { guideWanted(it, now) } ?: return
            val variant = next.variants.first { it.guideId.isNotEmpty() }
            try {
                val programmes = XtreamClient(login, http).guide(variant.id, now, GUIDE_PROGRAMMES)
                val until = programmes.maxOfOrNull { it.end }?.takeIf { it.isAfter(now) } ?: now + GUIDE_EMPTY_KEPT_FOR
                guideCache[next.key] = GuideEntry(programmes, until)
                guideUnanswered.remove(next.key)
                store.saveGuideDay(day.copy(reads = day.reads + 1).kept())
                _guideChanges.value++
            } catch (e: GuideRefused) {
                // The Provider refused: no more guide calls today.
                store.saveGuideDay(day.copy(refused = true).kept())
                return
            } catch (e: IOException) {
                guideUnanswered[next.key] = now
            }
        }
    }

    /** Whether [channel]'s guide is to be asked for at [now]: the guide covers it, nothing cached stands, and no unanswered call is still fresh. */
    private fun guideWanted(channel: Channel, now: Instant): Boolean {
        if (channel.guideId.isEmpty()) return false
        if (guideCache[channel.key]?.until?.isAfter(now) == true) return false
        val unanswered = guideUnanswered[channel.key] ?: return true
        return !now.isBefore(unanswered + GUIDE_RETRY_AFTER)
    }

    /** A person search found: how close their name was, and their Titles, as positions in the search index. */
    private class FoundPerson(val name: String, val distance: Int, val titles: Set<Int>)

    /** The genres with the most Titles in the Viewer's [contentLanguages], named in the [appLanguage], to search before typing. */
    private fun genreShortcuts(index: SearchIndex, contentLanguages: List<String>, appLanguage: String): List<String> {
        val wanted = contentLanguages.map(::primaryLanguage).toSet()
        val inWanted = { titles: IntArray -> titles.count { position -> index.languages(position).any(wanted::contains) } }
        return index.genres.indices.map { Genres.named(index.genres[it], appLanguage) to inWanted(index.genreTitles[it]) }
            .filter { (_, count) -> count > 0 }
            .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })
            .take(COMPLETIONS)
            .map { it.first }
    }

    /**
     * Ranks [titles] (search index positions and how close they matched), [people] and the genres
     * [query] matches, and shows the best of them, with the completions they offer. TMDB's search
     * tells the [popularity] of Titles the device has no metadata for.
     */
    private fun searchResults(
        index: SearchIndex,
        query: String,
        titles: Map<Int, Int>,
        people: List<FoundPerson>,
        popularity: Map<Int, Double>,
        contentLanguages: List<String>,
        appLanguage: String,
        ranks: Map<TitleId, ChartRank>,
    ): SearchResults {
        val wanted = contentLanguages.map(::primaryLanguage).toSet()
        val popularityOf = { position: Int -> maxOf(index.popularity(position), popularity[position] ?: 0.0) }
        val byRank = compareBy<SearchIndex.Hit>(
            { index.languages(it.index).none(wanted::contains) },
            { it.distance },
            { -popularityOf(it.index) },
            { it.index },
        )
        val ranked = titles.map { (position, distance) -> SearchIndex.Hit(position, distance) }.sortedWith(byRank)
        val shownPeople = people.filter { it.titles.isNotEmpty() }
            .sortedWith(compareBy<FoundPerson>({ it.distance }, { person -> -person.titles.maxOf(popularityOf) }))
            .take(PEOPLE_FOUND)
            .map { person -> person.name to person.titles.map { SearchIndex.Hit(it, 0) }.sortedWith(byRank).take(TITLES_FOUND) }
        val shownGenres = index.findGenres(query)
            .sortedWith(compareBy<SearchIndex.Hit>({ it.distance }, { -index.genreTitles[it.index].size }))
            .take(GENRES_FOUND)
            .map { Genres.named(index.genres[it.index], appLanguage) to index.genreTitles[it.index].map { position -> SearchIndex.Hit(position, 0) }.sortedWith(byRank).take(TITLES_FOUND) }
        val ofKind = { kind: TitleKind -> ranked.filter { index.titleId(it.index).kind == kind }.take(TITLES_FOUND) }
        val shown = (TitleKind.entries.flatMap(ofKind) + shownPeople.flatMap { it.second } + shownGenres.flatMap { it.second })
            .map { index.titleId(it.index) }.distinct()
        val summaries = summaries(shown.mapNotNull(store::title), contentLanguages, appLanguage).associate { it.id to it.copy(rank = ranks[it.id]) }
        val found = { hits: List<SearchIndex.Hit> -> hits.mapNotNull { summaries[index.titleId(it.index)] } }
        // The best Titles of either kind are among those shown, so their names are at hand.
        val titleNames = ranked.take(COMPLETIONS * 2).mapNotNull { summaries[index.titleId(it.index)]?.name }
        return SearchResults(
            movies = found(ofKind(TitleKind.Movie)),
            shows = found(ofKind(TitleKind.Show)),
            people = shownPeople.map { (name, hits) -> Person(name, found(hits)) },
            genres = shownGenres.map { (name, hits) -> Genre(name, found(hits)) },
            completions = completions(query, titleNames, shownPeople.map { it.first }, shownGenres.map { it.first }),
        )
    }

    /**
     * Up to [COMPLETIONS] queries one press away from [query], as Netflix offers them: Title names
     * first, then people, then genres, each kind keeping a few places and lending the rest. A name
     * the query already is, or one offered already, isn't offered again.
     */
    private fun completions(query: String, titles: List<String>, people: List<String>, genres: List<String>): List<String> {
        val typed = SearchIndex.words(query)
        val seen = HashSet<List<String>>()
        val offered = { names: List<String> -> names.filter { name -> SearchIndex.words(name).let { it != typed && seen.add(it) } } }
        val kinds = listOf(offered(titles), offered(people), offered(genres))
        val kept = listOf(COMPLETIONS - 3, 2, 1)
        val taken = kinds.zip(kept) { names, places -> minOf(names.size, places) }.toMutableList()
        // Places a kind leaves empty go to the others, Titles first.
        var spare = COMPLETIONS - taken.sum()
        for (kind in kinds.indices) {
            val more = minOf(spare, kinds[kind].size - taken[kind])
            taken[kind] += more
            spare -= more
        }
        return kinds.indices.flatMap { kinds[it].take(taken[it]) }
    }

    /** What TMDB's search finds for [query] through the cloud, or null when the cloud can't say right now. */
    private fun tmdbSearch(query: String): CloudSearch? {
        val now = Instant.now()
        if (cloud == null || !now.isAfter(maxOf(cloudRetryAt, searchRetryAt))) return null
        return try {
            cloud.search(query.trim().take(MAX_TMDB_QUERY))
        } catch (e: CloudException) {
            searchRetryAt = now + CLOUD_RETRY_AFTER
            null
        }
    }

    /**
     * Builds the search index now, so the Viewer's first letters don't wait for it. A build already
     * running in the background is sped up to the Viewer's priority and waited for.
     */
    suspend fun prepareSearch() {
        withContext(Dispatchers.IO) { rebuildSearchIndex() }
    }

    /**
     * Starts building the search index on a low-priority thread, unless it is built or being built,
     * so the first search after the app starts doesn't wait for it (about a minute on the stick)
     * and Home's frames don't either. Each Sync does this once it finishes.
     */
    fun prepareSearchInBackground() {
        if (searchIndexReady || !rebuildingSearch.compareAndSet(false, true)) return
        val builder = thread(start = false, name = "search-index", isDaemon = true, priority = Thread.MIN_PRIORITY) {
            try {
                // A Sync or Matches may change the catalog while it builds: the next build starts at once.
                // Metadata cached meanwhile waits for the next search, so browsing doesn't keep it building.
                do rebuildSearchIndex() while (searchIndex?.catalog != catalogGeneration.get())
            } catch (e: Exception) {
                // The Library closed meanwhile; the next search tries again.
            } finally {
                searchBuilder = null
                rebuildingSearch.set(false)
            }
        }
        searchBuilder = builder
        builder.start()
    }

    /** Whether the search index of the current catalog and metadata is built. */
    internal val searchIndexReady: Boolean
        get() = searchIndex?.let { it.catalog == catalogGeneration.get() && it.metadata == metadataGeneration.get() } == true

    private class BuiltIndex(val catalog: Int, val metadata: Int, val index: SearchIndex)

    /**
     * The search index last built. A Sync, Matches or metadata cached since then (often for the
     * results of the last search) only start a rebuild in the background, so typing never waits on
     * one (#241): only the first search with no index at all does. The old index's Titles and
     * Channels the catalog no longer has drop out of the results, and new ones show once it's rebuilt.
     */
    private fun searchIndex(): SearchIndex {
        val built = searchIndex ?: return rebuildSearchIndex(anyBuilt = true)
        if (!searchIndexReady) prepareSearchInBackground()
        return built.index
    }

    /**
     * The search index of the current catalog and metadata, built now unless it is, or [anyBuilt]
     * one; the Viewer waits for it.
     */
    private fun rebuildSearchIndex(anyBuilt: Boolean = false): SearchIndex {
        // The Viewer is waiting: a background build holding the lock gets their priority.
        if (Thread.currentThread() != searchBuilder) searchBuilder?.priority = Thread.NORM_PRIORITY
        return synchronized(searchLock) {
            val catalog = catalogGeneration.get()
            val metadata = metadataGeneration.get()
            searchIndex?.takeIf { anyBuilt || it.catalog == catalog && it.metadata == metadata }?.index
                ?: run {
                    onSearchIndexBuild()
                    buildSearchIndex(store, prefixTable).also { searchIndex = BuiltIndex(catalog, metadata, it) }
                }
        }
    }

    /** Runs before each build of the search index: tests hold a build there, as the stick's minute-long one would. */
    @Volatile internal var onSearchIndexBuild: () -> Unit = {}

    /**
     * [titles]' cards: TMDB's name and poster in the [appLanguage], the best Version in the Viewer's
     * [contentLanguages], and the art a focused card grows into.
     */
    private fun summaries(titles: List<StoredTitle>, contentLanguages: List<String>, appLanguage: String): List<TitleSummary> {
        val metadata = metadata(titles.map { it.id }, appLanguage)
        return titles.map { rowCard(it, contentLanguages, appLanguage, metadata) }
    }

    /** A Title with its Versions grouped by language, the Viewer's [contentLanguages] first; null when the catalog doesn't have it. */
    suspend fun title(id: TitleId, contentLanguages: List<String>, appLanguage: String, region: String = DEFAULT_REGION): TitleDetails? = withContext(Dispatchers.IO) {
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
            backdrop = metadata?.pageBackdrop,
            logo = metadata?.logo,
            cast = metadata?.cast.orEmpty(),
            rank = topRanks(region, contentLanguages, appLanguage)[id],
            fromTmdb = metadata != null,
        )
    }

    /**
     * A Show's Episodes, from the Provider's show details of each of its Versions (API calls, a
     * few at a time, never a stream), merged by season and episode number. Details are kept on the
     * device and asked again only once the Provider changes that Version's Show; when it can't be
     * reached, the details kept from before stand. The Episodes TMDB lists as not aired yet that no
     * Version has are added, named in [appLanguage], unless the cloud can't say right now. Each
     * Episode has how far the Viewer got in it, and [ShowEpisodes.next] is the one to watch next.
     * Null for a Movie, or a Show the catalog doesn't have.
     */
    suspend fun episodes(id: TitleId, contentLanguages: List<String>, appLanguage: String = "en"): ShowEpisodes? = withContext(Dispatchers.IO) {
        if (id.kind != TitleKind.Show) return@withContext null
        val title = store.title(id) ?: return@withContext null
        // Asked beside the Provider, which takes about a second a show Listing.
        val upcoming = async { upcoming(id, appLanguage) }
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
        val shows = title.listings.filter { it.id in read }.map { candidates(it) to read.getValue(it.id) }
        val played = store.episodeProgress(id)
        val merged = mergeEpisodes(shows, contentLanguages, store.fileReads(shows.flatMap { (_, episodes) -> episodes.map { it.id } }))
        val seasons = withUpcomingAndProgress(merged, upcoming.await(), played, LocalDate.now())
        ShowEpisodes(seasons, unreadVersions = title.listings.count { it.id !in read }, next = nextEpisode(seasons, played))
    }

    /** The Episodes TMDB lists Show [id] as airing next, through the cloud; none when it can't say right now. */
    /**
     * What TMDB knows of each Episode of season [season] of Show [id] for the Episode picker: its
     * still and runtime, by episode number, in the [appLanguage]. Asked of the cloud once a season
     * per run of the app; empty for a Show TMDB doesn't know, or while the cloud can't answer.
     */
    suspend fun seasonStills(id: TitleId, season: Int, appLanguage: String = "en"): Map<Int, EpisodeStill> = withContext(Dispatchers.IO) {
        if (cloud == null || id !is TitleId.Tmdb || id.kind != TitleKind.Show) return@withContext emptyMap()
        val key = Triple(id.tmdbId, season, primaryLanguage(appLanguage))
        seasonStillsCache[key]?.let { return@withContext it }
        if (!canAskCloud()) return@withContext emptyMap()
        val stills = try {
            cloud.season(id.tmdbId, season, key.third).associate { it.number to EpisodeStill(it.number, it.still?.let { path -> "https://image.tmdb.org/t/p/$STILL_SIZE$path" }, it.runtimeMinutes) }
        } catch (e: CloudException) {
            return@withContext emptyMap()
        }
        seasonStillsCache[key] = stills
        stills
    }

    /** The seasons' stills asked this run ([seasonStills]), by TMDB ID, season and language. */
    private val seasonStillsCache = ConcurrentHashMap<Triple<Long, Int, String>, Map<Int, EpisodeStill>>()

    private fun upcoming(id: TitleId, appLanguage: String): List<UpcomingEpisode> {
        if (cloud == null || id !is TitleId.Tmdb || !canAskCloud()) return emptyList()
        return try {
            cloud.upcoming(id.tmdbId, primaryLanguage(appLanguage))
        } catch (e: CloudException) {
            emptyList()
        }
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
        val stopped = stopped(id)
        ranked.firstOrNull()?.let {
            PlayPlan(
                id, withBackups(it, ranked), contentLanguages, leadersWithBackups(ranked), stopped?.position ?: 0,
                remembered = remembered, length = stopped?.duration, originalLanguage = originalLanguage(id), choices = titleChoices(id),
            )
        }
    }

    /**
     * What playing a Show's [episode] starts: its Best playable Version, chosen as a Movie's is, from
     * the Episodes [episodes] read before; it starts where the Viewer stopped, unless they finished it. Null for a Movie, or when the
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
        val plans = episodePlans(plan.titleId ?: return@withContext null, plan.contentLanguages, device, qualityCap) ?: return@withContext null
        plans.tailMap(playing).entries.asSequence().filter { it.key != playing }.firstNotNullOfOrNull { it.value() }
    }

    /** A Show's Episodes, in order, each with what playing it starts, from the show details kept on the device. */
    private fun episodePlans(id: TitleId, contentLanguages: List<String>, device: DeviceProfile, qualityCap: QualityCap): SortedMap<EpisodeNumber, () -> PlayPlan?>? {
        if (id.kind != TitleKind.Show) return null
        val byEpisode = episodeCandidates(id) ?: return null
        val remembered = rememberedTracks()
        val languages = rankingLanguages(remembered, contentLanguages)
        val stopped = store.episodeProgress(id).filter { !it.watched }.associateBy { it.episode }
        val original = originalLanguage(id)
        // The Show's: what the Viewer chose in one Episode carries to the next.
        val choices = titleChoices(id)
        val plans: SortedMap<EpisodeNumber, () -> PlayPlan?> = TreeMap(byEpisode.comparator())
        byEpisode.forEach { (episode, candidates) ->
            plans[episode] = {
                val ranked = playable(candidates, languages, device, qualityCap)
                ranked.firstOrNull()?.let {
                    val at = stopped[episode]
                    PlayPlan(id, withBackups(it, ranked), contentLanguages, leadersWithBackups(ranked), at?.position ?: 0, episode, remembered, at?.duration, original, choices)
                }
            }
        }
        return plans
    }

    /** Each of the Show [id]'s Episodes with its Versions' candidates, from the show details kept on the device. */
    private fun episodeCandidates(id: TitleId): SortedMap<EpisodeNumber, List<Candidate>>? {
        val title = store.title(id) ?: return null
        val kept = store.showDetails(title.listings.map { it.id })
        val shows = title.listings.filter { it.id in kept }.map { candidates(it) to kept.getValue(it.id).episodes }
        return episodeCandidates(shows, store.fileReads(shows.flatMap { (_, episodes) -> episodes.map { it.id } }))
    }

    /**
     * What choosing the Version [listingId] (a Provider Episode id) of the Show [id]'s [episode] in its
     * Versions plays, as [chosenPlayPlan] does for a Movie: that Version with its Backups, then the
     * Episode's other Versions, from where the Viewer stopped in it. Null when the Show doesn't have it.
     */
    suspend fun chosenEpisodePlayPlan(id: TitleId, episode: EpisodeNumber, listingId: Long, contentLanguages: List<String>): PlayPlan? = withContext(Dispatchers.IO) {
        if (id.kind != TitleKind.Show) return@withContext null
        val candidates = episodeCandidates(id)?.get(episode) ?: return@withContext null
        candidates.find { it.listing.id == listingId }?.let { chosen ->
            val version = withBackups(chosen, candidates)
            val remembered = rememberedTracks()
            val others = leadersWithBackups(preferred(candidates, rankingLanguages(remembered, contentLanguages)).filter { it.equivalence != chosen.equivalence })
            val at = store.episodeProgress(id).firstOrNull { it.episode == episode && !it.watched }
            PlayPlan(
                id, version, contentLanguages, listOf(version) + others, at?.position ?: 0, episode,
                remembered, at?.duration, originalLanguage(id), titleChoices(id),
            )
        }
    }

    /** The language [id] was released in, from TMDB's metadata kept on the device: the details screen has fetched it before Play. */
    private fun originalLanguage(id: TitleId): String? = (id as? TitleId.Tmdb)?.let(store::anyMetadata)?.let { TitleMetadata(it).originalLanguage }

    /** The audio and subtitle languages the Viewer last picked in the player. */
    fun rememberedTracks(): RememberedTracks = remembered ?: run {
        val subtitles = store.rememberedTrack(SUBTITLES)
        RememberedTracks(
            store.rememberedTrack(AUDIO),
            subtitles?.takeIf { it != SUBTITLES_OFF && it != CLOSED_CAPTIONS },
            subtitlesOff = subtitles == SUBTITLES_OFF,
            closedCaptions = subtitles == CLOSED_CAPTIONS,
        ).also { remembered = it }
    }

    /** Remembers that the Viewer picked audio in [language] (a BCP 47 tag), so it plays next time, and its Versions rank first. */
    suspend fun rememberAudio(language: String) = withContext(Dispatchers.IO) {
        store.rememberTrack(AUDIO, language)
        remembered = rememberedTracks().copy(audio = language)
    }

    /** Remembers that the Viewer picked subtitles in [language], or turned them off when null, so the next file starts the same. */
    suspend fun rememberSubtitles(language: String?) = withContext(Dispatchers.IO) {
        store.rememberTrack(SUBTITLES, language ?: SUBTITLES_OFF)
        remembered = rememberedTracks().copy(subtitles = language, subtitlesOff = language == null, closedCaptions = false)
    }

    /** Remembers that the Viewer turned on a Channel's closed captions, so they stay on in the other player and on the next Channel. */
    suspend fun rememberClosedCaptions() = withContext(Dispatchers.IO) {
        store.rememberTrack(SUBTITLES, CLOSED_CAPTIONS)
        remembered = rememberedTracks().copy(subtitles = null, subtitlesOff = false, closedCaptions = true)
    }

    @Volatile private var remembered: RememberedTracks? = null

    /** What the Viewer chose the last time [id] played (#291); nothing, when they never chose anything in it. */
    fun titleChoices(id: TitleId): TitleChoices = store.titleChoices(id)?.let(::titleChoices) ?: TitleChoices()

    /**
     * Keeps what the Viewer chose while [plan] plays, so the Title starts the same next time: a
     * Movie's, or its Show's, which its next Episode starts with too. A Channel's plan keeps nothing.
     */
    suspend fun saveChoices(plan: PlayPlan, choices: TitleChoices) = withContext(Dispatchers.IO) {
        val id = plan.titleId ?: return@withContext
        store.saveTitleChoices(id, choices.toJson(), Instant.now())
    }

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
            val stopped = stopped(id)
            PlayPlan(
                id, version, contentLanguages, listOf(version) + others, stopped?.position ?: 0,
                remembered = remembered, length = stopped?.duration, originalLanguage = originalLanguage(id), choices = titleChoices(id),
            )
        }
    }

    /**
     * The subtitles that can be downloaded for [plan]'s Movie or Episode in [languages] (ISO 639-1),
     * the best few in each language first. Empty for a Title without a TMDB ID, or when the cloud
     * can't answer: the Viewer plays on without them.
     */
    suspend fun subtitleOffers(plan: PlayPlan, languages: List<String>): List<SubtitleOffer> = withContext(Dispatchers.IO) {
        val id = plan.titleId as? TitleId.Tmdb
        if (cloud == null || id == null || languages.isEmpty()) return@withContext emptyList()
        try {
            cloud.searchSubtitles(id, plan.episode, languages.take(MAX_SUBTITLE_LANGUAGES))
        } catch (e: CloudException) {
            emptyList()
        }
    }

    /** Downloads the subtitle file [offer] names, through the cloud; null when it can't be had now. */
    suspend fun downloadSubtitle(offer: SubtitleOffer): DownloadedSubtitle? = withContext(Dispatchers.IO) {
        try {
            cloud?.downloadSubtitle(offer.id)
        } catch (e: CloudException) {
            null
        }
    }

    /** Where Play starts in [id]: where the Viewer stopped, or null for the start once they finished it. */
    private fun stopped(id: TitleId): StoredProgress? = store.progress(id)?.takeUnless { it.watched }

    /**
     * Records that the Viewer is at [position] of [duration] (milliseconds, null when the player
     * doesn't know it) in [id]. In its credits ([inCredits]) the Title counts as watched, and under a minute
     * in nothing is recorded, so a Title opened by mistake doesn't land in Continue watching.
     */
    internal fun saveProgress(id: TitleId, position: Long, duration: Long?) {
        val watched = inCredits(position, duration)
        if (!watched && position < MIN_RESUME_POSITION) return
        store.saveProgress(id, if (watched) 0 else position, duration, watched, Instant.now())
    }

    /** Records how far the Viewer is in what [plan] plays, as [saveProgress] does: a Movie, or a Show's Episode. A Channel has none. */
    internal fun saveProgress(plan: PlayPlan, position: Long, duration: Long?) {
        val id = plan.titleId ?: return
        val episode = plan.episode ?: return saveProgress(id, position, duration)
        val watched = inCredits(position, duration)
        if (!watched && position < MIN_RESUME_POSITION) return
        store.saveEpisodeProgress(id, episode, if (watched) 0 else position, duration, watched, Instant.now())
    }

    /** Records that the Viewer played [id] to its end. */
    internal fun finished(id: TitleId) = store.saveProgress(id, 0, null, watched = true, Instant.now())

    /** Records that the Viewer played what [plan] plays to its end. */
    internal fun finished(plan: PlayPlan) {
        val id = plan.titleId ?: return
        val episode = plan.episode ?: return finished(id)
        store.saveEpisodeProgress(id, episode, 0, null, watched = true, Instant.now())
    }

    /** Takes [id] out of Continue watching and forgets where the Viewer stopped, so Play starts it over. */
    suspend fun removeFromContinueWatching(id: TitleId) = withContext(Dispatchers.IO) { store.forgetProgress(id) }

    /**
     * The home screen's rows: Continue watching, then Live now (the Games of the [leagues] on that
     * are in progress with a Feed, as on the Live tab; no row at all otherwise), then the Charts for the Viewer's [region] (Top
     * movies, Top shows), then New on your service (Titles among the most recently added, most
     * popular on TMDB first), then TMDB's trending this week, then the Viewer's Favourites (in the order they were added; finished
     * Event channels left out), then one row per TMDB genre, its most common first. Browse rows and
     * Charts show only Titles with a Version in the Viewer's [contentLanguages], or an Untagged one
     * Likely in one; a Chart also only the Titles the Provider has, and none at all under
     * [MIN_CHART_SIZE]. Continue watching shows whatever the Viewer started, a Show at the Episode to
     * watch next, until they finish it. Genres come from the TMDB metadata of the newest Titles, so
     * they need the cloud once a week. A Title shows in one browse row only, the highest it fits
     * ([ScreenRows]); a genre row left with too few Titles gives its place to the next genre. Rows
     * with no Titles are left out.
     */
    suspend fun homeRows(contentLanguages: List<String>, appLanguage: String, region: String = DEFAULT_REGION, leagues: List<League> = emptyList()): List<HomeRow> =
        homeRows(contentLanguages, appLanguage, region, leagues, Instant.now())

    internal suspend fun homeRows(contentLanguages: List<String>, appLanguage: String, region: String, leagues: List<League>, now: Instant): List<HomeRow> = withContext(Dispatchers.IO) {
        // Home gets one Games row, Live now, while a Game with a Feed is on (docs/roadmap.md, M3): the same Games and Feeds as the
        // Live tab's, but its first rows never wait on the cloud: the kept Games stand, and the rows are read again once fresh ones land.
        val liveNow = matchedGames(leagues, contentLanguages, region, now, waitForCloud = false).current.filter { it.game.state == GameState.Live && it.best != null }
        val resumes = continueWatching(contentLanguages)
        val favourites = store.favourites(Instant.now() - EVENT_FINISHED_AFTER)
        val movies = store.titles(TitleKind.Movie, 0, HOME_MOVIES)
        val shows = store.titles(TitleKind.Show, 0, HOME_SHOWS)
        val charts = charts(region, appLanguage, now)
        val tops = listOf(ChartList.TopMovies to charts.topMovies, ChartList.TopShows to charts.topShows).map { (list, ids) -> list to ids.mapNotNull(store::title) }
        val trends = charts.trendingAll.mapNotNull(store::title)
        val metadata = metadata((resumes.map { it.first } + movies + shows + tops.flatMap { it.second } + trends).map { it.id }.distinct(), appLanguage)
        fun card(title: StoredTitle) = rowCard(title, contentLanguages, appLanguage, metadata)
        val popularFirst = popularFirst(metadata)
        // The newest movies, then the newest shows, in the Viewer's languages.
        val browsable = listOf(movies, shows).map { titles ->
            titles.filter { inContentLanguages(candidates(it), contentLanguages, metadata[it.id]?.originalLanguage) }
        }
        val newest = (browsable[0].take(NEW_MOVIES) + browsable[1].take(NEW_SHOWS)).sortedWith(popularFirst)
        val byGenre = LinkedHashMap<String, MutableList<StoredTitle>>()
        browsable.flatten().forEach { title -> metadata[title.id]?.genres?.forEach { byGenre.getOrPut(it) { mutableListOf() } += title } }
        val screen = ScreenRows()
        val charted = tops.map { (list, titles) -> list to screen.chart(watchable(titles, contentLanguages, metadata), MIN_CHART_SIZE) }
        val new = screen.row(newest, ROW_SIZE)
        // Trending sits below New, so it skips what New shows.
        val trending = screen.row(watchable(trends, contentLanguages, metadata), ROW_SIZE, MIN_CHART_SIZE)
        val genres = buildList {
            for ((name, titles) in byGenre.entries.sortedByDescending { it.value.size }) {
                if (size == GENRE_ROWS) break
                val row = screen.row(titles.sortedWith(popularFirst), ROW_SIZE, MIN_GENRE_SIZE)
                if (row.isNotEmpty()) add(name to row)
            }
        }
        buildList {
            if (resumes.isNotEmpty()) {
                add(
                    HomeRow.ContinueWatching(
                        resumes.map { (title, at) ->
                            val played = when (at) {
                                is StoredProgress -> at.position to at.duration
                                is ShowInProgress -> at.played?.let { it.position to it.duration }
                                else -> null
                            }
                            val duration = played?.second?.takeIf { it > 0 }
                            val fraction = duration?.let { (played.first.toFloat() / it).coerceIn(0f, 1f) }
                            val left = duration?.let { Duration.ofMillis((it - played.first).coerceAtLeast(0)) }
                            InProgress(card(title), metadata[title.id]?.backdrop, fraction, left, (at as? ShowInProgress)?.next)
                        },
                    ),
                )
            }
            if (liveNow.isNotEmpty()) add(HomeRow.LiveNow(liveNow))
            charted.forEach { (list, titles) -> if (titles.isNotEmpty()) add(HomeRow.Chart(list, region.takeUnless { charts.worldwide }, titles.map { card(it) })) }
            if (new.isNotEmpty()) add(HomeRow.NewOnYourService(new.map { card(it) }))
            if (trending.isNotEmpty()) add(HomeRow.Chart(ChartList.Trending, null, trending.map { card(it) }))
            if (favourites.isNotEmpty()) add(HomeRow.Favourites(favourites))
            genres.forEach { (name, titles) -> add(HomeRow.Genre(name, titles.map { card(it) })) }
            wantGuide(favourites = favourites)
        }
    }

    /**
     * Continue watching's Titles, last played first, each with where the Viewer is in it: Movies by
     * how far they got ([StoredProgress]), Shows by the Episode they're at ([ShowInProgress]); a Show
     * they Watched to its end is left out.
     */
    private fun continueWatching(contentLanguages: List<String>): List<Pair<StoredTitle, Any>> {
        val progress = store.inProgress(ROW_SIZE).associateBy { it.id }
        return store.lastPlayed(ROW_SIZE).map { it.first }.distinct().mapNotNull { id ->
            val title = store.title(id) ?: return@mapNotNull null
            if (id.kind == TitleKind.Show) showInProgress(title, contentLanguages)?.let { title to it } else progress[id]?.let { title to it }
        }
    }

    /**
     * The Title [screen]'s hero features, chosen each time the screen is entered: one of the top
     * [FEATURED_FROM] of its Charts as its Top rows show them (Top movies and Top shows on Home,
     * taken in turn, a movie first; the kind's Top row on Movies and Shows), skipping the Titles in
     * Continue watching and those without a textless backdrop or a logo, which the hero needs to
     * look right. It rotates: each call features the one after the Title featured last on that
     * screen, kept on the device, so the same Title doesn't come back on the next entry, and from
     * the first again past the last. Null when no Title qualifies: the screen opens on its rows.
     */
    suspend fun featured(screen: FeaturedScreen, contentLanguages: List<String>, appLanguage: String, region: String = DEFAULT_REGION): FeaturedTitle? =
        featured(screen, contentLanguages, appLanguage, region, Instant.now())

    internal suspend fun featured(screen: FeaturedScreen, contentLanguages: List<String>, appLanguage: String, region: String, now: Instant): FeaturedTitle? = withContext(Dispatchers.IO) {
        val charts = charts(region, appLanguage, now)
        val lists = when (screen) {
            FeaturedScreen.Home -> listOf(charts.topMovies, charts.topShows)
            FeaturedScreen.Movies -> listOf(charts.topMovies)
            FeaturedScreen.Shows -> listOf(charts.topShows)
        }.map { it.mapNotNull(store::title) }
        val metadata = metadata(lists.flatten().map { it.id }.distinct(), appLanguage)
        // Each Top row as it shows, none under [MIN_CHART_SIZE], then its top few.
        val tops = lists.map { titles -> watchable(titles, contentLanguages, metadata).takeIf { it.size >= MIN_CHART_SIZE }.orEmpty().take(FEATURED_FROM) }
        val started = continueWatching(contentLanguages).mapTo(HashSet()) { it.first.id }
        val candidates = (0 until (tops.maxOfOrNull { it.size } ?: 0)).flatMap { i -> tops.mapNotNull { it.getOrNull(i) } }.filter {
            val about = metadata[it.id]
            it.id !in started && about?.heroBackdrop != null && about.logo != null
        }
        if (candidates.isEmpty()) return@withContext null
        val key = screen.name.lowercase()
        val last = lastFeatured[screen] ?: store.lastFeatured(key)
        val title = candidates[(candidates.indexOfFirst { it.id == last } + 1) % candidates.size]
        lastFeatured[screen] = title.id
        saveLater { store.saveLastFeatured(key, title.id) }
        val about = metadata.getValue(title.id)
        val shown = shown(candidates(title), contentLanguages, about, appLanguage)
        FeaturedTitle(title.id, shown.name, shown.year, about.runtimeMinutes, about.genres, about.description(appLanguage), about.heroBackdrop!!, about.logo!!, shown.best.attributes)
    }

    /** The Title featured last on each screen, as [featured] keeps it, so it needn't be read from the device again. */
    private val lastFeatured = ConcurrentHashMap<FeaturedScreen, TitleId>()

    /**
     * What a Title's Play starts, as its details' Play does: for a Movie, its [playPlan]; for a Show,
     * the plan of its Episode to watch next ([episodes], [episodePlayPlan]). Null when there is
     * nothing to play.
     */
    suspend fun titlePlayPlan(id: TitleId, contentLanguages: List<String>, appLanguage: String, device: DeviceProfile, qualityCap: QualityCap = QualityCap.UHD): PlayPlan? = when (id.kind) {
        TitleKind.Movie -> playPlan(id, contentLanguages, device, qualityCap)
        TitleKind.Show -> episodes(id, contentLanguages, appLanguage)?.next?.let { episodePlayPlan(id, it, contentLanguages, device, qualityCap) }
    }

    /**
     * The Live tab's rows (CONTEXT.md's Row), for the [leagues] on, in Settings' order: Live now, the
     * Games in progress with at least one Feed; Today, the Games still to start today (the device's
     * day); one row per League with its Games of the week ahead; then the Viewer's Favourites, in the
     * order they were added, finished Event channels left out; then the Channel browser's countries
     * ([liveCountries], for the [region] and [contentLanguages]). A Game shows once, in the highest row
     * it fits, by start; a live Game with no Feed left (none, or every one marked "Not this game") is
     * hidden, an upcoming one with none is shown (its card disabled); a finished Game isn't shown.
     * After the League rows, More live events: the Event channels whose name gives a start within
     * [MORE_LIVE_EVENTS_WITHIN] of now, either way, that no Game matched (a finished Game's included,
     * since the schedule's state is trusted over the name's time; an Event channel of a League that
     * is off is unmatched, so it shows here, the row being the catch-all for what no schedule covers;
     * one marked `Live |` or `Next |` without a start isn't shown, its time being unknown), by start
     * then the Provider's order, as Channels. Rows with nothing are left out: a League with no Game
     * in seven days gets none. The Games come from [games] (the cloud's, or the kept copy when it
     * fails) and their Feeds from the catalog ([matchedGames], run once for both the Game rows and
     * More live events), ranked for the [contentLanguages]. After More live events, Replays: the
     * Sports replays whose two teams are teams of the Leagues on (the teams the Leagues' Games name,
     * finished ones included), newest first by the date in the name, then by when the Provider added
     * them ([matchReplays]), at most [REPLAYS_ROW]; left out when none matches.
     */
    suspend fun liveRows(leagues: List<League> = emptyList(), region: String = DEFAULT_REGION, contentLanguages: List<String> = emptyList()): List<HomeRow> =
        liveRows(leagues, region, contentLanguages, Instant.now(), ZoneId.systemDefault())

    internal suspend fun liveRows(leagues: List<League>, region: String, contentLanguages: List<String>, now: Instant, zone: ZoneId): List<HomeRow> = withContext(Dispatchers.IO) {
        val favourites = store.favourites(now - EVENT_FINISHED_AFTER)
        val countries = liveCountries(region, contentLanguages)
        val matchedGames = matchedGames(leagues, contentLanguages, region, now)
        val matched = matchedGames.current
        // An Event channel a Game matched, finished or not, is that Game's Feed, not one no Game matched.
        val matchedEventChannels = matchedGames.all.flatMapTo(HashSet()) { entry -> entry.feeds.filter { it.kind == FeedKind.EventChannel }.map { it.channel.key } }
        val moreLiveEvents = matchedGames.events
            .mapNotNull { listing -> listing.start?.let { UnmatchedEventChannel(listing.channel, it) } }
            .filter { it.channel.key !in matchedEventChannels && Duration.between(now, it.start).abs() <= MORE_LIVE_EVENTS_WITHIN }
            .sortedBy { it.start }
        val schedule = matchedGames.all.map { it.game }
        val replays = if (schedule.isEmpty()) emptyList() else {
            val teams = leagues.associateWith { league -> schedule.filter { it.league == league.key }.flatMap { it.teams } }.filterValues { it.isNotEmpty() }
            // Matched once per catalog and set of teams: the tab reads its rows every couple of minutes, and the catalog holds thousands of Replays.
            val key = ReplaysKey(catalogGeneration.get(), teams.flatMapTo(HashSet()) { (league, members) -> members.map { league to it.name } })
            matchedReplays?.takeIf { it.key == key }?.replays
                ?: matchReplays(store.replays(withTeams = true), teams).take(REPLAYS_ROW).also { matchedReplays = MatchedReplays(key, it) }
        }
        val today = now.atZone(zone).toLocalDate()
        val weekAhead = now + WEEK_AHEAD
        val shown = HashSet<String>()
        // A Game shows in the highest row it fits.
        fun row(fits: (GameFeeds) -> Boolean): List<GameFeeds> = matched.filter { it.key !in shown && fits(it) }.also { taken -> taken.forEach { shown += it.key } }
        val liveNow = row { it.game.state == GameState.Live && it.best != null }
        val todayRow = row { it.game.state == GameState.Upcoming && it.game.start.atZone(zone).toLocalDate() == today }
        val perLeague = leagues.mapNotNull { league ->
            row { it.game.league == league.key && it.game.state == GameState.Upcoming && it.game.start < weekAhead }.takeIf { it.isNotEmpty() }?.let { HomeRow.LeagueGames(league, it) }
        }
        buildList {
            if (liveNow.isNotEmpty()) add(HomeRow.LiveNow(liveNow))
            if (todayRow.isNotEmpty()) add(HomeRow.Today(todayRow))
            addAll(perLeague)
            if (moreLiveEvents.isNotEmpty()) add(HomeRow.MoreLiveEvents(moreLiveEvents))
            if (replays.isNotEmpty()) add(HomeRow.Replays(replays))
            if (favourites.isNotEmpty()) add(HomeRow.Favourites(favourites))
            if (countries.isNotEmpty()) add(HomeRow.Channels(countries))
            wantGuide(favourites = favourites)
        }
    }

    /** Every Sports replay the catalog has (CONTEXT.md), as its name reads, in the Provider's order. */
    internal suspend fun replays(): List<Replay> = withContext(Dispatchers.IO) { store.replays() }

    /** What the Replays row was last matched for, and what it holds. */
    private class MatchedReplays(val key: ReplaysKey, val replays: List<LeagueReplay>)

    /** The catalog ([generation]) and the Leagues' [teams] (by name) a Replays row was matched for. */
    private data class ReplaysKey(val generation: Int, val teams: Set<Pair<League, String>>)

    @Volatile private var matchedReplays: MatchedReplays? = null

    /**
     * A finished [game]'s Replay: the Sports replay naming both its teams ([replayOf]) and dated its
     * day in the device's zone, or, failing one, the day after (a Provider's clock ahead of the
     * Viewer's), the last added when several. Null for a Game not over, and until the Provider has one.
     */
    suspend fun gameReplay(game: Game): Replay? = gameReplay(game, ZoneId.systemDefault())

    internal suspend fun gameReplay(game: Game, zone: ZoneId): Replay? = withContext(Dispatchers.IO) {
        if (game.state != GameState.Over) return@withContext null
        val day = game.start.atZone(zone).toLocalDate()
        val dated = store.replaysDated(day, day.plusDays(1)).filter { replayOf(it, game) }
        listOf(day, day.plusDays(1)).firstNotNullOfOrNull { on ->
            dated.filter { it.date == on }.maxWithOrNull(compareBy<Replay>({ it.added }, { it.listingId }))
        }
    }

    /**
     * What playing [replay] starts: its Listing's Version, as the Prefix table describes it, on its
     * own (a Replay has no Backups), from where the Viewer stopped in it. It is keyed as an Unmatched
     * Movie, so where they stopped outlives Syncs; it never joins Continue watching, which shows Titles.
     */
    suspend fun replayPlayPlan(replay: Replay, contentLanguages: List<String>): PlayPlan = withContext(Dispatchers.IO) {
        val id = TitleId.Unmatched(TitleKind.Movie, replay.listingId)
        val version = Version(replay.listingId, replay.name, prefixTable.describe(replay.name, replay.category).attributes)
        val stopped = stopped(id)
        PlayPlan(id, version, contentLanguages, listOf(version), stopped?.position ?: 0, remembered = rememberedTracks(), length = stopped?.duration, choices = titleChoices(id))
    }

    /**
     * Marks [feed] "Not this game" for [entry]'s Game on this device (CONTEXT.md: Feed), and answers
     * the entry as the Live tab will now list it: the Feed kept, last and [Feed.dropped], the rest
     * ranked for the [contentLanguages] and [region] as before, so the caller plays [GameFeeds.best]
     * next. The mark is kept by Game and Channel, so it survives Syncs and holds for every variant
     * of the Channel, and is forgotten once the Game is long over ([DROPPED_KEPT_FOR]). Once every
     * named Feed is dropped, the League network joins, as it does for a Game with none.
     */
    suspend fun notThisGame(entry: GameFeeds, feed: Feed, contentLanguages: List<String>, region: String = DEFAULT_REGION, now: Instant = Instant.now()): GameFeeds = withContext(Dispatchers.IO) {
        store.dropFeed(entry.key, feed.channel.key, now, now - DROPPED_KEPT_FOR)
        // Matched again, so the League network joins once every named Feed is dropped.
        matchedFeeds(listOf(entry.game), contentLanguages, region, now).all.firstOrNull()
            ?: entry.copy(feeds = rankedFeeds(entry.feeds.map { if (it.channel.key == feed.channel.key) it.copy(dropped = true) else it }, contentLanguages))
    }

    /**
     * The Games of the [leagues] on, every one the device has (finished ones too), each with its
     * Feeds from the catalog ([matchedFeeds]) ranked for the [contentLanguages] and [region], by
     * start, with the Event channels the matching looked at: what the Live tab's Game rows and
     * Home's Live now row are made of ([MatchedGames.current]), and what More live events and
     * Replays read. The Games come from [games] (the cloud's, or the kept copy when it fails or
     * when the read doesn't [waitForCloud]); with no League on, or none the device has Games for,
     * none are matched.
     */
    private suspend fun matchedGames(leagues: List<League>, contentLanguages: List<String>, region: String, now: Instant, waitForCloud: Boolean = true): MatchedGames =
        matchedFeeds(games(leagues.map { it.key }, now, waitForCloud), contentLanguages, region, now)

    /**
     * Each of [games] with its Feeds from the catalog ([matchFeeds]): the Event channels on around
     * [now], the Channels of the Games' Broadcasters and League networks under every spelling, and
     * the Feeds marked "Not this game" on this device, ranked for the [contentLanguages] and [region].
     */
    private fun matchedFeeds(games: List<Game>, contentLanguages: List<String>, region: String, now: Instant): MatchedGames {
        val categories = store.liveCategories().associateBy { it.id }
        val events = store.eventChannels(now - EVENT_WINDOW_BEHIND, now + EVENT_WINDOW_AHEAD).map { (channel, start) ->
            EventChannelListing(channel, start, channel.categoryIds.firstNotNullOfOrNull { categories[it] })
        }
        if (games.isEmpty()) return MatchedGames(emptyList(), events)
        val dropped = store.droppedFeeds(games.map { "${it.league}/${it.id}" })
        return MatchedGames(matchFeeds(games, events, store.channelsNamed(broadcasterChannelNames(games)), contentLanguages, now, dropped, region), events)
    }

    /** What [matchedGames] found: [all] the Games with their Feeds, finished ones included, and the Event channels in the matching window. */
    internal class MatchedGames(val all: List<GameFeeds>, val events: List<EventChannelListing>) {
        /** The Games not over: what the Game rows show. */
        val current: List<GameFeeds> get() = all.filter { it.game.state != GameState.Over }
    }

    /**
     * The Movies or Shows tab's rows, for [kind]: the Top 10 of that kind in the Viewer's [region]
     * (or the world's), TMDB's trending of that kind this week, then New movies (or shows): the
     * newest Titles of that kind, most popular on TMDB first, then a row per genre: its most popular
     * Titles across TMDB's whole catalog (the cloud's Discover lists), in TMDB's order, the genres with
     * the most of the Provider's Titles first. Each row shows only the Provider's Titles with a
     * Version in the Viewer's [contentLanguages], or an Untagged one Likely in one, a Chart none at
     * all under [MIN_CHART_SIZE] and a genre none under [MIN_GENRE_SIZE]. The tab shows a Title once,
     * in the highest row it fits ([ScreenRows]), on its own: a Title on Home's Top row is on the
     * tab's too. Rows with no Titles are left out. Like Home, the Charts and genre lists kept on the
     * device stand while the cloud can't answer.
     */
    suspend fun tabRows(kind: TitleKind, contentLanguages: List<String>, appLanguage: String, region: String = DEFAULT_REGION): List<HomeRow> =
        tabRows(kind, contentLanguages, appLanguage, region, Instant.now())

    internal suspend fun tabRows(kind: TitleKind, contentLanguages: List<String>, appLanguage: String, region: String, now: Instant): List<HomeRow> = withContext(Dispatchers.IO) {
        val charts = charts(region, appLanguage, now)
        val movies = kind == TitleKind.Movie
        val topList = if (movies) ChartList.TopMovies else ChartList.TopShows
        val trendingList = if (movies) ChartList.TrendingMovies else ChartList.TrendingShows
        val tops = (if (movies) charts.topMovies else charts.topShows).mapNotNull(store::title)
        val trends = (if (movies) charts.trendingMovies else charts.trendingShows).mapNotNull(store::title)
        val newest = store.titles(kind, 0, TAB_NEW)
        val metadata = metadata((tops + trends + newest).map { it.id }.distinct(), appLanguage)
        val screen = ScreenRows()
        val top = screen.chart(watchable(tops, contentLanguages, metadata), MIN_CHART_SIZE)
        val trending = screen.row(watchable(trends, contentLanguages, metadata), ROW_SIZE, MIN_CHART_SIZE)
        val new = screen.row(watchable(newest, contentLanguages, metadata).sortedWith(popularFirst(metadata)), ROW_SIZE)
        // The genre rows go after New, through the same [screen], so they skip what's shown above.
        val genres = genreLists(charts.genres.filter { it.kind == kind }, contentLanguages, appLanguage).mapNotNull { (name, titles) ->
            screen.row(titles, ROW_SIZE, MIN_GENRE_SIZE).takeIf { it.isNotEmpty() }?.let { name to it }
        }
        val all = metadata + metadata(genres.flatMap { (_, titles) -> titles.map { it.id } }, appLanguage)
        fun cards(titles: List<StoredTitle>) = titles.map { rowCard(it, contentLanguages, appLanguage, all) }
        buildList {
            if (top.isNotEmpty()) add(HomeRow.Chart(topList, region.takeUnless { charts.worldwide }, cards(top)))
            if (trending.isNotEmpty()) add(HomeRow.Chart(trendingList, null, cards(trending)))
            if (new.isNotEmpty()) add(HomeRow.NewOnYourService(cards(new), kind))
            genres.forEach { (name, titles) -> add(HomeRow.Genre(name, cards(titles))) }
        }
    }

    /**
     * Discover's genre [lists] kept to the Provider's Titles the Viewer can watch ([watchable]), each
     * in TMDB's order (most popular across TMDB first), the genres with the most such Titles first.
     * Lists run to a few hundred Titles each, so their Titles and Versions are read in a few queries,
     * and metadata is only read where it decides: for Untagged Titles, whose original language says
     * whether they are Likely in a Content language. The cloud is asked only about those near the
     * top of a genre, where they would show; the rest go by the metadata the device already has.
     */
    private fun genreLists(lists: List<Charts.GenreList>, contentLanguages: List<String>, appLanguage: String): List<Pair<String, List<StoredTitle>>> {
        val listed = store.titles(lists.flatMap { it.titles })
        val versions = candidates(listed.values)
        fun likely(id: TitleId, originalLanguage: String?) = inContentLanguages(versions.getValue(id), contentLanguages, originalLanguage)
        val tagged = listed.keys.filterTo(HashSet()) { likely(it, null) }
        val untagged = listed.keys.filterTo(HashSet()) { id -> id !in tagged && versions.getValue(id).any { it.attributes.language?.value == null } }
        val nearTop = lists.flatMap { list -> list.titles.filter { it in listed }.take(ROW_SIZE) }.filter { it in untagged }.distinct()
        val originalLanguages = keptMetadata(untagged - nearTop.toSet(), appLanguage) + metadata(nearTop, appLanguage)
        return lists
            .map { list -> list.name to list.titles.filter { it in tagged || it in untagged && likely(it, originalLanguages[it]?.originalLanguage) }.map(listed::getValue) }
            .sortedByDescending { (_, titles) -> titles.size }
    }

    /** The TMDB metadata of [ids] the device already has in [appLanguage]'s language, however old, without asking the cloud. */
    private fun keptMetadata(ids: Collection<TitleId>, appLanguage: String): Map<TitleId, TitleMetadata> =
        storedMetadata(ids.filterIsInstance<TitleId.Tmdb>(), primaryLanguage(appLanguage)).mapNotNull { (id, stored) -> stored.body?.let { id to TitleMetadata(it) } }.toMap()

    /** The metadata of [ids] in [language] the device has, with the cloud's answers still waiting on their save. */
    private fun storedMetadata(ids: List<TitleId.Tmdb>, language: String): Map<TitleId.Tmdb, StoredMetadata> {
        val stored = store.metadata(ids, language)
        if (unsavedMetadata.isEmpty()) return stored
        return stored + ids.mapNotNull { id -> unsavedMetadata[language to id]?.let { id to it } }
    }

    /**
     * Runs [save] on the saving thread ([saves]), then [after], whether it saved or not. It waits for
     * the save while no Sync holds the writer, so what is read next (the search index) has it, and
     * stops waiting once one does. A save that fails loses nothing but a cached answer, asked of the
     * cloud again next time.
     */
    private fun saveLater(after: () -> Unit = {}, save: () -> Unit) {
        val saving = try {
            saves.submit {
                try {
                    save()
                } catch (e: Exception) {
                    // The catalog is closing, or the disk is full: the answer is asked for again.
                } finally {
                    after()
                }
            }
        } catch (e: RejectedExecutionException) {
            return after()
        }
        while (!syncWriting) {
            try {
                saving.get(SAVE_WAIT_STEP_MS, TimeUnit.MILLISECONDS)
                return
            } catch (e: TimeoutException) {
                // Still saving: wait on, unless a Sync has taken the writer meanwhile.
            }
        }
    }

    /** [title]'s card on a row: its best Version in the Viewer's [contentLanguages], and what the info under the rail tells about it. */
    private fun rowCard(title: StoredTitle, contentLanguages: List<String>, appLanguage: String, metadata: Map<TitleId, TitleMetadata>): TitleSummary {
        val shown = shown(candidates(title), contentLanguages, metadata[title.id], appLanguage)
        val about = metadata[title.id]?.let { TitleAbout(it.description(appLanguage), it.genres, it.runtimeMinutes, it.cardLogo, it.textlessPoster.takeIf { _ -> it.cardLogo != null }, it.textlessBackdrop) }
        return TitleSummary(title.id, shown.name, shown.year, shown.image, shown.best.attributes, about)
    }

    /** Titles most popular on TMDB first; those without metadata last, in their order. */
    private fun popularFirst(metadata: Map<TitleId, TitleMetadata>) = compareByDescending<StoredTitle> { metadata[it.id]?.popularity ?: 0.0 }

    /**
     * Where the Viewer is in Show [title], from the show details read when its details last opened:
     * the Episode to watch next and how far they got in it. Null once they Watched its last Episode,
     * or when its details were never read.
     */
    private fun showInProgress(title: StoredTitle, contentLanguages: List<String>): ShowInProgress? {
        val read = store.showDetails(title.listings.map { it.id })
        val shows = title.listings.filter { it.id in read }.map { candidates(it) to read.getValue(it.id).episodes }
        val played = store.episodeProgress(title.id)
        val seasons = withUpcomingAndProgress(mergeEpisodes(shows, contentLanguages), emptyList(), played, LocalDate.now())
        val next = nextEpisode(seasons, played) ?: return null
        val episode = seasons.flatMap { it.episodes }.single { it.id == next }
        if (episode.watched) return null
        val stopped = played.firstOrNull { it.episode == next }
        return ShowInProgress(NextEpisode(next, episode.name, started = stopped != null), stopped)
    }

    /** The Episode of a Show the Viewer is at, and where they stopped in it when they [played] it. */
    private class ShowInProgress(val next: NextEpisode, val played: StoredEpisodeProgress?)

    /**
     * Takes the Viewer's connection to play [version], waiting until the [Connection] holding it is
     * closed: a second stream is never opened while one is in use. Close it when playback ends. An
     * [episode]'s Version streams from the Provider's Shows, not its Movies, and a Channel's (one
     * with a [Version.liveFormat]) from its live streams.
     */
    suspend fun connect(version: Version, episode: Boolean = false): Connection {
        val location = withContext(Dispatchers.IO) {
            val login = credentials.load() ?: throw ProviderException(WrongLogin)
            val client = XtreamClient(login, http)
            // Most of the surveyed Provider's files are MKV.
            val live = version.liveFormat
            if (live != null) client.liveLocation(version.listingId, live)
            else if (episode) client.episodeLocation(version.listingId, store.episodeContainer(version.listingId) ?: "mkv")
            else client.movieLocation(version.listingId, store.container(version.listingId) ?: "mkv")
        }
        return Connection(location, connection.lease())
    }

    /**
     * What a Title's details page starts, without the page waiting for it: for a Movie, the bitrates
     * from the Provider's movie details of its Versions (API calls, never a stream); what Viewers of
     * the same Provider read before (from the cloud); then Header reads one at a time while the
     * Viewer's connection is free. A Movie's are of the best unconfirmed Version in each language, the
     * Viewer's [contentLanguages] first. A Show's are of each Episode's, from the show details
     * [episodes] kept, in the Show's order: the Viewer's languages first, and among them first the
     * Versions whose first audio Track, as the Provider's details name it, disagrees with the Prefix.
     * Pressing Play takes the connection at once and ends the reads, and so does the Provider
     * refusing one. Calls [changed] each time a Version's attributes change, so the page shows them.
     * Results are kept on the device and shared through the cloud, so a Version is never read twice.
     */
    suspend fun readHeaders(id: TitleId, contentLanguages: List<String>, changed: suspend () -> Unit) = withContext(Dispatchers.IO) {
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
        val shows = if (id.kind == TitleKind.Show) {
            val kept = store.showDetails(title.listings.map { it.id })
            title.listings.filter { it.id in kept }.map { candidates(it) to kept.getValue(it.id).episodes }
        } else {
            readBitrates(client, title.listings.map { it.id }, changed)
            null
        }
        // The Provider numbers Movies and Episodes from one sequence, so Listing ids don't collide.
        val ids = shows?.flatMap { (_, episodes) -> episodes.map { it.id } } ?: title.listings.map { it.id }
        val provider = providerKey(login)
        shareFileReads(provider)
        if (lookUpFileReads(provider, ids)) changed()
        // This page's Versions come before Background reading's.
        connection.cancelRead()
        val unread = if (shows == null) unread(title, contentLanguages) else unreadEpisodes(shows, contentLanguages)
        for (version in unread) {
            val container = (if (shows == null) store.container(version.listingId) else store.episodeContainer(version.listingId)) ?: "mkv"
            if (container !in HEADER_READABLE) continue
            val location = if (shows == null) client.movieLocation(version.listingId, container) else client.episodeLocation(version.listingId, container)
            val read = try {
                reads.withLock { connection.whileFree { Read(headerReader.read(location, container)) } }
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

    /** Keeps the bitrates the Provider's movie details give the Movie Listings [ids] not asked before. */
    private suspend fun readBitrates(client: XtreamClient, ids: List<Long>, changed: suspend () -> Unit) {
        val asked = store.movieBitrates(ids).keys
        val unasked = ids.filter { it !in asked }
        if (unasked.isEmpty()) return
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
     * newest Movies in the Viewer's [contentLanguages]. A dead file is kept as unreadable and passed
     * over; a Provider answering with another error stops it until the next day, and one not
     * answering pauses it for a quarter of an hour. Each read that Confirms a card's Version is
     * announced in [confirmations].
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
        return (languages + otherLanguages).mapNotNull { firstUnread(it, read) }
    }

    /**
     * The best Version not yet read in each language of each of [shows]' Episodes, in the Show's order:
     * the Viewer's [contentLanguages] first, and among them first those whose first audio Track, as
     * the Provider's details name it, disagrees with the Prefix.
     */
    private fun unreadEpisodes(shows: List<Pair<Candidate, List<ProviderEpisode>>>, contentLanguages: List<String>): List<Version> {
        val reads = store.fileReads(shows.flatMap { (_, episodes) -> episodes.map { it.id } })
        val disagreeing = shows.flatMap { (show, episodes) -> episodes.filter { firstTrackDisagrees(show.attributes, it) }.map { it.id } }.toSet()
        val unread = mergeEpisodes(shows, contentLanguages, reads).flatMap { it.episodes }.flatMap { episode ->
            episode.languages.mapNotNull { firstUnread(it, reads.keys)?.let { version -> version to true } } +
                episode.otherLanguages.mapNotNull { firstUnread(it, reads.keys)?.let { version -> version to false } }
        }
        // A stable sort, so the Show's order stands within each.
        return unread.sortedWith(compareBy({ (_, wanted) -> !wanted }, { (version, _) -> version.listingId !in disagreeing })).map { it.first }
    }

    private fun firstUnread(group: LanguageGroup, read: Set<Long>): Version? =
        group.versions.flatMap { listOf(it) + it.backups }.firstOrNull { it.listingId !in read }

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
        val cached = storedMetadata(tmdbIds, language).toMutableMap()
        val now = Instant.now()
        // An answer from before the cloud gave textless backdrops (and textless posters and logos) is asked again: it would otherwise lack them for a week.
        val wanted = tmdbIds.filter { id ->
            val stored = cached[id]
            stored?.fetchedAt?.isAfter(now - METADATA_FRESH_FOR) != true || stored.body?.contains("\"textlessBackdropPath\"") == false
        }
        if (cloud != null && wanted.isNotEmpty() && now.isAfter(cloudRetryAt)) {
            try {
                wanted.chunked(CloudClient.MAX_BATCH).forEach { batch ->
                    val answer = cloud.metadata(language, batch)
                    val answered = answer.found.map { (id, body) -> id to StoredMetadata(body, now) } + answer.notFound.map { it to StoredMetadata(null, now) }
                    answered.forEach { (id, stored) ->
                        cached[id] = stored
                        unsavedMetadata[language to id] = stored
                    }
                    // Saved without waiting on a Sync; the search index is built with it once it is.
                    saveLater(after = { answered.forEach { (id, stored) -> unsavedMetadata.remove(language to id, stored) } }) {
                        store.saveMetadata(language, answer.found, answer.notFound, now)
                        if (answer.found.isNotEmpty()) metadataGeneration.incrementAndGet()
                    }
                }
            } catch (e: CloudException) {
                // The cards show what the Provider says until the cloud is back.
                cloudRetryAt = now + CLOUD_RETRY_AFTER
            }
        }
        return buildMap { cached.forEach { (id, stored) -> stored.body?.let { put(id, TitleMetadata(it)) } } }
    }

    /** A Chart's [titles] the Viewer can watch: those with a Version in their [contentLanguages], or an Untagged one Likely in one. */
    private fun watchable(titles: List<StoredTitle>, contentLanguages: List<String>, metadata: Map<TitleId, TitleMetadata>): List<StoredTitle> =
        titles.filter { inContentLanguages(candidates(it), contentLanguages, metadata[it.id]?.originalLanguage) }

    /**
     * Each Top 10 Title's rank in [region], as its Top row on Home numbers it: counted among the
     * Provider's Titles the Viewer can watch ([watchable]). Trending carries none.
     */
    private fun topRanks(region: String, contentLanguages: List<String>, appLanguage: String): Map<TitleId, ChartRank> {
        val charts = charts(region, appLanguage, Instant.now())
        val tops = listOf(ChartList.TopMovies to charts.topMovies, ChartList.TopShows to charts.topShows).map { (list, ids) -> list to ids.mapNotNull(store::title) }
        val metadata = metadata(tops.flatMap { it.second }.map { it.id }, appLanguage)
        return buildMap {
            tops.forEach { (list, titles) ->
                watchable(titles, contentLanguages, metadata).forEachIndexed { i, title -> put(title.id, ChartRank(i + 1, list, region.takeUnless { charts.worldwide })) }
            }
        }
    }

    /**
     * The Charts for [region] in [appLanguage]'s language: the copy kept on the device while it is
     * under [CHARTS_FRESH_FOR] old, else the cloud's, kept for next time. When the cloud can't answer,
     * the kept copy stands however old; with none, there are no Charts.
     */
    private fun charts(region: String, appLanguage: String, now: Instant): Charts {
        val language = primaryLanguage(appLanguage)
        val key = region to language
        val kept = unsavedCharts[key] ?: store.charts(region, language)
        if (kept != null && kept.fetchedAt.isAfter(now - CHARTS_FRESH_FOR)) return Charts(kept.body)
        if (cloud != null && now.isAfter(cloudRetryAt)) {
            try {
                val body = cloud.charts(region, language)
                val asked = StoredCharts(body, now)
                unsavedCharts[key] = asked
                // Saved without waiting on a Sync.
                saveLater(after = { unsavedCharts.remove(key, asked) }) { store.saveCharts(region, language, body, now) }
                return Charts(body)
            } catch (e: CloudException) {
                cloudRetryAt = now + CLOUD_RETRY_AFTER
            }
        }
        return kept?.let { Charts(it.body) } ?: Charts.NONE
    }

    /**
     * The Games of [leagues] (CONTEXT.md: League; by the ids the cloud's schedule knows,
     * cloud/src/schedule.ts) for today and the week ahead, by start: the copies kept on the device
     * while every League's is under [GAMES_FRESH_FOR] old, else the cloud's, each League's kept for
     * next time. When the cloud can't answer, or has no copy of a League yet, the kept copy stands
     * however old; a League with none is left out. The Leagues on are the caller's to say: the
     * Live tab asks for them when it opens and every couple of minutes while a Games row shows.
     * A read that doesn't [waitForCloud] (Home's, whose other rows mustn't wait on the network)
     * answers with the kept copies at once and asks the cloud behind it; once fresh copies land,
     * [catalogChanges] tells the screen to read again. The cloud is given [GAMES_CALL_TIMEOUT].
     */
    suspend fun games(leagues: List<String>, now: Instant = Instant.now(), waitForCloud: Boolean = true): List<Game> = withContext(Dispatchers.IO) {
        val asked = leagues.distinct()
        if (asked.isEmpty()) return@withContext emptyList()
        val stored = store.games(asked)
        val kept = asked.mapNotNull { league -> (unsavedGames[league] ?: stored[league])?.let { league to it } }.toMap()
        var copies = kept
        // A League the cloud left out of its last answer isn't due again before one it listed: asking at
        // once would land the others again, and Home would read its rows again for them, without end (#307).
        val due = asked.any { league -> listOfNotNull(kept[league]?.fetchedAt, gamesAnsweredAt[league]).none { it.isAfter(now - GAMES_FRESH_FOR) } }
        if (cloud != null && now.isAfter(cloudRetryAt) && due) {
            if (waitForCloud) {
                copies = kept + fetchGames(cloud, asked, now).orEmpty()
            } else if (gamesFetching.compareAndSet(false, true)) {
                try {
                    gamesFetches.execute {
                        try {
                            if (!fetchGames(cloud, asked, now).isNullOrEmpty()) changed()
                        } finally {
                            gamesFetching.set(false)
                        }
                    }
                } catch (e: RejectedExecutionException) {
                    // Closing: the kept copies are the answer.
                    gamesFetching.set(false)
                }
            }
        }
        copies.flatMap { (league, copy) -> Games.parse(league, copy.body) }.sortedWith(compareBy({ it.start }, { it.league }, { it.id }))
    }

    /**
     * Asks [cloud] for the Games of [asked] and keeps each League's answer for next time, saved
     * without waiting on a Sync; null when it couldn't answer, which holds the cloud off for a while.
     */
    private fun fetchGames(cloud: CloudClient, asked: List<String>, now: Instant): Map<String, StoredGames>? {
        return try {
            val answered = Games.leagues(cloud.games(asked, GAMES_CALL_TIMEOUT)).mapValues { (_, body) -> StoredGames(body, now) }
            asked.forEach { gamesAnsweredAt[it] = now }
            answered.forEach { (league, copy) -> unsavedGames[league] = copy }
            saveLater(after = { answered.forEach { (league, copy) -> unsavedGames.remove(league, copy) } }) {
                answered.forEach { (league, copy) -> store.saveGames(league, copy.body, now) }
            }
            answered
        } catch (e: CloudException) {
            cloudRetryAt = now + CLOUD_RETRY_AFTER
            null
        }
    }

    /** [title]'s Versions, a Movie's with what Header reads, Playback and the Provider's movie details Confirmed. */
    private fun candidates(title: StoredTitle): List<Candidate> = candidates(listOf(title)).getValue(title.id)

    /** Each of [titles]' Versions, as [candidates] gives them, read with a few queries for all of them. */
    private fun candidates(titles: Collection<StoredTitle>): Map<TitleId, List<Candidate>> {
        val ids = titles.filter { it.id.kind == TitleKind.Movie }.flatMap { title -> title.listings.map { it.id } }
        val reads = ids.chunked(LISTINGS_PER_QUERY).flatMap { store.fileReads(it).entries }.associate { it.key to it.value }
        val bitrates = ids.chunked(LISTINGS_PER_QUERY).flatMap { store.movieBitrates(it).entries }.associate { it.key to it.value }
        return titles.associate { title ->
            val plain = title.listings.map(::candidates)
            title.id to if (title.id.kind != TitleKind.Movie) plain else plain.map { candidate ->
                val id = candidate.listing.id
                val withBitrate = bitrates[id]?.let { candidate.attributes.copy(bitrate = Known(it, Provenance.Confirmed)) } ?: candidate.attributes
                Candidate(candidate.listing, reads[id]?.let(withBitrate::confirmedBy) ?: withBitrate)
            }
        }
    }

    private fun candidates(listing: StoredListing) = Candidate(listing, prefixTable.describe(listing.name, listing.category).attributes)

    override fun close() {
        gamesFetches.shutdownNow()
        guideFetches.shutdownNow()
        // The answers waiting are saved first, unless a Sync keeps them waiting.
        saves.shutdown()
        saves.awaitTermination(SAVES_ON_CLOSE_SECONDS, TimeUnit.SECONDS)
        store.close()
    }

    companion object {
        private val METADATA_FRESH_FOR: Duration = Duration.ofDays(7)
        private val CLOUD_RETRY_AFTER: Duration = Duration.ofMinutes(1)
        /** Background reading waits this long after the Provider didn't answer: the Wi-Fi may be down. */
        private val OFFLINE_PAUSE: Duration = Duration.ofMinutes(15)
        private const val MATCH_FAILURES_IN_A_ROW = 3
        /**
         * Matching stops when no batch has been answered for this long, its remaining suspects asked
         * next Sync: a batch takes seconds, and one the cloud doesn't answer fails after the 60 s read
         * timeout, so this is two of those in a row.
         */
        internal val MATCH_STALL_AFTER: Duration = Duration.ofMinutes(2)

        /** The kinds of Track the Viewer's last picks are kept by, and what turning subtitles off is kept as. */
        private const val AUDIO = "audio"
        private const val SUBTITLES = "subtitles"
        private const val SUBTITLES_OFF = "off"
        private const val CLOSED_CAPTIONS = "closed-captions"
        /** The cloud's answers about a Listing are asked again after this, so new Titles on TMDB come through. */
        private val MATCH_FRESH_FOR: Duration = Duration.ofDays(30)
        /** A name TMDB found nothing for is asked about again sooner, but not every daily Sync: few Titles are added in a week. */
        private val MATCH_NOT_FOUND_FRESH_FOR: Duration = Duration.ofDays(7)
        private val SHOW_DETAILS_FRESH_FOR: Duration = Duration.ofDays(1)
        /** How many show details are asked of the Provider at once: GoT alone has 21 Versions. */
        private const val SHOW_DETAILS_AT_ONCE = 4
        /** TMDB's still size for an Episode row's 16:9 thumbnail (about 200 px wide on the stick). */
        private const val STILL_SIZE = "w300"
        private const val MIN_RESUME_POSITION = 60_000L
        /** How many cards a home row holds. */
        private const val ROW_SIZE = 20
        /** The newest Titles the home rows are picked from: their metadata is asked of the cloud 50 at a time. */
        private const val HOME_MOVIES = 150
        private const val HOME_SHOWS = 50
        /** Of those, the ones New on your service is picked from. */
        private const val NEW_MOVIES = 40
        private const val NEW_SHOWS = 15
        /** The newest Titles of its kind a tab's New row is picked from. */
        private const val TAB_NEW = 40
        private const val GENRE_ROWS = 8
        /** A genre with fewer Titles than this, once those shown higher up are skipped, gets no row: a row of two looks broken. */
        private const val MIN_GENRE_SIZE = 5
        /** Listings read per query: SQLite takes at most 999 bound parameters in older builds. */
        private const val LISTINGS_PER_QUERY = 900

        /** A Chart with fewer Titles than this, once kept to the Viewer's, looks broken and isn't shown. */
        private const val MIN_CHART_SIZE = 3

        /** How far down each Chart the hero picks its Title from ([featured]). */
        private const val FEATURED_FROM = 5

        /** A League row shows the week ahead: the cloud answers today and the next seven days. */
        private val WEEK_AHEAD: Duration = Duration.ofDays(7)
        /** The Event channels read for matching: a day behind now (a live Game named for yesterday evening, in a zone ahead) and past the week ahead. */
        private val EVENT_WINDOW_BEHIND: Duration = Duration.ofDays(1)

        /** How far from now an unmatched Event channel's start may be for More live events (docs/roadmap.md, M3): an evening's events, not a wall of stale or far-off ones. */
        internal val MORE_LIVE_EVENTS_WITHIN: Duration = Duration.ofHours(6)
        private val EVENT_WINDOW_AHEAD: Duration = Duration.ofDays(9)
        /** A "Not this game" mark is forgotten this long after it was made: its Game is long over, and the rows ask for a week ahead at most. */
        private val DROPPED_KEPT_FOR: Duration = Duration.ofDays(14)
        /** The cloud refreshes Netflix's Top 10 daily; the device asks it again after this. */
        private val CHARTS_FRESH_FOR: Duration = Duration.ofHours(1)
        /** The cloud reads a League's current day every 5 minutes; a screen asking every couple of minutes gets a new answer each time. */
        private val GAMES_FRESH_FOR: Duration = Duration.ofMinutes(1)
        /** How long a Games request may take in all: Home's row must not hang on a slow network, and the kept copy stands meanwhile. */
        private val GAMES_CALL_TIMEOUT: Duration = Duration.ofSeconds(3)
        /** How many programmes of a guide call are kept: the one on now and the next, with two more so the Channel isn't asked again as soon as the next one starts. */
        private const val GUIDE_PROGRAMMES = 4
        /** How long a Channel the guide listed nothing for stays unasked: the guide covers 15% of Channels, and a listed one rarely goes blank for long. */
        private val GUIDE_EMPTY_KEPT_FOR: Duration = Duration.ofHours(1)
        /** How long a Channel whose guide call got no answer waits before being asked again: the Wi-Fi may be down, as Background reading assumes. */
        private val GUIDE_RETRY_AFTER: Duration = Duration.ofMinutes(5)
        /** How long closing waits for the answers still to be saved. */
        private const val SAVES_ON_CLOSE_SECONDS = 5L
        /** How often a read waiting on a save checks that no Sync has taken the writer. */
        private const val SAVE_WAIT_STEP_MS = 50L

        /** The Region when the device doesn't say (CONTEXT.md). */
        const val DEFAULT_REGION = "CA"
        /** At most this many Movies and this many Shows per search, and Titles per person: two rows of cards. */
        private const val TITLES_FOUND = 40
        private const val PEOPLE_FOUND = 10
        /** Genres whose names match a query: a few, as a query rarely names one. */
        private const val GENRES_FOUND = 3
        /** Completions under the keyboard: as many as fit below it before the list scrolls. */
        private const val COMPLETIONS = 7
        /** At most this many Channels per search: a row of cards. */
        private const val CHANNELS_FOUND = 40
        /** At most this many Sports replays per search, and in Live's Replays row: two rows of cards. */
        private const val REPLAYS_FOUND = 40
        private const val REPLAYS_ROW = 40
        /** How many of a Channel's variants watching it tries (each as TS then HLS) before giving up. */
        private const val CHAIN_VARIANTS = 3
        /** TMDB's search is asked from this many letters (ADR 0009): fewer find too much to be worth a request. */
        private const val TMDB_SEARCH_LETTERS = 3
        /** On top of the screen's own pause before searching (150 ms), so TMDB is asked after 400 ms without typing. */
        private const val TMDB_SEARCH_PAUSE_MS = 250L
        /** The cloud searches no longer query: anything longer is no name. */
        private const val MAX_TMDB_QUERY = 100

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
