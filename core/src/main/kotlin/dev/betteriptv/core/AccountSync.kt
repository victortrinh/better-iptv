package dev.betteriptv.core

import com.squareup.moshi.Moshi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** One Profile signing in could keep, when the household would have more than [ProfileChoice.most] (#440). */
data class ProfileToKeep(
    /** What [Library.keepProfiles] names it by. */
    val key: String,
    /** Null for the Profile that holds what the device kept before there were Profiles, until renamed. */
    val name: String?,
    val kids: Boolean,
    /** Whether this TV has it (merged into the Account's of its name, when [inAccount] too). */
    val onThisTv: Boolean,
    val inAccount: Boolean,
    /** The Profile the TV is on, which is always kept. */
    val current: Boolean,
)

/**
 * The household's Profiles signing in would bring together, the Account's and this TV's, more than
 * [most]: the TV asks which to keep before sign-in completes (#440).
 */
data class ProfileChoice(val profiles: List<ProfileToKeep>, val most: Int)

/** What landing the Account's changes changed, for the screens to read again. */
internal class Landed(val profiles: Boolean, val history: Boolean, val settings: Boolean, val household: Boolean) {
    val any: Boolean get() = profiles || history || settings || household
}

/**
 * Everything a Profile owns, and the Profiles themselves, kept alike on a household's TVs (#435,
 * #440): while signed in, every Profile's history, Favourites, "Not this game" marks, settings and
 * player choices sync with the Account, as do the household's Region and Household PIN.
 *
 * Each change the Viewer makes is queued ([recordHistory], [record]) and the queue is pushed after
 * each stop or change ([pushSoon]); a push that fails stays queued for the next. Each push pulls the
 * Account's changes since the device's cursor, which wait until they land between screens ([land]),
 * never under focus; a Title's page lands its own history at once ([landTitle]). Per thing the latest
 * change wins; "Remove from Continue watching", taking a Favourite off and deleting a Profile are
 * changes too.
 *
 * The first sync after signing in merges the device into the Account ([link]): each of its Profiles
 * takes the Account's Profile of its name (a Kids one merging into one that isn't stays Kids), the rest
 * are added, all of the device's data is queued, and the cursor starts from the beginning. When that
 * would make more than [maxProfiles], [choice] says so and nothing merges until [keep] says which to
 * keep. Signing out ([signedOut]) takes the Account's data off the device. A device that never signs
 * in never syncs, and keeps its Profiles as before.
 */
internal class AccountSync(
    private val store: CatalogStore,
    private val cloud: CloudClient?,
    private val pin: KeptHouseholdPin,
    private val maxProfiles: Int,
    /** Whether the device is signed in to an Account, as it last knew. */
    private val signedIn: () -> Boolean,
    /** The Profile the device is on. */
    private val currentProfile: () -> Long,
    /** The [providerKey] of the Provider logged in to, or null. */
    private val provider: () -> String?,
    /** Called when the cloud says the device is in no Account: it was signed out from another TV. */
    private val onSignedOut: () -> Unit,
) {
    private val lock = Any()
    private val pushes: ExecutorService = Executors.newSingleThreadExecutor { Thread(it, "library-account-sync").apply { isDaemon = true } }
    private val pushQueued = AtomicBoolean()
    private val _choice = MutableStateFlow<ProfileChoice?>(null)

    /** Set while signing in waits for the Viewer to say which Profiles to keep. */
    val choice: StateFlow<ProfileChoice?> = _choice.asStateFlow()

    /** Set by [link], which renamed, made Kids or deleted Profiles: the screens read them again at the next [land]. */
    @Volatile private var merged = false

    /** The keys of the Profiles the Viewer chose not to keep, for the next [link]. */
    @Volatile private var dropping: Set<String> = emptySet()

    /**
     * Whether the device's changes are queued: once it is merged into the Account, and from when the
     * merge starts, so a stop made while it runs isn't lost (queued twice is harmless: a key's latest wins).
     */
    private val linked: Boolean get() = signedIn() && store.syncState() != null

    /** Profile [id]'s Account id. */
    fun uidOf(id: Long): String? = store.profiles().find { it.id == id }?.uid

    /** Queues [change] to Profile [profile]'s pushes, once the device is merged into the Account. */
    fun recordHistory(profile: Long, change: HistoryChange) {
        if (linked) store.queueHistory(profile, change)
    }

    /**
     * Queues [entry] for the Account, once the device is merged into it. [perProvider] ones (a
     * Favourite, a mark, a Listing's choices) are kept with the Provider logged in to, and wait
     * without one.
     */
    fun record(entry: SyncEntry, perProvider: Boolean = false) {
        if (!linked) return
        val provider = if (perProvider) provider() ?: return else ""
        store.queueEntry(entry, provider)
    }

    /** [record]s Profile [profile]'s change of [kind] and [key]. */
    fun record(profile: Long, kind: String, key: String, value: String?, at: Instant, perProvider: Boolean = false) {
        if (!linked) return
        record(SyncEntry(kind, uidOf(profile) ?: return, key, value, at), perProvider)
    }

    /** Queues Profile [profile] as it is now (or its deletion, [deleted]), as it is added, renamed or deleted. */
    fun recordProfile(uid: String, name: String?, kids: Boolean, deleted: Boolean, at: Instant) {
        if (!linked) return
        if (deleted) store.unqueueProfile(uid)
        record(SyncEntry(SyncEntry.PROFILE, uid, "", if (deleted) null else profileValue(name, kids), at))
    }

    /** Syncs in the background soon, once however often it is asked meanwhile: after a stop, a change, or signing in. */
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
     * cloud answered and the device is merged into the Account. Nothing without a cloud or before
     * signing in; while the cloud can't be reached the queue waits for the next.
     */
    fun sync(): Boolean = synchronized(lock) {
        val cloud = cloud ?: return false
        if (!signedIn()) return false
        val provider = provider()
        try {
            var state = store.syncState()?.takeIf { it.linked } ?: run {
                if (!link(cloud, provider)) return false
                store.syncState() ?: return false
            }
            // On another Provider, the Provider's own data is another: everything is pulled again.
            if (state.provider != provider) state = state.copy(cursor = 0, provider = provider).also(store::saveSyncState)
            store.forgetTombstones(Instant.now() - TOMBSTONES_KEPT_FOR)
            do {
                val profiles = store.profiles()
                val progress = ArrayList<Pair<StoredProfile, HistoryChange>>()
                for (profile in profiles) {
                    val room = CloudClient.MAX_HISTORY_BATCH - progress.size
                    if (room == 0) break
                    // A Listing's progress names its Provider: it waits for a login.
                    store.queuedHistory(profile.id, room).filter { it.id is TitleId.Numbered || provider != null }.forEach { progress += profile to it }
                }
                val entries = store.queuedEntries(provider, CloudClient.MAX_HISTORY_BATCH)
                val answer = cloud.sync(state.cursor, provider, uidOf(currentProfile()), progress.map { (profile, change) -> profile.uid to change }, entries)
                // Signed out meanwhile (another TV, or the answer's own Zugig-Account): nothing of the Account is kept.
                if (!signedIn()) return false
                stage(answer)
                progress.groupBy({ it.first.id }, { it.second }).forEach { (profile, pushed) -> store.unqueueHistory(profile, pushed) }
                store.unqueueEntries(entries)
                state = state.copy(cursor = answer.cursor)
                store.saveSyncState(state)
            } while (progress.size == CloudClient.MAX_HISTORY_BATCH || entries.size == CloudClient.MAX_HISTORY_BATCH)
        } catch (e: CloudClient.NotSignedIn) {
            onSignedOut()
            return false
        } catch (e: CloudException) {
            return false
        }
        true
    }

    /**
     * Merges the device into the Account, the first time it syncs after signing in, and says whether
     * it did: pulls everything the Account has, gives each local Profile the Account's of its name
     * (or keeps it as a new one), queues all of the device's data, and stages the Account's to land.
     * Past [maxProfiles] it asks ([choice]) and merges nothing until [keep] answers.
     */
    private fun link(cloud: CloudClient, provider: String?): Boolean {
        if (store.syncState() == null) store.saveSyncState(SyncState(0, provider, linked = false))
        val answer = cloud.sync(0, provider, null, emptyList(), emptyList())
        if (!signedIn()) return false
        val accountProfiles = LinkedHashMap<String, Pair<String?, Boolean>>()
        val deleted = HashSet<String>()
        for (entry in answer.entries.filter { it.kind == SyncEntry.PROFILE }) {
            val value = entry.value
            val profile = value?.let(::readProfileValue)
            if (value == null) deleted += entry.profile ?: continue else if (profile != null) accountProfiles[entry.profile ?: continue] = profile
        }
        val locals = store.profiles()
        val current = currentProfile()
        // By Account id first (a device that synced before keeps its own), then by name.
        val matched = HashMap<Long, String>()
        for (local in locals) if (local.uid in accountProfiles) matched[local.id] = local.uid
        for (local in locals) {
            if (local.id in matched) continue
            val same = accountProfiles.entries.firstOrNull { (uid, profile) -> uid !in matched.values && sameName(profile.first, local.name) } ?: continue
            matched[local.id] = same.key
        }
        val byAccountId = matched.entries.associate { (id, uid) -> uid to locals.single { it.id == id } }
        val candidates = accountProfiles.map { (uid, profile) ->
            val local = byAccountId[uid]
            ProfileToKeep(uid, local?.name ?: profile.first, profile.second || local?.kids == true, local != null, inAccount = true, current = local?.id == current)
        } + locals.filter { it.id !in matched }.map { ProfileToKeep(it.uid, it.name, it.kids, onThisTv = true, inAccount = false, current = it.id == current) }
        val dropping = dropping.filterTo(HashSet()) { key -> candidates.any { it.key == key && !it.current } }
        if (candidates.count { it.key !in dropping } > maxProfiles) {
            _choice.value = ProfileChoice(candidates, maxProfiles)
            return false
        }
        _choice.value = null
        val now = Instant.now()
        for (local in locals) {
            val uid = matched[local.id]
            if ((uid ?: local.uid) in dropping) {
                store.deleteProfile(local.id)
                store.unqueueProfile(local.uid, all = true)
                continue
            }
            if (uid == null) continue
            if (uid != local.uid) store.setProfileUid(local.id, uid)
            // A Kids one merging into one that isn't stays Kids, and one that is makes this one Kids: stamped now, so it wins.
            if (local.kids != accountProfiles.getValue(uid).second) store.setProfileKids(local.id, true, now)
        }
        queueAll(provider)
        for (uid in dropping) if (uid in accountProfiles) store.queueEntry(SyncEntry(SyncEntry.PROFILE, uid, "", null, now), "")
        stage(
            SyncAnswer(
                answer.cursor,
                answer.reset,
                answer.changes.filter { it.first !in dropping },
                answer.entries.filter { it.profile !in dropping },
            ),
        )
        store.saveSyncState(SyncState(answer.cursor, provider, linked = true))
        this.dropping = emptySet()
        merged = true
        return true
    }

    /** Queues all of the device's data, as signing in merges it into the Account. */
    private fun queueAll(provider: String?) {
        fun at(millis: Long) = Instant.ofEpochMilli(maxOf(millis, 1))
        for (profile in store.profiles()) {
            val uid = profile.uid
            store.queueEntry(SyncEntry(SyncEntry.PROFILE, uid, "", profileValue(profile.name, profile.kids), at(profile.changedAt)), "")
            store.queueAllHistory(profile.id)
            for ((key, value, changed) in store.profileSettingRows(profile.id)) store.queueEntry(SyncEntry(SyncEntry.SETTING, uid, key, value, at(changed)), "")
            // Kept with no time: the oldest change, so the Account's wins.
            for ((kind, value) in store.rememberedTracks(profile.id)) store.queueEntry(SyncEntry(SyncEntry.SETTING, uid, SyncEntry.TRACK + kind, value, at(1)), "")
            for ((key, body, changed) in store.titleChoiceRows(profile.id)) {
                val listing = "/listing/" in key
                if (listing && provider == null) continue
                store.queueEntry(SyncEntry(SyncEntry.TITLE_CHOICES, uid, key, body, at(changed)), if (listing) provider!! else "")
            }
            if (provider == null) continue
            for ((key, added) in store.favouriteRows(profile.id)) store.queueEntry(SyncEntry(SyncEntry.FAVOURITE, uid, key, ON, at(added)), provider)
            for ((game, channel, dropped) in store.notThisGameRows(profile.id)) {
                store.queueEntry(SyncEntry(SyncEntry.NOT_THIS_GAME, uid, markKey(game, channel), ON, at(dropped)), provider)
            }
        }
        for ((key, value, changed) in store.householdRows()) store.queueEntry(SyncEntry(SyncEntry.HOUSEHOLD, null, key, value, at(changed)), "")
        store.householdPin()?.let { store.queueEntry(SyncEntry(SyncEntry.HOUSEHOLD, null, SyncEntry.PIN, it.hash, it.changedAt), "") }
    }

    /** Keeps what the Account answered until it lands: history by Profile, the rest (and history of a Profile still to land) as entries. */
    private fun stage(answer: SyncAnswer) {
        val byUid = store.profiles().associate { it.uid to it.id }
        val (known, pending) = answer.changes.partition { it.first in byUid }
        var reset = answer.reset
        for ((profile, changes) in known.groupBy({ byUid.getValue(it.first) }, { it.second })) {
            store.stagePulledHistory(profile, changes, reset)
            reset = false
        }
        if (reset) store.stagePulledHistory(currentProfile(), emptyList(), reset = true)
        store.stagePulledEntries(answer.entries + pending.map { (uid, change) -> progressEntry(uid, change) })
    }

    /** Whether anything pulled waits to land. Read apart from the writer, which a Sync may hold for minutes. */
    fun hasPulled(): Boolean = merged || store.hasPulledEntries() || store.historyResetPending() || store.profiles().any { store.hasPulledHistory(it.id) }

    /** Lands the Account's changes pulled, as a held catalog lands (ADR 0011): Profiles first, then each Profile's data. */
    fun land(): Landed {
        var profiles = merged.also { merged = false }
        var history = false
        var settings = false
        var household = false
        if (store.hasPulledEntries()) {
            val pulled = store.pulledEntries()
            for (entry in pulled.filter { it.kind == SyncEntry.PROFILE }) {
                profiles = landProfile(entry) || profiles
                store.unstagePulledEntry(entry)
            }
            val byUid = store.profiles().associate { it.uid to it.id }
            for (entry in pulled.filter { it.kind == PROGRESS }) {
                val profile = byUid[entry.profile]
                val change = readProgressEntry(entry)
                if (profile != null && change != null) store.stagePulledHistory(profile, listOf(change), reset = false)
                store.unstagePulledEntry(entry)
            }
            for (entry in pulled.filter { it.kind != SyncEntry.PROFILE && it.kind != PROGRESS }) {
                when (landEntry(entry, byUid)) {
                    LandedAs.Setting -> settings = true
                    LandedAs.Household -> household = true
                    LandedAs.Other, LandedAs.Nothing -> Unit
                }
                store.unstagePulledEntry(entry)
            }
        }
        val reset = store.historyResetPending()
        for (profile in store.profiles()) {
            if (reset || store.hasPulledHistory(profile.id)) history = store.applyPulledHistory(profile.id, reset = reset) || history
        }
        if (reset) store.clearHistoryReset()
        return Landed(profiles, history, settings, household)
    }

    private fun landProfile(entry: SyncEntry): Boolean {
        val uid = entry.profile ?: return false
        val all = store.profiles()
        val local = all.find { it.uid == uid }
        val value = entry.value
        if (value == null) {
            // The household's last Profile stays: a device always has one.
            if (local == null || all.size == 1) return false
            store.deleteProfile(local.id)
            store.unqueueProfile(uid)
            return true
        }
        val (name, kids) = readProfileValue(value) ?: return false
        // Renamed or deleted here since: this TV's change is the later, and goes to the Account.
        val queued = store.queuedEntryAt(SyncEntry.PROFILE, uid, "")
        if (queued != null && !entry.at.isAfter(queued)) return false
        if (local == null) {
            store.addProfile(name, kids, entry.at, uid)
            return true
        }
        if (entry.at.toEpochMilli() <= local.changedAt) return false
        if (name != local.name) store.renameProfile(local.id, name, entry.at)
        if (kids != local.kids) store.setProfileKids(local.id, kids, entry.at)
        return name != local.name || kids != local.kids
    }

    private enum class LandedAs { Nothing, Setting, Household, Other }

    private fun landEntry(entry: SyncEntry, byUid: Map<String, Long>): LandedAs {
        val queued = store.queuedEntryAt(entry.kind, entry.profile, entry.key)
        if (queued != null && !entry.at.isAfter(queued)) return LandedAs.Nothing
        if (entry.kind == SyncEntry.HOUSEHOLD) {
            return when (entry.key) {
                SyncEntry.REGION -> if (store.saveHousehold(SyncEntry.REGION, entry.value, entry.at, onlyIfLater = true)) LandedAs.Household else LandedAs.Nothing
                SyncEntry.PIN -> if (pin.adopt(entry.value, entry.at)) LandedAs.Household else LandedAs.Nothing
                else -> LandedAs.Nothing
            }
        }
        // A Profile deleted here, or not landed: its data has nowhere to go.
        val profile = byUid[entry.profile] ?: return LandedAs.Nothing
        when (entry.kind) {
            SyncEntry.FAVOURITE -> {
                val on = entry.value != null
                if ((entry.key in store.favouriteKeys(profile)) == on) return LandedAs.Nothing
                store.setFavourite(profile, entry.key, on, entry.at)
            }
            SyncEntry.NOT_THIS_GAME -> {
                val split = entry.key.indexOf('|').takeIf { it > 0 } ?: return LandedAs.Nothing
                store.setFeedDropped(profile, entry.key.substring(0, split), entry.key.substring(split + 1), entry.value != null, entry.at)
            }
            SyncEntry.SETTING -> {
                if (entry.key.startsWith(SyncEntry.TRACK)) {
                    val kind = entry.key.removePrefix(SyncEntry.TRACK)
                    val value = entry.value
                    if (value == null) store.forgetRememberedTrack(profile, kind) else store.rememberTrack(profile, kind, value)
                    return LandedAs.Other
                }
                return if (store.saveProfileSetting(profile, entry.key, entry.value, entry.at, onlyIfLater = true)) LandedAs.Setting else LandedAs.Nothing
            }
            SyncEntry.TITLE_CHOICES -> {
                val body = entry.value ?: return LandedAs.Nothing
                if (historyTitleId(entry.key) == null || !store.saveTitleChoicesIfLater(profile, entry.key, body, entry.at)) return LandedAs.Nothing
            }
            else -> return LandedAs.Nothing
        }
        return LandedAs.Other
    }

    /** Lands [id]'s pulled history, so its page and Play continue from where another TV stopped. Says whether any did. */
    fun landTitle(profile: Long, id: TitleId): Boolean {
        if (!store.hasPulledHistory(profile, id)) return false
        return store.applyPulledHistory(profile, id)
    }

    /** Keeps only the Profiles keyed [keys] of [choice] (the current one always), and merges the device into the Account with them. */
    fun keep(keys: Set<String>): Boolean {
        val choice = _choice.value ?: return false
        dropping = choice.profiles.filter { !it.current && it.key !in keys }.mapTo(HashSet()) { it.key }
        return sync()
    }

    /**
     * Takes the Account's data off the device, as signing out does: every Profile, the Region and the
     * PIN, leaving one blank Profile; the catalog and the Provider login stay. Nothing when the device
     * was never merged into the Account, whose data then never landed. Not under [lock], which a sync
     * holds while the cloud answers it with this very sign-out: a sync still running finds the device
     * signed out before it keeps anything. Says whether it took anything off.
     */
    fun signedOut(): Boolean {
        _choice.value = null
        dropping = emptySet()
        if (store.syncState()?.linked != true) {
            store.forgetSyncState()
            return false
        }
        store.forgetAccountData(Instant.now())
        pin.forget()
        return true
    }

    fun close() {
        pushes.shutdown()
        pushes.awaitTermination(CLOSE_WAIT_SECONDS, TimeUnit.SECONDS)
    }

    private companion object {
        /** The cloud keeps a removal as long (cloud/src/sync.ts). */
        val TOMBSTONES_KEPT_FOR: Duration = Duration.ofDays(30)
        const val CLOSE_WAIT_SECONDS = 5L
        /** A Favourite's or a mark's value. */
        const val ON = "1"
        /** Pulled history of a Profile not landed yet waits as an entry of this kind. */
        const val PROGRESS = "progress"
        private val json = Moshi.Builder().build().adapter(Any::class.java)

        fun markKey(gameKey: String, channelKey: String) = "$gameKey|$channelKey"

        fun sameName(a: String?, b: String?) = a?.trim()?.lowercase() == b?.trim()?.lowercase()

        fun profileValue(name: String?, kids: Boolean): String = json.serializeNulls().toJson(mapOf("name" to name, "kids" to kids))

        fun readProfileValue(value: String): Pair<String?, Boolean>? {
            val read = runCatching { json.fromJson(value) as? Map<*, *> }.getOrNull() ?: return null
            val kids = read["kids"] as? Boolean ?: return null
            return (read["name"] as? String)?.takeIf { it.isNotBlank() } to kids
        }

        fun progressEntry(uid: String, change: HistoryChange) = SyncEntry(
            PROGRESS,
            uid,
            "${historyKey(change.id)}|${change.episode?.season ?: -1}|${change.episode?.number ?: -1}",
            "${change.position}|${change.duration ?: ""}|${change.watched}|${change.removed}",
            change.at,
        )

        fun readProgressEntry(entry: SyncEntry): HistoryChange? {
            val key = entry.key.split('|')
            val value = entry.value?.split('|') ?: return null
            if (key.size != 3 || value.size != 4) return null
            val season = key[1].toIntOrNull() ?: return null
            val number = key[2].toIntOrNull() ?: return null
            return HistoryChange(
                historyTitleId(key[0]) ?: return null,
                if (season < 0) null else EpisodeNumber(season, number),
                value[0].toLongOrNull() ?: return null,
                value[1].toLongOrNull(),
                value[2] == "true",
                value[3] == "true",
                entry.at,
            )
        }
    }
}
