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
import kotlinx.coroutines.withTimeoutOrNull
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
import java.util.concurrent.ExecutionException
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
 * Plain reads are blocking and cheap; call them off the main thread. Reads that may need the
 * Provider or the cloud suspend.
 */
class Library private constructor(
    private val store: CatalogStore,
    private val credentials: CredentialStore,
    private val http: OkHttpClient,
    /**
     * The Provider table shipped with the app for a Provider key ([providerKey]), as text, or null for a
     * Provider with none: it stands in until the cloud has answered for that Provider once.
     */
    private val providerTables: (providerKey: String) -> String?,
    private val cloud: CloudClient?,
) : Closeable, KeptSettings {
    /**
     * The Prefix table for the Provider logged in to: the Base table with that Provider's Provider
     * table merged in, or the Base table alone before a login and for a Provider with no table. It
     * changes only as a Sync's catalog lands (#431), so what is on screen keeps the table it was
     * described with.
     */
    @Volatile private var prefixTable: PrefixTable = prefixTableFor(credentials.load())

    /**
     * The Base table with the Provider table kept for [login]'s Provider (the cloud's last answer, as
     * of the catalog on screen), else the one shipped with the app, else nothing.
     */
    private fun prefixTableFor(login: ProviderLogin?): PrefixTable {
        val key = login?.let(::providerKey) ?: return PrefixTable.base()
        store.providerTable(key)?.let(::readProviderTable)?.let { return it }
        return PrefixTable.withProviderTable(providerTables(key))
    }

    /** The Base table with [text] merged in, or null when [text] isn't a Provider table this app can read. */
    private fun readProviderTable(text: String): PrefixTable? =
        try {
            PrefixTable.withProviderTable(text)
        } catch (_: IllegalArgumentException) {
            null
        }

    /**
     * Asks the cloud for [providerKey]'s Provider table at the start of a Sync, or null when it can't
     * answer (or answers something this app can't read): the Sync then uses the table kept.
     */
    private fun fetchProviderTable(providerKey: String): Pair<String, PrefixTable>? {
        if (cloud == null || !canAskCloud()) return null
        val text = try {
            cloud.providerTable(providerKey, PROVIDER_TABLE_CALL_TIMEOUT)
        } catch (_: CloudException) {
            cloudRetryAt = Instant.now() + CLOUD_RETRY_AFTER
            return null
        }
        return readProviderTable(text)?.let { text to it }
    }

    /** After the cloud fails, it isn't asked again until then, so pages don't each wait out a timeout. */
    @Volatile private var cloudRetryAt = Instant.MIN

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
    /** Bumped when a Movie's details are kept (their cast and genres): the index is rebuilt in the background, the old one serving meanwhile. */
    private val detailsGeneration = AtomicInteger()
    private val rebuildingSearch = AtomicBoolean()
    /** The low-priority thread building the search index in the background, while it runs. */
    @Volatile private var searchBuilder: Thread? = null
    @Volatile private var searchIndex: BuiltIndex? = null
    private val connection = ConnectionGate()
    private val headerReader = HeaderReader(http)

    /** How long a Header read may take before it counts as the Provider not answering ([HEADER_READ_BOUND]); tests shorten it. */
    internal var headerReadBound by headerReader::bound
    /** One Header read at a time, a details page's or Background reading's. */
    private val reads = Mutex()
    /** Details pages reading right now: Background reading waits for them. */
    private val detailsReading = AtomicInteger()
    @Volatile private var cardsOnScreen: List<TitleId> = emptyList()
    /**
     * Saves the cloud's Trending feeds one at a time, in order, apart from the read
     * that asked for them: a Sync holds the catalog's writer while it downloads, for minutes on the
     * Fire TV Stick, and Home's rows mustn't wait for it (#213). Until its save lands, an answer is
     * read from [unsavedTrending] as if it were stored.
     */
    private val saves = Executors.newSingleThreadExecutor { Thread(it, "library-saves").apply { isDaemon = true } }
    /** The thread asking the cloud for other devices' file reads of a Movie whose details are read ([title]). */
    private val fileReadLookUps = Executors.newSingleThreadExecutor { Thread(it, "library-file-reads").apply { isDaemon = true } }
    /** The threads reading a Movie's details from the Provider for its page, which waits for them only so long ([movieDetailsForPage]). */
    private val movieDetailsReads = Executors.newCachedThreadPool { Thread(it, "library-movie-details").apply { isDaemon = true } }
    /** The details reads under way, by Listing. */
    private val movieDetailsReading = ConcurrentHashMap<Long, java.util.concurrent.Future<StoredMovieDetails?>>()
    /** How long a Movie's page waits for the Provider's details before it opens from what is kept. */
    internal var providerDetailsWait: Duration = PROVIDER_DETAILS_WAIT
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
    /** Trending's feeds asked of the cloud and not saved yet, by App language. */
    private val unsavedTrending = ConcurrentHashMap<String, StoredTrending>()
    private val trendingAsks = Any()
    private val unsavedGames = ConcurrentHashMap<String, StoredGames>()
    /** When the cloud last answered a request for each League, listing it or not: one it has no copy of yet waits as a kept copy does (#307). */
    private val gamesAnsweredAt = ConcurrentHashMap<String, Instant>()
    /** Whether a Sync is replacing the catalog, holding its writer: saves then don't wait for it. */
    @Volatile private var syncWriting = false
    private val _confirmations = MutableSharedFlow<TitleId>(extraBufferCapacity = 64)

    /** The Titles whose card's Version Background reading just Confirmed, so their badges update. */
    val confirmations: SharedFlow<TitleId> = _confirmations.asSharedFlow()
    private val _syncStatus = MutableStateFlow(openingStatus())
    val syncStatus: StateFlow<SyncStatus> = _syncStatus.asStateFlow()

    /** What follows a catalog landing runs under this, apart from [syncLock]: Sync now needn't wait for the cloud. */
    private val landedLock = Mutex()

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
            pendingAt != null -> SyncStatus.Synced(store.applyPending()!!).also { prefixTable = prefixTableFor(credentials.load()) }
            last != null -> SyncStatus.Synced(last)
            else -> SyncStatus.NeverSynced(store.channelsSyncedAt())
        }
    }

    val isLoggedIn: Boolean get() = credentials.load() != null

    private val _profiles = MutableStateFlow(readProfiles())

    /**
     * The household's Profiles on this device and the one it is on (#428). Everything a Viewer
     * keeps, where they stopped and what they Watched, Favourites, "Not this game" marks and what
     * they chose in the player, is the current Profile's; the catalog, the login and the Provider's
     * reads are the device's. The device stays on the Profile it was last on, across launches.
     */
    val profiles: StateFlow<Profiles> = _profiles.asStateFlow()

    private val keptPin: KeptHouseholdPin = KeptHouseholdPin(store) { hash, at ->
        accountSync.record(SyncEntry(SyncEntry.HOUSEHOLD, null, SyncEntry.PIN, hash, at))
        accountSync.pushSoon()
    }

    /** The Household PIN (#437), which lets a Viewer leave a Kids profile: the same on every TV of the Account (#440). */
    val householdPin: HouseholdPin = keptPin

    /** The current Profile's id, which every Viewer's read and write goes to. */
    private val profile: Long get() = _profiles.value.current.id

    private fun readProfiles(): Profiles {
        val kept = store.profiles()
        return Profiles(kept.map { Profile(it.id, it.name, it.kids) }, kept.maxWith(compareBy({ it.usedAt }, { -it.id })).id)
    }

    /**
     * Makes a Profile named [name] (trimmed), with nothing watched, no Favourites and no choices,
     * and gives it, or null when the household already has [MAX_PROFILES]. The device stays on the
     * Profile it is on.
     */
    suspend fun addProfile(name: String, kids: Boolean = false): Profile? = withContext(Dispatchers.IO) {
        require(name.isNotBlank()) { "A Profile needs a name" }
        synchronized(_profiles) {
            if (_profiles.value.all.size >= MAX_PROFILES) return@withContext null
            val at = Instant.now()
            val id = store.addProfile(name.trim(), kids, at)
            _profiles.value = readProfiles()
            accountSync.uidOf(id)?.let { accountSync.recordProfile(it, name.trim(), kids, deleted = false, at) }
            // The first second Profile: the chip appears, and pulses once so the household notices it.
            if (store.profileChipPulse() == null) {
                store.saveProfileChipPulse(PULSE_PENDING)
                _profileChipPulse.value = true
            }
            _profiles.value.all.single { it.id == id }
        }.also { accountSync.pushSoon() }
    }

    private val _profileChipPulse = MutableStateFlow(store.profileChipPulse() == PULSE_PENDING)

    /**
     * Whether the profile chip is to pulse once (#438): set the first time a second Profile is made on
     * this device, kept across launches until the chip has pulsed ([profileChipPulsed]), never set again.
     */
    val profileChipPulse: StateFlow<Boolean> = _profileChipPulse.asStateFlow()

    /** The chip has pulsed: it never does again on this device. */
    suspend fun profileChipPulsed() = withContext(Dispatchers.IO) {
        _profileChipPulse.value = false
        saveLater { store.saveProfileChipPulse(PULSE_DONE) }
    }

    /** Names Profile [id] [name] (trimmed), on every TV of the Account too. */
    suspend fun renameProfile(id: Long, name: String) = withContext(Dispatchers.IO) {
        require(name.isNotBlank()) { "A Profile needs a name" }
        synchronized(_profiles) {
            val at = Instant.now()
            store.renameProfile(id, name.trim(), at)
            _profiles.value = readProfiles()
            val renamed = _profiles.value.all.find { it.id == id } ?: return@withContext
            accountSync.uidOf(id)?.let { accountSync.recordProfile(it, renamed.name, renamed.kids, deleted = false, at) }
        }
        accountSync.pushSoon()
    }

    /** Puts the device on Profile [id]: from now on every Viewer's read and write is its. */
    suspend fun switchProfile(id: Long) = withContext(Dispatchers.IO) {
        synchronized(_profiles) {
            require(_profiles.value.all.any { it.id == id }) { "No Profile $id" }
            if (id == profile) return@withContext
            store.useProfile(id, Instant.now())
            forgetProfileCaches()
            _profiles.value = readProfiles()
            readSettings()
        }
    }

    /**
     * Deletes Profile [id] with everything kept for it, and says whether it did: the household's
     * last Profile is never deleted. Deleting the current one puts the device on the one used last
     * of the others.
     */
    suspend fun deleteProfile(id: Long): Boolean = withContext(Dispatchers.IO) {
        synchronized(_profiles) {
            val profiles = _profiles.value
            if (profiles.all.none { it.id == id } || profiles.all.size == 1) return@withContext false
            val current = id == profile
            val uid = accountSync.uidOf(id)
            store.deleteProfile(id)
            // Everywhere: the Account's other TVs delete it too, with its history.
            uid?.let { accountSync.recordProfile(it, null, false, deleted = true, Instant.now()) }
            if (current) forgetProfileCaches()
            _profiles.value = readProfiles()
            readSettings()
            true
        }.also { accountSync.pushSoon() }
    }

    /** What is held in memory for the Profile the device was on, so the next one's is read instead. */
    private fun forgetProfileCaches() {
        remembered = null
        guideFavourites = emptyList()
    }

    private val accountSync: AccountSync = AccountSync(
        store,
        cloud,
        keptPin,
        MAX_PROFILES,
        signedIn = { _account.value != null },
        currentProfile = { profile },
        provider = { credentials.load()?.let(::providerKey) },
        onSignedOut = { accountIs(null) },
    )

    /**
     * Pushes the changes this device made and pulls the Account's (#435, #440), when signed in: every
     * Profile's history, Favourites, "Not this game" marks, settings and player choices, the Profiles
     * themselves, the Region and the Household PIN. The app calls it on launch and on coming to the
     * front, and it runs by itself after each stop or change. What it pulls lands between screens
     * ([applyPendingHistory]), never under focus. The first sync after signing in merges the device
     * into the Account, unless that asks which Profiles to keep first ([profileChoice]). Says whether
     * the cloud answered and the device is merged; while it can't, the changes stay queued for the next.
     */
    suspend fun syncHistory(): Boolean = withContext(Dispatchers.IO) { accountSync.sync() }

    /**
     * Set while signing in would bring the household past [MAX_PROFILES] Profiles, the Account's and
     * this TV's: the TV asks which to keep ([keepProfiles]) before sign-in completes, and nothing of
     * either syncs until then.
     */
    val profileChoice: StateFlow<ProfileChoice?> get() = accountSync.choice

    /**
     * Keeps the Profiles of [profileChoice] keyed [keys], the current one always, and merges the
     * device into the Account with them: a Profile left out is deleted, from every TV of the Account
     * when it is the Account's, with its history. Says whether the merge is done.
     */
    suspend fun keepProfiles(keys: Set<String>): Boolean = withContext(Dispatchers.IO) { accountSync.keep(keys) }

    /**
     * Lands what was pulled from the Account, as a held catalog lands: on entering a top-level screen,
     * never while one is on screen or the player is open. Profiles added, renamed or deleted on another
     * TV appear so here (one deleted while this TV was on it puts it on the one used last), and their
     * settings change. Says whether what the screens show may have changed, in which case they read
     * again ([catalogChanges]).
     */
    suspend fun applyPendingHistory(): Boolean = withContext(Dispatchers.IO) {
        if (!accountSync.hasPulled()) return@withContext false
        val landed = synchronized(_profiles) {
            val was = profile
            accountSync.land().also {
                if (it.profiles) {
                    _profiles.value = readProfiles()
                    if (profile != was) forgetProfileCaches()
                }
                // A remembered track may have landed too.
                remembered = null
                readSettings()
            }
        }
        landed.any.also { if (it) changed() }
    }

    /** A stop, or the end of a Title: what changed is pushed to the Account in the background. */
    internal fun stoppedPlaying() = accountSync.pushSoon()

    /** Lands [id]'s pulled history before its page or Play reads where the Viewer stopped. */
    private fun landHistory(id: TitleId) {
        if (accountSync.landTitle(profile, id)) changed()
    }

    private val _profileSettings = MutableStateFlow(store.profileSettings(profile))
    private val _region = MutableStateFlow(store.household(SyncEntry.REGION)?.first)

    override val profileSettings: StateFlow<Map<String, String>> = _profileSettings.asStateFlow()

    override val region: StateFlow<String?> = _region.asStateFlow()

    /** Reads the current Profile's settings and the Region again, after a switch or what landed. */
    private fun readSettings() {
        _profileSettings.value = store.profileSettings(profile)
        _region.value = store.household(SyncEntry.REGION)?.first
    }

    override fun saveProfileSettings(changed: Map<String, String?>) {
        if (changed.isEmpty()) return
        require(changed.keys.all { it in ProfileSetting.ALL }) { "Not a Profile's setting: ${changed.keys - ProfileSetting.ALL}" }
        synchronized(_profiles) {
            val at = Instant.now()
            changed.forEach { (key, value) ->
                store.saveProfileSetting(profile, key, value, at)
                accountSync.record(profile, SyncEntry.SETTING, key, value, at)
            }
            _profileSettings.value = store.profileSettings(profile)
        }
        accountSync.pushSoon()
    }

    override fun saveRegion(region: String) {
        val at = Instant.now()
        store.saveHousehold(SyncEntry.REGION, region, at)
        accountSync.record(SyncEntry(SyncEntry.HOUSEHOLD, null, SyncEntry.REGION, region, at))
        _region.value = region
        accountSync.pushSoon()
    }

    override fun importSettings(profile: Long, values: Map<String, String>, region: String?) {
        // A deleted Profile's file may have outlived it.
        if (_profiles.value.all.any { it.id == profile }) store.importProfileSettings(profile, values.filterKeys { it in ProfileSetting.ALL })
        if (region != null) store.saveHousehold(SyncEntry.REGION, region, Instant.ofEpochMilli(1), unlessKept = true)
        synchronized(_profiles) { readSettings() }
    }

    /** Checks [login] with the Provider and keeps it only if the Provider accepts it. */
    suspend fun logIn(login: ProviderLogin): LoginResult = withContext(Dispatchers.IO) {
        try {
            XtreamClient(login, http).account().also {
                credentials.save(login)
                prefixTable = prefixTableFor(login)
            }
        } catch (e: ProviderException) {
            e.problem
        }
    }

    /**
     * Downloads the Provider's catalog and replaces the device's copy, in two stages. The Channels
     * stage (live Categories and Listings) comes first; the Titles stage (movie and show Categories
     * and Listings) after, grouped by their Title numbers with each Mismatch apart. Only `player_api` requests
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
     * Once a catalog lands, the cloud is told the unknown Prefixes and asked for the Title facts, apart
     * from this status: the Sync itself is finished, and another may start meanwhile. This returns once
     * that is done too.
     */
    suspend fun sync(hold: Boolean = false): SyncStatus {
        val landed = syncLock.withLock { withContext(Dispatchers.IO) { syncUnderLock(hold) } }
        if (landed is SyncStatus.Synced) afterCatalogLanded(hold)
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
            val login = credentials.load() ?: throw ProviderException(WrongLogin)
            val client = XtreamClient(login, http)
            client.account()
            store.discardPending()
            // The Provider table comes first: this Sync describes its Listings with it, and it is kept with the catalog it lands.
            val key = providerKey(login)
            val table = fetchProviderTable(key)?.let { (text, table) -> store.stageProviderTable(key, text); table } ?: prefixTableFor(login)
            val unknownPrefixes = LinkedHashMap<String, UnknownPrefix>()
            val tierTags = CandidateTierTags(table)
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
                        val start = eventStart(name, now, table.eventTimeZone)
                        val listing = table.describeLive(name, category)
                        val tally = tallies.getOrPut(categoryId) { IntArray(2) }
                        tally[0]++
                        if (listing == PrefixTable.LiveListing.EmptyEventSlot || isEventChannel(name, start)) tally[1]++
                        if (listing == PrefixTable.LiveListing.Channel) {
                            val channel = table.channelName(name)
                            if (!isEventChannel(name, start)) tierTags.add(name, channel.key)
                            staging.channel(id, name, image, start, categoryId, guideId, channel.key, channel.name, channel.country, channel.quality, channel.hevc)
                            entriesRead++
                            running()
                        }
                    }
                    if (tallies.isEmpty()) requireListings(liveCategories, "live")
                    liveCategories.forEach { (id, name) ->
                        val tally = tallies[id]
                        val kind = when {
                            table.isClutterCategory(name) -> CategoryKind.Clutter
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
                    prefixTable = table
                    channelsAt = at
                    catalogChanged()
                }
                step = SyncStep.Movies
                running()
                val pending = store.stageTitles(
                    fill = { staging ->
                        val movieCategories = HashMap<String, String>()
                        val showCategories = HashMap<String, String>()
                        val listing = { kind: TitleKind, id: Long, name: String, category: String, categoryId: String?, added: Long?, titleNumber: Long?, image: String?, container: String?,
                            rating: Double?, details: ProviderDetails?, releaseYear: Int? ->
                            val description = table.describe(name, category)
                            description.unknownPrefix?.let { prefix ->
                                unknownPrefixes.merge(prefix, UnknownPrefix(prefix, 1, name)) { seen, _ -> seen.copy(listingCount = seen.listingCount + 1) }
                            }
                            val hidden = description.hidden != null
                            staging.listing(kind, id, name, categoryId, added, image, hidden, titleNumber, table.cleanName(name).year ?: releaseYear, container, rating, details)
                            // A Sports replay is kept apart, with what its name says: its Title number and poster are wrong and ignored.
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
                        client.movieListings { id, name, categoryId, added, titleNumber, image, container, rating ->
                            movieListings++
                            listing(TitleKind.Movie, id, name, movieCategories[categoryId].orEmpty(), categoryId, added, titleNumber, image, container, rating, null, null)
                        }
                        if (movieListings == 0) requireListings(movieCategories, "movie")
                        step = SyncStep.Shows
                        running()
                        client.showCategories { id, name ->
                            showCategories[id] = name
                            staging.category(TitleKind.Show, id, name)
                        }
                        var showListings = 0
                        client.showListings { show ->
                            showListings++
                            // A Show whose name carries no year is dated by its release date.
                            listing(
                                TitleKind.Show, show.id, show.name, showCategories[show.categoryId].orEmpty(), show.categoryId, show.modified, show.titleNumber, show.image, null,
                                show.details.rating, show.details, releaseYear(show.details.releaseDate),
                            )
                        }
                        if (showListings == 0) requireListings(showCategories, "show")
                    },
                    onFilled = {
                        step = SyncStep.Saving
                        running()
                    },
                    completed = { movieCount, showCount, mismatches ->
                        CompletedSync(
                            Instant.now(),
                            Duration.ofNanos(System.nanoTime() - started),
                            movieCount,
                            unknownPrefixes.values.sortedWith(compareByDescending<UnknownPrefix> { it.listingCount }.thenBy { it.prefix }),
                            mismatches,
                            showCount,
                            channelCount,
                            tierTags.candidates(),
                        )
                    },
                )
                when {
                    last == null -> SyncStatus.Synced(store.swapTitles())
                    hold -> SyncStatus.Ready(last, pending.finishedAt)
                    else -> SyncStatus.Synced(store.applyPending()!!)
                }.also { if (it is SyncStatus.Synced) prefixTable = table }
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
     * Swaps a held Sync's catalog in ([SyncStatus.Ready]), then asks the cloud what a Sync asks once
     * its catalog lands. Returns false at once, without waiting, when none is held or a Sync is
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
                // The held catalog lands with the Provider table it was described with (#431).
                prefixTable = prefixTableFor(credentials.load())
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
        if (applied) afterCatalogLanded(hold = true)
        return applied
    }

    /**
     * Lands the Title facts the cloud answered since the last landing ([fetchTitleFacts]), between
     * screens like a held catalog (ADR 0011), so an Untagged Version's Likely language never changes
     * under focus. Says whether any landed, in which case the screens read again ([catalogChanges]).
     * Nothing lands while a Sync holds the writer: the next screen entry asks again.
     */
    suspend fun applyPendingTitleFacts(): Boolean = withContext(Dispatchers.IO) {
        if (syncWriting || !store.hasPendingTitleFacts()) return@withContext false
        landTitleFacts()
    }

    private fun landTitleFacts(): Boolean {
        val landed = store.landTitleFacts() > 0
        if (landed) changed()
        return landed
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
     * Once a catalog lands: reports the Prefixes the Sync didn't know, then asks the cloud for the
     * Title facts the catalog's Titles lack ([fetchTitleFacts]). With [hold], the facts wait for
     * [applyPendingTitleFacts] as the catalog waited; else they land here, as the catalog did.
     */
    private suspend fun afterCatalogLanded(hold: Boolean) = landedLock.withLock {
        withContext(Dispatchers.IO) {
            if (!canAskCloud()) return@withContext
            val sync = store.lastSync() ?: return@withContext
            reportUnknownPrefixes(sync)
            fetchTitleFacts(land = !hold)
        }
    }

    /**
     * Picks up the Title facts the last fetch couldn't get (the app closed, or the cloud couldn't
     * answer), without waiting for the next Sync, to land between screens. Asks nothing when every
     * Title has its answer.
     */
    suspend fun resumeTitleFacts() = landedLock.withLock {
        withContext(Dispatchers.IO) {
            if (store.lastSync() != null) fetchTitleFacts(land = false)
        }
    }

    /**
     * Asks the cloud what Wikidata knows of every Title number the catalog on screen has no answer for
     * (#476), in batches of [CloudClient.MAX_FACTS_BATCH], and keeps the answers with the catalog:
     * "no item" too, so a Title Wikidata doesn't know isn't asked again, while the Titles the cloud
     * couldn't answer for stay unanswered and are asked again after the next Sync. The answers wait to
     * [land] between screens, or land at once. Without a cloud nothing is known and nothing is asked.
     * A few batches may fail on their own (the cloud ran out of CPU time, say); several in a row mean
     * the cloud is down, and the rest wait for the next Sync.
     */
    private fun fetchTitleFacts(land: Boolean) {
        if (cloud == null || !canAskCloud()) return
        try {
            var failedInARow = 0
            for (batch in store.titlesWithoutFacts().chunked(CloudClient.MAX_FACTS_BATCH)) {
                try {
                    val answer = cloud.titleFacts(batch)
                    store.stageTitleFacts(answer.found, answer.notFound, Instant.now())
                    failedInARow = 0
                } catch (e: CloudException) {
                    if (++failedInARow == CLOUD_FAILURES_IN_A_ROW) {
                        cloudRetryAt = Instant.now() + CLOUD_RETRY_AFTER
                        break
                    }
                }
            }
            if (land) landTitleFacts()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The catalog shows as it is; the facts are asked for again after the next Sync.
        }
    }

    /**
     * Tells the cloud which Prefixes the Sync found that the Prefix table doesn't know, so they get
     * added to it, and its candidate Tier tags (#439). The Viewer never sees them; a Sync that can't
     * report them leaves it to the next.
     */
    private fun reportUnknownPrefixes(sync: CompletedSync) {
        if (cloud == null || (sync.unknownPrefixes.isEmpty() && sync.candidateTierTags.isEmpty()) || !canAskCloud()) return
        val login = credentials.load() ?: return
        try {
            cloud.reportUnknownPrefixes(
                providerKey(login),
                sync.unknownPrefixes.take(CloudClient.MAX_PREFIX_BATCH),
                sync.candidateTierTags.take(CloudClient.MAX_TIER_TAG_BATCH),
            )
        } catch (e: CloudException) {
            cloudRetryAt = Instant.now() + CLOUD_RETRY_AFTER
        }
    }

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
     * none is in them, and the name and poster of its Listings in the [appLanguage].
     */
    suspend fun titles(offset: Int, limit: Int, contentLanguages: List<String>, appLanguage: String, kind: TitleKind = TitleKind.Movie): List<TitleSummary> = withContext(Dispatchers.IO) {
        summaries(store.titles(kind, offset, limit), contentLanguages, appLanguage)
    }

    /**
     * Movies, Shows, people and genres whose names match [query], once it has two letters, all from
     * the device's index of what the Provider says (ADR 0014): it answers once, and the cloud isn't
     * asked. Titles are found by any Listing name of their Versions, in every language the Provider
     * carries; people by the cast the Provider names (a Show's from its Listing at Sync, a Movie's once
     * its details were read, when its page opened); genres by their names in any App language
     * ([Genres]), as the Prefix table names them from Categories and the Provider's genre fields.
     * Accents and case don't matter, and small typos are tolerated; a year after a name finds that
     * year's Title. Titles with Versions in the Viewer's [contentLanguages] come first, then closer
     * matches (a whole name, then whole words, then words being typed, then typos), then the
     * Provider's order. A person's Titles are those the Provider has. Cards show the Listing's name
     * and poster in the [appLanguage].
     *
     * Channels are found by their names (country tag and name, without the Provider's Tier tags and
     * quality words) the same way, each Channel once whatever its variants, Favourites first among
     * equally close matches, then in the Provider's order; finished Event channels aren't.
     *
     * The results offer completions: the names of the best Titles, people and genres found. Before
     * anything is typed, they are the genres with the most Titles in the Viewer's languages, named in
     * the App language.
     *
     * The search index of the whole catalog is built in the background after a Sync and by
     * [prepareSearchInBackground]; a search before the first one waits for it, and one during or
     * after a Sync answers from the index built before it until the new one is.
     */
    fun search(query: String, contentLanguages: List<String>, appLanguage: String): Flow<SearchResults> = flow {
        val letters = SearchIndex.words(query).sumOf { it.length }
        // A Kids profile searches the Kids catalog alone: its Titles, and the Channels of Kids Categories.
        val forKids = if (kids) KidsSearch(kidsCatalog().values.flatten().toHashSet(), kidsChannels(Instant.now()).values.flatten().mapTo(HashSet()) { it.key }) else null
        val inCatalog: (SearchIndex, Int) -> Boolean = if (forKids == null) { _, _ -> true } else { index, position -> index.titleId(position) in forKids.titles }
        if (letters == 0) return@flow emit(SearchResults.NONE.copy(completions = genreShortcuts(searchIndex(), contentLanguages, appLanguage, inCatalog)))
        if (letters < SearchIndex.MIN_LETTERS) return@flow emit(SearchResults.NONE)
        val index = searchIndex()
        val inIndex = { position: Int -> inCatalog(index, position) }
        val titles = index.findTitles(query).filter { inIndex(it.index) }.associate { it.index to it.distance }
        val people = index.findPeople(query).map { FoundPerson(index.person(it.index), it.distance, index.personTitles(it.index).filterTo(HashSet(), inIndex)) }
        val channels = foundChannels(index, query, forKids?.channels)
        val replays = if (forKids == null) foundReplays(index, query) else emptyList()
        emit(searchResults(index, query, titles, people, contentLanguages, appLanguage, inIndex).copy(channels = channels, replays = replays))
    }.flowOn(Dispatchers.IO)

    /** What a Kids profile's search keeps: the Kids catalog's [titles] and the Channels of Kids Categories, by key. */
    private class KidsSearch(val titles: Set<TitleId>, val channels: Set<String>)

    /** The Sports replays whose names match [query], closest first, then the newest by the date in the name, then in the Provider's order: every one, a highlights show as well as a game. */
    private fun foundReplays(index: SearchIndex, query: String): List<Replay> {
        val hits = index.findReplays(query).take(REPLAYS_FOUND)
        val replays = store.replays(hits.map { index.replays[it.index] })
        return hits.mapNotNull { replays[index.replays[it.index]] }
    }

    /** The Channels on now whose names match [query], closest first, then Favourites, then in the Provider's order; only those keyed in [only] when it is given. */
    private fun foundChannels(index: SearchIndex, query: String, only: Set<String>? = null): List<Channel> {
        val hits = index.findChannels(query).sortedWith(compareBy<SearchIndex.Hit>({ it.distance }, { it.index })).let { if (only == null) it.take(CHANNELS_FOUND * 4) else it }
        // Finished Event channels drop out, so a few more are read than are shown.
        val on = store.channels(profile, hits.map { index.channels[it.index] }, Instant.now() - EVENT_FINISHED_AFTER)
        return hits.mapNotNull { hit -> on[index.channels[hit.index]]?.takeIf { only == null || it.key in only }?.let { hit to it } }
            .sortedWith(compareBy({ it.first.distance }, { !it.second.favourite }, { it.first.index }))
            .map { it.second }
            .take(CHANNELS_FOUND)
    }

    /** The Viewer's Favourites that are on, in the order they were added. */
    suspend fun favourites(): List<Channel> = withContext(Dispatchers.IO) { store.favourites(profile, Instant.now() - EVENT_FINISHED_AFTER) }

    /** Saves the Channel keyed [channel] ([Channel.key]) as a Favourite, or removes it when [favourite] is false. */
    suspend fun setFavourite(channel: String, favourite: Boolean) = withContext(Dispatchers.IO) {
        val at = Instant.now()
        store.setFavourite(profile, channel, favourite, at)
        // Kept per Provider: a Channel key means nothing on another.
        accountSync.record(profile, SyncEntry.FAVOURITE, channel, if (favourite) ON else null, at, perProvider = true)
        accountSync.pushSoon()
    }

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
        store.categoryChannels(profile, category.categoryIds, Instant.now() - EVENT_FINISHED_AFTER)
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
    private fun genreShortcuts(index: SearchIndex, contentLanguages: List<String>, appLanguage: String, inCatalog: (SearchIndex, Int) -> Boolean): List<String> {
        val wanted = contentLanguages.map(::primaryLanguage).toSet()
        val inWanted = { titles: IntArray -> titles.count { position -> inCatalog(index, position) && index.languages(position).any(wanted::contains) } }
        return index.genres.indices.map { Genres.named(index.genres[it], appLanguage) to inWanted(index.genreTitles[it]) }
            .filter { (_, count) -> count > 0 }
            .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })
            .take(COMPLETIONS)
            .map { it.first }
    }

    /**
     * Ranks [titles] (search index positions and how close they matched), [people] and the genres
     * [query] matches, and shows the best of them, with the completions they offer. Equally close
     * matches come in the Provider's order: a Title's place in the index.
     */
    private fun searchResults(
        index: SearchIndex,
        query: String,
        titles: Map<Int, Int>,
        people: List<FoundPerson>,
        contentLanguages: List<String>,
        appLanguage: String,
        inCatalog: (Int) -> Boolean,
    ): SearchResults {
        val wanted = contentLanguages.map(::primaryLanguage).toSet()
        val byRank = compareBy<SearchIndex.Hit>(
            { index.languages(it.index).none(wanted::contains) },
            { it.distance },
            { it.index },
        )
        val ranked = titles.map { (position, distance) -> SearchIndex.Hit(position, distance) }.sortedWith(byRank)
        val shownPeople = people.filter { it.titles.isNotEmpty() }
            .sortedWith(compareBy<FoundPerson>({ it.distance }, { person -> person.titles.min() }))
            .take(PEOPLE_FOUND)
            .map { person -> person.name to person.titles.map { SearchIndex.Hit(it, 0) }.sortedWith(byRank).take(TITLES_FOUND) }
        val shownGenres = index.findGenres(query)
            .filter { hit -> index.genreTitles[hit.index].any(inCatalog) }
            .sortedWith(compareBy<SearchIndex.Hit>({ it.distance }, { -index.genreTitles[it.index].size }))
            .take(GENRES_FOUND)
            .map { Genres.named(index.genres[it.index], appLanguage) to index.genreTitles[it.index].filter(inCatalog).map { position -> SearchIndex.Hit(position, 0) }.sortedWith(byRank).take(TITLES_FOUND) }
        val ofKind = { kind: TitleKind -> ranked.filter { index.titleId(it.index).kind == kind }.take(TITLES_FOUND) }
        val shown = (TitleKind.entries.flatMap(ofKind) + shownPeople.flatMap { it.second } + shownGenres.flatMap { it.second })
            .map { index.titleId(it.index) }.distinct()
        val summaries = summaries(shown.mapNotNull(store::title), contentLanguages, appLanguage).associateBy { it.id }
        val found = { hits: List<SearchIndex.Hit> -> hits.mapNotNull { summaries[index.titleId(it.index)] } }
        // The best Titles of either kind are among those shown, so their names are at hand.
        val titleNames = ranked.take(COMPLETIONS * 2).mapNotNull { summaries[index.titleId(it.index)]?.name }
        return SearchResults(
            movies = found(ofKind(TitleKind.Movie)),
            shows = found(ofKind(TitleKind.Show)),
            // The same Titles together, best first whatever their kind: Search shows them in one grid.
            titles = found(TitleKind.entries.flatMap(ofKind).sortedWith(byRank)),
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
                // Details kept meanwhile wait for the next search, so browsing doesn't keep it building.
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

    /** Whether the search index of the current catalog and the details kept for it is built. */
    internal val searchIndexReady: Boolean
        get() = searchIndex?.let { it.catalog == catalogGeneration.get() && it.details == detailsGeneration.get() } == true

    private class BuiltIndex(val catalog: Int, val details: Int, val index: SearchIndex)

    /**
     * The search index last built. A Sync, Matches or a Movie's details kept since then (its page
     * opened from the last search, say) only start a rebuild in the background, so typing never waits on
     * one (#241): only the first search with no index at all does. The old index's Titles and
     * Channels the catalog no longer has drop out of the results, and new ones show once it's rebuilt.
     */
    private fun searchIndex(): SearchIndex {
        val built = searchIndex ?: return rebuildSearchIndex(anyBuilt = true)
        if (!searchIndexReady) prepareSearchInBackground()
        return built.index
    }

    /**
     * The search index of the current catalog and details, built now unless it is, or [anyBuilt]
     * one; the Viewer waits for it.
     */
    private fun rebuildSearchIndex(anyBuilt: Boolean = false): SearchIndex {
        // The Viewer is waiting: a background build holding the lock gets their priority.
        if (Thread.currentThread() != searchBuilder) searchBuilder?.priority = Thread.NORM_PRIORITY
        return synchronized(searchLock) {
            val catalog = catalogGeneration.get()
            val details = detailsGeneration.get()
            searchIndex?.takeIf { anyBuilt || it.catalog == catalog && it.details == details }?.index
                ?: run {
                    onSearchIndexBuild()
                    buildSearchIndex(store, prefixTable).also { searchIndex = BuiltIndex(catalog, details, it) }
                }
        }
    }

    /** Runs before each build of the search index: tests hold a build there, as the stick's minute-long one would. */
    @Volatile internal var onSearchIndexBuild: () -> Unit = {}

    /**
     * [titles]' cards: the name and poster of their Listings in the [appLanguage], and the best
     * Version in the Viewer's [contentLanguages].
     */
    private fun summaries(titles: List<StoredTitle>, contentLanguages: List<String>, appLanguage: String): List<TitleSummary> {
        val kept = keptMovieDetails(titles)
        val originals = originalLanguages(titles.map { it.id })
        return titles.map { rowCard(it, contentLanguages, appLanguage, kept, originals[it.id]) }
    }

    /**
     * A Title with its Versions grouped by language, the Viewer's [contentLanguages] first, then,
     * with [preferOriginalAudio] on a Title released outside them, its original language's; null
     * when the catalog doesn't have it. A Movie's Versions come with what other devices on the
     * Provider read of them (from the cloud), waited for up to [SHARED_READS_WAIT].
     *
     * What the Title is comes from the Provider (ADR 0014): a Movie's details are read from the
     * Provider for its best Version's Listing when the page opens, and for the Listing that names it
     * when that is another (its overview, cast and director are that one's, #504), unless kept from before, and kept
     * for good (read again only once the Provider changes that Listing); a Show's are on its Listing
     * since Sync. With the Provider unreachable the page opens whole from what is kept.
     */
    suspend fun title(id: TitleId, contentLanguages: List<String>, appLanguage: String, preferOriginalAudio: Boolean = true): TitleDetails? = withContext(Dispatchers.IO) {
        val title = store.title(id) ?: return@withContext null
        // What other Viewers of the Provider read is asked while the Provider's details are read, so a Movie's page
        // arrives with its badges and plan Confirmed and they don't change once it shows (#387). A
        // slow cloud is waited for no longer than a page waits to arrive; its answer is kept anyway.
        val asked = System.nanoTime()
        val shared = if (id.kind != TitleKind.Movie) null else credentials.load()?.let { login ->
            fileReadLookUps.submit { lookUpFileReads(providerKey(login), title.listings.map { it.id }) }
        }
        try {
            shared?.get((SHARED_READS_WAIT.toNanos() - (System.nanoTime() - asked)).coerceAtLeast(0), TimeUnit.NANOSECONDS)
        } catch (e: TimeoutException) {
            // Kept once it comes: the page shows it the next time it reads its details.
        } catch (e: ExecutionException) {
            // The details stand without it, as without a cloud.
        }
        val originalLanguage = originalLanguage(id)
        var candidates = candidates(title, contentLanguages)
        var shown = shown(candidates, contentLanguages, appLanguage, originalLanguage)
        // A Provider slow to answer never holds the page: past this it opens from what is kept, and
        // has the details once they come (the page's reads tell it, [readMovieDetails]).
        val detailsBy = System.nanoTime() + providerDetailsWait.toNanos()
        var kept = if (id.kind == TitleKind.Movie) movieDetailsForPage(title, shown.best.listing, detailsBy) else emptyMap()
        if (kept[shown.best.listing.id]?.bitrate != null) {
            // The details just read Confirm the bitrate: the page arrives with it, as with the shared reads.
            candidates = candidates(title, contentLanguages)
            shown = shown(candidates, contentLanguages, appLanguage, originalLanguage)
        }
        // What the page tells of the Title is in the App language, else English, else its original
        // language: the Listing telling of it, not the best Version's, as an `ES` copy with English
        // audio may be the best Version of an English film.
        if (id.kind == TitleKind.Movie) {
            // The next Listings in that order are read while what is kept has no plot the Viewer reads:
            // the English Listing may say nothing, or tell of the Title in Greek.
            for (teller in shown.tellers.take(TELLERS_READ)) {
                if (teller.listing.id != shown.best.listing.id) kept = movieDetailsForPage(title, teller.listing, detailsBy)
                // Unread still: the Provider isn't answering, and the next call would wait as long.
                val told = kept[teller.listing.id] ?: break
                if (Scripts.reads(appLanguage, told.details.plot)) break
            }
        }
        val details = providerDetails(title, shown.tellers, kept, appLanguage)
        val original = originalAudioLanguage(originalLanguage, contentLanguages, preferOriginalAudio)
        val (languages, otherLanguages) = groupByLanguage(likelyInOriginalLanguage(candidates, originalLanguage), contentLanguages, original)
        TitleDetails(
            id, shown.name, shown.year ?: details.year, shown.image, languages, otherLanguages,
            description = details.plot,
            genres = titleGenres(title, candidates, kept, appLanguage),
            runtimeMinutes = details.runtimeMinutes,
            cast = cleanList(details.cast),
            trailer = details.trailer,
            directors = cleanList(details.director),
            rating = titleRating(title, shown.best.listing, kept),
            originalLanguage = original,
            posters = shown.posters,
        )
    }

    /**
     * The Provider's movie details kept for [title]'s Listings, with [listing]'s read now when none
     * are kept for it from when the Provider last changed it, and kept for good. One call, when the
     * page opens; none when the Provider can't be reached, and the kept ones stand. With a [deadline]
     * (a [System.nanoTime]), a Provider slow to answer is waited for no longer than that: the kept
     * ones stand meanwhile, and its answer is kept when it comes.
     */
    private fun movieDetailsForPage(title: StoredTitle, listing: StoredListing, deadline: Long? = null): Map<Long, StoredMovieDetails> {
        val kept = store.movieDetails(title.listings.map { it.id })
        val have = kept[listing.id]
        if (have != null && have.added == listing.added) return kept
        val login = credentials.load() ?: return kept
        // One call per Listing at a time: a page read again while the Provider is slow joins the call under way.
        val reading = try {
            movieDetailsReading.computeIfAbsent(listing.id) { id ->
                movieDetailsReads.submit(java.util.concurrent.Callable<StoredMovieDetails?> {
                    try {
                        val read = XtreamClient(login, http).movieDetails(id)
                        val now = Instant.now()
                        store.saveMovieDetails(id, read, listing.added, now)
                        detailsGeneration.incrementAndGet()
                        StoredMovieDetails(read.bitrate, now, listing.added, read.details)
                    } catch (e: ProviderException) {
                        null
                    } finally {
                        movieDetailsReading.remove(id)
                    }
                })
            }
        } catch (e: RejectedExecutionException) {
            // The Library is closing.
            return kept
        }
        val read = try {
            if (deadline == null) reading.get() else reading.get((deadline - System.nanoTime()).coerceAtLeast(0), TimeUnit.NANOSECONDS)
        } catch (e: TimeoutException) {
            // Kept once it comes: the page shows it the next time it reads its details.
            null
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
        return if (read == null) kept else kept + (listing.id to read)
    }

    /**
     * What the Provider says of [title]: the details (a Movie's from [kept], a Show's from its
     * Listing) of the first of [tellers] that says something ([Shown.tellers]: the App language's
     * Listings, then English's, then the original language's, then the rest) whose plot a Viewer of
     * the [appLanguage] reads ([Scripts.reads]: a Provider files Greek plots under `EN` Listings too),
     * else of the first that says something, else any of its Listings', else nothing. A Greek plot
     * under an English name only when no other Listing has one.
     */
    private fun providerDetails(title: StoredTitle, tellers: List<Candidate>, kept: Map<Long, StoredMovieDetails>, appLanguage: String): ProviderDetails {
        fun of(listing: StoredListing) = (if (title.id.kind == TitleKind.Movie) kept[listing.id]?.details else listing.details)?.takeUnless { it.isEmpty }
        val said = tellers.mapNotNull { of(it.listing) }
        return said.firstOrNull { Scripts.reads(appLanguage, it.plot) } ?: said.firstOrNull() ?: title.listings.firstNotNullOfOrNull(::of) ?: ProviderDetails.NONE
    }

    /**
     * [title]'s genres as [Genres] names them in English (ADR 0014): a Movie's from the Prefix table's
     * genre for each of its Listings' Categories, then from its kept details' genre field; a Show's
     * from its Listings' genre field, then their Categories. A Title's Listings in several languages
     * fold into one set, named in the [appLanguage]. A genre word the Prefix table can't map is kept as
     * written only from a Listing listed in the [appLanguage] ([candidates]' [Candidate.listedLanguage]):
     * an `IL -` Listing's `קומדיה` never shows in an English UI (#514).
     */
    private fun titleGenres(title: StoredTitle, candidates: List<Candidate>, kept: Map<Long, StoredMovieDetails>, appLanguage: String): List<String> {
        val language = primaryLanguage(appLanguage)
        val inAppLanguage = candidates.filter { it.listedLanguage == language }.mapTo(HashSet()) { it.listing.id }
        fun field(listing: StoredListing, genre: String?) = prefixTable.genres(genre, unknown = listing.id in inAppLanguage)
        val fromCategories = title.listings.flatMap { prefixTable.titleGenres(it.category) }
        val fromFields = when (title.id.kind) {
            TitleKind.Movie -> title.listings.flatMap { field(it, kept[it.id]?.details?.genre) }
            TitleKind.Show -> title.listings.flatMap { field(it, it.details?.genre) }
        }
        return (if (title.id.kind == TitleKind.Movie) fromCategories + fromFields else fromFields + fromCategories).distinct().map { Genres.named(it, appLanguage) }
    }

    /**
     * Runs [clear], the image loader's disk cache being emptied, once on this catalog: on the first
     * launch after the release that stopped holding the cloud's metadata (#480), so the curated art
     * an older app cached goes while the Provider's links load again as they are. Recorded once it
     * succeeds; one that fails runs again at the next launch. Blocking: call it off the main thread.
     */
    fun clearImagesOnce(clear: () -> Unit) {
        if (store.imagesCleared()) return
        try {
            clear()
        } catch (e: Exception) {
            return
        }
        store.saveImagesCleared()
    }

    /** The Provider's rating of [title] on a 10 scale: its [best] Listing's (its details' for a Movie), else any Listing's; null when it gives none. */
    private fun titleRating(title: StoredTitle, best: StoredListing, kept: Map<Long, StoredMovieDetails>): Double? {
        fun of(listing: StoredListing) = kept[listing.id]?.details?.rating ?: listing.rating
        return of(best) ?: title.listings.firstNotNullOfOrNull(::of)
    }

    /** The Provider's movie details kept for [titles]' movie Listings, by Listing id, read with a few queries for all of them. */
    private fun keptMovieDetails(titles: Collection<StoredTitle>): Map<Long, StoredMovieDetails> {
        val ids = titles.filter { it.id.kind == TitleKind.Movie }.flatMap { title -> title.listings.map { it.id } }
        return ids.chunked(LISTINGS_PER_QUERY).flatMap { store.movieDetails(it).entries }.associate { it.key to it.value }
    }

    /**
     * A Show's Episodes, from the Provider's show details of each of its Versions (API calls, a
     * few at a time, never a stream), merged by season and episode number. Details are kept on the
     * device and asked again only once the Provider changes that Version's Show; when it can't be
     * reached, the details kept from before stand. The Episodes are the Provider's and no others, each
     * with the still and runtime its details give (ADR 0014). Each Episode has how far the Viewer got
     * in it, and [ShowEpisodes.next] is the one to watch next. Null for a Movie, or a Show the catalog
     * doesn't have.
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun episodes(id: TitleId, contentLanguages: List<String>, appLanguage: String = "en", preferOriginalAudio: Boolean = true): ShowEpisodes? = withContext(Dispatchers.IO) {
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
        val original = originalLanguage(id)
        val shows = title.listings.filter { it.id in read }.map { likelyInOriginalLanguage(listOf(candidates(it)), original).single() to read.getValue(it.id) }
        landHistory(id)
        val played = store.episodeProgress(profile, id)
        val reads = store.fileReads(shows.flatMap { (_, episodes) -> episodes.map { it.id } })
        val merged = mergeEpisodes(shows, contentLanguages, reads, originalAudioLanguage(original, contentLanguages, preferOriginalAudio), expected(title, contentLanguages))
        val seasons = withProgress(merged, played)
        ShowEpisodes(seasons, unreadVersions = title.listings.count { it.id !in read }, next = nextEpisode(seasons, played))
    }

    private val _account = MutableStateFlow(store.accountEmail())

    /** When this device last learned it was signed in ([System.nanoTime]): answers to requests sent before then can't sign it out. */
    @Volatile private var signedInSince = Long.MIN_VALUE

    /** The thread telling the cloud of a sign-out it couldn't hear when it happened. */
    private val signOuts = Executors.newSingleThreadExecutor { Thread(it, "library-sign-outs").apply { isDaemon = true } }

    init {
        cloud?.onAccountHeard = ::accountHeard
    }

    /**
     * What every cloud answer says (#434): signed out by another TV, or with its Account deleted, the
     * device goes anonymous at its next request, keeping its catalog and Provider login. Still signed
     * in after signing itself out offline, it tells the cloud again.
     */
    private fun accountHeard(signedIn: Boolean, sentAt: Long) {
        if (!signedIn && _account.value != null && sentAt > signedInSince) {
            accountIs(null)
        } else if (signedIn && _account.value == null && store.signOutPending()) {
            signOuts.execute {
                if (!store.signOutPending()) return@execute
                try {
                    cloud?.signOut()
                    store.saveSignOutPending(false)
                } catch (_: CloudException) {
                }
            }
        }
    }

    /**
     * The email of the Account this device is signed in to (ADR 0015), or null: kept on the device,
     * so it reads without the cloud. A device that never signs in never asks the cloud about one.
     */
    val account: StateFlow<String?> = _account.asStateFlow()

    private val _offersSignInCard = MutableStateFlow(cloud != null && _account.value == null && !store.signInCardDone())

    /**
     * Whether the first Sync's checklist offers the sign-in card (#438): with a cloud, no Account and
     * the card neither dismissed ([dismissSignInCard]) nor used to sign in on this device. Once either,
     * never again, signed out or not.
     */
    val offersSignInCard: StateFlow<Boolean> = _offersSignInCard.asStateFlow()

    /** Back or Home on the sign-in card: it never shows again on this device; Settings keeps its row. */
    suspend fun dismissSignInCard() = withContext(Dispatchers.IO) {
        _offersSignInCard.value = false
        saveLater { store.saveSignInCardDone() }
    }

    /**
     * A fresh Sign-in code for the TV to show with its QR, replacing the one before; null without a
     * cloud or while it can't be reached. The phone signs the TV in with it; [checkSignIn] notices.
     * The device's [model] ("Fire TV Stick 4K Max") names it in the Account's device list: it is sent
     * here and nowhere else.
     */
    suspend fun startSignIn(model: String? = null): SignInCode? = withContext(Dispatchers.IO) {
        try {
            cloud?.startSignIn(model)
        } catch (e: CloudException) {
            null
        }
    }

    /**
     * Asks the cloud which Account this device is signed in to, keeps the answer in [account] and
     * returns it. While the cloud can't answer, what the device last knew.
     */
    suspend fun checkSignIn(): String? = withContext(Dispatchers.IO) {
        if (cloud == null) return@withContext null
        val sentAt = System.nanoTime()
        val email = try {
            cloud.account()
        } catch (e: CloudException) {
            return@withContext _account.value
        }
        synchronized(_account) {
            if (email != null) {
                signedInSince = sentAt
                // On the saving thread: a first Sync's Titles stage holds the writer for minutes, and
                // the sign-in card under its checklist must turn to "Signed in as …" meanwhile.
                // Skipped when the device signed out meanwhile, whose pending sign-out must stand.
                saveLater { if (_account.value != null) store.saveSignOutPending(false) }
            }
            accountIs(email)
        }
        if (email != null && _offersSignInCard.value) {
            _offersSignInCard.value = false
            saveLater { store.saveSignInCardDone() }
        }
        email
    }

    /**
     * Keeps [email] as the Account the device is in. Signing out takes the Account's history off
     * the device; signing in syncs, merging the device's history into the Account.
     */
    private fun accountIs(email: String?): Unit = synchronized(_account) {
        val was = _account.value
        if (email == was) return
        // Known at once, kept on the saving thread, which waits out a Sync holding the writer.
        _account.value = email
        saveLater { store.saveAccountEmail(email) }
        if (was != null) {
            synchronized(_profiles) {
                if (accountSync.signedOut()) {
                    forgetProfileCaches()
                    _profiles.value = readProfiles()
                    readSettings()
                }
            }
            changed()
        }
        if (email != null) accountSync.pushSoon()
    }

    /**
     * The devices signed in to this one's Account (#434), by when they signed in, this one marked;
     * null without a cloud, while it can't be reached, or when this device isn't signed in.
     */
    suspend fun accountDevices(): List<AccountDevice>? = withContext(Dispatchers.IO) {
        if (cloud == null || _account.value == null) return@withContext null
        try {
            cloud.accountDevices()
        } catch (e: CloudException) {
            null
        }
    }

    /**
     * Signs [device] out of the Account, or this device when it is null or [AccountDevice.thisDevice].
     * This device goes anonymous at once, keeping its catalog and Provider login, and the cloud is
     * told now or, when it can't be reached, at the device's next request. Another device is signed
     * out in the cloud and goes anonymous at its own next request; false when the cloud can't be reached.
     */
    suspend fun signOut(device: AccountDevice? = null): Boolean = withContext(Dispatchers.IO) {
        if (device != null && !device.thisDevice) {
            return@withContext try {
                cloud?.signOut(device.id) != null
            } catch (e: CloudException) {
                false
            }
        }
        synchronized(_account) {
            store.saveSignOutPending(cloud != null)
            accountIs(null)
        }
        try {
            cloud?.signOut()
            store.saveSignOutPending(false)
        } catch (e: CloudException) {
            // Told again at the next request that finds the device still signed in.
        }
        true
    }

    /**
     * What pressing Play on a Movie starts: the Best playable Version for the Viewer's
     * [contentLanguages] (BCP 47 tags, in order of preference) and [device] within the Viewer's
     * [qualityCap], with its Backups. Null for a Show, when the catalog doesn't have the Title, or
     * when it has no Version the device can display other than Audio-description ones, which are
     * never picked automatically. A Title with nothing within the cap plays its lowest quality above
     * it. It starts where the Viewer stopped last time, unless they finished the Title. With
     * [preferOriginalAudio], a Version with Original audio comes first on a Title released outside
     * the Content languages ([playable]).
     */
    suspend fun playPlan(id: TitleId, contentLanguages: List<String>, device: DeviceProfile, qualityCap: QualityCap = QualityCap.UHD, preferOriginalAudio: Boolean = true): PlayPlan? = withContext(Dispatchers.IO) {
        if (id.kind != TitleKind.Movie) return@withContext null
        val title = store.title(id) ?: return@withContext null
        val remembered = rememberedTracks()
        val originalLanguage = originalLanguage(id)
        val original = originalAudioLanguage(originalLanguage, contentLanguages, preferOriginalAudio)
        val candidates = candidates(title, contentLanguages).let { if (original != null) likelyInOriginalLanguage(it, originalLanguage) else it }
        val ranked = playable(candidates, contentLanguages, device, qualityCap, remembered.audio, original)
        val stopped = stopped(id)
        ranked.firstOrNull()?.let {
            PlayPlan(
                id, withBackups(it, ranked), contentLanguages, leadersWithBackups(ranked), stopped?.position ?: 0,
                remembered = remembered, length = stopped?.duration, originalLanguage = originalLanguage, choices = titleChoices(id),
                preferOriginalAudio = preferOriginalAudio,
            )
        }
    }

    /**
     * What pressing Play starts: the Header read in flight, if one is, is let finish (for up to
     * [PLAY_WAITS_FOR_READ] from the press), the reads behind it are dropped, and then the plan is
     * made, so a Version Confirmed in that moment is the one that plays: a Movie's [playPlan], or a
     * Show's [episodePlayPlan] for [episode]. Null as those are.
     */
    suspend fun pressPlay(
        id: TitleId,
        contentLanguages: List<String>,
        device: DeviceProfile,
        qualityCap: QualityCap = QualityCap.UHD,
        preferOriginalAudio: Boolean = true,
        episode: EpisodeNumber? = null,
    ): PlayPlan? {
        connection.finishRead()
        return if (episode != null) episodePlayPlan(id, episode, contentLanguages, device, qualityCap, preferOriginalAudio)
        else playPlan(id, contentLanguages, device, qualityCap, preferOriginalAudio)
    }

    /**
     * What playing a Show's [episode] starts: its Best playable Version, chosen as a Movie's is, from
     * the Episodes [episodes] read before; it starts where the Viewer stopped, unless they finished it. Null for a Movie, or when the
     * Show has no such Episode or no Version of it the device can display.
     */
    suspend fun episodePlayPlan(id: TitleId, episode: EpisodeNumber, contentLanguages: List<String>, device: DeviceProfile, qualityCap: QualityCap = QualityCap.UHD, preferOriginalAudio: Boolean = true): PlayPlan? =
        withContext(Dispatchers.IO) { episodePlans(id, contentLanguages, device, qualityCap, preferOriginalAudio)?.get(episode)?.invoke() }

    /**
     * What to offer once [plan]'s Episode ends: the Show's next Episode the device can play, the next
     * season's first after a season's last, and specials after the last season. Null for a Movie, or
     * after the Show's last Episode.
     */
    suspend fun nextEpisodePlan(plan: PlayPlan, device: DeviceProfile, qualityCap: QualityCap = QualityCap.UHD): PlayPlan? = withContext(Dispatchers.IO) {
        val playing = plan.episode ?: return@withContext null
        val plans = episodePlans(plan.titleId ?: return@withContext null, plan.contentLanguages, device, qualityCap, plan.preferOriginalAudio) ?: return@withContext null
        plans.tailMap(playing).entries.asSequence().filter { it.key != playing }.firstNotNullOfOrNull { it.value() }
    }

    /** A Show's Episodes, in order, each with what playing it starts, from the show details kept on the device. */
    private fun episodePlans(id: TitleId, contentLanguages: List<String>, device: DeviceProfile, qualityCap: QualityCap, preferOriginalAudio: Boolean): SortedMap<EpisodeNumber, () -> PlayPlan?>? {
        if (id.kind != TitleKind.Show) return null
        val byEpisode = episodeCandidates(id, contentLanguages) ?: return null
        val remembered = rememberedTracks()
        landHistory(id)
        val stopped = store.episodeProgress(profile, id).filter { !it.watched }.associateBy { it.episode }
        val originalLanguage = originalLanguage(id)
        val original = originalAudioLanguage(originalLanguage, contentLanguages, preferOriginalAudio)
        // The Show's: what the Viewer chose in one Episode carries to the next.
        val choices = titleChoices(id)
        val plans: SortedMap<EpisodeNumber, () -> PlayPlan?> = TreeMap(byEpisode.comparator())
        byEpisode.forEach { (episode, candidates) ->
            plans[episode] = {
                val ranked = playable(candidates, contentLanguages, device, qualityCap, remembered.audio, original)
                ranked.firstOrNull()?.let {
                    val at = stopped[episode]
                    PlayPlan(id, withBackups(it, ranked), contentLanguages, leadersWithBackups(ranked), at?.position ?: 0, episode, remembered, at?.duration, originalLanguage, choices, preferOriginalAudio)
                }
            }
        }
        return plans
    }

    /** Each of the Show [id]'s Episodes with its Versions' candidates, from the show details kept on the device. */
    private fun episodeCandidates(id: TitleId, contentLanguages: List<String>): SortedMap<EpisodeNumber, List<Candidate>>? {
        val title = store.title(id) ?: return null
        val kept = store.showDetails(title.listings.map { it.id })
        val original = originalLanguage(id)
        val shows = title.listings.filter { it.id in kept }.map { likelyInOriginalLanguage(listOf(candidates(it)), original).single() to kept.getValue(it.id).episodes }
        return episodeCandidates(shows, store.fileReads(shows.flatMap { (_, episodes) -> episodes.map { it.id } }), expected(title, contentLanguages))
    }

    /**
     * What choosing the Version [listingId] (a Provider Episode id) of the Show [id]'s [episode] in its
     * Versions plays, as [chosenPlayPlan] does for a Movie: that Version with its Backups, then the
     * Episode's other Versions, from where the Viewer stopped in it. Null when the Show doesn't have it.
     */
    suspend fun chosenEpisodePlayPlan(id: TitleId, episode: EpisodeNumber, listingId: Long, contentLanguages: List<String>, preferOriginalAudio: Boolean = true): PlayPlan? = withContext(Dispatchers.IO) {
        if (id.kind != TitleKind.Show) return@withContext null
        val candidates = episodeCandidates(id, contentLanguages)?.get(episode) ?: return@withContext null
        candidates.find { it.listing.id == listingId }?.let { chosen ->
            val version = withBackups(chosen, candidates)
            val remembered = rememberedTracks()
            val others = leadersWithBackups(preferred(candidates, rankingLanguages(remembered, contentLanguages)).filter { it.equivalence != chosen.equivalence })
            landHistory(id)
            val at = store.episodeProgress(profile, id).firstOrNull { it.episode == episode && !it.watched }
            PlayPlan(
                id, version, contentLanguages, listOf(version) + others, at?.position ?: 0, episode,
                remembered, at?.duration, originalLanguage(id), titleChoices(id), preferOriginalAudio,
            )
        }
    }

    /**
     * The language [id] was released in, as a primary subtag, from the Title facts Wikidata gave through
     * the cloud after a Sync (#476), kept with the catalog; null when Wikidata names none, doesn't know
     * the Title, or hasn't been asked.
     */
    private fun originalLanguage(id: TitleId): String? = store.titleFacts(id)?.originalLanguage

    /** [originalLanguage] of each of [ids] that has one, in a few queries. */
    private fun originalLanguages(ids: Collection<TitleId>): Map<TitleId, String?> = store.titleFacts(ids).mapValues { it.value?.originalLanguage }

    /** The audio and subtitle languages the Viewer last picked in the player. */
    fun rememberedTracks(): RememberedTracks = remembered ?: run {
        val subtitles = store.rememberedTrack(profile, SUBTITLES)
        RememberedTracks(
            store.rememberedTrack(profile, AUDIO),
            subtitles?.takeIf { it != SUBTITLES_OFF && it != CLOSED_CAPTIONS },
            subtitlesOff = subtitles == SUBTITLES_OFF,
            closedCaptions = subtitles == CLOSED_CAPTIONS,
        ).also { remembered = it }
    }

    /** Remembers that the Viewer picked audio in [language] (a BCP 47 tag), so it plays next time, and its Versions rank first. */
    suspend fun rememberAudio(language: String) = withContext(Dispatchers.IO) {
        rememberTrack(AUDIO, language)
        remembered = rememberedTracks().copy(audio = language)
    }

    /** Remembers that the Viewer picked subtitles in [language], or turned them off when null, so the next file starts the same. */
    suspend fun rememberSubtitles(language: String?) = withContext(Dispatchers.IO) {
        rememberTrack(SUBTITLES, language ?: SUBTITLES_OFF)
        remembered = rememberedTracks().copy(subtitles = language, subtitlesOff = language == null, closedCaptions = false)
    }

    /** Remembers that the Viewer turned on a Channel's closed captions, so they stay on in the other player and on the next Channel. */
    suspend fun rememberClosedCaptions() = withContext(Dispatchers.IO) {
        rememberTrack(SUBTITLES, CLOSED_CAPTIONS)
        remembered = rememberedTracks().copy(subtitles = null, subtitlesOff = false, closedCaptions = true)
    }

    @Volatile private var remembered: RememberedTracks? = null

    /** Keeps what the Viewer picked in the player, and sends it to the Account's other TVs. */
    private fun rememberTrack(kind: String, value: String) {
        store.rememberTrack(profile, kind, value)
        accountSync.record(profile, SyncEntry.SETTING, SyncEntry.TRACK + kind, value, Instant.now())
        accountSync.pushSoon()
    }

    /** What the Viewer chose the last time [id] played (#291); nothing, when they never chose anything in it. */
    fun titleChoices(id: TitleId): TitleChoices = store.titleChoices(profile, id)?.let(::titleChoices) ?: TitleChoices()

    /**
     * Keeps what the Viewer chose while [plan] plays, so the Title starts the same next time: a
     * Movie's, or its Show's, which its next Episode starts with too. A Channel's plan keeps nothing.
     */
    suspend fun saveChoices(plan: PlayPlan, choices: TitleChoices) = withContext(Dispatchers.IO) {
        val id = plan.titleId ?: return@withContext
        val at = Instant.now()
        val body = choices.toJson()
        store.saveTitleChoices(profile, id, body, at)
        accountSync.record(profile, SyncEntry.TITLE_CHOICES, historyKey(id), body, at, perProvider = id is TitleId.Unmatched)
        accountSync.pushSoon()
    }

    /**
     * What playing the Version from Listing [listingId], chosen by the Viewer, starts, from where they
     * stopped last time; null for a Show or when the Title doesn't have it.
     */
    suspend fun chosenPlayPlan(id: TitleId, listingId: Long, contentLanguages: List<String>, preferOriginalAudio: Boolean = true): PlayPlan? = withContext(Dispatchers.IO) {
        if (id.kind != TitleKind.Movie) return@withContext null
        val candidates = store.title(id)?.let { candidates(it, contentLanguages) } ?: return@withContext null
        candidates.find { it.listing.id == listingId }?.let { chosen ->
            val version = withBackups(chosen, candidates)
            val remembered = rememberedTracks()
            // The chosen Version leads the Versions to switch to, whether or not it would have led its Backups.
            val others = leadersWithBackups(preferred(candidates, rankingLanguages(remembered, contentLanguages)).filter { it.equivalence != chosen.equivalence })
            val stopped = stopped(id)
            PlayPlan(
                id, version, contentLanguages, listOf(version) + others, stopped?.position ?: 0,
                remembered = remembered, length = stopped?.duration, originalLanguage = originalLanguage(id), choices = titleChoices(id),
                preferOriginalAudio = preferOriginalAudio,
            )
        }
    }

    /**
     * The subtitles that can be downloaded for [plan]'s Movie or Episode in [languages] (ISO 639-1),
     * the best few in each language first. Empty for a Title without a Title number, or when the cloud
     * can't answer: the Viewer plays on without them.
     */
    suspend fun subtitleOffers(plan: PlayPlan, languages: List<String>): List<SubtitleOffer> = withContext(Dispatchers.IO) {
        val id = plan.titleId as? TitleId.Numbered
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
    private fun stopped(id: TitleId): StoredProgress? {
        landHistory(id)
        return store.progress(profile, id)?.takeUnless { it.watched }
    }

    /**
     * Records that the Viewer is at [position] of [duration] (milliseconds, null when the player
     * doesn't know it) in [id]. In its credits ([inCredits]) the Title counts as watched, and under a minute
     * in nothing is recorded, so a Title opened by mistake doesn't land in Continue watching.
     */
    internal fun saveProgress(id: TitleId, position: Long, duration: Long?) {
        val watched = inCredits(position, duration)
        if (!watched && position < MIN_RESUME_POSITION) return
        record(id, null, if (watched) 0 else position, duration, watched)
    }

    /** Keeps where the Viewer is in [id] (an [episode] of it, for a Show), and queues it for the Account. */
    private fun record(id: TitleId, episode: EpisodeNumber?, position: Long, duration: Long?, watched: Boolean) {
        val at = Instant.now()
        if (episode == null) store.saveProgress(profile, id, position, duration, watched, at)
        else store.saveEpisodeProgress(profile, id, episode, position, duration, watched, at)
        accountSync.recordHistory(profile, HistoryChange(id, episode, position, duration, watched, removed = false, at))
    }

    /** Records how far the Viewer is in what [plan] plays, as [saveProgress] does: a Movie, or a Show's Episode. A Channel has none. */
    internal fun saveProgress(plan: PlayPlan, position: Long, duration: Long?) {
        val id = plan.titleId ?: return
        val episode = plan.episode ?: return saveProgress(id, position, duration)
        val watched = inCredits(position, duration)
        if (!watched && position < MIN_RESUME_POSITION) return
        record(id, episode, if (watched) 0 else position, duration, watched)
    }

    /** Records that the Viewer played [id] to its end. */
    internal fun finished(id: TitleId) = record(id, null, 0, null, watched = true)

    /** Records that the Viewer played what [plan] plays to its end. */
    internal fun finished(plan: PlayPlan) {
        val id = plan.titleId ?: return
        val episode = plan.episode ?: return finished(id)
        record(id, episode, 0, null, watched = true)
    }

    /**
     * Takes [id] out of Continue watching and forgets where the Viewer stopped, so Play starts it
     * over: on every TV of the Account too, unless one of them plays it later.
     */
    suspend fun removeFromContinueWatching(id: TitleId) = withContext(Dispatchers.IO) {
        val at = Instant.now()
        store.forgetProgress(profile, id, at)
        accountSync.recordHistory(profile, HistoryChange(id, null, 0, null, watched = false, removed = true, at))
        accountSync.pushSoon()
    }

    /**
     * The home screen's rows: Continue watching, then Live now (the Games of the [leagues] on that
     * are in progress with a Feed, as on the Live tab; no row at all otherwise), then Trending
     * ([trendingTitles]: what Wikipedia's readers looked up most this week, movies and shows mixed),
     * then New on your service (the most recently added Titles, newest first by when the Provider
     * added them, two added the same second by its rating), then the Viewer's Favourites (in the
     * order they were added; finished Event channels left out), then one row per genre
     * ([genreRows]), the genre the Provider has the most Titles of first. Browse rows show only
     * Titles with a Version in the Viewer's [contentLanguages], or an Untagged one Likely in one;
     * Trending none at all under [MIN_TRENDING_SIZE]. Continue watching shows whatever the Viewer started, a Show at the Episode to
     * watch next, until they finish it. Genres come from the Prefix table's mapping of the Provider's
     * Categories and genre fields, so they need no cloud. A Title shows in one browse row only, the highest it fits
     * ([ScreenRows]); a genre row left with too few Titles gives its place to the next genre. Rows
     * with no Titles are left out.
     */
    suspend fun homeRows(contentLanguages: List<String>, appLanguage: String, region: String = DEFAULT_REGION, leagues: List<League> = emptyList()): List<HomeRow> =
        homeRows(contentLanguages, appLanguage, region, leagues, Instant.now())

    internal suspend fun homeRows(contentLanguages: List<String>, appLanguage: String, region: String, leagues: List<League>, now: Instant): List<HomeRow> = withContext(Dispatchers.IO) {
        if (kids) return@withContext kidsHomeRows(contentLanguages, appLanguage)
        // Home gets one Games row, Live now, while a Game with a Feed is on (docs/roadmap.md, M3): the same Games and Feeds as the
        // Live tab's, but its first rows never wait on the cloud: the kept Games stand, and the rows are read again once fresh ones land.
        val liveNow = matchedGames(leagues, contentLanguages, region, now, waitForCloud = false).current.filter { it.game.state == GameState.Live && it.best != null }
        val resumes = continueWatching(contentLanguages)
        val favourites = store.favourites(profile, Instant.now() - EVENT_FINISHED_AFTER)
        val movies = store.titles(TitleKind.Movie, 0, HOME_MOVIES)
        val shows = store.titles(TitleKind.Show, 0, HOME_SHOWS)
        val trends = trendingTitles(appLanguage, now)
        // The newest movies and the newest shows, in the Viewer's languages, together newest first.
        val browsable = listOf(movies, shows).map { titles -> watchable(titles, contentLanguages) }
        val newest = (browsable[0].take(NEW_MOVIES) + browsable[1].take(NEW_SHOWS)).sortedWith(newestFirst)
        val screen = ScreenRows()
        // Trending sits above New, so New skips what Trending shows.
        val trending = screen.row(watchable(trends, contentLanguages), ROW_SIZE, MIN_TRENDING_SIZE)
        val new = screen.row(newest, ROW_SIZE)
        val genres = genreRows(TitleKind.entries, contentLanguages, appLanguage, screen, GENRE_ROWS)
        val inGenres = genres.flatMap { (_, titles) -> titles }
        val onScreen = (resumes.map { it.first } + movies + trending + inGenres).distinctBy { it.id }
        val kept = keptMovieDetails(onScreen)
        val originals = originalLanguages(onScreen.map { it.id })
        fun card(title: StoredTitle) = rowCard(title, contentLanguages, appLanguage, kept, originals[title.id])
        buildList {
            continueWatchingRow(resumes, kept, ::card)?.let(::add)
            if (liveNow.isNotEmpty()) add(HomeRow.LiveNow(liveNow))
            if (trending.isNotEmpty()) add(HomeRow.Trending(trending.map { card(it) }))
            if (new.isNotEmpty()) add(HomeRow.NewOnYourService(new.map { card(it) }))
            if (favourites.isNotEmpty()) add(HomeRow.Favourites(favourites))
            genres.forEach { (name, titles) -> add(HomeRow.Genre(name, titles.map { card(it) })) }
            wantGuide(favourites = favourites)
        }
    }

    /** Continue watching's row of [resumes] ([continueWatching]), each with its [card]. Null when there are none. */
    private fun continueWatchingRow(resumes: List<Pair<StoredTitle, Any>>, kept: Map<Long, StoredMovieDetails>, card: (StoredTitle) -> TitleSummary): HomeRow.ContinueWatching? {
        if (resumes.isEmpty()) return null
        return HomeRow.ContinueWatching(
            resumes.map { (title, at) ->
                val played = when (at) {
                    is StoredProgress -> at.position to at.duration
                    is ShowInProgress -> at.played?.let { it.position to it.duration }
                    else -> null
                }
                val duration = played?.second?.takeIf { it > 0 }
                val fraction = duration?.let { (played.first.toFloat() / it).coerceIn(0f, 1f) }
                val left = duration?.let { Duration.ofMillis((it - played.first).coerceAtLeast(0)) }
                val summary = card(title)
                InProgress(summary, fraction, left, (at as? ShowInProgress)?.next)
            },
        )
    }

    /**
     * Continue watching's Titles, last played first, each with where the Viewer is in it: Movies by
     * how far they got ([StoredProgress]), Shows by the Episode they're at ([ShowInProgress]); a Show
     * they Watched to its end is left out.
     */
    private fun continueWatching(contentLanguages: List<String>): List<Pair<StoredTitle, Any>> {
        val progress = store.inProgress(profile, ROW_SIZE).associateBy { it.id }
        return store.lastPlayed(profile, ROW_SIZE).map { it.first }.distinct().mapNotNull { id ->
            val title = store.title(id) ?: return@mapNotNull null
            if (id.kind == TitleKind.Show) showInProgress(title, contentLanguages)?.let { title to it } else progress[id]?.let { title to it }
        }
    }

    /**
     * The Title [screen]'s hero features, chosen each time the screen is entered: one of the first
     * [FEATURED_FROM] of each kind it shows in Trending ([trendingTitles]), else, when Trending has
     * none that qualifies, of the newest as New on your service has them (movies and shows in
     * turn on Home, a movie first; the kind's on Movies and Shows), with a Version in the Viewer's
     * [contentLanguages] and not in Continue watching. A Show's details are on its Listing since Sync;
     * a Movie whose details (those of the Listing that names it, whose plot the hero shows) were never
     * read has them read here, as its page would, at most once per call and only when [readDetails],
     * so an entry costs the Provider one call at most and none once they are kept. It rotates: each
     * call features the one after the Title featured last on that screen, kept on the device, so the
     * same Title doesn't come back on the next entry, and from the first again past the last. Its
     * badge is the Title page's ([featuredBadge]), for the [device], [qualityCap] and
     * [preferOriginalAudio] its Play goes by. Null when no Title qualifies: the screen opens on its rows.
     */
    suspend fun featured(
        screen: FeaturedScreen,
        contentLanguages: List<String>,
        appLanguage: String,
        device: DeviceProfile,
        qualityCap: QualityCap = QualityCap.UHD,
        preferOriginalAudio: Boolean = true,
        readDetails: Boolean = true,
    ): FeaturedTitle? = featured(screen, contentLanguages, appLanguage, Instant.now(), device, qualityCap, preferOriginalAudio, readDetails)

    internal suspend fun featured(
        screen: FeaturedScreen,
        contentLanguages: List<String>,
        appLanguage: String,
        now: Instant,
        device: DeviceProfile,
        qualityCap: QualityCap = QualityCap.UHD,
        preferOriginalAudio: Boolean = true,
        readDetails: Boolean = true,
    ): FeaturedTitle? = withContext(Dispatchers.IO) {
        val title = featuredTitle(screen, contentLanguages, appLanguage, now, readDetails) ?: return@withContext null
        title.copy(badge = featuredBadge(title.id, contentLanguages, device, qualityCap, preferOriginalAudio))
    }

    /**
     * The quality and language the hero badges a featured Title with, those of the Version its Play
     * starts, as the Title page's badges (#515, #521), from what the device keeps and never the
     * Provider: a Movie's [playPlan]'s Version (its best Version when nothing plays); a Show's, that
     * of the Episode to watch next, from the Episodes read when its page last opened. Null for a Show
     * whose Episodes were never read, which no badge could name truthfully, and for a Title the
     * catalog doesn't have.
     */
    suspend fun featuredBadge(id: TitleId, contentLanguages: List<String>, device: DeviceProfile, qualityCap: QualityCap = QualityCap.UHD, preferOriginalAudio: Boolean = true): VersionAttributes? = withContext(Dispatchers.IO) {
        when (id.kind) {
            TitleKind.Movie -> playPlan(id, contentLanguages, device, qualityCap, preferOriginalAudio)?.version?.attributes
                ?: store.title(id)?.let { shown(candidates(it), contentLanguages, "en", originalLanguage(id)).best.attributes }
            TitleKind.Show -> keptNextEpisode(id, contentLanguages, preferOriginalAudio)?.let { episodePlayPlan(id, it, contentLanguages, device, qualityCap, preferOriginalAudio)?.version?.attributes }
        }
    }

    /** The Show [id]'s Episode to watch next, from the show details kept on the device; null when none were read. */
    private fun keptNextEpisode(id: TitleId, contentLanguages: List<String>, preferOriginalAudio: Boolean): EpisodeNumber? {
        val title = store.title(id) ?: return null
        val read = store.showDetails(title.listings.map { it.id })
        if (read.isEmpty()) return null
        val original = originalLanguage(id)
        val shows = title.listings.filter { it.id in read }.map { likelyInOriginalLanguage(listOf(candidates(it)), original).single() to read.getValue(it.id).episodes }
        val played = store.episodeProgress(profile, id)
        val reads = store.fileReads(shows.flatMap { (_, episodes) -> episodes.map { it.id } })
        val merged = mergeEpisodes(shows, contentLanguages, reads, originalAudioLanguage(original, contentLanguages, preferOriginalAudio), expected(title, contentLanguages))
        return nextEpisode(withProgress(merged, played), played)
    }

    /** The Title [screen]'s hero features, as [featured] picks it, with no badge yet. */
    private fun featuredTitle(screen: FeaturedScreen, contentLanguages: List<String>, appLanguage: String, now: Instant, readDetails: Boolean): FeaturedTitle? {
        if (kids) return kidsFeatured(screen, contentLanguages, appLanguage, readDetails)
        val kinds = featuredKinds(screen)
        val trends = trendingTitles(appLanguage, now)
        val trending = kinds.map { kind -> trends.filter { it.id.kind == kind } }
        val newest = kinds.map { kind -> store.titles(kind, 0, if (kind == TitleKind.Movie) HOME_MOVIES else HOME_SHOWS) }
        return nextFeatured(screen.name.lowercase(), listOf(trending, newest), contentLanguages, appLanguage, readDetails)
    }

    /** The kinds of Title [screen]'s hero features, in the order it takes them in turn. */
    private fun featuredKinds(screen: FeaturedScreen) = when (screen) {
        FeaturedScreen.Home -> listOf(TitleKind.Movie, TitleKind.Show)
        FeaturedScreen.Movies -> listOf(TitleKind.Movie)
        FeaturedScreen.Shows -> listOf(TitleKind.Show)
    }

    /**
     * The hero's Title from [sources], in order of preference (Trending's Titles of each kind, then the
     * newest of each kind, newest first): of each kind's list, the first [FEATURED_FROM] that are
     * watchable in [contentLanguages] and not in Continue watching, taken in turn. The first source
     * with such a Title is featured from; the next stands in only when it has none. The one after the
     * Title featured last on the screen kept as [key] is featured, from the first again past the last.
     * What the hero tells of a Title is in its original language, else English, as on its page (the
     * Listing telling of it, [Shown.telling]); a Movie's details of that Listing, unread, are read for
     * the first such Movie reached, when [readDetails], and for no other: one Provider call at most. A
     * Movie whose details stay unread (not read this call, or the Provider didn't answer) is passed
     * over: the hero tells of its Title. Null when none qualifies.
     */
    private fun nextFeatured(key: String, sources: List<List<List<StoredTitle>>>, contentLanguages: List<String>, appLanguage: String, readDetails: Boolean): FeaturedTitle? {
        val started = continueWatching(contentLanguages).mapTo(HashSet()) { it.first.id }
        val all = sources.flatten().flatten().distinctBy { it.id }
        val kept = keptMovieDetails(all).toMutableMap()
        val originals = originalLanguages(all.map { it.id })
        fun unread(title: StoredTitle, listing: StoredListing) = title.id.kind == TitleKind.Movie && kept[listing.id]?.added != listing.added
        val last = lastFeatured[key] ?: store.lastFeatured(key)
        var mayRead = readDetails
        for (lists in sources) {
            val tops = lists.map { titles ->
                watchable(titles, contentLanguages).asSequence().filter { it.id !in started }.map { it to shown(candidates(it), contentLanguages, appLanguage, originals[it.id]) }.take(FEATURED_FROM).toList()
            }
            val candidates = (0 until (tops.maxOfOrNull { it.size } ?: 0)).flatMap { i -> tops.mapNotNull { it.getOrNull(i) } }
            if (candidates.isEmpty()) continue
            val from = candidates.indexOfFirst { it.first.id == last } + 1
            for (i in candidates.indices) {
                val (title, shown) = candidates[(from + i) % candidates.size]
                val telling = shown.telling.listing
                if (unread(title, telling)) {
                    if (!mayRead) continue
                    mayRead = false
                    kept += movieDetailsForPage(title, telling)
                    if (unread(title, telling)) continue
                }
                lastFeatured[key] = title.id
                saveLater { store.saveLastFeatured(key, title.id) }
                // Told of in the App language, else English, else its original language, as on its page:
                // a subtitled `GR` copy with English audio may be the best Version of an English film.
                val details = providerDetails(title, shown.tellers, kept, appLanguage)
                return FeaturedTitle(
                    title.id, shown.name, shown.year ?: details.year, details.runtimeMinutes,
                    titleGenres(title, candidates(title), kept, appLanguage), details.plot, badge = null,
                    posters = shown.posters,
                )
            }
        }
        return null
    }

    /** The Title featured last on each screen, by the key [nextFeatured] keeps it under, so it needn't be read from the device again. */
    private val lastFeatured = ConcurrentHashMap<String, TitleId>()

    /**
     * What a Title's Play starts, as its details' Play does: for a Movie, its [playPlan]; for a Show,
     * the plan of its Episode to watch next ([episodes], [episodePlayPlan]). Null when there is
     * nothing to play.
     */
    suspend fun titlePlayPlan(id: TitleId, contentLanguages: List<String>, appLanguage: String, device: DeviceProfile, qualityCap: QualityCap = QualityCap.UHD, preferOriginalAudio: Boolean = true): PlayPlan? {
        // A press of Play: a Header read in flight (Background reading's) is let finish first.
        connection.finishRead()
        return when (id.kind) {
            TitleKind.Movie -> playPlan(id, contentLanguages, device, qualityCap, preferOriginalAudio)
            TitleKind.Show -> episodes(id, contentLanguages, appLanguage, preferOriginalAudio)?.next?.let { episodePlayPlan(id, it, contentLanguages, device, qualityCap, preferOriginalAudio) }
        }
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
        if (kids) return@withContext kidsLiveRows(region, contentLanguages, now)
        val favourites = store.favourites(profile, now - EVENT_FINISHED_AFTER)
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
        store.dropFeed(profile, entry.key, feed.channel.key, now, now - DROPPED_KEPT_FOR)
        accountSync.record(profile, SyncEntry.NOT_THIS_GAME, "${entry.key}|${feed.channel.key}", ON, now, perProvider = true)
        accountSync.pushSoon()
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
        val events = store.eventChannels(profile, now - EVENT_WINDOW_BEHIND, now + EVENT_WINDOW_AHEAD).map { (channel, start) ->
            EventChannelListing(channel, start, channel.categoryIds.firstNotNullOfOrNull { categories[it] })
        }
        if (games.isEmpty()) return MatchedGames(emptyList(), events)
        val dropped = store.droppedFeeds(profile, games.map { "${it.league}/${it.id}" })
        return MatchedGames(matchFeeds(games, events, store.channelsNamed(profile, broadcasterChannelNames(games)), contentLanguages, now, dropped, region), events)
    }

    /** What [matchedGames] found: [all] the Games with their Feeds, finished ones included, and the Event channels in the matching window. */
    internal class MatchedGames(val all: List<GameFeeds>, val events: List<EventChannelListing>) {
        /** The Games not over: what the Game rows show. */
        val current: List<GameFeeds> get() = all.filter { it.game.state != GameState.Over }
    }

    /**
     * The Movies or Shows tab's rows, for [kind]: Trending of that kind ([trendingTitles]), then New
     * movies (or shows): the newest Titles of that kind, newest first, two added the same second by
     * the Provider's rating, then a row per genre of that kind ([genreRows]), the genres with the
     * most of the Provider's Titles first. Each row shows only the Provider's Titles with a Version in
     * the Viewer's [contentLanguages], or an Untagged one Likely in one, Trending none at all under
     * [MIN_TRENDING_SIZE] and a genre none under [MIN_GENRE_SIZE]. The tab shows a Title once, in the
     * highest row it fits ([ScreenRows]), on its own: a Title in Home's Trending is in the tab's too.
     * Rows with no Titles are left out. Like Home, the Trending feed kept on the device stands while
     * the cloud can't answer; the genre rows need no cloud.
     */
    suspend fun tabRows(kind: TitleKind, contentLanguages: List<String>, appLanguage: String): List<HomeRow> =
        tabRows(kind, contentLanguages, appLanguage, Instant.now())

    internal suspend fun tabRows(kind: TitleKind, contentLanguages: List<String>, appLanguage: String, now: Instant): List<HomeRow> = withContext(Dispatchers.IO) {
        if (kids) return@withContext kidsTabRows(kind, contentLanguages, appLanguage)
        val trends = trendingTitles(appLanguage, now).filter { it.id.kind == kind }
        val newest = store.titles(kind, 0, TAB_NEW)
        val screen = ScreenRows()
        val trending = screen.row(watchable(trends, contentLanguages), ROW_SIZE, MIN_TRENDING_SIZE)
        val new = screen.row(watchable(newest, contentLanguages).sortedWith(newestFirst), ROW_SIZE)
        // The genre rows go after New, through the same [screen], so they skip what's shown above.
        val genres = genreRows(listOf(kind), contentLanguages, appLanguage, screen, TAB_GENRE_ROWS)
        val onScreen = trending + new + genres.flatMap { it.second }
        val kept = keptMovieDetails(onScreen)
        val originals = originalLanguages(onScreen.map { it.id })
        fun cards(titles: List<StoredTitle>) = titles.map { rowCard(it, contentLanguages, appLanguage, kept, originals[it.id]) }
        buildList {
            if (trending.isNotEmpty()) add(HomeRow.Trending(cards(trending), kind))
            if (new.isNotEmpty()) add(HomeRow.NewOnYourService(cards(new), kind))
            genres.forEach { (name, titles) -> add(HomeRow.Genre(name, cards(titles))) }
        }
    }

    /** Whether the device is on a Kids profile, which sees only the Kids catalog ([kidsCatalog]). */
    val kids: Boolean get() = _profiles.value.current.kids

    /**
     * The Kids catalog (CONTEXT.md) by its Categories alone: the Titles of each kind, newest first,
     * any of whose Listings is filed under a Category the Prefix table names with a Kids word
     * ([PrefixTable.isKids]). It needs no cloud. Worked out once per catalog and Prefix table.
     */
    internal fun kidsCatalog(): Map<TitleKind, List<TitleId>> {
        val table = prefixTable
        val generation = catalogGeneration.get()
        kidsByCategory?.takeIf { it.generation == generation && it.table === table }?.let { return it.titles }
        val titles = store.titlesFiled(store.listingCategories().filter(table::isKids))
        kidsByCategory = KidsByCategory(generation, table, titles)
        return titles
    }

    private class KidsByCategory(val generation: Int, val table: PrefixTable, val titles: Map<TitleKind, List<TitleId>>)

    @Volatile private var kidsByCategory: KidsByCategory? = null

    /** The newest [count] of the Kids catalog's [ids], as stored, in their order. */
    private fun newestKids(ids: List<TitleId>, count: Int): List<StoredTitle> {
        val taken = ids.take(count)
        val titles = store.titles(taken)
        return taken.mapNotNull(titles::get)
    }

    /**
     * Home's rows on a Kids profile: Continue watching, then New for kids (the newest Kids movies
     * and shows, newest first, two added the same second by the Provider's rating), Movies for kids
     * and Shows for kids (the newest of each kind left), then one row per genre over the Kids catalog
     * ([kidsGenres]). No Trending, no Games, no Favourites. Each row shows only Titles the Viewer can
     * watch in their [contentLanguages], and a Title once, in the highest row it fits.
     */
    private fun kidsHomeRows(contentLanguages: List<String>, appLanguage: String): List<HomeRow> {
        val catalog = kidsCatalog()
        val resumes = continueWatching(contentLanguages)
        val movies = newestKids(catalog.getValue(TitleKind.Movie), HOME_MOVIES)
        val shows = newestKids(catalog.getValue(TitleKind.Show), HOME_SHOWS)
        val browsable = listOf(movies, shows).map { watchable(it, contentLanguages) }
        val screen = ScreenRows()
        val new = screen.row((browsable[0].take(NEW_MOVIES) + browsable[1].take(NEW_SHOWS)).sortedWith(newestFirst), ROW_SIZE)
        val ofKind = TitleKind.entries.zip(browsable).map { (kind, titles) -> kind to screen.row(titles, ROW_SIZE) }
        val genres = kidsGenres(TitleKind.entries, catalog, contentLanguages, appLanguage, screen)
        fun cards(titles: List<StoredTitle>) = titles.map { rowCard(it, contentLanguages, appLanguage) }
        return buildList {
            continueWatchingRow(resumes, keptMovieDetails(resumes.map { it.first })) { rowCard(it, contentLanguages, appLanguage) }?.let(::add)
            if (new.isNotEmpty()) add(HomeRow.NewForKids(cards(new)))
            ofKind.forEach { (kind, titles) -> if (titles.isNotEmpty()) add(HomeRow.ForKids(kind, cards(titles))) }
            genres.forEach { (name, titles) -> add(HomeRow.Genre(name, cards(titles))) }
        }
    }

    /**
     * The genre rows of [kinds] over the Kids [catalog] ([genreRows]), through [screen] so they skip
     * what is shown above: by Category words and the Provider's genre fields alone, with no Family
     * row, every Kids Category being Family.
     */
    private fun kidsGenres(kinds: List<TitleKind>, catalog: Map<TitleKind, List<TitleId>>, contentLanguages: List<String>, appLanguage: String, screen: ScreenRows) =
        genreRows(kinds, contentLanguages, appLanguage, screen, GENRE_ROWS, within = kinds.flatMap { catalog.getValue(it) }.toHashSet(), leaveOut = KIDS_GENRE)

    /**
     * The Movies or Shows tab on a Kids profile, for [kind]: New for kids (the newest Kids Titles of
     * the kind, newest first, two added the same second by the Provider's rating), Movies (or Shows)
     * for kids (the newest left), then one row per genre over the Kids catalog. No Trending.
     */
    private fun kidsTabRows(kind: TitleKind, contentLanguages: List<String>, appLanguage: String): List<HomeRow> {
        val catalog = kidsCatalog()
        val titles = newestKids(catalog.getValue(kind), if (kind == TitleKind.Movie) HOME_MOVIES else HOME_SHOWS)
        val browsable = watchable(titles, contentLanguages)
        val screen = ScreenRows()
        val new = screen.row(browsable.take(TAB_NEW).sortedWith(newestFirst), ROW_SIZE)
        val ofKind = screen.row(browsable, ROW_SIZE)
        val genres = kidsGenres(listOf(kind), catalog, contentLanguages, appLanguage, screen)
        fun cards(of: List<StoredTitle>) = of.map { rowCard(it, contentLanguages, appLanguage) }
        return buildList {
            if (new.isNotEmpty()) add(HomeRow.NewForKids(cards(new), kind))
            if (ofKind.isNotEmpty()) add(HomeRow.ForKids(kind, cards(ofKind)))
            genres.forEach { (name, inGenre) -> add(HomeRow.Genre(name, cards(inGenre))) }
        }
    }

    /**
     * The hero on a Kids profile: one of the newest Kids Titles (movies and shows in turn on Home, a
     * movie first; the kind's on its tab), picked and rotated as [featured] does.
     */
    private fun kidsFeatured(screen: FeaturedScreen, contentLanguages: List<String>, appLanguage: String, readDetails: Boolean): FeaturedTitle? {
        val catalog = kidsCatalog()
        val lists = featuredKinds(screen).map { newestKids(catalog.getValue(it), if (it == TitleKind.Movie) HOME_MOVIES else HOME_SHOWS) }
        return nextFeatured("kids/${screen.name.lowercase()}", listOf(lists), contentLanguages, appLanguage, readDetails)
    }

    /**
     * Live on a Kids profile: the Profile's Favourites, then the Channels of Kids Categories as one
     * row per country, in the Channel browser's order of countries ([orderedCountries]). No Games,
     * Replays or Channel browser.
     */
    private fun kidsLiveRows(region: String, contentLanguages: List<String>, now: Instant): List<HomeRow> {
        val favourites = store.favourites(profile, now - EVENT_FINISHED_AFTER)
        val byCountry = kidsChannels(now)
        val channels = byCountry.mapKeys { it.key.code }
        return buildList {
            if (favourites.isNotEmpty()) add(HomeRow.Favourites(favourites))
            orderedCountries(byCountry.keys, region, contentLanguages).forEach { add(HomeRow.KidsChannels(it, channels.getValue(it.code))) }
            wantGuide(favourites = favourites)
        }
    }

    /** The Channels of the regular live Categories named with a Kids word, by the country their Categories' codes name ([browserCountry]). */
    private fun kidsChannels(now: Instant): Map<LiveCountry, List<Channel>> {
        val byCountry = LinkedHashMap<String, Pair<BrowserCountry, MutableList<String>>>()
        store.liveCategories().filter { it.kind == CategoryKind.Regular && prefixTable.isKids(it.name) }.forEach { category ->
            val where = category.country?.let(::browserCountry) ?: return@forEach
            byCountry.getOrPut(where.code) { where to mutableListOf() }.second += category.id
        }
        return byCountry.values.mapNotNull { (where, ids) ->
            val channels = store.categoryChannels(profile, ids, now - EVENT_FINISHED_AFTER)
            if (channels.isEmpty()) null else LiveCountry(where.code, channels.size, where.alias) to channels
        }.toMap()
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

    /**
     * [title]'s card on a row: its best Version in the Viewer's [contentLanguages], and what the info
     * under the rail tells about it, from what the device holds ([kept]: the Provider's movie details
     * kept for its Listings), so focus costs no Provider call (ADR 0014). The card names and pictures
     * it from its Listings alone, its posters' order starting from the Title's [originalLanguage].
     */
    private fun rowCard(
        title: StoredTitle, contentLanguages: List<String>, appLanguage: String,
        kept: Map<Long, StoredMovieDetails> = keptMovieDetails(listOf(title)),
        originalLanguage: String? = originalLanguage(title.id),
    ): TitleSummary {
        val candidates = candidates(title)
        val shown = shown(candidates, contentLanguages, appLanguage, originalLanguage)
        val about = TitleAbout(
            titleGenres(title, candidates, kept, appLanguage),
            titleRating(title, shown.best.listing, kept),
        )
        return TitleSummary(title.id, shown.name, shown.year, shown.image, shown.best.attributes, about, shown.posters)
    }

    /**
     * Titles newest first, by when the Provider added each one's newest Listing (a Show's when it
     * last changed), two added the same second by the Provider's best rating of it; otherwise in
     * their order. New on your service's order (#478).
     */
    private val newestFirst: Comparator<StoredTitle> =
        compareByDescending<StoredTitle> { title -> title.listings.maxOf { it.added ?: Long.MIN_VALUE } }.thenByDescending { title -> title.listings.maxOf { it.rating ?: 0.0 } }

    /**
     * One row per genre of [kinds], the genre the Provider has the most Titles of first (those
     * [within] a set when given, a genre of [leaveOut] left out), at most [maxRows] of them, each
     * named in [appLanguage]: its newest Titles in the Viewer's [contentLanguages] not on a row above
     * ([screen]), none under [MIN_GENRE_SIZE], a genre with too few left giving its place to the next.
     * The genres are the Prefix table's ([genreIndex]): a Movie's from its Category at Sync and its
     * details once read, so Movie rows fill as details arrive.
     */
    private fun genreRows(
        kinds: List<TitleKind>, contentLanguages: List<String>, appLanguage: String, screen: ScreenRows, maxRows: Int,
        within: Set<TitleId>? = null, leaveOut: Set<String> = emptySet(),
    ): List<Pair<String, List<StoredTitle>>> {
        val counted = genreIndex().byGenre.mapNotNull { (genre, ids) ->
            if (genre in leaveOut) return@mapNotNull null
            val of = ids.filter { it.kind in kinds && (within == null || it in within) }
            of.takeIf { it.size >= MIN_GENRE_SIZE }?.let { genre to it }
        }.sortedByDescending { it.second.size }
        return buildList {
            for ((genre, ids) in counted) {
                if (size == maxRows) break
                val row = screen.row(firstWatchable(ids, contentLanguages, screen), ROW_SIZE, MIN_GENRE_SIZE)
                if (row.isNotEmpty()) add(Genres.named(genre, appLanguage) to row)
            }
        }
    }

    /** Up to [ROW_SIZE] of [ids] in the Viewer's [contentLanguages] not yet on [screen], in their order, read a page at a time. */
    private fun firstWatchable(ids: List<TitleId>, contentLanguages: List<String>, screen: ScreenRows): List<StoredTitle> {
        val found = mutableListOf<StoredTitle>()
        for (page in ids.asSequence().filter { !screen.shows(it) }.chunked(GENRE_PAGE).take(GENRE_PAGES)) {
            val titles = store.titles(page)
            found += watchable(page.mapNotNull(titles::get), contentLanguages)
            if (found.size >= ROW_SIZE) break
        }
        return found
    }

    /**
     * The Provider's Titles of each genre the Prefix table names (#478, ADR 0014), in the table's
     * order of genres, each genre's Titles newest first as [newestFirst] orders them (movies before
     * shows added the same second): a Title is of the genres its Listings' Categories name
     * ([PrefixTable.titleGenres]) and those of the Provider's genre fields ([PrefixTable.genres]; a
     * Show's from its Listing, a Movie's from its details once read). Worked out once per catalog,
     * Prefix table, then kept up to date as movie details are kept: only their Titles are worked out
     * again (#497).
     */
    private fun genreIndex(): GenreIndex = synchronized(genreIndexLock) {
        // One build at a time: a second landing waits for the first's index instead of building its own (#497).
        val table = prefixTable
        val generation = catalogGeneration.get()
        val mark = store.movieGenresMark()
        val kept = genreIndex?.takeIf { it.generation == generation && it.table === table }
        if (kept != null && kept.mark == mark) return kept
        // What the table made of each Category and genre field stands across a Sync too.
        val parses = genreIndex?.parses?.takeIf { it.table === table } ?: GenreParses(table)
        val titles: HashMap<TitleId, GenreTitle>
        val order: List<TitleId>
        if (kept != null && mark.count >= kept.mark.count) {
            // Only movie details were kept since (Background reading confirms one about every 30 s): work out
            // again the Titles they're of, not the whole catalog (#497).
            titles = HashMap(kept.titles)
            val changed = HashMap<TitleId, GenreTitle>()
            store.forEachGenreSourceDetailedSince(kept.mark.latest) { id, position, category, field, added, rating ->
                changed.getOrPut(id) { GenreTitle(position) }.add(parses, category, field, added, rating)
            }
            titles += changed
            // Details change a Title's genres, not when its Listings were added or their rating: the order stands.
            order = kept.order
        } else {
            titles = HashMap()
            store.forEachGenreSource { id, position, category, field, added, rating ->
                titles.getOrPut(id) { GenreTitle(position) }.add(parses, category, field, added, rating)
            }
            order = titles.entries
                .sortedWith(compareByDescending<Map.Entry<TitleId, GenreTitle>> { it.value.added }.thenByDescending { it.value.rating }.thenBy { it.key.kind }.thenBy { it.value.position })
                .map { it.key }
        }
        val byGenre = LinkedHashMap<String, MutableList<TitleId>>()
        parses.known.forEach { byGenre[it] = mutableListOf() }
        for (id in order) titles.getValue(id).genres?.forEach { byGenre.getValue(it) += id }
        GenreIndex(generation, table, mark, parses, titles, order, byGenre).also { genreIndex = it }
    }

    /**
     * What [table] makes of each Category and genre field met so far ([PrefixTable.titleGenres],
     * [PrefixTable.genres]), only the genres it names a row for ([known]): kept from one genre index
     * to the next, so a later one parses only what's new.
     */
    private class GenreParses(val table: PrefixTable) {
        val known: List<String> = table.titleGenreNames
        private val isKnown = known.toHashSet()
        private val ofCategory = HashMap<String, List<String>>()
        private val ofField = HashMap<String, List<String>>()

        fun of(category: String, field: String?): List<String> {
            val named = ofCategory.getOrPut(category) { table.titleGenres(category).filter(isKnown::contains) }
            val fielded = field?.let { ofField.getOrPut(it) { table.genres(it).filter(isKnown::contains) } }.orEmpty()
            return if (fielded.isEmpty()) named else named + fielded
        }
    }

    /** One Title of the genre index: its [position] in its kind's list, its newest Listing's [added] and best [rating], and its [genres] (null for none). */
    private class GenreTitle(val position: Int) {
        var added = Long.MIN_VALUE
        var rating = 0.0
        var genres: MutableSet<String>? = null

        fun add(parses: GenreParses, category: String, field: String?, added: Long?, rating: Double?) {
            if (added != null && added > this.added) this.added = added
            if (rating != null && rating > this.rating) this.rating = rating
            val named = parses.of(category, field)
            if (named.isNotEmpty()) (genres ?: HashSet<String>(4).also { genres = it }) += named
        }
    }

    private class GenreIndex(
        val generation: Int,
        val table: PrefixTable,
        val mark: CatalogStore.MovieGenresMark,
        val parses: GenreParses,
        val titles: Map<TitleId, GenreTitle>,
        /** Every Title of the catalog, newest first as [newestFirst] orders them. */
        val order: List<TitleId>,
        val byGenre: Map<String, List<TitleId>>,
    )

    private val genreIndexLock = Any()

    @Volatile private var genreIndex: GenreIndex? = null

    /**
     * Where the Viewer is in Show [title], from the show details read when its details last opened:
     * the Episode to watch next and how far they got in it. Null once they Watched its last Episode,
     * or when its details were never read.
     */
    private fun showInProgress(title: StoredTitle, contentLanguages: List<String>): ShowInProgress? {
        val read = store.showDetails(title.listings.map { it.id })
        val shows = title.listings.filter { it.id in read }.map { candidates(it) to read.getValue(it.id).episodes }
        val played = store.episodeProgress(profile, title.id)
        val seasons = withProgress(mergeEpisodes(shows, contentLanguages), played)
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
     * Viewer's [contentLanguages] first; with [preferOriginalAudio] on a Title released outside them,
     * in order of how likely each is to carry Original audio ([readOrder]). A Show's are of each
     * Episode's, from the show details [episodes] kept, in the Show's order: the Viewer's languages
     * first, and among them first the Versions whose first audio Track, as the Provider's details
     * name it, disagrees with the Prefix. In both, the Versions whose Prefix neither the Base table
     * nor the Provider table knows come first, so what the app knows least about is Confirmed within
     * the first seconds. Pressing Play lets the read in flight finish (within
     * [PLAY_WAITS_FOR_READ]) and ends the reads behind it, and the Provider refusing one ends them
     * too. Calls [changed] each time a Version's attributes change, so the page shows them, and
     * [checking] with how many Versions are left to read once that is known, after each read, and
     * with 0 once the reads end, however they end (#457); a Title whose Versions are all read hears
     * nothing. Results are kept on the device and shared through the cloud, so a Version
     * is never read twice.
     */
    suspend fun readHeaders(
        id: TitleId,
        contentLanguages: List<String>,
        preferOriginalAudio: Boolean = true,
        checking: (left: Int) -> Unit = {},
        changed: suspend () -> Unit,
    ) = withContext(Dispatchers.IO) {
        detailsReading.incrementAndGet()
        var left = 0
        try {
            readDetailsHeaders(id, contentLanguages, preferOriginalAudio, { left = it; checking(it) }, changed)
        } finally {
            if (left > 0) checking(0)
            detailsReading.decrementAndGet()
        }
    }

    private suspend fun readDetailsHeaders(id: TitleId, contentLanguages: List<String>, preferOriginalAudio: Boolean, checking: (Int) -> Unit, changed: suspend () -> Unit) {
        val title = store.title(id) ?: return
        val login = credentials.load() ?: return
        val client = XtreamClient(login, http)
        val shows = keptShows(title)
        if (shows == null) readMovieDetails(client, title.listings, changed)
        // The Provider numbers Movies and Episodes from one sequence, so Listing ids don't collide.
        val ids = shows?.flatMap { (_, episodes) -> episodes.map { it.id } } ?: title.listings.map { it.id }
        val provider = providerKey(login)
        shareFileReads(provider)
        if (lookUpFileReads(provider, ids)) changed()
        // This page's Versions come before Background reading's.
        connection.cancelRead()
        val unread = toRead(title, shows, contentLanguages, preferOriginalAudio)
        var left = unread.size
        if (left > 0) checking(left)
        for ((listingId, container) in unread) {
            val location = if (shows == null) client.movieLocation(listingId, container) else client.episodeLocation(listingId, container)
            // A Background read that started before this page's turn ends within its bound: never wait past it (#505).
            withTimeoutOrNull(headerReader.bound + PROVIDER_RELEASE_GRACE) { reads.lock() } ?: break
            val read = try {
                connection.whileFree {
                    val contents = headerReader.read(location, container)
                    // Kept before the connection is let go, so a plan made as Play finishes this read names what it Confirmed.
                    store.saveFileRead(listingId, contents?.toJson(), Instant.now(), shared = false)
                    Read(contents)
                }
            } catch (e: IOException) {
                // The Provider refused: no more reads for now, nor Background reading today.
                if (e is HeaderReadRefused) store.saveReadingDay(ReadingDay.of(store.readingDay(), LocalDate.now()).copy(refused = true).kept())
                null
            } finally {
                reads.unlock()
            } ?: break
            if (read.contents != null) changed()
            checking(--left)
        }
        shareFileReads(provider)
    }

    /**
     * Whether a details page of [id] has nothing left to Header read, so what it shows of its
     * Versions is what their files hold, as far as they can be read (#546): every Version
     * [readHeaders] would read is read, and a Show's show details are kept. Only from what the device
     * keeps; nothing is asked of the Provider or the cloud.
     */
    suspend fun versionsChecked(id: TitleId, contentLanguages: List<String>, preferOriginalAudio: Boolean = true): Boolean = withContext(Dispatchers.IO) {
        val title = store.title(id) ?: return@withContext true
        val shows = keptShows(title)
        // A Show whose Episodes aren't read yet has Versions nothing has looked at.
        if (shows != null && shows.size < title.listings.size) return@withContext false
        toRead(title, shows, contentLanguages, preferOriginalAudio).isEmpty()
    }

    /** A Show's show Listings with the Episodes their kept show details list, or null for a Movie. */
    private fun keptShows(title: StoredTitle): List<Pair<Candidate, List<ProviderEpisode>>>? {
        if (title.id.kind != TitleKind.Show) return null
        val kept = store.showDetails(title.listings.map { it.id })
        return title.listings.filter { it.id in kept }.map { candidates(it) to kept.getValue(it.id).episodes }
    }

    /** The Listings [readHeaders] reads, in its order, with their file containers: only those a Header read can read. */
    private fun toRead(title: StoredTitle, shows: List<Pair<Candidate, List<ProviderEpisode>>>?, contentLanguages: List<String>, preferOriginalAudio: Boolean): List<Pair<Long, String>> =
        (if (shows == null) unread(title, contentLanguages, preferOriginalAudio) else unreadEpisodes(title, shows, contentLanguages, preferOriginalAudio))
            .map { listingId -> listingId to ((if (shows == null) store.container(listingId) else store.episodeContainer(listingId)) ?: "mkv") }
            .filter { (_, container) -> container in HEADER_READABLE }

    /**
     * Keeps the Provider's movie details (their bitrate among them) of the movie [listings] whose
     * details aren't kept from when the Provider last changed them. The page's own Listing was read
     * as the page opened ([title]); this reads its other Versions'.
     */
    private suspend fun readMovieDetails(client: XtreamClient, listings: List<StoredListing>, changed: suspend () -> Unit) {
        val kept = store.movieDetails(listings.map { it.id })
        val unread = listings.filter { listing -> kept[listing.id].let { it == null || it.added != listing.added } }
        if (unread.isEmpty()) return
        val gate = Semaphore(SHOW_DETAILS_AT_ONCE)
        val read = coroutineScope {
            unread.map { listing ->
                async {
                    gate.withPermit {
                        try {
                            listing to client.movieDetails(listing.id)
                        } catch (e: ProviderException) {
                            null
                        }
                    }
                }
            }.awaitAll()
        }.filterNotNull()
        val now = Instant.now()
        read.forEach { (listing, details) -> store.saveMovieDetails(listing.id, details, listing.added, now) }
        if (read.isNotEmpty()) detailsGeneration.incrementAndGet()
        // Whatever they say: a page that opened before a slow Provider answered shows its details now.
        if (read.isNotEmpty()) changed()
    }

    private class Read(val contents: FileContents?)

    /** The Titles whose cards show right now, in the order Background reading should read them. */
    fun onScreen(ids: List<TitleId>) {
        cardsOnScreen = ids
    }

    /** The attributes of the Version [id]'s card shows, with what reads Confirmed; null when the catalog no longer has it. */
    fun cardBest(id: TitleId, contentLanguages: List<String>): VersionAttributes? =
        store.title(id)?.let { preferred(candidates(it, contentLanguages), contentLanguages).firstOrNull()?.attributes }

    /**
     * Background reading at [pace], until cancelled: a Header read of the Version a Movie's card
     * shows, one at a time and only while the connection is free, so never during Playback nor while
     * a details page reads. Cards on screen come first ([onScreen]), then Continue watching, then the
     * newest Movies in the Viewer's [contentLanguages]; on each, a Version whose Prefix no table knows
     * comes before the one its card shows. A dead file is kept as unreadable and passed
     * over; a Provider answering with another error stops it until the next day, and one not
     * answering pauses it for a quarter of an hour. Each read that Confirms a card's Version is
     * announced in [confirmations]. With [preferOriginalAudio], a Title released outside the Content
     * languages is read in its details page's order ([readOrder]) until a Version is Confirmed to
     * carry Original audio, so it is ready before the Viewer opens it.
     */
    suspend fun readInBackground(contentLanguages: List<String>, pace: BackgroundPace, preferOriginalAudio: Boolean = true) =
        readInBackground(contentLanguages, pace, Clock.systemDefaultZone(), OFFLINE_PAUSE, preferOriginalAudio)

    internal suspend fun readInBackground(contentLanguages: List<String>, pace: BackgroundPace, clock: Clock, offlinePause: Duration, preferOriginalAudio: Boolean = true) = withContext(Dispatchers.IO) {
        var pausedUntil = Instant.MIN
        while (true) {
            delay(pace.gap)
            val day = ReadingDay.of(store.readingDay(), LocalDate.now(clock))
            if (day.refused || day.reads >= pace.perDay || clock.instant().isBefore(pausedUntil) || detailsReading.get() > 0) continue
            val login = credentials.load() ?: continue
            val next = nextBackgroundRead(contentLanguages, preferOriginalAudio) ?: continue
            if (!reads.tryLock()) continue
            // A details page that opened while this one was chosen reads first.
            if (detailsReading.get() > 0) {
                reads.unlock()
                continue
            }
            val read = try {
                connection.whileFree {
                    val contents = headerReader.read(XtreamClient(login, http).movieLocation(next.listingId, next.container), next.container)
                    store.saveFileRead(next.listingId, contents?.toJson(), Instant.now(), shared = false)
                    Read(contents)
                }
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
            store.saveReadingDay(day.copy(reads = day.reads + 1).kept())
            if (read.contents != null) _confirmations.emit(next.id)
        }
    }

    private class BackgroundRead(val id: TitleId, val listingId: Long, val container: String)

    /**
     * The first Movie, in Background reading's order, with a Version whose Prefix no table knows not
     * yet read (one per such Prefix, [unknownPrefixFirst]) or whose card shows a Version not yet read,
     * the unknown-Prefix one first; on a Title Prefer original audio acts on, the first Version of its
     * details page's order not yet read, as long as none is Confirmed to carry Original audio.
     */
    private fun nextBackgroundRead(contentLanguages: List<String>, preferOriginalAudio: Boolean): BackgroundRead? {
        val newest = store.titles(TitleKind.Movie, 0, NEW_MOVIES).filter { inContentLanguages(candidates(it), contentLanguages, null) }.map { it.id }
        val order = cardsOnScreen.asSequence() + store.inProgress(profile, ROW_SIZE).asSequence().map { it.id } + newest.asSequence()
        return order.filter { it.kind == TitleKind.Movie }.distinct().firstNotNullOfOrNull { id ->
            val title = store.title(id) ?: return@firstNotNullOfOrNull null
            val candidates = candidates(title, contentLanguages)
            val original = originalAudioLanguage(originalLanguage(id), contentLanguages, preferOriginalAudio)
            val next = if (original == null) {
                val read = store.fileReads(title.listings.map { it.id }).keys
                val shown = preferred(candidates, contentLanguages).firstOrNull()?.listing ?: return@firstNotNullOfOrNull null
                unknownPrefixFirst(candidates, read).firstOrNull()?.listing ?: shown.takeUnless { it.id in read }
            } else {
                if (candidates.any { it.attributes.hasConfirmedOriginalAudio(original) }) null
                else readOrder(candidates, store.fileReads(title.listings.map { it.id }).keys, contentLanguages, original).firstOrNull()?.listing
            } ?: return@firstNotNullOfOrNull null
            val container = store.container(next.id) ?: "mkv"
            if (container !in HEADER_READABLE) null else BackgroundRead(id, next.id, container)
        }
    }

    /**
     * The Listings of the best Version not yet read in each of [title]'s languages, the Viewer's
     * [contentLanguages] first, or, with [preferOriginalAudio] on a Title released outside them,
     * in order of how likely each is to carry Original audio ([readOrder]).
     */
    private fun unread(title: StoredTitle, contentLanguages: List<String>, preferOriginalAudio: Boolean): List<Long> {
        val read = store.fileReads(title.listings.map { it.id }).keys
        val original = originalAudioLanguage(originalLanguage(title.id), contentLanguages, preferOriginalAudio)
        return readOrder(candidates(title, contentLanguages), read, contentLanguages, original).map { it.listing.id }
    }

    /**
     * The Listings of the best Version not yet read in each language of each of [shows]' Episodes,
     * in the Show's order: the Viewer's [contentLanguages] (and, with [preferOriginalAudio], the
     * Show's original language) first, and among them first those whose first audio Track, as the
     * Provider's details name it, disagrees with the Prefix. Before all of them, the Episodes of a
     * show Listing whose Prefix no table knows.
     */
    private fun unreadEpisodes(title: StoredTitle, shows: List<Pair<Candidate, List<ProviderEpisode>>>, contentLanguages: List<String>, preferOriginalAudio: Boolean): List<Long> {
        val reads = store.fileReads(shows.flatMap { (_, episodes) -> episodes.map { it.id } })
        val disagreeing = shows.flatMap { (show, episodes) -> episodes.filter { firstTrackDisagrees(show.attributes, it) }.map { it.id } }.toSet()
        val unknownPrefix = shows.filter { (show, _) -> show.unknownPrefix != null }.flatMap { (_, episodes) -> episodes.map { it.id } }.toSet()
        val original = originalAudioLanguage(originalLanguage(title.id), contentLanguages, preferOriginalAudio)
        val unread = mergeEpisodes(shows, contentLanguages, reads, original, expected(title, contentLanguages)).flatMap { it.episodes }.flatMap { episode ->
            episode.languages.mapNotNull { firstUnread(it, reads.keys)?.let { version -> version to true } } +
                episode.otherLanguages.mapNotNull { firstUnread(it, reads.keys)?.let { version -> version to false } }
        }
        // A stable sort, so the Show's order stands within each.
        return unread.sortedWith(
            compareBy({ (version, _) -> version.listingId !in unknownPrefix }, { (_, wanted) -> !wanted }, { (version, _) -> version.listingId !in disagreeing }),
        ).map { it.first.listingId }
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

    /**
     * Shares the file reads the cloud doesn't have yet, each with its Listing's Prefix and matched
     * Category hints (ADR 0014), on every device, Unlocked or not; those the cloud doesn't take are
     * shared next time.
     */
    private fun shareFileReads(provider: String) {
        if (cloud == null || Instant.now().isBefore(cloudRetryAt)) return
        try {
            val table = prefixTable
            store.unsharedFileReads().entries.chunked(CloudClient.MAX_BATCH).forEach { batch ->
                val sources = store.fileReadSources(batch.map { it.key })
                val files = batch.map { (id, file) ->
                    val reported = sources[id]?.let { (name, category) -> table.reported(name, category) }
                    CloudClient.SharedFileRead(id, file, reported?.prefix, reported?.hints.orEmpty())
                }
                cloud.shareFileReads(provider, files)
                store.markFileReadsShared(files.map { it.listingId })
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

    /**
     * What a Title shows: its [best] Version, the Listing [naming] it, that name and year, the Listing
     * [telling] of it (whose details give its plot) and the [tellers] after it should its details say
     * nothing, and its [posters] in the order a card tries them, the first being its [image].
     */
    private class Shown(val best: Candidate, val naming: Candidate, val tellers: List<Candidate>, val name: String, val year: Int?, val posters: List<String>) {
        val telling get() = tellers.first()
        val image get() = posters.firstOrNull()
    }

    /**
     * What a Title's card and header show, from its Listings (ADR 0014): the cleaned name of its
     * Listing in the [appLanguage], else of the English one, else of the best Version's, a Listing's
     * language being the one it is written in, not its audio's ([Candidate.listedLanguage]: an `ES`
     * copy whose audio a read found English is still named in Spanish, #504, and so is every `GR`
     * copy once the Provider's reads teach that `GR` files carry English audio); the year in that name, else a Show's release date;
     * the Listing telling of it: the one in the App language, else the English one, else the one in
     * the Title's [originalLanguage] (Wikidata's, from its Title facts), else a Content language's (in
     * the Viewer's order), else French's, else the naming one, so the plot is one the Viewer reads;
     * and its [posters], in the same order.
     * Worked out from the stored Listings each time, so a Listing added in another language changes
     * nothing.
     */
    private fun shown(candidates: List<Candidate>, contentLanguages: List<String>, appLanguage: String, originalLanguage: String?): Shown {
        val ranked = preferred(candidates, contentLanguages)
        val language = primaryLanguage(appLanguage)
        fun inLanguage(wanted: String?) = wanted?.let { ranked.firstOrNull { it.listedLanguage == wanted } }
        val naming = inLanguage(language) ?: inLanguage("en") ?: ranked.first()
        val first = listOf(language, "en").distinct()
        val then = (listOfNotNull(originalLanguage) + contentLanguages.map(::primaryLanguage) + "fr").distinct() - first
        val tellers = ((first + then).flatMap { wanted -> ranked.filter { it.listedLanguage == wanted } } + naming + ranked).distinct()
        val cleaned = prefixTable.cleanName(naming.listing.name)
        return Shown(ranked.first(), naming, tellers, cleaned.name, cleaned.year ?: naming.listing.details?.year, posters(ranked, first, then))
    }

    /**
     * A Title's posters in the order its card tries them (#526), in the order its plot is read: the
     * Listings' in the App language, then English's, both in [first] (so Verity's English Viewer never
     * gets its Greek one), the best Version's first within one; then a streaming service's Listing's
     * (a Source, no language: `NF`, `D+`, `AMZ`), which links the English one-sheet where no Listing is
     * English; only then the Listings' in each of the [then] languages in turn (the Title's original
     * language, the Content languages in their order, then French, the other App language); then the file most of [ranked]'s Listings
     * share, a file counting once per language so a copy the Provider lists twice doesn't outvote the
     * others, ties to the best Version's; then the rest. A poster whose URL names a width under
     * [MIN_POSTER_WIDTH] ([posterTooSmall]) is left out; one that turns out that narrow as it decodes
     * is the app's to pass over for the next.
     */
    private fun posters(ranked: List<Candidate>, first: List<String>, then: List<String>): List<String> {
        val pictured = ranked.filter { it.listing.image != null && !posterTooSmall(it.listing.image) }
        fun inLanguages(languages: List<String>) = languages.flatMap { wanted -> pictured.filter { it.listedLanguage == wanted } }
        val fromSources = pictured.filter { it.listedLanguage == null && it.attributes.source != null }
        val votes = pictured.groupBy({ it.listing.image!! }, { it.listedLanguage }).mapValues { (_, listed) -> listed.distinct().size }
        val byVotes = pictured.sortedByDescending { votes.getValue(it.listing.image!!) }
        return (inLanguages(first) + fromSources + inLanguages(then) + byVotes).map { it.listing.image!! }.distinct()
    }

    /**
     * The [titles] the Viewer can watch: those with a Version in their [contentLanguages], or an
     * Untagged one Likely in one because Wikidata says the Title was released in it ([originalLanguages]).
     */
    private fun watchable(titles: List<StoredTitle>, contentLanguages: List<String>): List<StoredTitle> {
        val originalLanguages = originalLanguages(titles.map { it.id })
        return titles.filter { inContentLanguages(candidates(it), contentLanguages, originalLanguages[it.id]) }
    }

    /**
     * Trending's Titles (CONTEXT.md, #477): the Provider's Titles Wikipedia's readers looked up most
     * over the week, most viewed first, movies and shows mixed. Each wiki's article names in the feed
     * ([trendingViews]: English's, and the App language's) are matched to the Titles' article names
     * in their Wikidata facts; a Title's views are its article's summed over the week, and a Title in
     * both wikis counts once, with its larger sum. Not kept to the Content languages: the rows do that.
     */
    private fun trendingTitles(appLanguage: String, now: Instant): List<StoredTitle> {
        val sums = HashMap<TitleId, Long>()
        trendingViews(appLanguage, now).forEach { (wiki, articles) ->
            store.titlesByArticle(wiki, articles.keys).forEach { (article, ids) ->
                val views = articles.getValue(article)
                ids.forEach { sums.merge(it, views, ::maxOf) }
            }
        }
        val ranked = sums.entries.sortedWith(compareByDescending<Map.Entry<TitleId, Long>> { it.value }.thenBy { it.key.toString() }).map { it.key }
        val titles = store.titles(ranked)
        return ranked.mapNotNull(titles::get)
    }

    /**
     * Trending's feed for [appLanguage], each wiki's article names with their views summed over the
     * week: the copy kept on the device while it is under [TRENDING_FRESH_FOR] old, across runs, else
     * the cloud's, kept for next time. When the cloud can't answer, the kept copy stands however old;
     * with none, there is no Trending. One ask at a time, so Home's rows and its hero, read together,
     * ask once.
     */
    private fun trendingViews(appLanguage: String, now: Instant): Map<String, Map<String, Long>> = synchronized(trendingAsks) {
        val language = primaryLanguage(appLanguage)
        val kept = unsavedTrending[language] ?: store.trending(language)
        if (kept != null && kept.fetchedAt.isAfter(now - TRENDING_FRESH_FOR)) return kept.views
        if (cloud != null && now.isAfter(cloudRetryAt)) {
            try {
                val views = cloud.trending(language)
                val asked = StoredTrending(views, now)
                unsavedTrending[language] = asked
                // Saved without waiting on a Sync.
                saveLater(after = { unsavedTrending.remove(language, asked) }) { store.saveTrending(language, views, now) }
                return views
            } catch (e: CloudException) {
                cloudRetryAt = now + CLOUD_RETRY_AFTER
            }
        }
        kept?.views.orEmpty()
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

    /**
     * [title]'s Versions, a Movie's with what Header reads, Playback and the Provider's movie details
     * Confirmed; a read moves a Version to a language only when it is expected there ([expected], for
     * the Viewer's [contentLanguages]).
     */
    private fun candidates(title: StoredTitle, contentLanguages: List<String> = emptyList()): List<Candidate> = candidates(listOf(title), contentLanguages).getValue(title.id)

    /** Each of [titles]' Versions, as [candidates] gives them, read with a few queries for all of them. */
    private fun candidates(titles: Collection<StoredTitle>, contentLanguages: List<String> = emptyList()): Map<TitleId, List<Candidate>> {
        val ids = titles.filter { it.id.kind == TitleKind.Movie }.flatMap { title -> title.listings.map { it.id } }
        val reads = ids.chunked(LISTINGS_PER_QUERY).flatMap { store.fileReads(it).entries }.associate { it.key to it.value }
        val bitrates = ids.chunked(LISTINGS_PER_QUERY).flatMap { store.movieBitrates(it).entries }.associate { it.key to it.value }
        return titles.associate { title ->
            val plain = title.listings.map(::candidates)
            val expect = expected(title, contentLanguages)
            title.id to if (title.id.kind != TitleKind.Movie) plain else plain.map { candidate ->
                val id = candidate.listing.id
                val withBitrate = bitrates[id]?.let { candidate.attributes.copy(bitrate = Known(it, Provenance.Confirmed)) } ?: candidate.attributes
                Candidate(candidate.listing, reads[id]?.let { withBitrate.confirmedBy(it, expect) } ?: withBitrate, candidate.unknownPrefix, candidate.listedLanguage)
            }
        }
    }

    /**
     * Whether a read may move one of [title]'s Versions to a language (a primary subtag), for
     * [confirmedBy]: the Title's original language, one of the Viewer's [contentLanguages], or one
     * the Prefix table knows. The original language is looked up only when a read names a language
     * the table doesn't know, which is rare.
     */
    private fun expected(title: StoredTitle, contentLanguages: List<String>): (String) -> Boolean {
        val wanted = contentLanguages.map(::primaryLanguage).toSet()
        return { language -> language in wanted || prefixTable.knowsLanguage(language) || namedOriginalLanguage(originalLanguage(title.id)) == language }
    }

    /**
     * [listing] as the Prefix table describes it, listed in the language the table's own lines name
     * for it ([PrefixTable.Description.listedLanguage]) unless its name or its plot is written in
     * another script ([Scripts.written]): an `EN` Listing named or told of in Greek is a Greek one.
     */
    private fun candidates(listing: StoredListing): Candidate {
        val described = prefixTable.describe(listing.name, listing.category)
        val listed = Scripts.written(
            described.listedLanguage,
            // The name again in another script after the year is not the name (`cleanName` drops it).
            { listing.name.takeIf { name -> name.any { it >= Scripts.FIRST_OTHER } }?.let { prefixTable.cleanName(it).name } },
            { listing.details?.plot },
        )
        return Candidate(listing, described.attributes, described.unknownPrefix, listed)
    }

    override fun close() {
        accountSync.close()
        gamesFetches.shutdownNow()
        signOuts.shutdownNow()
        fileReadLookUps.shutdownNow()
        movieDetailsReads.shutdownNow()
        guideFetches.shutdownNow()
        // The answers waiting are saved first, unless a Sync keeps them waiting.
        saves.shutdown()
        saves.awaitTermination(SAVES_ON_CLOSE_SECONDS, TimeUnit.SECONDS)
        store.close()
    }

    companion object {
        /** The most Profiles a household keeps (#428). */
        const val MAX_PROFILES = 5

        /** A Favourite's or a "Not this game" mark's value, as the Account sync sends it. */
        private const val ON = "1"
        private const val PULSE_PENDING = "pending"
        private const val PULSE_DONE = "done"

        private val CLOUD_RETRY_AFTER: Duration = Duration.ofMinutes(1)
        /** Background reading waits this long after the Provider didn't answer: the Wi-Fi may be down. */
        private val OFFLINE_PAUSE: Duration = Duration.ofMinutes(15)
        /** Batches failing this many times in a row mean the cloud is down: the rest wait for the next Sync. */
        private const val CLOUD_FAILURES_IN_A_ROW = 3

        /** The kinds of Track the Viewer's last picks are kept by, and what turning subtitles off is kept as. */
        private const val AUDIO = "audio"
        private const val SUBTITLES = "subtitles"
        private const val SUBTITLES_OFF = "off"
        private const val CLOSED_CAPTIONS = "closed-captions"
        private val SHOW_DETAILS_FRESH_FOR: Duration = Duration.ofDays(1)
        /** How many show details are asked of the Provider at once: GoT alone has 21 Versions. */
        /** Provider details calls in flight at once, a page's Episodes or its Versions' movie details. */
        private const val SHOW_DETAILS_AT_ONCE = 4
        private const val MIN_RESUME_POSITION = 60_000L
        /** How many cards a home row holds. */
        private const val ROW_SIZE = 20
        /** The newest Titles the home rows are picked from. */
        private const val HOME_MOVIES = 150
        private const val HOME_SHOWS = 50
        /** Of those, the ones New on your service is picked from. */
        private const val NEW_MOVIES = 40
        private const val NEW_SHOWS = 15
        /** The newest Titles of its kind a tab's New row is picked from. */
        private const val TAB_NEW = 40
        private const val GENRE_ROWS = 8
        /** The most genre rows a Movies or Shows tab shows. */
        private const val TAB_GENRE_ROWS = 20
        /** A genre with fewer Titles than this, once those shown higher up are skipped, gets no row: a row of two looks broken. */
        private const val MIN_GENRE_SIZE = 5
        /** Listings read per query: SQLite takes at most 999 bound parameters in older builds. */
        private const val LISTINGS_PER_QUERY = 900

        /** A Trending row with fewer Titles than this, once kept to the Viewer's, looks broken and isn't shown. */
        private const val MIN_TRENDING_SIZE = 3

        /** The most Listings a Movie's page reads the details of, in the order they tell of it, looking for a plot the Viewer reads. */
        private const val TELLERS_READ = 3

        /** How many of the newest Titles of each kind the hero picks its Title from ([featured]). */
        private const val FEATURED_FROM = 5

        /**
         * The narrowest a poster may be, in pixels, before a card refuses it for the Title's next one
         * (#526): a 154 × 231 file blurs at card size.
         */
        const val MIN_POSTER_WIDTH = 300

        private val TMDB_WIDTH = Regex("/t/p/w(\\d+)[/_]")

        /**
         * Whether the poster at [url] is narrower than [MIN_POSTER_WIDTH] by its URL alone: a TMDB
         * path names the width it was cut to (`/t/p/w154/`, `/t/p/w220_and_h330_face/`). The Provider's catalog gives no sizes, so
         * any other URL passes here, and the app judges it as it decodes.
         */
        fun posterTooSmall(url: String): Boolean =
            TMDB_WIDTH.find(url)?.groupValues?.get(1)?.toIntOrNull()?.let { it < MIN_POSTER_WIDTH } ?: false

        /** The genre every Kids Category names, so no row on a Kids profile (#478). */
        private val KIDS_GENRE = setOf("Family")
        /** How many Titles of a genre are read at a time while filling its row, and the most pages read. */
        private const val GENRE_PAGE = 60
        private const val GENRE_PAGES = 5

        /** A League row shows the week ahead: the cloud answers today and the next seven days. */
        private val WEEK_AHEAD: Duration = Duration.ofDays(7)
        /** The Event channels read for matching: a day behind now (a live Game named for yesterday evening, in a zone ahead) and past the week ahead. */
        private val EVENT_WINDOW_BEHIND: Duration = Duration.ofDays(1)

        /** How far from now an unmatched Event channel's start may be for More live events (docs/roadmap.md, M3): an evening's events, not a wall of stale or far-off ones. */
        internal val MORE_LIVE_EVENTS_WITHIN: Duration = Duration.ofHours(6)
        private val EVENT_WINDOW_AHEAD: Duration = Duration.ofDays(9)
        /** A "Not this game" mark is forgotten this long after it was made: its Game is long over, and the rows ask for a week ahead at most. */
        private val DROPPED_KEPT_FOR: Duration = Duration.ofDays(14)
        /** The cloud reads Wikipedia's lists once a day; the device asks Trending's feed again after this, at most once an hour. */
        private val TRENDING_FRESH_FOR: Duration = Duration.ofHours(1)
        /** The cloud reads a League's current day every 5 minutes; a screen asking every couple of minutes gets a new answer each time. */
        private val GAMES_FRESH_FOR: Duration = Duration.ofMinutes(1)
        /** How long a Movie's details wait for the cloud's file reads of its Versions: the page-arrival time (DESIGN.md, Motion). */
        private val SHARED_READS_WAIT: Duration = Duration.ofMillis(300)
        /** How long a Movie's page waits for the Provider's details: several times what the Provider takes when well, far under the HTTP client's minute. */
        private val PROVIDER_DETAILS_WAIT: Duration = Duration.ofSeconds(3)
        /** How long a Games request may take in all: Home's row must not hang on a slow network, and the kept copy stands meanwhile. */
        private val GAMES_CALL_TIMEOUT: Duration = Duration.ofSeconds(3)
        /** How long a Sync waits for the Provider table before going on with the one kept. */
        private val PROVIDER_TABLE_CALL_TIMEOUT: Duration = Duration.ofSeconds(10)
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

        /**
         * Opens the catalog at [databasePath], creating it if needed. What the cloud adds (Title facts,
         * Trending, Games, the Account) comes from [cloudAddress]; without one, the Provider's catalog stands alone.
         */
        fun open(databasePath: String, credentials: CredentialStore, cloudAddress: String? = null, http: OkHttpClient = defaultHttp()): Library =
            open(databasePath, credentials, cloudAddress, PrefixTable::bundledProviderTable, http)

        /** [open], with each Provider's Provider table, by Provider key, from [providerTables] instead of the ones shipped with the app. */
        internal fun open(
            databasePath: String,
            credentials: CredentialStore,
            cloudAddress: String?,
            providerTables: (providerKey: String) -> String?,
            http: OkHttpClient = defaultHttp(),
        ): Library {
            val store = CatalogStore(databasePath)
            return Library(store, credentials, http, providerTables, cloudAddress?.let { CloudClient(it, http, store) })
        }

        private fun defaultHttp() = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}
