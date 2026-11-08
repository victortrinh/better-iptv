package dev.betteriptv.core

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import okio.Buffer
import java.io.Closeable
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/**
 * The device's copy of the Provider catalog, in SQLite.
 *
 * A Sync fills staging tables and swaps them in with one transaction, so readers see either the
 * previous catalog or the new one, never a mix, and a failed Sync leaves the previous one untouched.
 * Movie and show Listings and Titles are stored by kind with their list position as the primary
 * key, so any slice of a 144k-row list is an index lookup. Each Listing points at its Title's
 * position, and a Title is found by its key (`movie/238`, `show/1399`, or `movie/listing/1001` for
 * an Unmatched one). Hidden Listings (Clutter, Sports replays) get no position and no Title.
 *
 * A Listing is Matched by the Title number its Provider files it under, unless it is a Mismatch: it
 * is dated more than a year from the year its siblings (the Listings of its kind and number) carry
 * most (Matching.kt). A Mismatch, like a Listing without a number, is an Unmatched Title of its own.
 *
 * Title metadata from the cloud is cached here too, per Title and language, along with the
 * device's cloud token, and so are show Listings' show details, until the Provider changes the Show.
 * What movie Listings' files hold, from Header reads and Playback, and their bitrates from the
 * Provider's movie details, are kept forever.
 * So is where the Viewer stopped in each Title, keyed by Title so it outlives Syncs.
 *
 * What a Viewer keeps (where they stopped, Favourites, "Not this game" marks, what they chose in the
 * player) is kept per Profile, the household's Profiles in their own table (#428); the catalog and
 * what the Provider's files hold are the device's. Each Viewer's read and write names its Profile.
 *
 * Channel variants (live Listings, Clutter left out) are swapped in with the movie and show
 * Listings, in the Provider's order, each with its Channel's key, the Category it was filed under and
 * its guide id, and so are the live Categories with their normalised names and kinds. A Channel is
 * its variants read together by that key. Favourites are kept by Channel key, so they outlive Syncs
 * and whichever variants the Provider lists next; one the Provider drops comes back if it lists the
 * Channel again. Favourites kept by stream id by an older app are carried over once, by looking
 * each id up in the catalog the next Sync stores.
 *
 * Sports replays (the movie Listings the Prefix table marks so) are swapped in beside the Listings,
 * in the Provider's order, each with the two teams and the date read off its name, so the Replays
 * row and a Game's page find them without reading the names again.
 */
internal class CatalogStore(path: String) : Closeable, DeviceTokenStore, PinStore {
    private val driver = BundledSQLiteDriver()
    private val writer: SQLiteConnection = driver.open(path).apply {
        execSQL("PRAGMA journal_mode=WAL")
        execSQL("PRAGMA synchronous=NORMAL")
        // A big write grows the write-ahead log; once checkpointed, it is cut back to this.
        execSQL("PRAGMA journal_size_limit=$WAL_SIZE_LIMIT")
        execSQL("CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        // What the player remembered was kept in meta before Profiles (#428), by kind: it becomes the first Profile's,
        // before an older catalog's meta is cleared below.
        execSQL(PROFILE_TABLES.toMap().getValue("remembered_track").format("remembered_track"))
        execSQL(
            "INSERT OR IGNORE INTO remembered_track SELECT $FIRST_PROFILE, substr(key, ${REMEMBERED_TRACK.length + 1}), value FROM meta " +
                "WHERE substr(key, 1, ${REMEMBERED_TRACK.length}) = '$REMEMBERED_TRACK'",
        )
        execSQL("DELETE FROM meta WHERE substr(key, 1, ${REMEMBERED_TRACK.length}) = '$REMEMBERED_TRACK'")
        if (prepare("PRAGMA user_version").use { it.step(); it.getLong(0) } < SCHEMA_VERSION) {
            // A catalog stored in an older shape is dropped; the next Sync stores it again.
            execSQL("DROP TABLE IF EXISTS movie_listing")
            execSQL("DROP TABLE IF EXISTS listing")
            execSQL("DROP TABLE IF EXISTS title")
            execSQL("DROP TABLE IF EXISTS show_details")
            execSQL("DROP TABLE IF EXISTS episode")
            execSQL("DROP TABLE IF EXISTS channel")
            execSQL("DROP TABLE IF EXISTS live_category")
            execSQL("DROP TABLE IF EXISTS replay")
            execSQL("DROP TABLE IF EXISTS staging_kept_listing")
            execSQL("DROP TABLE IF EXISTS staging_title")
            execSQL("DROP TABLE IF EXISTS staging_replay")
            execSQL("DROP TABLE IF EXISTS staging_channel")
            execSQL("DROP TABLE IF EXISTS staging_live_category")
            execSQL("DROP TABLE IF EXISTS staging_unknown_prefix")
            execSQL("DROP TABLE IF EXISTS staging_tier_tag")
            execSQL("DELETE FROM meta")
            execSQL("PRAGMA user_version = $SCHEMA_VERSION")
        }
        execSQL(LISTING_TABLE.format("listing"))
        // What the Provider says of a Title on its Listing (ADR 0014), kept at Sync; a catalog from before these columns gets them empty until its next Sync.
        DETAILS_COLUMNS.forEach { (column, type) -> if (!hasColumn("listing", column)) execSQL("ALTER TABLE listing ADD COLUMN $column $type") }
        execSQL(TITLE_TABLE.format("title"))
        execSQL(CHANNEL_TABLE.format("channel"))
        execSQL(LIVE_CATEGORY_TABLE.format("live_category"))
        execSQL(REPLAY_TABLE.format("replay"))
        INDEXES.forEach { execSQL(it) }
        execSQL(UNKNOWN_PREFIX_TABLE.format("unknown_prefix"))
        execSQL(TIER_TAG_TABLE.format("tier_tag"))
        // Favourites an older app kept by stream id wait for the next Sync's catalog to be looked up in. An older
        // app run again before that Sync makes a new table by stream id, which joins the ones waiting.
        if (prepare("SELECT count(*) FROM pragma_table_info('favourite') WHERE name = 'id'").use { it.step(); it.getLong(0) } > 0) {
            if (prepare("SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name = '$LEGACY_FAVOURITES'").use { it.step(); it.getLong(0) } > 0) {
                execSQL("INSERT OR IGNORE INTO $LEGACY_FAVOURITES SELECT id, added_at FROM favourite")
                execSQL("DROP TABLE favourite")
            } else {
                execSQL("ALTER TABLE favourite RENAME TO $LEGACY_FAVOURITES")
            }
        }
        // The household's Profiles (#428): the first is made with the table, and holds what the device kept before there were Profiles.
        // The current one is the one used last, so a change to how meta is kept never moves the TV to another.
        execSQL(
            "CREATE TABLE IF NOT EXISTS profile (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT, kids INTEGER NOT NULL, created_at INTEGER NOT NULL, " +
                "used_at INTEGER NOT NULL)",
        )
        // Each Profile's id for the Account (#440), the same on every TV once it syncs, and when its name or Kids flag last changed.
        if (!hasColumn("profile", "uid")) execSQL("ALTER TABLE profile ADD COLUMN uid TEXT")
        if (!hasColumn("profile", "changed_at")) execSQL("ALTER TABLE profile ADD COLUMN changed_at INTEGER NOT NULL DEFAULT 0")
        // The Household PIN (#437), one row: the household's, not the catalog's, so a new catalog shape keeps it.
        execSQL("CREATE TABLE IF NOT EXISTS household_pin (id INTEGER PRIMARY KEY CHECK (id = 1), hash TEXT NOT NULL, failures INTEGER NOT NULL, locked_until INTEGER)")
        // When it was set, so the latest set on any TV of the Account wins (#440); one set before is the oldest.
        if (!hasColumn("household_pin", "changed_at")) execSQL("ALTER TABLE household_pin ADD COLUMN changed_at INTEGER NOT NULL DEFAULT 1")
        // The household's own settings (the Region), each with when it changed, synced with the Account (#440).
        execSQL("CREATE TABLE IF NOT EXISTS household (key TEXT PRIMARY KEY, value TEXT, updated_at INTEGER NOT NULL)")
        // What the Account hasn't been sent yet, besides history, and what it answered that waits to land between screens
        // (#440), by kind and Profile id (SyncEntry). A Profile deleted here takes its rows in neither: they name its id.
        execSQL(
            "CREATE TABLE IF NOT EXISTS sync_outbox (kind TEXT NOT NULL, profile TEXT NOT NULL, provider TEXT NOT NULL, key TEXT NOT NULL, value TEXT, " +
                "at INTEGER NOT NULL, PRIMARY KEY (kind, profile, provider, key))",
        )
        execSQL(
            "CREATE TABLE IF NOT EXISTS sync_pulled (kind TEXT NOT NULL, profile TEXT NOT NULL, key TEXT NOT NULL, value TEXT, at INTEGER NOT NULL, " +
                "PRIMARY KEY (kind, profile, key))",
        )
        execSQL("BEGIN")
        // Each Viewer's table is kept per Profile. One kept before Profiles is rebuilt with its rows as the first Profile's.
        for ((table, shape) in PROFILE_TABLES) {
            val exists = prepare("SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name = '$table'").use { it.step(); it.getLong(0) } > 0
            val kept = exists && prepare("SELECT count(*) FROM pragma_table_info('$table') WHERE name = 'profile'").use { it.step(); it.getLong(0) } == 0L
            if (kept) execSQL("ALTER TABLE $table RENAME TO ${table}_before_profiles")
            execSQL(shape.format(table))
            if (kept) {
                execSQL("INSERT INTO $table SELECT $FIRST_PROFILE, * FROM ${table}_before_profiles")
                execSQL("DROP TABLE ${table}_before_profiles")
            }
        }
        val now = System.currentTimeMillis()
        execSQL("INSERT INTO profile (id, name, kids, created_at, used_at) SELECT $FIRST_PROFILE, NULL, 0, $now, $now WHERE NOT EXISTS (SELECT 1 FROM profile)")
        execSQL("UPDATE profile SET uid = lower(hex(randomblob(8))) WHERE uid IS NULL")
        execSQL("COMMIT")
        // A catalog stored while the cloud Matched Listings by name (before ADR 0014) is kept, with where its Viewers stopped: the Listings it
        // Matched otherwise than by their number are Unmatched Titles again, and its answers go.
        if (hasColumn("listing", "suspect")) unmatchCloudMatches()
        execSQL("DROP TABLE IF EXISTS match_answer")
        // Keyed by Title, not by Listing, so it outlives Syncs and catalog shape changes.
        execSQL(
            "CREATE TABLE IF NOT EXISTS title_metadata (title_key TEXT NOT NULL, language TEXT NOT NULL, body TEXT, fetched_at INTEGER NOT NULL, " +
                "PRIMARY KEY (title_key, language))",
        )
        // What Wikidata knows of a Title (#476), by Title key, kept for good: found is 0 when Wikidata has no item for its
        // number, and the other columns are then null. The cloud's answers wait in the pending table until they land
        // between screens (ADR 0011), so nothing moves under focus.
        execSQL(TITLE_FACTS_TABLE.format("title_facts"))
        execSQL(TITLE_FACTS_TABLE.format("title_facts_pending"))
        // Trending finds its Titles by their Wikipedia article names (#477).
        execSQL("CREATE INDEX IF NOT EXISTS title_facts_by_enwiki ON title_facts (enwiki)")
        execSQL("CREATE INDEX IF NOT EXISTS title_facts_by_frwiki ON title_facts (frwiki)")
        // Trending's feed as the cloud last gave it in an App language, summed over its week, so Home builds without the cloud (#477).
        execSQL("CREATE TABLE IF NOT EXISTS trending (language TEXT PRIMARY KEY, body TEXT NOT NULL, fetched_at INTEGER NOT NULL)")
        // Episodes kept before their file extension was are asked again, so they can play; so are those kept before their still and length were (ADR 0014).
        if (prepare("SELECT count(*) FROM pragma_table_info('episode') WHERE name = 'audio_language'").use { it.step(); it.getLong(0) } > 0 &&
            (!hasColumn("episode", "container") || !hasColumn("episode", "still"))
        ) {
            execSQL("DROP TABLE show_details")
            execSQL("DROP TABLE episode")
        }
        // The cloud's Charts answer for a Region in an App language, as its JSON, so Home builds without it.
        execSQL("CREATE TABLE IF NOT EXISTS chart (region TEXT NOT NULL, language TEXT NOT NULL, body TEXT NOT NULL, fetched_at INTEGER NOT NULL, PRIMARY KEY (region, language))")
        // The cloud's Games answer for a League, as its JSON, so the Live tab builds without the cloud (#252).
        execSQL("CREATE TABLE IF NOT EXISTS game (league TEXT PRIMARY KEY, body TEXT NOT NULL, fetched_at INTEGER NOT NULL)")
        // A show Listing's show details as the Provider gave them while the Show was last changed at `modified`.
        execSQL("CREATE TABLE IF NOT EXISTS show_details (listing_id INTEGER PRIMARY KEY, modified INTEGER, fetched_at INTEGER NOT NULL)")
        // What a movie Listing's file holds, from a Header read, Playback or the cloud; a null body: its header can't be read.
        // `shared` says whether the cloud has it. Kept forever: a Listing's file doesn't change.
        execSQL("CREATE TABLE IF NOT EXISTS file_read (listing_id INTEGER PRIMARY KEY, body TEXT, read_at INTEGER NOT NULL, shared INTEGER NOT NULL)")
        // A movie Listing's Provider details (ADR 0014): the bitrate they give (null when none), what they say of the Movie, and the Listing's
        // `added` when they were read, so they are read again only once the Provider changes the Listing. Kept for good.
        execSQL("CREATE TABLE IF NOT EXISTS movie_details (listing_id INTEGER PRIMARY KEY, bitrate INTEGER, fetched_at INTEGER NOT NULL)")
        (DETAILS_COLUMNS + listOf("runtime" to "INTEGER", "added" to "INTEGER")).forEach { (column, type) ->
            if (!hasColumn("movie_details", column)) execSQL("ALTER TABLE movie_details ADD COLUMN $column $type")
        }
        // Backdrops the app refused once decoded (a poster handed over as one, #475): they count as none from then on.
        execSQL("CREATE TABLE IF NOT EXISTS refused_backdrop (url TEXT PRIMARY KEY)")
        execSQL(
            "CREATE TABLE IF NOT EXISTS episode (listing_id INTEGER NOT NULL, id INTEGER NOT NULL, season INTEGER NOT NULL, number INTEGER NOT NULL, " +
                "title TEXT NOT NULL, added INTEGER, width INTEGER, height INTEGER, audio_language TEXT, container TEXT, still TEXT, duration INTEGER, PRIMARY KEY (listing_id, id))",
        )
    }
    private val reader: SQLiteConnection = driver.open(path)

    init {
        // A Sync the app was closed during left half a catalog staged: it goes, the next Sync stages it again.
        if (!hasPending()) discardPending()
    }

    /** Where a Sync writes the Channels stage: the Channel variants and live Categories, before they are swapped in. */
    class ChannelStaging internal constructor(connection: SQLiteConnection) {
        private val insertChannel = connection.prepare("INSERT OR REPLACE INTO staging_channel VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)")
        private val insertLiveCategory = connection.prepare("INSERT OR REPLACE INTO staging_live_category VALUES (?, ?, ?, ?, ?, ?)")
        private var channelPosition = 0L
        private var liveCategoryPosition = 0L

        /**
         * Stores a Channel variant (a live Listing that isn't Clutter), in the Provider's order, as a
         * variant of the Channel keyed [channelKey], named [channelName] with its [country] tag; what
         * its name says of it is its [quality] and whether it is [hevc]. [eventStart] is when the Game
         * an Event channel carries starts, so it drops out of the lists once finished. [categoryId] is
         * the Provider's id of the Category it was filed under, and [guideId] what the Provider's guide
         * knows it by, empty when the guide doesn't cover it.
         */
        fun channel(
            id: Long, name: String, image: String?, eventStart: Instant?, categoryId: String?, guideId: String,
            channelKey: String, channelName: String, country: String?, quality: Quality?, hevc: Boolean,
        ) = insertChannel.run {
            bindLong(1, id)
            bindLong(2, ++channelPosition)
            bindText(3, name)
            if (image == null) bindNull(4) else bindText(4, image)
            if (eventStart == null) bindNull(5) else bindLong(5, eventStart.epochSecond)
            if (categoryId == null) bindNull(6) else bindText(6, categoryId)
            bindText(7, guideId)
            bindText(8, channelKey)
            bindText(9, channelName)
            if (country == null) bindNull(10) else bindText(10, country)
            if (quality == null) bindNull(11) else bindText(11, quality.name)
            bindLong(12, if (hevc) 1 else 0)
            stepAndReset()
        }

        /** Stores a live Category, in the Provider's order, with its normalised name, country code and kind. */
        fun liveCategory(id: String, name: String, normalisedName: String, country: String?, kind: CategoryKind) = insertLiveCategory.run {
            bindText(1, id)
            bindLong(2, ++liveCategoryPosition)
            bindText(3, name)
            bindText(4, normalisedName)
            if (country == null) bindNull(5) else bindText(5, country)
            bindText(6, kindKey(kind))
            stepAndReset()
        }

        internal fun close() {
            insertChannel.close()
            insertLiveCategory.close()
        }
    }

    /** Where a Sync writes the Titles stage: the movie and show Categories and Listings, and the Sports replays. */
    class TitleStaging internal constructor(connection: SQLiteConnection) {
        private val insertCategory = connection.prepare("INSERT OR REPLACE INTO staging_category VALUES (?, ?, ?)")
        private val insertListing = connection.prepare("INSERT INTO staging_listing VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
        private val insertReplay = connection.prepare("INSERT OR REPLACE INTO staging_replay VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
        private var replayPosition = 0L

        /** Stores a Category of [kind]'s Listings: movie and show Categories are numbered apart. */
        fun category(kind: TitleKind, id: String, name: String) = insertCategory.run {
            bindText(1, kindKey(kind))
            bindText(2, id)
            bindText(3, name)
            stepAndReset()
        }

        /**
         * Stores a Listing of [kind], Matched to its Title by the Title number its Provider files it
         * under ([number]), or standing alone without one; [year] is the year it is dated, which tells
         * a Mismatch once every Listing is stored. A [hidden] one is kept out of the lists and gets no
         * Title. [added] orders the list, newest first: for a Show, it is when the Provider last changed
         * it. [container] is a movie file's extension (`mkv`), which its stream location ends with.
         * [rating] is the Provider's, on a 10 scale, and [details] what a show Listing says of its Show
         * (ADR 0014); a movie Listing has none.
         */
        fun listing(
            kind: TitleKind,
            id: Long,
            name: String,
            categoryId: String?,
            added: Long?,
            image: String?,
            hidden: Boolean,
            number: Long?,
            year: Int?,
            container: String? = null,
            rating: Double? = null,
            details: ProviderDetails? = null,
        ) = insertListing.run {
            bindText(1, kindKey(kind))
            bindLong(2, id)
            bindText(3, name)
            if (categoryId == null) bindNull(4) else bindText(4, categoryId)
            if (added == null) bindNull(5) else bindLong(5, added)
            bindLong(6, if (hidden) 1 else 0)
            if (image == null) bindNull(7) else bindText(7, image)
            bindText(8, key(number?.let { TitleId.Tmdb(kind, it) } ?: TitleId.Unmatched(kind, id)))
            if (number == null) bindNull(9) else bindLong(9, number)
            if (year == null) bindNull(10) else bindLong(10, year.toLong())
            if (container == null) bindNull(11) else bindText(11, container)
            val said = details?.takeUnless { it.isEmpty }
            bindDetails(12, rating ?: said?.rating, said)
            stepAndReset()
        }

        /**
         * Stores a Sports replay (a movie Listing the Prefix table marks so, which [listing] keeps out
         * of the lists), in the Provider's order, with what its name says ([parsed]: the teams and the
         * date), the [category] it is filed under and its file's [container] extension.
         */
        fun replay(id: Long, name: String, category: String, added: Long?, container: String?, parsed: ParsedReplay) = insertReplay.run {
            bindLong(1, id)
            bindLong(2, ++replayPosition)
            bindText(3, name)
            bindText(4, category)
            if (added == null) bindNull(5) else bindLong(5, added)
            if (container == null) bindNull(6) else bindText(6, container)
            if (parsed.away == null) bindNull(7) else bindText(7, parsed.away)
            if (parsed.home == null) bindNull(8) else bindText(8, parsed.home)
            if (parsed.date == null) bindNull(9) else bindText(9, parsed.date.toString())
            if (parsed.year == null) bindNull(10) else bindLong(10, parsed.year.toLong())
            stepAndReset()
        }

        internal fun close() {
            insertCategory.close()
            insertListing.close()
            insertReplay.close()
        }
    }

    /**
     * Writes the Channels stage of a Sync, what [fill] stores, beside the catalog on screen: swapped
     * in by [swapChannels] or [applyPending]. Returns how many Channel variants it stored.
     */
    fun stageChannels(fill: (ChannelStaging) -> Unit): Int = synchronized(writer) {
        writer.execSQL("DROP TABLE IF EXISTS staging_channel")
        writer.execSQL("DROP TABLE IF EXISTS staging_live_category")
        writer.execSQL(CHANNEL_TABLE.format("staging_channel"))
        writer.execSQL(LIVE_CATEGORY_TABLE.format("staging_live_category"))
        try {
            writer.execSQL("BEGIN")
            val staging = ChannelStaging(writer)
            try {
                fill(staging)
            } finally {
                staging.close()
            }
            writer.execSQL("COMMIT")
        } catch (e: Throwable) {
            if (writer.inTransaction()) writer.execSQL("ROLLBACK")
            writer.execSQL("DROP TABLE IF EXISTS staging_channel")
            writer.execSQL("DROP TABLE IF EXISTS staging_live_category")
            throw e
        }
        truncateLog()
        writer.prepare("SELECT count(*) FROM staging_channel").use { it.step(); it.getLong(0).toInt() }
    }

    /**
     * Writes the Titles stage of a Sync, what [fill] stores, beside the catalog on screen, with each
     * Mismatch an Unmatched Title of its own. [onFilled] runs once [fill] returns, before the Titles are
     * built. [completed] runs once they are, with the new Listing counts and how many Unmatched Titles
     * the year rule made, and the Sync it returns is kept as pending until [swapTitles] or [applyPending]
     * lands it, through a close and reopen if need be.
     */
    fun stageTitles(fill: (TitleStaging) -> Unit, onFilled: () -> Unit, completed: (movieCount: Int, showCount: Int, mismatches: Int) -> CompletedSync): CompletedSync =
        synchronized(writer) {
            writer.execSQL("DROP TABLE IF EXISTS staging_category")
            writer.execSQL("DROP TABLE IF EXISTS staging_listing")
            writer.execSQL("DROP TABLE IF EXISTS staging_kept_listing")
            writer.execSQL("DROP TABLE IF EXISTS staging_title")
            writer.execSQL("DROP TABLE IF EXISTS staging_replay")
            writer.execSQL("DROP TABLE IF EXISTS staging_unknown_prefix")
            writer.execSQL("DROP TABLE IF EXISTS staging_tier_tag")
            writer.execSQL(REPLAY_TABLE.format("staging_replay"))
            writer.execSQL("CREATE TABLE staging_category (kind TEXT, id TEXT, name TEXT NOT NULL, PRIMARY KEY (kind, id))")
            writer.execSQL(
                "CREATE TABLE staging_listing (kind TEXT, id INTEGER, name TEXT, category_id TEXT, added INTEGER, hidden INTEGER, image TEXT, title_key TEXT, " +
                    "provider_tmdb INTEGER, year INTEGER, container TEXT, $DETAILS_COLUMN_NAMES)",
            )
            writer.execSQL(LISTING_TABLE.format("staging_kept_listing"))
            writer.execSQL(TITLE_TABLE.format("staging_title"))
            writer.execSQL(UNKNOWN_PREFIX_TABLE.format("staging_unknown_prefix"))
            writer.execSQL(TIER_TAG_TABLE.format("staging_tier_tag"))
            try {
                writer.execSQL("BEGIN")
                val staging = TitleStaging(writer)
                try {
                    fill(staging)
                } finally {
                    staging.close()
                }
                onFilled()
                // Each number's year: the one its dated Listings carry most, ties going to the earliest.
                writer.execSQL("CREATE TABLE staging_number_year (kind TEXT, provider_tmdb INTEGER, year INTEGER, PRIMARY KEY (kind, provider_tmdb))")
                writer.execSQL(
                    """
                    INSERT INTO staging_number_year SELECT kind, provider_tmdb, year FROM (
                        SELECT kind, provider_tmdb, year, row_number() OVER (PARTITION BY kind, provider_tmdb ORDER BY count(*) DESC, year) AS rank
                        FROM staging_listing WHERE hidden = 0 AND provider_tmdb IS NOT NULL AND year IS NOT NULL GROUP BY kind, provider_tmdb, year
                    ) WHERE rank = 1
                    """.trimIndent(),
                )
                // A Listing dated further from it is a Mismatch, an Unmatched Title of its own.
                writer.execSQL(
                    """
                    UPDATE staging_listing SET title_key = $LISTING_KEY
                    WHERE hidden = 0 AND year IS NOT NULL AND abs(year - (
                        SELECT n.year FROM staging_number_year n WHERE n.kind = staging_listing.kind AND n.provider_tmdb = staging_listing.provider_tmdb
                    )) > $MISMATCH_YEARS
                    """.trimIndent(),
                )
                writer.execSQL("DROP TABLE staging_number_year")
                // Titles are listed by their newest Listing.
                writer.execSQL("INSERT INTO staging_title (kind, position, key) $TITLES_BY_NEWEST_LISTING".format("staging_listing WHERE hidden = 0"))
                writer.execSQL("CREATE INDEX staging_title_key ON staging_title (key)")
                writer.execSQL(
                    """
                    INSERT INTO staging_kept_listing (kind, position, id, name, category, added, image, title, title_key, provider_tmdb, container, $DETAILS_COLUMN_NAMES)
                    SELECT l.kind, row_number() OVER (PARTITION BY l.kind ORDER BY l.added DESC, l.name, l.id), l.id, l.name, coalesce(c.name, ''), l.added, l.image,
                        t.position, l.title_key, l.provider_tmdb, l.container, ${DETAILS_COLUMNS.joinToString(", ") { "l." + it.first }}
                    FROM staging_listing l
                    LEFT JOIN staging_category c ON c.kind = l.kind AND c.id = l.category_id
                    JOIN staging_title t ON t.key = l.title_key
                    WHERE l.hidden = 0
                    """.trimIndent(),
                )
                writer.execSQL("DROP INDEX staging_title_key")
                writer.execSQL("DROP TABLE staging_listing")
                writer.execSQL("DROP TABLE staging_category")
                writer.execSQL("COMMIT")

                fun count(kind: TitleKind) = writer.prepare("SELECT count(*) FROM staging_kept_listing WHERE kind = ?").use {
                    it.bindText(1, kindKey(kind))
                    it.step()
                    it.getLong(0).toInt()
                }
                val sync = completed(count(TitleKind.Movie), count(TitleKind.Show), mismatchCount("staging_kept_listing"))
                writer.execSQL("BEGIN")
                writer.prepare("INSERT INTO staging_unknown_prefix VALUES (?, ?, ?)").use { insert ->
                    sync.unknownPrefixes.forEach {
                        insert.bindText(1, it.prefix)
                        insert.bindLong(2, it.listingCount.toLong())
                        insert.bindText(3, it.example)
                        insert.stepAndReset()
                    }
                }
                writer.prepare("INSERT INTO staging_tier_tag VALUES (?, ?, ?)").use { insert ->
                    sync.candidateTierTags.forEach {
                        insert.bindText(1, it.word)
                        insert.bindLong(2, it.listingCount.toLong())
                        insert.bindText(3, it.example)
                        insert.stepAndReset()
                    }
                }
                writeMeta(
                    mapOf(
                        PENDING_SYNC_AT to sync.finishedAt.toString(),
                        PENDING_SYNC_MILLIS to sync.duration.toMillis().toString(),
                        PENDING_MOVIE_LISTINGS to sync.movieListingCount.toString(),
                        PENDING_SHOW_LISTINGS to sync.showListingCount.toString(),
                        PENDING_CHANNEL_LISTINGS to sync.channelListingCount.toString(),
                        PENDING_MISMATCHES to sync.mismatches.toString(),
                    ),
                )
                writer.execSQL("COMMIT")
                truncateLog()
                sync
            } catch (e: Throwable) {
                if (writer.inTransaction()) writer.execSQL("ROLLBACK")
                dropTitleStaging()
                throw e
            }
        }

    /** Whether a Sync's catalog is staged and waiting to be swapped in. */
    fun hasPending(): Boolean = synchronized(writer) { pendingAt() != null }

    /** When the pending catalog finished computing, if there is one. */
    fun pendingAt(): Instant? = synchronized(writer) {
        val at = meta(PENDING_SYNC_AT) ?: return null
        val staged = listOf("staging_kept_listing", "staging_title", "staging_replay", "staging_channel", "staging_live_category")
        if (staged.all { tableExists(it) }) Instant.parse(at) else null
    }

    /** Drops whatever a Sync staged and didn't land, so it takes no room beside the catalog. */
    fun discardPending() = synchronized(writer) {
        writer.execSQL("BEGIN")
        try {
            dropTitleStaging()
            writer.execSQL("DROP TABLE IF EXISTS staging_channel")
            writer.execSQL("DROP TABLE IF EXISTS staging_live_category")
            writer.execSQL("DELETE FROM meta WHERE key LIKE 'pending_sync_%'")
            writer.execSQL("COMMIT")
        } catch (e: Throwable) {
            if (writer.inTransaction()) writer.execSQL("ROLLBACK")
            throw e
        }
    }

    /** When the Channels on screen landed, or null before any have. */
    fun channelsSyncedAt(): Instant? = meta(CHANNELS_SYNCED_AT)?.let(Instant::parse)

    /** Swaps the staged Channels in, as of [at], so Live and Home have them while the Titles are still read. */
    fun swapChannels(at: Instant) = synchronized(writer) {
        writer.execSQL("BEGIN IMMEDIATE")
        try {
            swapChannelsIn(at)
            writer.execSQL("COMMIT")
        } catch (e: Throwable) {
            writer.execSQL("ROLLBACK")
            throw e
        }
        truncateLog()
    }

    /** Swaps the staged Titles in, recording the pending Sync as the last one, its duration now counting the swap. */
    fun swapTitles(): CompletedSync = synchronized(writer) {
        val started = System.nanoTime()
        writer.execSQL("BEGIN IMMEDIATE")
        try {
            swapTitlesIn(started)
            writer.execSQL("COMMIT")
        } catch (e: Throwable) {
            writer.execSQL("ROLLBACK")
            throw e
        }
        truncateLog()
        lastSync()!!
    }

    /** Swaps a pending catalog in, Channels and Titles together, or returns null without one. */
    fun applyPending(): CompletedSync? = synchronized(writer) {
        if (pendingAt() == null) return null
        val started = System.nanoTime()
        writer.execSQL("BEGIN IMMEDIATE")
        try {
            val at = Instant.now()
            swapChannelsIn(at)
            swapTitlesIn(started, at)
            writer.execSQL("COMMIT")
        } catch (e: Throwable) {
            writer.execSQL("ROLLBACK")
            throw e
        }
        truncateLog()
        lastSync()
    }

    private fun swapChannelsIn(at: Instant) {
        writer.execSQL("DROP TABLE channel")
        writer.execSQL("ALTER TABLE staging_channel RENAME TO channel")
        writer.execSQL("DROP TABLE live_category")
        writer.execSQL("ALTER TABLE staging_live_category RENAME TO live_category")
        CHANNEL_INDEXES.forEach { writer.execSQL(it) }
        // Each Channel's first variant leads it: the search index and [channels] read Channels by their leads.
        writer.execSQL("UPDATE channel SET lead = 1 WHERE position IN (SELECT min(position) FROM channel GROUP BY channel_key)")
        // Favourites an older app kept by stream id become Favourites of the Channels those ids are variants of now;
        // an id the Provider no longer lists can't be told, and goes.
        if (tableExists(LEGACY_FAVOURITES)) {
            writer.execSQL(
                "INSERT OR IGNORE INTO favourite (profile, key, added_at) SELECT $FIRST_PROFILE, c.channel_key, min(l.added_at) FROM $LEGACY_FAVOURITES l " +
                    "JOIN channel c ON c.id = l.id GROUP BY c.channel_key ORDER BY 2",
            )
            writer.execSQL("DROP TABLE $LEGACY_FAVOURITES")
        }
        writeMeta(mapOf(CHANNELS_SYNCED_AT to at.toString()))
    }

    private fun swapTitlesIn(swapStarted: Long, at: Instant = Instant.now()) {
        writer.execSQL("DROP TABLE listing")
        writer.execSQL("DROP TABLE title")
        writer.execSQL("ALTER TABLE staging_kept_listing RENAME TO listing")
        writer.execSQL("ALTER TABLE staging_title RENAME TO title")
        writer.execSQL("DROP TABLE replay")
        writer.execSQL("ALTER TABLE staging_replay RENAME TO replay")
        TITLE_INDEXES.forEach { writer.execSQL(it) }
        // Show details of Listings the Provider no longer has won't be asked for again.
        writer.execSQL("DELETE FROM show_details WHERE listing_id NOT IN (SELECT id FROM listing WHERE kind = 'show')")
        writer.execSQL("DELETE FROM episode WHERE listing_id NOT IN (SELECT listing_id FROM show_details)")
        writer.execSQL("DELETE FROM unknown_prefix")
        writer.execSQL("INSERT INTO unknown_prefix SELECT * FROM staging_unknown_prefix")
        writer.execSQL("DROP TABLE staging_unknown_prefix")
        writer.execSQL("DELETE FROM tier_tag")
        writer.execSQL("INSERT INTO tier_tag SELECT * FROM staging_tier_tag")
        writer.execSQL("DROP TABLE staging_tier_tag")
        val computed = meta(PENDING_SYNC_MILLIS)!!.toLong()
        val swapped = Duration.ofNanos(System.nanoTime() - swapStarted).toMillis()
        writeMeta(
            mapOf(
                LAST_SYNC_AT to at.toString(),
                LAST_SYNC_MILLIS to (computed + swapped).toString(),
                LAST_SYNC_LISTINGS to meta(PENDING_MOVIE_LISTINGS)!!,
                LAST_SYNC_SHOW_LISTINGS to meta(PENDING_SHOW_LISTINGS)!!,
                LAST_SYNC_CHANNELS to meta(PENDING_CHANNEL_LISTINGS)!!,
                LAST_SYNC_MISMATCHES to meta(PENDING_MISMATCHES)!!,
            ),
        )
        // The Provider table the staged catalog was described with is the one kept from now on.
        meta(PENDING_PROVIDER_TABLE_FOR)?.let { key -> writeMeta(mapOf(PROVIDER_TABLE_FOR to key, PROVIDER_TABLE to meta(PENDING_PROVIDER_TABLE)!!)) }
        writer.execSQL("DELETE FROM meta WHERE key LIKE 'pending_sync_%'")
    }

    private fun dropTitleStaging() {
        writer.execSQL("DROP TABLE IF EXISTS staging_listing")
        writer.execSQL("DROP TABLE IF EXISTS staging_category")
        writer.execSQL("DROP TABLE IF EXISTS staging_kept_listing")
        writer.execSQL("DROP TABLE IF EXISTS staging_title")
        writer.execSQL("DROP TABLE IF EXISTS staging_replay")
        writer.execSQL("DROP TABLE IF EXISTS staging_unknown_prefix")
        writer.execSQL("DROP TABLE IF EXISTS staging_tier_tag")
    }

    private fun tableExists(name: String): Boolean =
        writer.prepare("SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name = ?").use { it.bindText(1, name); it.step(); it.getLong(0) > 0 }

    private fun writeMeta(values: Map<String, String>) = writer.prepare("INSERT OR REPLACE INTO meta VALUES (?, ?)").use { insert ->
        values.forEach { (key, value) ->
            insert.bindText(1, key)
            insert.bindText(2, value)
            insert.stepAndReset()
        }
    }

    /**
     * Copies the write-ahead log into the database and empties it, so a Sync's ~100 MB of writes
     * don't stay on the device's storage beside the catalog. The reader is held off meanwhile, since
     * a checkpoint can't empty the log while a read is going on.
     */
    private fun truncateLog() = synchronized(reader) {
        writer.prepare("PRAGMA wal_checkpoint(TRUNCATE)").use { it.step() }
    }

    /** The Sync whose catalog is stored, if any. */
    fun lastSync(): CompletedSync? {
        val at = meta(LAST_SYNC_AT) ?: return null
        return CompletedSync(
            Instant.parse(at),
            Duration.ofMillis(meta(LAST_SYNC_MILLIS)!!.toLong()),
            meta(LAST_SYNC_LISTINGS)!!.toInt(),
            unknownPrefixes(),
            meta(LAST_SYNC_MISMATCHES)?.toInt() ?: 0,
            meta(LAST_SYNC_SHOW_LISTINGS)?.toInt() ?: 0,
            meta(LAST_SYNC_CHANNELS)?.toInt() ?: 0,
            candidateTierTags(),
        )
    }

    /** How many of [table]'s Listings are Mismatches: Unmatched Titles though their Provider gave them a number. */
    private fun mismatchCount(table: String): Int =
        writer.prepare("SELECT count(*) FROM $table WHERE provider_tmdb IS NOT NULL AND title_key <> $PROVIDER_KEY").use { it.step(); it.getLong(0).toInt() }

    private fun candidateTierTags(): List<CandidateTierTag> = synchronized(reader) {
        reader.prepare("SELECT word, listings, example FROM tier_tag ORDER BY listings DESC, word").use {
            buildList { while (it.step()) add(CandidateTierTag(it.getText(0), it.getLong(1).toInt(), it.getText(2))) }
        }
    }

    private fun unknownPrefixes(): List<UnknownPrefix> = synchronized(reader) {
        reader.prepare("SELECT prefix, listings, example FROM unknown_prefix ORDER BY listings DESC, prefix").use {
            buildList { while (it.step()) add(UnknownPrefix(it.getText(0), it.getLong(1).toInt(), it.getText(2))) }
        }
    }

    fun movieListingCount(): Int = synchronized(reader) {
        reader.prepare("SELECT coalesce(max(position), 0) FROM listing WHERE kind = 'movie'").use { it.step(); it.getLong(0).toInt() }
    }

    /** Gives [consume] the name and Category of every movie and show Listing in the lists. */
    fun forEachListing(consume: (name: String, category: String) -> Unit) = synchronized(reader) {
        reader.prepare("SELECT name, category FROM listing").use {
            while (it.step()) consume(it.getText(0), it.getText(1))
        }
    }

    /**
     * Gives [each] every Channel's lead id (its first variant's stream id) and the name it's found
     * by (its country tag and name, `UK: BBC ONE NI`), in the Provider's order, a page at a time like
     * [forEachTitle].
     */
    fun forEachChannel(each: (leadId: Long, name: String) -> Unit) {
        var after = 0L
        while (true) {
            val page = synchronized(reader) {
                reader.prepare("SELECT position, id, channel_name, country FROM channel WHERE lead = 1 AND position > ? ORDER BY position LIMIT $SCAN_PAGE").use {
                    it.bindLong(1, after)
                    buildList {
                        while (it.step()) add(Triple(it.getLong(0), it.getLong(1), listOfNotNull(if (it.isNull(3)) null else it.getText(3), it.getText(2)).joinToString(": ")))
                    }
                }
            }
            page.forEach { (_, id, name) -> each(id, name) }
            if (page.size < SCAN_PAGE) return
            after = page.last().first
        }
    }

    /**
     * The Channels whose lead ids (as [forEachChannel] gives them) are among [leadIds], by lead id,
     * with the variants that are on: every one but Event channels whose Game started before
     * [startedBefore]. A Channel with none on is left out.
     */
    fun channels(profile: Long, leadIds: Collection<Long>, startedBefore: Instant): Map<Long, Channel> = synchronized(reader) {
        if (leadIds.isEmpty()) return emptyMap()
        leadIds.chunked(MAX_BOUND).flatMap { chunk ->
            reader.prepare(
                "SELECT lead.id, $CHANNEL_COLUMNS FROM channel lead JOIN channel c ON c.channel_key = lead.channel_key LEFT JOIN favourite f ON f.profile = $profile AND f.key = c.channel_key " +
                    "WHERE lead.id IN (${chunk.joinToString(",") { "?" }}) AND (c.event_start IS NULL OR c.event_start >= ?) ORDER BY lead.id, c.position",
            ).use {
                chunk.forEachIndexed { i, id -> it.bindLong(i + 1, id) }
                it.bindLong(chunk.size + 1, startedBefore.epochSecond)
                buildList { while (it.step()) add(it.getLong(0) to it.variantRow(1)) }
            }
        }.groupBy({ it.first }, { it.second }).mapValues { (_, rows) -> channel(rows) }
    }

    /** The Viewer's Favourites that are on (as [channels] tells), in the order they were added. */
    fun favourites(profile: Long, startedBefore: Instant): List<Channel> = synchronized(reader) {
        reader.prepare(
            "SELECT $CHANNEL_COLUMNS FROM favourite f JOIN channel c ON c.channel_key = f.key " +
                "WHERE f.profile = $profile AND (c.event_start IS NULL OR c.event_start >= ?) ORDER BY f.added_at, f.key, c.position",
        ).use {
            it.bindLong(1, startedBefore.epochSecond)
            buildList { while (it.step()) add(it.variantRow(0)) }
        }.groupBy { it.key }.values.map(::channel)
    }

    /**
     * The Event channels on: the Channels with a variant whose name says its Game starts from [from]
     * (included) to [to] (excluded), or marked `Live |` or `Next |` ([isMarkedOn]), merged as
     * [channels] merges them, in the Provider's order, each with the start its name gives (null when
     * only marked). A Sync keeps a finished Event channel's rows until the next, so the caller bounds the window.
     */
    fun eventChannels(profile: Long, from: Instant, to: Instant): List<Pair<Channel, Instant?>> = synchronized(reader) {
        reader.prepare(
            "SELECT $CHANNEL_COLUMNS, c.event_start FROM channel c LEFT JOIN favourite f ON f.profile = $profile AND f.key = c.channel_key " +
                "WHERE c.channel_key IN (SELECT channel_key FROM channel WHERE (event_start >= ? AND event_start < ?) OR name LIKE 'Live%|%' OR name LIKE 'Next%|%') " +
                "ORDER BY c.position",
        ).use {
            it.bindLong(1, from.epochSecond)
            it.bindLong(2, to.epochSecond)
            buildList { while (it.step()) add(it.variantRow(0) to (if (it.isNull(11)) null else Instant.ofEpochSecond(it.getLong(11)))) }
        }.groupBy { it.first.key }.values.mapNotNull { rows ->
            val start = rows.firstNotNullOfOrNull { it.second }
            val name = rows.first().first.variant.name
            // The LIKE over-matches ("Liverpool | …"): only names marked on, or with a start in the window, are Event channels here.
            if (start == null && !isMarkedOn(name)) null else channel(rows.map { it.first }) to start
        }
    }

    /**
     * The Channels named like one of [names] (the merged name, without tags or quality words), case
     * and spaces apart ("ESPN2" is "ESPN 2"), by that name lower-cased with its spaces dropped: a
     * Broadcaster's Channels, in every country. Merged as [channels] merges them, in the Provider's order.
     */
    fun channelsNamed(profile: Long, names: Collection<String>): Map<String, List<Channel>> = synchronized(reader) {
        val asked = names.map(::channelKey).distinct()
        if (asked.isEmpty()) return emptyMap()
        reader.prepare(
            "SELECT $CHANNEL_COLUMNS FROM channel c LEFT JOIN favourite f ON f.profile = $profile AND f.key = c.channel_key " +
                "WHERE c.channel_key IN (SELECT channel_key FROM channel WHERE lead = 1 AND replace(lower(channel_name), ' ', '') IN (${asked.joinToString(",") { "?" }})) " +
                "ORDER BY c.position",
        ).use {
            asked.forEachIndexed { i, name -> it.bindText(i + 1, name) }
            buildList { while (it.step()) add(it.variantRow(0)) }
        }.groupBy { it.key }.values.map(::channel).groupBy { channelKey(it.name) }
    }

    /**
     * The Feeds marked "Not this game" among the Games keyed [gameKeys] ([GameFeeds.key]), each as
     * [GameFeeds.droppedKey] forms it.
     */
    fun droppedFeeds(profile: Long, gameKeys: Collection<String>): Set<String> = synchronized(reader) {
        if (gameKeys.isEmpty()) return emptySet()
        val found = HashSet<String>()
        gameKeys.distinct().chunked(MAX_BOUND).forEach { chunk ->
            reader.prepare("SELECT game_key, channel_key FROM not_this_game WHERE profile = $profile AND game_key IN (${chunk.joinToString(",") { "?" }})").use {
                chunk.forEachIndexed { i, key -> it.bindText(i + 1, key) }
                while (it.step()) found += GameFeeds.droppedKey(it.getText(0), it.getText(1))
            }
        }
        found
    }

    /**
     * Marks the Channel keyed [channelKey] "Not this game" for the Game keyed [gameKey], at [at], and
     * forgets the marks made before [forgetBefore]: their Games are long over, and a schedule id is
     * never reused, so they would only grow.
     */
    fun dropFeed(profile: Long, gameKey: String, channelKey: String, at: Instant, forgetBefore: Instant) = synchronized(writer) {
        writer.prepare("DELETE FROM not_this_game WHERE dropped_at < ?").use {
            it.bindLong(1, forgetBefore.toEpochMilli())
            it.step()
        }
        writer.prepare("INSERT OR REPLACE INTO not_this_game VALUES ($profile, ?, ?, ?)").use {
            it.bindText(1, gameKey)
            it.bindText(2, channelKey)
            it.bindLong(3, at.toEpochMilli())
            it.step()
        }
        Unit
    }

    /** The keys of the Viewer's Favourites, whether or not the Provider still has them. */
    fun favouriteKeys(profile: Long): Set<String> = synchronized(reader) {
        reader.prepare("SELECT key FROM favourite WHERE profile = $profile").use { buildSet { while (it.step()) add(it.getText(0)) } }
    }

    /** Saves the Channel keyed [key] as a Favourite at [at], or forgets it. */
    fun setFavourite(profile: Long, key: String, favourite: Boolean, at: Instant) = synchronized(writer) {
        // Two added within a millisecond still keep their order.
        val insert = "INSERT OR IGNORE INTO favourite VALUES ($profile, ?, max(?, (SELECT coalesce(max(added_at), 0) + 1 FROM favourite WHERE profile = $profile)))"
        writer.prepare(if (favourite) insert else "DELETE FROM favourite WHERE profile = $profile AND key = ?").use {
            it.bindText(1, key)
            if (favourite) it.bindLong(2, at.toEpochMilli())
            it.step()
        }
        Unit
    }

    /** One variant's row, read from column [from] on: its Channel's key, name, country and whether it's a Favourite, then the variant. */
    private class VariantRow(val key: String, val channelName: String, val country: String?, val favourite: Boolean, val image: String?, val variant: ChannelVariant)

    private fun SQLiteStatement.variantRow(from: Int) = VariantRow(
        getText(from), getText(from + 1), if (isNull(from + 2)) null else getText(from + 2), !isNull(from + 3),
        if (isNull(from + 5)) null else getText(from + 5),
        ChannelVariant(
            getLong(from + 4), getText(from + 6), if (isNull(from + 7)) null else Quality.valueOf(getText(from + 7)), getLong(from + 8) != 0L,
            if (isNull(from + 9)) null else getText(from + 9), getText(from + 10),
        ),
    )

    /** A Channel from its variants' [rows], in the Provider's order: named and captioned by the first, with the first logo among them. */
    private fun channel(rows: List<VariantRow>): Channel {
        val first = rows.first()
        return Channel(first.key, first.channelName, first.country, rows.firstNotNullOfOrNull { it.image }, first.favourite, rows.map { it.variant })
    }

    /** The live Categories as the last Sync stored them, in the Provider's order. */
    fun liveCategories(): List<LiveCategory> = synchronized(reader) {
        reader.prepare("SELECT id, name, normalised_name, country, kind FROM live_category ORDER BY position").use {
            buildList {
                while (it.step()) add(LiveCategory(it.getText(0), it.getText(1), it.getText(2), if (it.isNull(3)) null else it.getText(3), categoryKind(it.getText(4))))
            }
        }
    }

    /**
     * The Channel browser's countries, unordered: the regular Categories' country codes as [country]
     * reads them (null keeps a code out), each with how many Channels its Categories hold, a Channel
     * once however many of them it's in, Event channels whose Game started before [startedBefore]
     * left out. Categories with no country code, and Clutter and Event Categories, count for nothing.
     */
    fun liveCountries(startedBefore: Instant, country: (String) -> BrowserCountry?): List<LiveCountry> = synchronized(reader) {
        val resolved = HashMap<String, BrowserCountry?>()
        val keys = LinkedHashMap<String, HashSet<String>>()
        val aliases = HashMap<String, String?>()
        reader.prepare(
            "SELECT DISTINCT lc.country, c.channel_key FROM live_category lc JOIN channel c ON c.category_id = lc.id " +
                "WHERE lc.kind = ? AND lc.country IS NOT NULL AND (c.event_start IS NULL OR c.event_start >= ?)",
        ).use {
            it.bindText(1, kindKey(CategoryKind.Regular))
            it.bindLong(2, startedBefore.epochSecond)
            while (it.step()) {
                val where = resolved.getOrPut(it.getText(0)) { country(it.getText(0)) } ?: continue
                keys.getOrPut(where.code) { HashSet() }.add(it.getText(1))
                aliases[where.code] = where.alias
            }
        }
        keys.map { (code, channels) -> LiveCountry(code, channels.size, aliases[code]) }
    }

    /**
     * The regular Categories the browser lists for the country coded [code] (as [country] reads
     * their codes), in the Provider's order, those sharing a name merged into one with their ids,
     * each with how many Channels they hold between them (once each, finished Event channels left
     * out as in [liveCountries]); one holding none is left out. The name is the normalised one with
     * the code's language after it (`SPORTS · FR`), so a country's Categories of two languages stay apart.
     */
    fun browsedCategories(code: String, startedBefore: Instant, country: (String) -> BrowserCountry?): List<BrowsedCategory> = synchronized(reader) {
        val names = LinkedHashMap<String, MutableList<String>>()
        reader.prepare("SELECT id, normalised_name, country FROM live_category WHERE kind = ? AND country IS NOT NULL ORDER BY position").use {
            it.bindText(1, kindKey(CategoryKind.Regular))
            while (it.step()) {
                val where = country(it.getText(2))?.takeIf { where -> where.code == code } ?: continue
                val name = listOfNotNull(it.getText(1), where.language).joinToString(" · ")
                names.getOrPut(name) { ArrayList() }.add(it.getText(0))
            }
        }
        val keys = HashMap<String, HashSet<String>>()
        names.values.flatten().chunked(MAX_BOUND).forEach { chunk ->
            reader.prepare(
                "SELECT category_id, channel_key FROM channel WHERE category_id IN (${chunk.joinToString(",") { "?" }}) AND (event_start IS NULL OR event_start >= ?)",
            ).use {
                chunk.forEachIndexed { i, id -> it.bindText(i + 1, id) }
                it.bindLong(chunk.size + 1, startedBefore.epochSecond)
                while (it.step()) keys.getOrPut(it.getText(0)) { HashSet() }.add(it.getText(1))
            }
        }
        names.mapNotNull { (name, ids) ->
            val channels = ids.flatMap { keys[it].orEmpty() }.toSet().size
            if (channels == 0) null else BrowsedCategory(name, ids, channels)
        }
    }

    /**
     * The Channels with a variant in any of the Categories [categoryIds], whole (every variant, in
     * whichever Category), in the Provider's order of their first variant in those Categories,
     * each once; as in [channels], Event channels whose Game started before [startedBefore] are
     * left out, and a Channel with no variant on with them.
     */
    fun categoryChannels(profile: Long, categoryIds: Collection<String>, startedBefore: Instant): List<Channel> = synchronized(reader) {
        if (categoryIds.isEmpty()) return emptyList()
        val ids = categoryIds.toList()
        val marks = ids.joinToString(",") { "?" }
        reader.prepare(
            "SELECT $CHANNEL_COLUMNS, c.position FROM channel c LEFT JOIN favourite f ON f.profile = $profile AND f.key = c.channel_key " +
                "WHERE c.channel_key IN (SELECT channel_key FROM channel WHERE category_id IN ($marks)) AND (c.event_start IS NULL OR c.event_start >= ?) ORDER BY c.position",
        ).use {
            ids.forEachIndexed { i, id -> it.bindText(i + 1, id) }
            it.bindLong(ids.size + 1, startedBefore.epochSecond)
            val rows = buildList { while (it.step()) add(it.variantRow(0) to it.getLong(11)) }
            val inCategory = ids.toSet()
            rows.groupBy { (row, _) -> row.key }.values
                .filter { group -> group.any { (row, _) -> row.variant.categoryId in inCategory } }
                .sortedBy { group -> group.filter { (row, _) -> row.variant.categoryId in inCategory }.minOf { (_, position) -> position } }
                .map { group -> channel(group.map { (row, _) -> row }) }
        }
    }

    /** Movie Listings from [offset], with [attributes] working out each one's Version attributes from its name and Category. */
    fun movieListings(offset: Int, limit: Int, attributes: (name: String, category: String) -> VersionAttributes): List<MovieListing> = synchronized(reader) {
        reader.prepare("SELECT id, name, category FROM listing WHERE kind = 'movie' AND position > ? AND position <= ? ORDER BY position").use {
            it.bindLong(1, offset.toLong())
            it.bindLong(2, offset.toLong() + limit)
            buildList {
                while (it.step()) {
                    val name = it.getText(1)
                    val category = it.getText(2)
                    add(MovieListing(it.getLong(0), name, category, attributes(name, category)))
                }
            }
        }
    }

    fun titleCount(kind: TitleKind): Int = synchronized(reader) {
        reader.prepare("SELECT coalesce(max(position), 0) FROM title WHERE kind = ?").use {
            it.bindText(1, kindKey(kind))
            it.step()
            it.getLong(0).toInt()
        }
    }

    /** [kind]'s Titles from [offset] (0-based), each with its Listings. */
    fun titles(kind: TitleKind, offset: Int, limit: Int): List<StoredTitle> = synchronized(reader) {
        reader.prepare(
            """
            SELECT t.key, $LISTING_COLUMNS FROM title t JOIN listing l ON l.kind = t.kind AND l.title = t.position
            WHERE t.kind = ? AND t.position > ? AND t.position <= ? ORDER BY t.position
            """.trimIndent(),
        ).use {
            it.bindText(1, kindKey(kind))
            it.bindLong(2, offset.toLong())
            it.bindLong(3, offset.toLong() + limit)
            val titles = LinkedHashMap<String, MutableList<StoredListing>>()
            while (it.step()) titles.getOrPut(it.getText(0)) { mutableListOf() } += it.listing(1)
            titles.map { (key, listings) -> StoredTitle(titleId(key), listings) }
        }
    }

    /** Every Category name a movie or show Listing is filed under, once each. */
    fun listingCategories(): List<String> = synchronized(reader) {
        reader.prepare("SELECT DISTINCT category FROM listing").use { buildList { while (it.step()) add(it.getText(0)) } }
    }

    /**
     * The Titles any of whose Listings is filed under one of [categories], or that are among [ids],
     * of each kind in list order (newest first): what the Kids catalog is made of.
     */
    fun titlesFiled(categories: Collection<String>, ids: Collection<TitleId> = emptyList()): Map<TitleKind, List<TitleId>> = synchronized(reader) {
        val found = HashMap<String, Pair<String, Long>>()
        categories.distinct().chunked(MAX_BOUND).forEach { chunk ->
            reader.prepare(
                "SELECT DISTINCT t.key, t.kind, t.position FROM title t JOIN listing l ON l.kind = t.kind AND l.title = t.position " +
                    "WHERE l.category IN (${chunk.joinToString(",") { "?" }})",
            ).use {
                chunk.forEachIndexed { i, category -> it.bindText(i + 1, category) }
                while (it.step()) found[it.getText(0)] = it.getText(1) to it.getLong(2)
            }
        }
        ids.map(::key).distinct().chunked(MAX_BOUND).forEach { chunk ->
            reader.prepare("SELECT key, kind, position FROM title WHERE key IN (${chunk.joinToString(",") { "?" }})").use {
                chunk.forEachIndexed { i, key -> it.bindText(i + 1, key) }
                while (it.step()) found[it.getText(0)] = it.getText(1) to it.getLong(2)
            }
        }
        TitleKind.entries.associateWith { kind ->
            found.entries.filter { it.value.first == kindKey(kind) }.sortedBy { it.value.second }.map { titleId(it.key) }
        }
    }

    /**
     * What names every Title's genres, one Listing at a time: the Title, its place in its kind's list
     * (1 the newest), the Listing's Category, and the Provider's genre field (a Show's from its
     * Listing at Sync, a Movie's from its details once read; null otherwise), when the Provider added the
     * Listing and its rating. What genre rows are made of (#478).
     */
    fun forEachGenreSource(each: (id: TitleId, position: Int, category: String, genre: String?, added: Long?, rating: Double?) -> Unit) = synchronized(reader) {
        reader.prepare(
            """
            SELECT t.key, t.position, l.category, CASE WHEN l.kind = 'movie' THEN d.genre ELSE l.genre END, l.added, l.rating
            FROM listing l JOIN title t ON t.kind = l.kind AND t.position = l.title
            LEFT JOIN movie_details d ON l.kind = 'movie' AND d.listing_id = l.id
            """.trimIndent(),
        ).use {
            val ids = HashMap<String, TitleId>()
            while (it.step()) {
                val key = it.getText(0)
                each(
                    ids.getOrPut(key) { titleId(key) }, it.getLong(1).toInt(), it.getText(2), if (it.isNull(3)) null else it.getText(3),
                    if (it.isNull(4)) null else it.getLong(4), if (it.isNull(5)) null else it.getDouble(5),
                )
            }
        }
    }

    /** A mark that changes whenever movie details with a genre field are kept, so what's worked out from them is worked out again. */
    fun movieGenresMark(): Long = synchronized(reader) {
        reader.prepare("SELECT count(*) * 31 + coalesce(max(fetched_at), 0) FROM movie_details WHERE genre IS NOT NULL").use {
            it.step()
            it.getLong(0)
        }
    }

    /** The Title [id] with its Listings, or null when the catalog doesn't have it. */
    fun title(id: TitleId): StoredTitle? = synchronized(reader) {
        reader.prepare(
            "SELECT $LISTING_COLUMNS FROM title t JOIN listing l ON l.kind = t.kind AND l.title = t.position WHERE t.key = ?",
        ).use {
            it.bindText(1, key(id))
            val listings = buildList { while (it.step()) add(it.listing(0)) }
            listings.takeIf { it.isNotEmpty() }?.let { StoredTitle(id, it) }
        }
    }

    /** The Titles of [ids] the catalog has, each with its Listings, read a few hundred at a time. */
    fun titles(ids: Collection<TitleId>): Map<TitleId, StoredTitle> = synchronized(reader) {
        buildMap {
            ids.distinct().chunked(MAX_BOUND).forEach { chunk ->
                reader.prepare(
                    "SELECT t.key, $LISTING_COLUMNS FROM title t JOIN listing l ON l.kind = t.kind AND l.title = t.position " +
                        "WHERE t.key IN (${chunk.joinToString(",") { "?" }})",
                ).use {
                    chunk.forEachIndexed { i, id -> it.bindText(i + 1, key(id)) }
                    val listings = LinkedHashMap<String, MutableList<StoredListing>>()
                    while (it.step()) listings.getOrPut(it.getText(0)) { mutableListOf() } += it.listing(1)
                    listings.forEach { (key, of) -> titleId(key).let { id -> put(id, StoredTitle(id, of)) } }
                }
            }
        }
    }

    /**
     * Calls [each] with every Title of [kind], in list order, and what search needs of its Listings:
     * their names and Categories, and the Provider's genre field and cast (a Show's from its Listing
     * at Sync, a Movie's from its details once read; null otherwise). Read a page at a time, so other
     * reads go on while [each] works through one.
     */
    fun forEachTitle(kind: TitleKind, each: (TitleId, List<SearchListing>) -> Unit) {
        val count = titleCount(kind)
        for (offset in 0 until count step SCAN_PAGE) {
            titleNames(kind, offset, SCAN_PAGE).forEach { (key, listings) -> each(titleId(key), listings) }
        }
    }

    private fun titleNames(kind: TitleKind, offset: Int, limit: Int): Map<String, List<SearchListing>> = synchronized(reader) {
        reader.prepare(
            """
            SELECT t.key, l.name, l.category, CASE WHEN l.kind = 'movie' THEN d.genre ELSE l.genre END, CASE WHEN l.kind = 'movie' THEN d.actors ELSE l.actors END
            FROM title t JOIN listing l ON l.kind = t.kind AND l.title = t.position
            LEFT JOIN movie_details d ON l.kind = 'movie' AND d.listing_id = l.id
            WHERE t.kind = ? AND t.position > ? AND t.position <= ? ORDER BY t.position
            """.trimIndent(),
        ).use {
            it.bindText(1, kindKey(kind))
            it.bindLong(2, offset.toLong())
            it.bindLong(3, offset.toLong() + limit)
            val titles = LinkedHashMap<String, MutableList<SearchListing>>()
            while (it.step()) {
                titles.getOrPut(it.getText(0)) { mutableListOf() } +=
                    SearchListing(it.getText(1), it.getText(2), if (it.isNull(3)) null else it.getText(3), if (it.isNull(4)) null else it.getText(4))
            }
            titles
        }
    }

    /** The file extension of Episode [id] (`mkv`), or null when the Provider didn't say or no kept show details list it. */
    fun episodeContainer(id: Long): String? = synchronized(reader) {
        reader.prepare("SELECT container FROM episode WHERE id = ? AND container IS NOT NULL LIMIT 1").use {
            it.bindLong(1, id)
            if (it.step()) it.getText(0) else null
        }
    }

    /** Background reading's day ([ReadingDay.kept]), or null before its first read. */
    fun readingDay(): String? = meta(READING_DAY)

    fun saveReadingDay(day: String) = saveDay(READING_DAY, day)

    /** The guide's day ([ReadingDay.kept]: how many guide calls were made, and whether one was refused), or null before its first call. */
    fun guideDay(): String? = meta(GUIDE_DAY)

    fun saveGuideDay(day: String) = saveDay(GUIDE_DAY, day)

    private fun saveDay(key: String, day: String) = synchronized(writer) {
        writer.prepare("INSERT OR REPLACE INTO meta VALUES (?, ?)").use {
            it.bindText(1, key)
            it.bindText(2, day)
            it.step()
        }
        Unit
    }

    /** What the Viewer last picked in the player, by kind (`audio`, `subtitles`): a language, or what turning subtitles off is kept as. */
    fun rememberedTrack(profile: Long, kind: String): String? = synchronized(reader) {
        reader.prepare("SELECT value FROM remembered_track WHERE profile = $profile AND kind = ?").use {
            it.bindText(1, kind)
            if (it.step()) it.getText(0) else null
        }
    }

    fun rememberTrack(profile: Long, kind: String, value: String) = synchronized(writer) {
        writer.prepare("INSERT OR REPLACE INTO remembered_track VALUES ($profile, ?, ?)").use {
            it.bindText(1, kind)
            it.bindText(2, value)
            it.step()
        }
        Unit
    }

    /** The backdrops the app refused ([Library.refuseBackdrop]). */
    fun refusedBackdrops(): Set<String> = synchronized(reader) {
        reader.prepare("SELECT url FROM refused_backdrop").use { buildSet { while (it.step()) add(it.getText(0)) } }
    }

    fun refuseBackdrop(url: String) = synchronized(writer) {
        writer.prepare("INSERT OR IGNORE INTO refused_backdrop VALUES (?)").use {
            it.bindText(1, url)
            it.step()
        }
        Unit
    }

    /** The Title featured last on [screen]'s hero ([Library.featured]), or null before the first. */
    fun lastFeatured(screen: String): TitleId? = meta(LAST_FEATURED + screen)?.let(::titleId)

    fun saveLastFeatured(screen: String, id: TitleId) = synchronized(writer) {
        writer.prepare("INSERT OR REPLACE INTO meta VALUES (?, ?)").use {
            it.bindText(1, LAST_FEATURED + screen)
            it.bindText(2, key(id))
            it.step()
        }
        Unit
    }

    /** The file extension of movie Listing [id] (`mkv`), a Sports replay's included, or null when the Provider didn't say or the catalog doesn't have it. */
    fun container(id: Long): String? = synchronized(reader) {
        reader.prepare("SELECT container FROM listing WHERE kind = 'movie' AND id = ? UNION ALL SELECT container FROM replay WHERE id = ?").use {
            it.bindLong(1, id)
            it.bindLong(2, id)
            if (it.step() && !it.isNull(0)) it.getText(0) else null
        }
    }

    /** Every Sports replay, in the Provider's order; with [withTeams], only those whose names give two teams. */
    fun replays(withTeams: Boolean = false): List<Replay> = synchronized(reader) {
        reader.prepare("SELECT $REPLAY_COLUMNS FROM replay" + (if (withTeams) " WHERE away IS NOT NULL" else "") + " ORDER BY position").use {
            buildList { while (it.step()) add(it.replay()) }
        }
    }

    /** The Sports replays whose Listing ids are among [ids], by id. */
    fun replays(ids: Collection<Long>): Map<Long, Replay> = synchronized(reader) {
        if (ids.isEmpty()) return emptyMap()
        ids.distinct().chunked(MAX_BOUND).flatMap { chunk ->
            reader.prepare("SELECT $REPLAY_COLUMNS FROM replay WHERE id IN (${chunk.joinToString(",") { "?" }})").use {
                chunk.forEachIndexed { i, id -> it.bindLong(i + 1, id) }
                buildList { while (it.step()) add(it.replay()) }
            }
        }.associateBy { it.listingId }
    }

    /** The Sports replays with two teams dated from [from] to [to], both included, in the Provider's order. */
    fun replaysDated(from: LocalDate, to: LocalDate): List<Replay> = synchronized(reader) {
        reader.prepare("SELECT $REPLAY_COLUMNS FROM replay WHERE away IS NOT NULL AND date >= ? AND date <= ? ORDER BY position").use {
            it.bindText(1, from.toString())
            it.bindText(2, to.toString())
            buildList { while (it.step()) add(it.replay()) }
        }
    }

    /** Gives [each] every Sports replay's Listing id, name and day (the date in its name, or the first day of its year, or null), in the Provider's order, a page at a time like [forEachChannel]. */
    fun forEachReplay(each: (id: Long, name: String, day: LocalDate?) -> Unit) {
        var after = 0L
        while (true) {
            val page = synchronized(reader) {
                reader.prepare("SELECT position, id, name, date, year FROM replay WHERE position > ? ORDER BY position LIMIT $SCAN_PAGE").use {
                    it.bindLong(1, after)
                    buildList {
                        while (it.step()) {
                            val day = if (!it.isNull(3)) LocalDate.parse(it.getText(3)) else if (!it.isNull(4)) LocalDate.of(it.getLong(4).toInt(), 1, 1) else null
                            add(Pair(it.getLong(0), Triple(it.getLong(1), it.getText(2), day)))
                        }
                    }
                }
            }
            page.forEach { (_, replay) -> each(replay.first, replay.second, replay.third) }
            if (page.size < SCAN_PAGE) return
            after = page.last().first
        }
    }

    private fun SQLiteStatement.replay() = Replay(
        listingId = getLong(0),
        name = getText(1),
        category = getText(2),
        added = if (isNull(3)) null else Instant.ofEpochSecond(getLong(3)),
        away = if (isNull(4)) null else getText(4),
        home = if (isNull(5)) null else getText(5),
        date = if (isNull(6)) null else LocalDate.parse(getText(6)),
        year = if (isNull(7)) null else getLong(7).toInt(),
    )

    private fun SQLiteStatement.listing(from: Int): StoredListing {
        val details = details(from + 5)
        return StoredListing(
            id = getLong(from),
            name = getText(from + 1),
            category = getText(from + 2),
            added = if (isNull(from + 3)) null else getLong(from + 3),
            image = if (isNull(from + 4)) null else getText(from + 4),
            rating = details.rating,
            details = details.takeUnless { it.isEmpty },
        )
    }

    /** The details columns ([DETAILS_COLUMNS], in order) read from [from]. */
    private fun SQLiteStatement.details(from: Int): ProviderDetails {
        fun text(i: Int) = if (isNull(from + i)) null else getText(from + i)
        val backdrops = text(6)?.let { json -> (runCatching { JsonReader.of(Buffer().writeUtf8(json)).readJsonValue() }.getOrNull() as? List<*>).orEmpty().filterIsInstance<String>() }.orEmpty()
        return ProviderDetails(
            plot = text(1),
            genre = text(2),
            releaseDate = text(5),
            director = text(4),
            cast = text(3),
            rating = if (isNull(from)) null else getDouble(from),
            backdrop = backdrops.firstOrNull(),
            trailer = text(7),
            backdrops = backdrops,
        )
    }

    /**
     * Trending's feed (#477) the cloud last gave in App [language] (ISO 639-1), summed over its week:
     * each wiki's article names with their views, and when; null before it ever did.
     */
    fun trending(language: String): StoredTrending? = synchronized(reader) {
        reader.prepare("SELECT body, fetched_at FROM trending WHERE language = ?").use {
            it.bindText(1, language)
            if (!it.step()) return@use null
            val body = JsonReader.of(Buffer().writeUtf8(it.getText(0))).use { json -> json.readJsonValue() } as? Map<*, *>
            val views = body.orEmpty().mapNotNull { (wiki, articles) ->
                (wiki as? String)?.let { name -> name to (articles as? Map<*, *>).orEmpty().mapNotNull { (k, v) -> (k as? String)?.let { a -> (v as? Number)?.let { a to it.toLong() } } }.toMap() }
            }.toMap()
            StoredTrending(views, Instant.ofEpochMilli(it.getLong(1)))
        }
    }

    fun saveTrending(language: String, views: Map<String, Map<String, Long>>, at: Instant) = synchronized(writer) {
        val body = Buffer()
        JsonWriter.of(body).use { json ->
            json.beginObject()
            views.forEach { (wiki, articles) ->
                json.name(wiki).beginObject()
                articles.forEach { (article, count) -> json.name(article).value(count) }
                json.endObject()
            }
            json.endObject()
        }
        writer.prepare("INSERT OR REPLACE INTO trending VALUES (?, ?, ?)").use {
            it.bindText(1, language)
            it.bindText(2, body.readUtf8())
            it.bindLong(3, at.toEpochMilli())
            it.step()
        }
        Unit
    }

    /**
     * The Titles whose [wiki] (`en`, `fr`) Wikipedia article is one of [names], as Wikidata names it
     * (the landed Title facts), by article name. A wiki the facts don't name articles in has none.
     */
    fun titlesByArticle(wiki: String, names: Collection<String>): Map<String, List<TitleId>> = synchronized(reader) {
        val column = when (wiki) {
            "en" -> "enwiki"
            "fr" -> "frwiki"
            else -> return@synchronized emptyMap()
        }
        buildMap<String, MutableList<TitleId>> {
            names.distinct().chunked(MAX_BOUND).forEach { chunk ->
                reader.prepare("SELECT title_key, $column FROM title_facts WHERE $column IN (${chunk.joinToString(",") { "?" }})").use {
                    chunk.forEachIndexed { i, name -> it.bindText(i + 1, name) }
                    while (it.step()) getOrPut(it.getText(1)) { mutableListOf() } += titleId(it.getText(0))
                }
            }
        }
    }

    /** The Charts the cloud last gave for [region] in [language] (ISO 639-1), as JSON, and when; null before it ever did. */
    fun charts(region: String, language: String): StoredCharts? = synchronized(reader) {
        reader.prepare("SELECT body, fetched_at FROM chart WHERE region = ? AND language = ?").use {
            it.bindText(1, region)
            it.bindText(2, language)
            if (it.step()) StoredCharts(it.getText(0), Instant.ofEpochMilli(it.getLong(1))) else null
        }
    }

    fun saveCharts(region: String, language: String, body: String, at: Instant) = synchronized(writer) {
        writer.prepare("INSERT OR REPLACE INTO chart VALUES (?, ?, ?, ?)").use {
            it.bindText(1, region)
            it.bindText(2, language)
            it.bindText(3, body)
            it.bindLong(4, at.toEpochMilli())
            it.step()
        }
        Unit
    }

    /** The Games the cloud last gave for each of [leagues], as JSON, and when; a League it never did is absent. */
    fun games(leagues: List<String>): Map<String, StoredGames> = synchronized(reader) {
        reader.prepare("SELECT body, fetched_at FROM game WHERE league = ?").use { statement ->
            leagues.mapNotNull { league ->
                statement.reset()
                statement.bindText(1, league)
                if (statement.step()) league to StoredGames(statement.getText(0), Instant.ofEpochMilli(statement.getLong(1))) else null
            }.toMap()
        }
    }

    fun saveGames(league: String, body: String, at: Instant) = synchronized(writer) {
        writer.prepare("INSERT OR REPLACE INTO game VALUES (?, ?, ?)").use {
            it.bindText(1, league)
            it.bindText(2, body)
            it.bindLong(3, at.toEpochMilli())
            it.step()
        }
        Unit
    }

    /** Any cached metadata of [id], in whichever language was fetched last; null when none is. */
    fun anyMetadata(id: TitleId.Tmdb): String? = synchronized(reader) {
        reader.prepare("SELECT body FROM title_metadata WHERE title_key = ? AND body IS NOT NULL ORDER BY fetched_at DESC LIMIT 1").use {
            it.bindText(1, key(id))
            if (it.step()) it.getText(0) else null
        }
    }

    /** The cached metadata of [ids] in [language] (ISO 639-1), by Title. A null body means TMDB has no such Title. */
    fun metadata(ids: List<TitleId.Tmdb>, language: String): Map<TitleId.Tmdb, StoredMetadata> = synchronized(reader) {
        if (ids.isEmpty()) return emptyMap()
        val byKey = ids.associateBy(::key)
        reader.prepare(
            "SELECT title_key, body, fetched_at FROM title_metadata WHERE language = ? AND title_key IN (${ids.joinToString(",") { "?" }})",
        ).use {
            it.bindText(1, language)
            byKey.keys.forEachIndexed { i, key -> it.bindText(i + 2, key) }
            buildMap {
                while (it.step()) {
                    put(byKey.getValue(it.getText(0)), StoredMetadata(if (it.isNull(1)) null else it.getText(1), Instant.ofEpochMilli(it.getLong(2))))
                }
            }
        }
    }

    /**
     * What Wikidata knows of [ids] (#476), as landed: a Title mapped to null is one Wikidata has no item
     * for; one absent was never answered, or its answer waits to land.
     */
    fun titleFacts(ids: Collection<TitleId>): Map<TitleId, TitleFacts?> = synchronized(reader) {
        buildMap {
            ids.distinct().filterIsInstance<TitleId.Tmdb>().chunked(MAX_BOUND).forEach { chunk ->
                val byKey = chunk.associateBy(::key)
                reader.prepare(
                    "SELECT title_key, found, original_language, imdb_id, enwiki, frwiki FROM title_facts WHERE title_key IN (${chunk.joinToString(",") { "?" }})",
                ).use {
                    byKey.keys.forEachIndexed { i, key -> it.bindText(i + 1, key) }
                    while (it.step()) {
                        val facts = if (it.getLong(1) == 0L) null else TitleFacts(it.textOrNull(2), it.textOrNull(3), it.textOrNull(4), it.textOrNull(5))
                        put(byKey.getValue(it.getText(0)), facts)
                    }
                }
            }
        }
    }

    /** [titleFacts] of one Title: null when Wikidata has no item for it, or it was never answered. */
    fun titleFacts(id: TitleId): TitleFacts? = titleFacts(listOf(id))[id]

    /** The Title numbers of the catalog on screen with no facts, landed or waiting, in list order: what to ask the cloud for. */
    fun titlesWithoutFacts(): List<TitleId.Tmdb> = synchronized(reader) {
        reader.prepare(
            "SELECT key FROM title WHERE key NOT LIKE '%/listing/%' AND key NOT IN (SELECT title_key FROM title_facts) " +
                "AND key NOT IN (SELECT title_key FROM title_facts_pending) ORDER BY kind, position",
        ).use {
            buildList { while (it.step()) (titleId(it.getText(0)) as? TitleId.Tmdb)?.let(::add) }
        }
    }

    /** Keeps what the cloud answered at [at] to land later: [found] Titles' facts, and the Titles Wikidata has no item for. */
    fun stageTitleFacts(found: Map<TitleId.Tmdb, TitleFacts>, notFound: List<TitleId.Tmdb>, at: Instant) = synchronized(writer) {
        writer.execSQL("BEGIN")
        try {
            writer.prepare("INSERT OR REPLACE INTO title_facts_pending VALUES (?, ?, ?, ?, ?, ?, ?)").use { insert ->
                (found.entries.map { it.key to it.value } + notFound.map { it to null }).forEach { (id, facts) ->
                    insert.bindText(1, key(id))
                    insert.bindLong(2, if (facts == null) 0 else 1)
                    listOf(facts?.originalLanguage, facts?.imdbId, facts?.enwiki, facts?.frwiki).forEachIndexed { i, value ->
                        if (value == null) insert.bindNull(i + 3) else insert.bindText(i + 3, value)
                    }
                    insert.bindLong(7, at.toEpochMilli())
                    insert.stepAndReset()
                }
            }
            writer.execSQL("COMMIT")
        } catch (e: Throwable) {
            writer.execSQL("ROLLBACK")
            throw e
        }
    }

    /** Lands the facts waiting since [stageTitleFacts]; how many Titles gained an answer. */
    fun landTitleFacts(): Int = synchronized(writer) {
        writer.execSQL("BEGIN")
        try {
            val landed = writer.prepare("SELECT count(*) FROM title_facts_pending").use { it.step(); it.getLong(0).toInt() }
            if (landed > 0) {
                writer.execSQL("INSERT OR REPLACE INTO title_facts SELECT * FROM title_facts_pending")
                writer.execSQL("DELETE FROM title_facts_pending")
            }
            writer.execSQL("COMMIT")
            landed
        } catch (e: Throwable) {
            writer.execSQL("ROLLBACK")
            throw e
        }
    }

    /** Whether any facts wait to land. */
    fun hasPendingTitleFacts(): Boolean = synchronized(reader) {
        reader.prepare("SELECT 1 FROM title_facts_pending LIMIT 1").use { it.step() }
    }

    /** Caches what the cloud answered at [at]: [found] Titles' metadata as JSON, and the Titles TMDB doesn't have. */
    fun saveMetadata(language: String, found: Map<TitleId.Tmdb, String>, notFound: List<TitleId.Tmdb>, at: Instant) = synchronized(writer) {
        writer.execSQL("BEGIN")
        try {
            writer.prepare("INSERT OR REPLACE INTO title_metadata VALUES (?, ?, ?, ?)").use { insert ->
                (found.entries.map { it.key to it.value } + notFound.map { it to null }).forEach { (id, body) ->
                    insert.bindText(1, key(id))
                    insert.bindText(2, language)
                    if (body == null) insert.bindNull(3) else insert.bindText(3, body)
                    insert.bindLong(4, at.toEpochMilli())
                    insert.stepAndReset()
                }
            }
            writer.execSQL("COMMIT")
        } catch (e: Throwable) {
            writer.execSQL("ROLLBACK")
            throw e
        }
    }

    /** The show details kept for these show Listings, by Listing id. */
    fun showDetails(listingIds: List<Long>): Map<Long, StoredShowDetails> = synchronized(reader) {
        if (listingIds.isEmpty()) return emptyMap()
        val marks = listingIds.joinToString(",") { "?" }
        val kept = reader.prepare("SELECT listing_id, modified, fetched_at FROM show_details WHERE listing_id IN ($marks)").use {
            listingIds.forEachIndexed { i, id -> it.bindLong(i + 1, id) }
            buildMap { while (it.step()) put(it.getLong(0), (if (it.isNull(1)) null else it.getLong(1)) to Instant.ofEpochMilli(it.getLong(2))) }
        }
        val episodes = reader.prepare(
            "SELECT listing_id, id, season, number, title, added, width, height, audio_language, container, still, duration FROM episode WHERE listing_id IN ($marks) ORDER BY season, number, id",
        ).use {
            listingIds.forEachIndexed { i, id -> it.bindLong(i + 1, id) }
            val byListing = HashMap<Long, MutableList<ProviderEpisode>>()
            while (it.step()) {
                byListing.getOrPut(it.getLong(0)) { mutableListOf() } += ProviderEpisode(
                    id = it.getLong(1),
                    season = it.getLong(2).toInt(),
                    number = it.getLong(3).toInt(),
                    title = it.getText(4),
                    added = if (it.isNull(5)) null else it.getLong(5),
                    width = if (it.isNull(6)) null else it.getLong(6).toInt(),
                    height = if (it.isNull(7)) null else it.getLong(7).toInt(),
                    audioLanguage = if (it.isNull(8)) null else it.getText(8),
                    container = if (it.isNull(9)) null else it.getText(9),
                    still = if (it.isNull(10)) null else it.getText(10),
                    durationSeconds = if (it.isNull(11)) null else it.getLong(11).toInt(),
                )
            }
            byListing
        }
        kept.mapValues { (id, details) -> StoredShowDetails(details.first, details.second, episodes[id].orEmpty()) }
    }

    /** Keeps a show Listing's show details, given at [at] while the Provider had last changed it at [modified]. */
    fun saveShowDetails(listingId: Long, modified: Long?, episodes: List<ProviderEpisode>, at: Instant) = synchronized(writer) {
        writer.execSQL("BEGIN")
        try {
            writer.prepare("INSERT OR REPLACE INTO show_details VALUES (?, ?, ?)").use {
                it.bindLong(1, listingId)
                if (modified == null) it.bindNull(2) else it.bindLong(2, modified)
                it.bindLong(3, at.toEpochMilli())
                it.step()
            }
            writer.prepare("DELETE FROM episode WHERE listing_id = ?").use {
                it.bindLong(1, listingId)
                it.step()
            }
            writer.prepare("INSERT OR REPLACE INTO episode VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)").use { insert ->
                episodes.forEach { episode ->
                    insert.bindLong(1, listingId)
                    insert.bindLong(2, episode.id)
                    insert.bindLong(3, episode.season.toLong())
                    insert.bindLong(4, episode.number.toLong())
                    insert.bindText(5, episode.title)
                    if (episode.added == null) insert.bindNull(6) else insert.bindLong(6, episode.added)
                    if (episode.width == null) insert.bindNull(7) else insert.bindLong(7, episode.width.toLong())
                    if (episode.height == null) insert.bindNull(8) else insert.bindLong(8, episode.height.toLong())
                    if (episode.audioLanguage == null) insert.bindNull(9) else insert.bindText(9, episode.audioLanguage)
                    if (episode.container == null) insert.bindNull(10) else insert.bindText(10, episode.container)
                    if (episode.still == null) insert.bindNull(11) else insert.bindText(11, episode.still)
                    if (episode.durationSeconds == null) insert.bindNull(12) else insert.bindLong(12, episode.durationSeconds.toLong())
                    insert.stepAndReset()
                }
            }
            writer.execSQL("COMMIT")
        } catch (e: Throwable) {
            writer.execSQL("ROLLBACK")
            throw e
        }
    }

    /** What these movie Listings' files hold, for those read: null for one whose header can't be read. */
    fun fileReads(listingIds: List<Long>): Map<Long, FileContents?> = synchronized(reader) {
        if (listingIds.isEmpty()) return emptyMap()
        reader.prepare("SELECT listing_id, body FROM file_read WHERE listing_id IN (${listingIds.joinToString(",") { "?" }})").use {
            listingIds.forEachIndexed { i, id -> it.bindLong(i + 1, id) }
            buildMap { while (it.step()) put(it.getLong(0), if (it.isNull(1)) null else fileContents(it.getText(1))) }
        }
    }

    /** Keeps what movie Listing [listingId]'s file holds ([body] as JSON, null when it can't be read), and whether the cloud has it. */
    fun saveFileRead(listingId: Long, body: String?, at: Instant, shared: Boolean) = synchronized(writer) {
        writer.prepare("INSERT OR REPLACE INTO file_read VALUES (?, ?, ?, ?)").use {
            it.bindLong(1, listingId)
            if (body == null) it.bindNull(2) else it.bindText(2, body)
            it.bindLong(3, at.toEpochMilli())
            it.bindLong(4, if (shared) 1 else 0)
            it.step()
        }
        Unit
    }

    /** Records that the Viewer stopped at [position] of [duration] in [id] at [at], or finished it when [watched]. */
    fun saveProgress(profile: Long, id: TitleId, position: Long, duration: Long?, watched: Boolean, at: Instant) = synchronized(writer) {
        forgetTombstone(profile, key(id), NO_EPISODE, NO_EPISODE)
        writer.prepare("INSERT OR REPLACE INTO progress VALUES ($profile, ?, ?, ?, ?, ?)").use {
            it.bindText(1, key(id))
            it.bindLong(2, position)
            if (duration == null) it.bindNull(3) else it.bindLong(3, duration)
            it.bindLong(4, if (watched) 1 else 0)
            it.bindLong(5, at.toEpochMilli())
            it.step()
        }
        Unit
    }

    /** Records that the Viewer stopped at [position] of [duration] in Show [id]'s [episode] at [at], or finished it when [watched]. */
    fun saveEpisodeProgress(profile: Long, id: TitleId, episode: EpisodeNumber, position: Long, duration: Long?, watched: Boolean, at: Instant) = synchronized(writer) {
        forgetTombstone(profile, key(id), episode.season, episode.number)
        writer.prepare("INSERT OR REPLACE INTO episode_progress VALUES ($profile, ?, ?, ?, ?, ?, ?, ?)").use {
            it.bindText(1, key(id))
            it.bindLong(2, episode.season.toLong())
            it.bindLong(3, episode.number.toLong())
            it.bindLong(4, position)
            if (duration == null) it.bindNull(5) else it.bindLong(5, duration)
            it.bindLong(6, if (watched) 1 else 0)
            it.bindLong(7, at.toEpochMilli())
            it.step()
        }
        Unit
    }

    /** Where the Viewer stopped in each Episode of Show [id] they played, last played first. */
    fun episodeProgress(profile: Long, id: TitleId): List<StoredEpisodeProgress> = synchronized(reader) {
        reader.prepare("SELECT season, number, position, duration, watched FROM episode_progress WHERE profile = $profile AND title_key = ? ORDER BY updated_at DESC").use {
            it.bindText(1, key(id))
            buildList {
                while (it.step()) add(StoredEpisodeProgress(EpisodeNumber(it.getInt(0), it.getInt(1)), it.getLong(2), if (it.isNull(3)) null else it.getLong(3), it.getLong(4) != 0L))
            }
        }
    }

    /** Keeps what the Viewer chose while [id] played ([body]: TitleChoices as JSON). */
    fun saveTitleChoices(profile: Long, id: TitleId, body: String, at: Instant) = synchronized(writer) {
        writer.prepare("INSERT OR REPLACE INTO title_choices VALUES ($profile, ?, ?, ?)").use {
            it.bindText(1, key(id))
            it.bindText(2, body)
            it.bindLong(3, at.toEpochMilli())
            it.step()
        }
        Unit
    }

    /** What the Viewer chose while [id] last played, as JSON; null when they never chose anything in it. */
    fun titleChoices(profile: Long, id: TitleId): String? = synchronized(reader) {
        reader.prepare("SELECT body FROM title_choices WHERE profile = $profile AND title_key = ?").use {
            it.bindText(1, key(id))
            if (it.step()) it.getText(0) else null
        }
    }

    /** Forgets where the Viewer stopped in [id], and whether they finished it, at [at]: kept as a tombstone for history sync. */
    fun forgetProgress(profile: Long, id: TitleId, at: Instant) = synchronized(writer) {
        writer.prepare("DELETE FROM progress WHERE profile = $profile AND title_key = ?").use {
            it.bindText(1, key(id))
            it.step()
        }
        saveTombstone(profile, key(id), NO_EPISODE, NO_EPISODE, at.toEpochMilli())
    }

    private fun forgetTombstone(profile: Long, titleKey: String, season: Int, number: Int) {
        writer.prepare("DELETE FROM history_tombstone WHERE profile = $profile AND title_key = ? AND season = ? AND number = ?").use {
            it.bindText(1, titleKey)
            it.bindLong(2, season.toLong())
            it.bindLong(3, number.toLong())
            it.step()
        }
    }

    private fun saveTombstone(profile: Long, titleKey: String, season: Int, number: Int, at: Long) {
        writer.prepare("INSERT OR REPLACE INTO history_tombstone VALUES ($profile, ?, ?, ?, ?)").use {
            it.bindText(1, titleKey)
            it.bindLong(2, season.toLong())
            it.bindLong(3, number.toLong())
            it.bindLong(4, at)
            it.step()
        }
    }

    /** Queues [change] to be pushed to the Account, replacing the one queued for its Movie or Episode. */
    fun queueHistory(profile: Long, change: HistoryChange) = synchronized(writer) { writeChange("history_outbox", profile, change) }

    /** Queues all of [profile]'s history, its removals too, as signing in merges it into the Account. */
    fun queueAllHistory(profile: Long) = synchronized(writer) {
        writer.execSQL(
            "INSERT OR REPLACE INTO history_outbox SELECT profile, title_key, $NO_EPISODE, $NO_EPISODE, position, duration, watched, 0, updated_at " +
                "FROM progress WHERE profile = $profile",
        )
        writer.execSQL(
            "INSERT OR REPLACE INTO history_outbox SELECT profile, title_key, season, number, position, duration, watched, 0, updated_at " +
                "FROM episode_progress WHERE profile = $profile",
        )
        writer.execSQL(
            "INSERT OR REPLACE INTO history_outbox SELECT profile, title_key, season, number, 0, NULL, 0, 1, removed_at FROM history_tombstone WHERE profile = $profile",
        )
    }

    /** The changes waiting to be pushed, oldest first, at most [limit]. */
    fun queuedHistory(profile: Long, limit: Int): List<HistoryChange> = synchronized(writer) {
        writer.prepare("SELECT $HISTORY_COLUMNS FROM history_outbox WHERE profile = $profile ORDER BY updated_at LIMIT $limit").use {
            buildList { while (it.step()) add(it.historyChange()) }
        }
    }

    /** Takes [pushed] off the queue, unless their Movie or Episode changed again since. */
    fun unqueueHistory(profile: Long, pushed: List<HistoryChange>) = synchronized(writer) {
        writer.prepare("DELETE FROM history_outbox WHERE profile = $profile AND title_key = ? AND season = ? AND number = ? AND updated_at = ?").use { delete ->
            pushed.forEach {
                delete.bindChangeKey(it)
                delete.bindLong(4, it.at.toEpochMilli())
                delete.stepAndReset()
            }
        }
    }

    /**
     * Keeps what the Account answered until it lands ([applyPulledHistory]), the latest per Movie or
     * Episode. A [reset] answer is everything the Account has: what was pulled before is dropped, and
     * landing it forgets what the device kept that isn't in it.
     */
    fun stagePulledHistory(profile: Long, changes: List<HistoryChange>, reset: Boolean) = synchronized(writer) {
        writer.execSQL("BEGIN")
        try {
            if (reset) {
                writer.execSQL("DELETE FROM history_pulled")
                writeMeta(HISTORY_RESET, "1")
            }
            writer.prepare(
                "INSERT INTO history_pulled VALUES ($profile, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (profile, title_key, season, number) DO UPDATE SET " +
                    "position = excluded.position, duration = excluded.duration, watched = excluded.watched, removed = excluded.removed, " +
                    "updated_at = excluded.updated_at WHERE excluded.updated_at > history_pulled.updated_at",
            ).use { insert -> changes.forEach { insert.bindChange(it); insert.stepAndReset() } }
            writer.execSQL("COMMIT")
        } catch (e: Throwable) {
            if (writer.inTransaction()) writer.execSQL("ROLLBACK")
            throw e
        }
    }

    /** Whether the Account's changes wait to land: any, or [id]'s. Read apart from the writer, which a Sync may hold for minutes. */
    fun hasPulledHistory(profile: Long, id: TitleId? = null): Boolean = synchronized(reader) { hasPulled(profile, id) }

    /** Whether a reset answer waits to land ([stagePulledHistory]): every Profile's history not in it is forgotten. */
    fun historyResetPending(): Boolean = meta(HISTORY_RESET) != null

    fun clearHistoryReset() = synchronized(writer) { deleteMeta(HISTORY_RESET) }

    private fun hasPulled(profile: Long, id: TitleId?): Boolean =
        reader.prepare("SELECT 1 FROM history_pulled WHERE profile = $profile ${if (id == null) "" else "AND title_key = ?"} LIMIT 1").use {
            if (id != null) it.bindText(1, key(id))
            it.step()
        }

    /**
     * Lands the Account's changes waiting for [profile] (only [id]'s, unless [reset]), each where it
     * is later than what the device has for its Movie or Episode, a removal as a removal. A [reset]
     * first forgets what the device kept that the Account's answer hasn't, unless it waits to be
     * pushed. Says whether anything the screens show changed.
     */
    fun applyPulledHistory(profile: Long, id: TitleId? = null, reset: Boolean = false): Boolean = synchronized(writer) {
        val only = if (reset) null else id?.let(::key)
        writer.execSQL("BEGIN")
        try {
            var changed = false
            if (reset) {
                for (table in listOf("progress", "episode_progress")) {
                    val episode = if (table == "progress") "$NO_EPISODE = p.season AND $NO_EPISODE = p.number" else "t.season = p.season AND t.number = p.number"
                    val outbox = episode.replace("p.", "o.")
                    writer.execSQL(
                        "DELETE FROM $table AS t WHERE profile = $profile " +
                            "AND NOT EXISTS (SELECT 1 FROM history_pulled p WHERE p.profile = $profile AND p.title_key = t.title_key AND $episode AND p.removed = 0) " +
                            "AND NOT EXISTS (SELECT 1 FROM history_outbox o WHERE o.profile = $profile AND o.title_key = t.title_key AND $outbox)",
                    )
                    changed = changed || writerChanges() > 0
                }
            }
            val pulled = writer.prepare("SELECT $HISTORY_COLUMNS FROM history_pulled WHERE profile = $profile ${if (only == null) "" else "AND title_key = ?"}").use {
                if (only != null) it.bindText(1, only)
                buildList { while (it.step()) add(it.historyChange()) }
            }
            for (change in pulled) {
                val titleKey = key(change.id)
                val season = change.episode?.season ?: NO_EPISODE
                val number = change.episode?.number ?: NO_EPISODE
                val local = if (change.episode == null) {
                    writer.prepare("SELECT updated_at FROM progress WHERE profile = $profile AND title_key = ?").use {
                        it.bindText(1, titleKey)
                        if (it.step()) it.getLong(0) else null
                    }
                } else {
                    writer.prepare("SELECT updated_at FROM episode_progress WHERE profile = $profile AND title_key = ? AND season = ? AND number = ?").use {
                        it.bindText(1, titleKey)
                        it.bindLong(2, season.toLong())
                        it.bindLong(3, number.toLong())
                        if (it.step()) it.getLong(0) else null
                    }
                }
                val removedAt = writer.prepare("SELECT removed_at FROM history_tombstone WHERE profile = $profile AND title_key = ? AND season = ? AND number = ?").use {
                    it.bindText(1, titleKey)
                    it.bindLong(2, season.toLong())
                    it.bindLong(3, number.toLong())
                    if (it.step()) it.getLong(0) else null
                }
                val at = change.at.toEpochMilli()
                if (at > maxOf(local ?: Long.MIN_VALUE, removedAt ?: Long.MIN_VALUE)) {
                    if (change.removed) {
                        val table = if (change.episode == null) "progress" else "episode_progress"
                        val episode = if (change.episode == null) "" else "AND season = $season AND number = $number"
                        writer.prepare("DELETE FROM $table WHERE profile = $profile AND title_key = ? $episode").use {
                            it.bindText(1, titleKey)
                            it.step()
                        }
                        saveTombstone(profile, titleKey, season, number, at)
                        changed = changed || local != null
                    } else {
                        forgetTombstone(profile, titleKey, season, number)
                        if (change.episode == null) {
                            writer.prepare("INSERT OR REPLACE INTO progress VALUES ($profile, ?, ?, ?, ?, ?)").use {
                                it.bindText(1, titleKey)
                                it.bindLong(2, change.position)
                                if (change.duration == null) it.bindNull(3) else it.bindLong(3, change.duration)
                                it.bindLong(4, if (change.watched) 1 else 0)
                                it.bindLong(5, at)
                                it.step()
                            }
                        } else {
                            writer.prepare("INSERT OR REPLACE INTO episode_progress VALUES ($profile, ?, ?, ?, ?, ?, ?, ?)").use {
                                it.bindText(1, titleKey)
                                it.bindLong(2, season.toLong())
                                it.bindLong(3, number.toLong())
                                it.bindLong(4, change.position)
                                if (change.duration == null) it.bindNull(5) else it.bindLong(5, change.duration)
                                it.bindLong(6, if (change.watched) 1 else 0)
                                it.bindLong(7, at)
                                it.step()
                            }
                        }
                        changed = true
                    }
                }
                writer.prepare("DELETE FROM history_pulled WHERE profile = $profile AND title_key = ? AND season = ? AND number = ? AND updated_at = ?").use {
                    it.bindChangeKey(change)
                    it.bindLong(4, at)
                    it.step()
                }
            }
            writer.execSQL("COMMIT")
            changed
        } catch (e: Throwable) {
            if (writer.inTransaction()) writer.execSQL("ROLLBACK")
            throw e
        }
    }

    /** Forgets removals made before [before]: the Account keeps its own as long. */
    fun forgetTombstones(before: Instant) = synchronized(writer) {
        writer.execSQL("DELETE FROM history_tombstone WHERE removed_at < ${before.toEpochMilli()}")
    }

    /** The cursor the last sync with the Account got, for which Provider key, and whether this TV's data is merged into it yet; null before. */
    fun syncState(): SyncState? {
        val cursor = meta(SYNC_CURSOR)?.toLongOrNull() ?: return null
        return SyncState(cursor, meta(SYNC_PROVIDER), meta(SYNC_LINKED) != null)
    }

    fun forgetSyncState() = synchronized(writer) { listOf(SYNC_CURSOR, SYNC_PROVIDER, SYNC_LINKED).forEach(::deleteMeta) }

    fun saveSyncState(state: SyncState) = synchronized(writer) {
        writeMeta(SYNC_CURSOR, state.cursor.toString())
        if (state.provider == null) deleteMeta(SYNC_PROVIDER) else writeMeta(SYNC_PROVIDER, state.provider)
        if (state.linked) writeMeta(SYNC_LINKED, "1") else deleteMeta(SYNC_LINKED)
    }

    /**
     * Takes the Account's data off the device, as signing out does (#440): every Profile with its
     * history, Favourites, marks, settings and choices, the household's Region and PIN, and what waits
     * to sync. The device is left one new Profile, unnamed, as a device that never had any; the catalog
     * and the Provider login stay. Gives the new Profile's id.
     */
    fun forgetAccountData(at: Instant): Long = synchronized(writer) {
        writer.execSQL("BEGIN")
        try {
            PROFILE_TABLES.forEach { (table, _) -> writer.execSQL("DELETE FROM $table") }
            for (table in listOf("profile", "household", "household_pin", "sync_outbox", "sync_pulled")) writer.execSQL("DELETE FROM $table")
            listOf(SYNC_CURSOR, SYNC_PROVIDER, SYNC_LINKED, HISTORY_RESET).forEach(::deleteMeta)
            val id = addProfile(null, kids = false, at)
            writer.execSQL("UPDATE profile SET used_at = ${at.toEpochMilli()}, changed_at = 0 WHERE id = $id")
            writer.execSQL("COMMIT")
            id
        } catch (e: Throwable) {
            if (writer.inTransaction()) writer.execSQL("ROLLBACK")
            throw e
        }
    }

    /** Queues [entry] for the Account, kept with [provider] ('' for one the same on every Provider), replacing the one queued for its key. */
    fun queueEntry(entry: SyncEntry, provider: String) = synchronized(writer) {
        writer.prepare("INSERT OR REPLACE INTO sync_outbox VALUES (?, ?, ?, ?, ?, ?)").use {
            it.bindText(1, entry.kind)
            it.bindText(2, entry.profile.orEmpty())
            it.bindText(3, provider)
            it.bindText(4, entry.key)
            if (entry.value == null) it.bindNull(5) else it.bindText(5, entry.value)
            it.bindLong(6, entry.at.toEpochMilli())
            it.step()
        }
        Unit
    }

    /** The entries waiting to be pushed with Provider key [provider] (or none), oldest first, at most [limit]. */
    fun queuedEntries(provider: String?, limit: Int): List<SyncEntry> = synchronized(writer) {
        writer.prepare("SELECT kind, profile, key, value, at FROM sync_outbox WHERE provider IN ('', ?) ORDER BY at LIMIT $limit").use {
            it.bindText(1, provider.orEmpty())
            buildList { while (it.step()) add(it.syncEntry()) }
        }
    }

    /** When the entry of [kind] and [key] queued for [profile] was made, if one waits: it is later than anything the Account answered meanwhile. */
    fun queuedEntryAt(kind: String, profile: String?, key: String): Instant? = synchronized(writer) {
        writer.prepare("SELECT max(at) FROM sync_outbox WHERE kind = ? AND profile = ? AND key = ?").use {
            it.bindText(1, kind)
            it.bindText(2, profile.orEmpty())
            it.bindText(3, key)
            if (it.step() && !it.isNull(0)) Instant.ofEpochMilli(it.getLong(0)) else null
        }
    }

    /** Takes [pushed] off the queue, unless they changed again since. */
    fun unqueueEntries(pushed: List<SyncEntry>) = synchronized(writer) {
        writer.prepare("DELETE FROM sync_outbox WHERE kind = ? AND profile = ? AND key = ? AND at = ?").use { delete ->
            pushed.forEach {
                delete.bindText(1, it.kind)
                delete.bindText(2, it.profile.orEmpty())
                delete.bindText(3, it.key)
                delete.bindLong(4, it.at.toEpochMilli())
                delete.stepAndReset()
            }
        }
    }

    /** Forgets what waits to be pushed for the Profile [uid], once it is deleted: all but its deletion, unless [all]. */
    fun unqueueProfile(uid: String, all: Boolean = false) = synchronized(writer) {
        writer.prepare("DELETE FROM sync_outbox WHERE profile = ? ${if (all) "" else "AND kind != '${SyncEntry.PROFILE}'"}").use {
            it.bindText(1, uid)
            it.step()
        }
        Unit
    }

    /** Keeps the Account's [entries] until they land between screens, the latest per key. */
    fun stagePulledEntries(entries: List<SyncEntry>) = synchronized(writer) {
        writer.prepare(
            "INSERT INTO sync_pulled VALUES (?, ?, ?, ?, ?) ON CONFLICT (kind, profile, key) DO UPDATE SET value = excluded.value, at = excluded.at " +
                "WHERE excluded.at >= sync_pulled.at",
        ).use { insert ->
            entries.forEach {
                insert.bindText(1, it.kind)
                insert.bindText(2, it.profile.orEmpty())
                insert.bindText(3, it.key)
                if (it.value == null) insert.bindNull(4) else insert.bindText(4, it.value)
                insert.bindLong(5, it.at.toEpochMilli())
                insert.stepAndReset()
            }
        }
    }

    /** Whether any of the Account's entries waits to land. Read apart from the writer, which a Sync may hold for minutes. */
    fun hasPulledEntries(): Boolean = synchronized(reader) { reader.prepare("SELECT 1 FROM sync_pulled LIMIT 1").use { it.step() } }

    /** The Account's entries waiting to land, Profiles first, each kind oldest first. */
    fun pulledEntries(): List<SyncEntry> = synchronized(writer) {
        writer.prepare("SELECT kind, profile, key, value, at FROM sync_pulled ORDER BY kind != '${SyncEntry.PROFILE}', at").use {
            buildList { while (it.step()) add(it.syncEntry()) }
        }
    }

    fun unstagePulledEntry(entry: SyncEntry) = synchronized(writer) {
        writer.prepare("DELETE FROM sync_pulled WHERE kind = ? AND profile = ? AND key = ? AND at = ?").use {
            it.bindText(1, entry.kind)
            it.bindText(2, entry.profile.orEmpty())
            it.bindText(3, entry.key)
            it.bindLong(4, entry.at.toEpochMilli())
            it.step()
        }
        Unit
    }

    private fun SQLiteStatement.syncEntry(): SyncEntry =
        SyncEntry(getText(0), getText(1).ifEmpty { null }, getText(2), if (isNull(3)) null else getText(3), Instant.ofEpochMilli(getLong(4)))

    /** [profile]'s settings as the app wrote them, by name, those taken back left out. */
    fun profileSettings(profile: Long): Map<String, String> = synchronized(reader) {
        reader.prepare("SELECT key, value FROM profile_setting WHERE profile = $profile AND value IS NOT NULL").use {
            buildMap { while (it.step()) put(it.getText(0), it.getText(1)) }
        }
    }

    /** [profile]'s settings with when each changed, those taken back as null. */
    fun profileSettingRows(profile: Long): List<Triple<String, String?, Long>> = synchronized(reader) {
        reader.prepare("SELECT key, value, updated_at FROM profile_setting WHERE profile = $profile").use {
            buildList { while (it.step()) add(Triple(it.getText(0), if (it.isNull(1)) null else it.getText(1), it.getLong(2))) }
        }
    }

    /**
     * Keeps [profile]'s setting [key] as [value] (null: taken back), changed at [at], unless [onlyIfLater]
     * and it changed later already. Says whether it kept it.
     */
    fun saveProfileSetting(profile: Long, key: String, value: String?, at: Instant, onlyIfLater: Boolean = false): Boolean = synchronized(writer) {
        writer.prepare(
            "INSERT INTO profile_setting VALUES ($profile, ?, ?, ?) ON CONFLICT (profile, key) DO UPDATE SET value = excluded.value, updated_at = excluded.updated_at " +
                if (onlyIfLater) "WHERE excluded.updated_at > profile_setting.updated_at" else "",
        ).use {
            it.bindText(1, key)
            if (value == null) it.bindNull(2) else it.bindText(2, value)
            it.bindLong(3, at.toEpochMilli())
            it.step()
        }
        writerChanges() > 0
    }

    /** Keeps the settings an older app kept for [profile] in its own file, unless the Profile has its own since: the oldest change, so the Account's win. */
    fun importProfileSettings(profile: Long, values: Map<String, String>) = synchronized(writer) {
        writer.prepare("INSERT OR IGNORE INTO profile_setting VALUES ($profile, ?, ?, 1)").use { insert ->
            values.forEach { (key, value) ->
                insert.bindText(1, key)
                insert.bindText(2, value)
                insert.stepAndReset()
            }
        }
    }

    /** The household's setting [key] (the Region) and when it changed; null when never set. */
    fun household(key: String): Pair<String?, Instant>? = synchronized(reader) {
        reader.prepare("SELECT value, updated_at FROM household WHERE key = ?").use {
            it.bindText(1, key)
            if (it.step()) (if (it.isNull(0)) null else it.getText(0)) to Instant.ofEpochMilli(it.getLong(1)) else null
        }
    }

    fun householdRows(): List<Triple<String, String?, Long>> = synchronized(reader) {
        reader.prepare("SELECT key, value, updated_at FROM household").use {
            buildList { while (it.step()) add(Triple(it.getText(0), if (it.isNull(1)) null else it.getText(1), it.getLong(2))) }
        }
    }

    /** Keeps the household's [key] as [value] at [at]; with [onlyIfLater], not over a later change; with [unlessKept], not over any. Says whether it kept it. */
    fun saveHousehold(key: String, value: String?, at: Instant, onlyIfLater: Boolean = false, unlessKept: Boolean = false): Boolean = synchronized(writer) {
        val conflict = when {
            unlessKept -> "ON CONFLICT (key) DO NOTHING"
            else -> "ON CONFLICT (key) DO UPDATE SET value = excluded.value, updated_at = excluded.updated_at" +
                if (onlyIfLater) " WHERE excluded.updated_at > household.updated_at" else ""
        }
        writer.prepare("INSERT INTO household VALUES (?, ?, ?) $conflict").use {
            it.bindText(1, key)
            if (value == null) it.bindNull(2) else it.bindText(2, value)
            it.bindLong(3, at.toEpochMilli())
            it.step()
        }
        writerChanges() > 0
    }

    /** [profile]'s Favourites by Channel key, with when each was added. */
    fun favouriteRows(profile: Long): List<Pair<String, Long>> = synchronized(reader) {
        reader.prepare("SELECT key, added_at FROM favourite WHERE profile = $profile").use { buildList { while (it.step()) add(it.getText(0) to it.getLong(1)) } }
    }

    /** [profile]'s "Not this game" marks: Game key, Channel key, when. */
    fun notThisGameRows(profile: Long): List<Triple<String, String, Long>> = synchronized(reader) {
        reader.prepare("SELECT game_key, channel_key, dropped_at FROM not_this_game WHERE profile = $profile").use {
            buildList { while (it.step()) add(Triple(it.getText(0), it.getText(1), it.getLong(2))) }
        }
    }

    /** Keeps or forgets the mark of [channelKey] for [gameKey], as another TV of the Account made or took it back. */
    fun setFeedDropped(profile: Long, gameKey: String, channelKey: String, dropped: Boolean, at: Instant) = synchronized(writer) {
        writer.prepare(if (dropped) "INSERT OR REPLACE INTO not_this_game VALUES ($profile, ?, ?, ?)" else "DELETE FROM not_this_game WHERE profile = $profile AND game_key = ? AND channel_key = ?").use {
            it.bindText(1, gameKey)
            it.bindText(2, channelKey)
            if (dropped) it.bindLong(3, at.toEpochMilli())
            it.step()
        }
        Unit
    }

    /** [profile]'s player choices by Title key: the JSON and when it changed. */
    fun titleChoiceRows(profile: Long): List<Triple<String, String, Long>> = synchronized(reader) {
        reader.prepare("SELECT title_key, body, updated_at FROM title_choices WHERE profile = $profile").use {
            buildList { while (it.step()) add(Triple(it.getText(0), it.getText(1), it.getLong(2))) }
        }
    }

    /** Keeps the choices another TV made for the Title keyed [titleKey], unless this one chose later. Says whether it kept them. */
    fun saveTitleChoicesIfLater(profile: Long, titleKey: String, body: String, at: Instant): Boolean = synchronized(writer) {
        writer.prepare(
            "INSERT INTO title_choices VALUES ($profile, ?, ?, ?) ON CONFLICT (profile, title_key) DO UPDATE SET body = excluded.body, updated_at = excluded.updated_at " +
                "WHERE excluded.updated_at > title_choices.updated_at",
        ).use {
            it.bindText(1, titleKey)
            it.bindText(2, body)
            it.bindLong(3, at.toEpochMilli())
            it.step()
        }
        writerChanges() > 0
    }

    /** [profile]'s remembered tracks by kind. */
    fun rememberedTracks(profile: Long): Map<String, String> = synchronized(reader) {
        reader.prepare("SELECT kind, value FROM remembered_track WHERE profile = $profile").use { buildMap { while (it.step()) put(it.getText(0), it.getText(1)) } }
    }

    fun forgetRememberedTrack(profile: Long, kind: String) = synchronized(writer) {
        writer.prepare("DELETE FROM remembered_track WHERE profile = $profile AND kind = ?").use {
            it.bindText(1, kind)
            it.step()
        }
        Unit
    }

    private fun writeChange(table: String, profile: Long, change: HistoryChange) {
        writer.prepare("INSERT OR REPLACE INTO $table VALUES ($profile, ?, ?, ?, ?, ?, ?, ?, ?)").use {
            it.bindChange(change)
            it.step()
        }
    }

    private fun SQLiteStatement.bindChangeKey(change: HistoryChange) {
        bindText(1, key(change.id))
        bindLong(2, (change.episode?.season ?: NO_EPISODE).toLong())
        bindLong(3, (change.episode?.number ?: NO_EPISODE).toLong())
    }

    private fun SQLiteStatement.bindChange(change: HistoryChange) {
        bindChangeKey(change)
        bindLong(4, change.position)
        if (change.duration == null) bindNull(5) else bindLong(5, change.duration)
        bindLong(6, if (change.watched) 1 else 0)
        bindLong(7, if (change.removed) 1 else 0)
        bindLong(8, change.at.toEpochMilli())
    }

    private fun SQLiteStatement.historyChange(): HistoryChange {
        val season = getInt(1)
        return HistoryChange(
            titleId(getText(0)),
            if (season == NO_EPISODE) null else EpisodeNumber(season, getInt(2)),
            getLong(3),
            if (isNull(4)) null else getLong(4),
            getLong(5) != 0L,
            getLong(6) != 0L,
            Instant.ofEpochMilli(getLong(7)),
        )
    }

    private fun writerMeta(key: String): String? = writer.prepare("SELECT value FROM meta WHERE key = ?").use {
        it.bindText(1, key)
        if (it.step()) it.getText(0) else null
    }

    private fun writeMeta(key: String, value: String) = writer.prepare("INSERT OR REPLACE INTO meta VALUES (?, ?)").use {
        it.bindText(1, key)
        it.bindText(2, value)
        it.step()
    }

    private fun deleteMeta(key: String) = writer.prepare("DELETE FROM meta WHERE key = ?").use {
        it.bindText(1, key)
        it.step()
    }

    private fun writerChanges(): Long = writer.prepare("SELECT changes()").use { it.step(); it.getLong(0) }

    /** The file reads the cloud doesn't have yet, as JSON by Listing id. */
    fun unsharedFileReads(): Map<Long, String> = synchronized(reader) {
        reader.prepare("SELECT listing_id, body FROM file_read WHERE shared = 0 AND body IS NOT NULL").use {
            buildMap { while (it.step()) put(it.getLong(0), it.getText(1)) }
        }
    }

    /**
     * The name and Category whose Prefix and hints a file read of each of [ids] reports: a movie
     * Listing's or Sports replay's own, an Episode's Show's. Ids the catalog no longer has are left out.
     */
    fun fileReadSources(ids: Collection<Long>): Map<Long, Pair<String, String>> = synchronized(reader) {
        if (ids.isEmpty()) return emptyMap()
        val marks = ids.joinToString(",") { "?" }
        reader.prepare(
            "SELECT id, name, category FROM listing WHERE kind = 'movie' AND id IN ($marks) " +
                "UNION ALL SELECT id, name, category FROM replay WHERE id IN ($marks) " +
                "UNION ALL SELECT e.id, l.name, l.category FROM episode e JOIN listing l ON l.kind = 'show' AND l.id = e.listing_id WHERE e.id IN ($marks)",
        ).use { query ->
            var i = 1
            repeat(3) { ids.forEach { id -> query.bindLong(i++, id) } }
            buildMap { while (query.step()) putIfAbsent(query.getLong(0), query.getText(1) to query.getText(2)) }
        }
    }

    fun markFileReadsShared(listingIds: Collection<Long>) = synchronized(writer) {
        writer.prepare("UPDATE file_read SET shared = 1 WHERE listing_id = ?").use { update ->
            listingIds.forEach {
                update.bindLong(1, it)
                update.stepAndReset()
            }
        }
    }

    /** The bitrates the Provider's movie details gave for these Listings, for those asked: null when they gave none. */
    fun movieBitrates(listingIds: List<Long>): Map<Long, Long?> = synchronized(reader) {
        if (listingIds.isEmpty()) return emptyMap()
        reader.prepare("SELECT listing_id, bitrate FROM movie_details WHERE listing_id IN (${listingIds.joinToString(",") { "?" }})").use {
            listingIds.forEachIndexed { i, id -> it.bindLong(i + 1, id) }
            buildMap { while (it.step()) put(it.getLong(0), if (it.isNull(1)) null else it.getLong(1)) }
        }
    }

    /** The Provider's movie details kept for these movie Listings, by Listing id, for those asked. */
    fun movieDetails(listingIds: List<Long>): Map<Long, StoredMovieDetails> = synchronized(reader) {
        if (listingIds.isEmpty()) return emptyMap()
        reader.prepare(
            "SELECT listing_id, bitrate, fetched_at, added, runtime, $DETAILS_COLUMN_NAMES FROM movie_details WHERE listing_id IN (${listingIds.joinToString(",") { "?" }})",
        ).use {
            listingIds.forEachIndexed { i, id -> it.bindLong(i + 1, id) }
            buildMap {
                while (it.step()) {
                    put(
                        it.getLong(0),
                        StoredMovieDetails(
                            bitrate = if (it.isNull(1)) null else it.getLong(1),
                            fetchedAt = Instant.ofEpochMilli(it.getLong(2)),
                            added = if (it.isNull(3)) null else it.getLong(3),
                            details = it.details(5).copy(runtimeMinutes = if (it.isNull(4)) null else it.getLong(4).toInt()),
                        ),
                    )
                }
            }
        }
    }

    /**
     * Keeps movie Listing [listingId]'s Provider details, read at [at] while the Provider had the
     * Listing [added] then: they stand until the Provider changes that.
     */
    fun saveMovieDetails(listingId: Long, details: ProviderMovieDetails, added: Long?, at: Instant) = synchronized(writer) {
        writer.prepare("INSERT OR REPLACE INTO movie_details (listing_id, bitrate, fetched_at, added, runtime, $DETAILS_COLUMN_NAMES) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)").use { insert ->
            insert.bindLong(1, listingId)
            if (details.bitrate == null) insert.bindNull(2) else insert.bindLong(2, details.bitrate)
            insert.bindLong(3, at.toEpochMilli())
            if (added == null) insert.bindNull(4) else insert.bindLong(4, added)
            val said = details.details
            if (said.runtimeMinutes == null) insert.bindNull(5) else insert.bindLong(5, said.runtimeMinutes.toLong())
            insert.bindDetails(6, said.rating, said)
            insert.step()
        }
    }
    /** Where the Viewer stopped in [id], or null when they never played it. */
    fun progress(profile: Long, id: TitleId): StoredProgress? = synchronized(reader) {
        reader.prepare("SELECT title_key, position, duration, watched FROM progress WHERE profile = $profile AND title_key = ?").use {
            it.bindText(1, key(id))
            if (it.step()) it.progress() else null
        }
    }

    /** The Titles the Viewer stopped in without finishing, last played first, at most [limit]. */
    fun inProgress(profile: Long, limit: Int): List<StoredProgress> = synchronized(reader) {
        reader.prepare("SELECT title_key, position, duration, watched FROM progress WHERE profile = $profile AND watched = 0 ORDER BY updated_at DESC LIMIT ?").use {
            it.bindLong(1, limit.toLong())
            buildList { while (it.step()) add(it.progress()) }
        }
    }

    private fun SQLiteStatement.progress() =
        StoredProgress(titleId(getText(0)), getLong(1), if (isNull(2)) null else getLong(2), getLong(3) != 0L)

    /** When the Viewer last played each Title in [inProgress] and each Show they played an Episode of, last played first, at most [limit]. */
    fun lastPlayed(profile: Long, limit: Int): List<Pair<TitleId, Instant>> = synchronized(reader) {
        reader.prepare(
            "SELECT title_key, updated_at FROM progress WHERE profile = $profile AND watched = 0 " +
                "UNION ALL SELECT title_key, MAX(updated_at) FROM episode_progress WHERE profile = $profile GROUP BY title_key ORDER BY 2 DESC LIMIT ?",
        ).use {
            it.bindLong(1, limit.toLong())
            buildList { while (it.step()) add(titleId(it.getText(0)) to Instant.ofEpochMilli(it.getLong(1))) }
        }
    }

    /** The household's Profiles, in the order they were made. */
    fun profiles(): List<StoredProfile> = synchronized(reader) {
        reader.prepare("SELECT id, name, kids, used_at, uid, changed_at FROM profile ORDER BY id").use {
            buildList {
                while (it.step()) add(StoredProfile(it.getLong(0), if (it.isNull(1)) null else it.getText(1), it.getLong(2) != 0L, it.getLong(3), it.getText(4), it.getLong(5)))
            }
        }
    }

    /**
     * Makes a Profile named [name] at [at], not yet used, and gives its id: never one a deleted Profile
     * had, so nothing kept for that one comes back. [uid] is its id for the Account: a new one, or the
     * one another TV made for it.
     */
    fun addProfile(name: String?, kids: Boolean, at: Instant, uid: String = newProfileUid()): Long = synchronized(writer) {
        writer.prepare("INSERT INTO profile (name, kids, created_at, used_at, uid, changed_at) VALUES (?, ?, ?, 0, ?, ?)").use {
            if (name == null) it.bindNull(1) else it.bindText(1, name)
            it.bindLong(2, if (kids) 1 else 0)
            it.bindLong(3, at.toEpochMilli())
            it.bindText(4, uid)
            it.bindLong(5, at.toEpochMilli())
            it.step()
        }
        writer.prepare("SELECT last_insert_rowid()").use { it.step(); it.getLong(0) }
    }

    fun renameProfile(profile: Long, name: String?, at: Instant) = synchronized(writer) {
        writer.prepare("UPDATE profile SET name = ?, changed_at = ? WHERE id = $profile").use {
            if (name == null) it.bindNull(1) else it.bindText(1, name)
            it.bindLong(2, at.toEpochMilli())
            it.step()
        }
        Unit
    }

    fun setProfileKids(profile: Long, kids: Boolean, at: Instant) = synchronized(writer) {
        writer.execSQL("UPDATE profile SET kids = ${if (kids) 1 else 0}, changed_at = ${at.toEpochMilli()} WHERE id = $profile")
    }

    /** Gives [profile] the Account's id [uid], as signing in merges it into the Account's Profile of its name: what waits to sync for it follows. */
    fun setProfileUid(profile: Long, uid: String) = synchronized(writer) {
        val was = writer.prepare("SELECT uid FROM profile WHERE id = $profile").use { if (it.step()) it.getText(0) else null }
        writer.prepare("UPDATE profile SET uid = ? WHERE id = $profile").use {
            it.bindText(1, uid)
            it.step()
        }
        if (was != null) {
            writer.prepare("UPDATE OR REPLACE sync_outbox SET profile = ? WHERE profile = ?").use {
                it.bindText(1, uid)
                it.bindText(2, was)
                it.step()
            }
        }
        Unit
    }

    /** Records that the device is on [profile] from [at]: the one used last is the current one. */
    fun useProfile(profile: Long, at: Instant) = synchronized(writer) {
        // Never before the one used last, so a clock set back doesn't move the TV back to it.
        writer.execSQL("UPDATE profile SET used_at = max(${at.toEpochMilli()}, (SELECT max(used_at) + 1 FROM profile)) WHERE id = $profile")
    }

    /** Deletes [profile] and everything kept for it, at once: its history, Favourites, "Not this game" marks and player choices. */
    fun deleteProfile(profile: Long) = synchronized(writer) {
        writer.execSQL("BEGIN")
        try {
            PROFILE_TABLES.forEach { (table, _) -> writer.execSQL("DELETE FROM $table WHERE profile = $profile") }
            writer.execSQL("DELETE FROM profile WHERE id = $profile")
            writer.execSQL("COMMIT")
        } catch (e: Throwable) {
            if (writer.inTransaction()) writer.execSQL("ROLLBACK")
            throw e
        }
    }

    override fun householdPin(): StoredPin? = synchronized(reader) {
        reader.prepare("SELECT hash, failures, locked_until, changed_at FROM household_pin").use {
            if (it.step()) {
                StoredPin(it.getText(0), it.getLong(1).toInt(), if (it.isNull(2)) null else Instant.ofEpochMilli(it.getLong(2)), Instant.ofEpochMilli(it.getLong(3)))
            } else {
                null
            }
        }
    }

    override fun saveHouseholdPin(pin: StoredPin) = synchronized(writer) {
        writer.prepare("INSERT OR REPLACE INTO household_pin (id, hash, failures, locked_until, changed_at) VALUES (1, ?, ?, ?, ?)").use {
            it.bindText(1, pin.hash)
            it.bindLong(2, pin.failures.toLong())
            val until = pin.lockedUntil
            if (until == null) it.bindNull(3) else it.bindLong(3, until.toEpochMilli())
            it.bindLong(4, pin.changedAt.toEpochMilli())
            it.step()
        }
        Unit
    }

    override fun forgetHouseholdPin() = synchronized(writer) { writer.execSQL("DELETE FROM household_pin") }

    override fun deviceToken(): String? = meta(DEVICE_TOKEN)

    override fun saveDeviceToken(token: String) = synchronized(writer) {
        writer.prepare("INSERT OR REPLACE INTO meta VALUES (?, ?)").use {
            it.bindText(1, DEVICE_TOKEN)
            it.bindText(2, token)
            it.step()
        }
        Unit
    }

    /**
     * The Provider table kept for the Provider whose key is [providerKey] (#431): what the cloud last
     * answered for it, as of the catalog on screen. Null when none was kept, or it was another Provider's.
     */
    fun providerTable(providerKey: String): String? =
        if (meta(PROVIDER_TABLE_FOR) == providerKey) meta(PROVIDER_TABLE) else null

    /**
     * Keeps [table], the cloud's answer for [providerKey], with the catalog a Sync is staging: it
     * becomes the kept table when that catalog is swapped in, and goes when it is discarded.
     */
    fun stageProviderTable(providerKey: String, table: String) = synchronized(writer) {
        writeMeta(mapOf(PENDING_PROVIDER_TABLE_FOR to providerKey, PENDING_PROVIDER_TABLE to table))
    }

    /** The email of the Account the device is signed in to, as the cloud last said; null when none. */
    fun accountEmail(): String? = meta(ACCOUNT_EMAIL)

    fun saveAccountEmail(email: String?) = saveOrDeleteMeta(ACCOUNT_EMAIL, email)

    /** Whether this device signed itself out while the cloud couldn't hear it, so the cloud still has it signed in (#434). */
    fun signOutPending(): Boolean = meta(SIGN_OUT_PENDING) != null

    fun saveSignOutPending(pending: Boolean) = saveOrDeleteMeta(SIGN_OUT_PENDING, if (pending) "1" else null)

    private fun saveOrDeleteMeta(key: String, value: String?) = synchronized(writer) {
        if (value == null) {
            writer.prepare("DELETE FROM meta WHERE key = ?").use {
                it.bindText(1, key)
                it.step()
            }
        } else {
            writer.prepare("INSERT OR REPLACE INTO meta VALUES (?, ?)").use {
                it.bindText(1, key)
                it.bindText(2, value)
                it.step()
            }
        }
        Unit
    }

    /** Whether the first Sync's sign-in card was dismissed, or the device signed in (#438): it never shows again. */
    fun signInCardDone(): Boolean = meta(SIGN_IN_CARD) != null

    fun saveSignInCardDone() = synchronized(writer) { writeMeta(mapOf(SIGN_IN_CARD to "done")) }

    /** Whether the profile chip's one pulse is due ("pending"), has played ("done"), or neither yet (null) (#438). */
    fun profileChipPulse(): String? = meta(PROFILE_CHIP_PULSE)

    fun saveProfileChipPulse(value: String) = synchronized(writer) { writeMeta(mapOf(PROFILE_CHIP_PULSE to value)) }

    private fun meta(key: String): String? = synchronized(reader) {
        reader.prepare("SELECT value FROM meta WHERE key = ?").use {
            it.bindText(1, key)
            if (it.step()) it.getText(0) else null
        }
    }

    override fun close() {
        synchronized(writer) { writer.close() }
        synchronized(reader) { reader.close() }
    }

    /**
     * Turns a catalog stored while the cloud Matched Listings by name (before ADR 0014) into today's
     * shape without a Sync: each Listing the cloud had Matched otherwise than by its number is an
     * Unmatched Title again, and where a Viewer stopped in a Title that no longer exists is kept under
     * the Unmatched Title of its first such Listing, so it stays in Continue watching. The next Sync
     * applies the year rule. A catalog held for the next screen, staged in the old shape, goes.
     */
    private fun SQLiteConnection.unmatchCloudMatches() {
        execSQL("BEGIN")
        try {
            execSQL("CREATE TEMP TABLE unmatched (old_key TEXT NOT NULL, new_key TEXT NOT NULL, kind TEXT NOT NULL, position INTEGER NOT NULL)")
            execSQL("INSERT INTO unmatched SELECT title_key, $LISTING_KEY, kind, position FROM listing WHERE title_key NOT IN ($PROVIDER_KEY, $LISTING_KEY)")
            execSQL("UPDATE listing SET title_key = $LISTING_KEY WHERE title_key NOT IN ($PROVIDER_KEY, $LISTING_KEY)")
            execSQL("DELETE FROM title")
            execSQL("INSERT INTO title (kind, position, key) $TITLES_BY_NEWEST_LISTING".format("listing"))
            execSQL("UPDATE listing SET title = (SELECT position FROM title WHERE key = listing.title_key)")
            for (table in listOf("progress", "episode_progress", "title_choices")) {
                execSQL(
                    "UPDATE OR IGNORE $table SET title_key = (SELECT new_key FROM unmatched u WHERE u.old_key = $table.title_key ORDER BY u.kind, u.position LIMIT 1) " +
                        "WHERE title_key IN (SELECT old_key FROM unmatched) AND title_key NOT IN (SELECT key FROM title)",
                )
            }
            execSQL("DROP TABLE unmatched")
            execSQL("ALTER TABLE listing DROP COLUMN suspect")
            execSQL("ALTER TABLE listing DROP COLUMN match_key")
            execSQL("DROP TABLE IF EXISTS staging_kept_listing")
            execSQL("DELETE FROM meta WHERE key = '$LAST_SYNC_REMATCHED' OR key LIKE 'pending_sync_%'")
            execSQL("COMMIT")
        } catch (e: Throwable) {
            execSQL("ROLLBACK")
            throw e
        }
    }

    private companion object {
        /** Raised whenever the stored catalog's shape changes, so an older one is dropped and Synced again. */
        const val SCHEMA_VERSION = 7
        /** Titles read per lock by a whole-catalog scan: a few milliseconds, even on the stick. */
        const val SCAN_PAGE = 2_000
        /** The most the write-ahead log keeps on the device's storage between checkpoints, in bytes. */
        const val WAL_SIZE_LIMIT = 4L * 1024 * 1024
        /**
         * What the Provider says of a Title (ADR 0014), as columns on a Listing (a show Listing's, read at
         * Sync) and on a Movie's kept details: its rating, plot, genre field, cast, director, release date,
         * backdrops (a JSON list of URLs) and trailer key. [SQLiteStatement.details] reads them in this order.
         */
        val DETAILS_COLUMNS = listOf(
            "rating" to "REAL", "plot" to "TEXT", "genre" to "TEXT", "actors" to "TEXT", "director" to "TEXT", "release_date" to "TEXT", "backdrops" to "TEXT", "trailer" to "TEXT",
        )
        val DETAILS_COLUMN_NAMES = DETAILS_COLUMNS.joinToString(", ") { it.first }
        val LISTING_TABLE =
            "CREATE TABLE IF NOT EXISTS %s (kind TEXT NOT NULL, position INTEGER NOT NULL, id INTEGER NOT NULL, name TEXT NOT NULL, category TEXT NOT NULL, " +
                "added INTEGER, image TEXT, title INTEGER NOT NULL, title_key TEXT NOT NULL, provider_tmdb INTEGER, " +
                "container TEXT, ${DETAILS_COLUMNS.joinToString(", ") { "${it.first} ${it.second}" }}, PRIMARY KEY (kind, position))"
        const val CHANNEL_TABLE =
            "CREATE TABLE IF NOT EXISTS %s (id INTEGER PRIMARY KEY, position INTEGER NOT NULL, name TEXT NOT NULL, image TEXT, event_start INTEGER, " +
                "category_id TEXT, guide_id TEXT NOT NULL, channel_key TEXT NOT NULL, channel_name TEXT NOT NULL, country TEXT, quality TEXT, hevc INTEGER NOT NULL, " +
                "lead INTEGER NOT NULL)"
        const val LIVE_CATEGORY_TABLE =
            "CREATE TABLE IF NOT EXISTS %s (id TEXT PRIMARY KEY, position INTEGER NOT NULL, name TEXT NOT NULL, normalised_name TEXT NOT NULL, country TEXT, kind TEXT NOT NULL)"
        /** A Sports replay: its Listing, and the teams and date read off its name (the date as `yyyy-mm-dd`, so it sorts and compares as text). */
        const val REPLAY_TABLE =
            "CREATE TABLE IF NOT EXISTS %s (id INTEGER PRIMARY KEY, position INTEGER NOT NULL, name TEXT NOT NULL, category TEXT NOT NULL, added INTEGER, " +
                "container TEXT, away TEXT, home TEXT, date TEXT, year INTEGER)"
        const val REPLAY_COLUMNS = "id, name, category, added, away, home, date, year"
        /** A variant's row as [variantRow] reads it: its Channel, whether it's a Favourite, then the variant. */
        const val CHANNEL_COLUMNS = "c.channel_key, c.channel_name, c.country, f.key, c.id, c.image, c.name, c.quality, c.hevc, c.category_id, c.guide_id"
        /** The Profile that holds what the device kept before there were Profiles (#428). */
        const val FIRST_PROFILE = Profile.FIRST_ID
        /**
         * Each Viewer's tables, kept per Profile, by name and shape (`%s` for the name), the Profile
         * first so a table kept before Profiles copies across with it put in front of its columns.
         */
        val PROFILE_TABLES = listOf(
            // The Channels the Viewer saved, by Channel key, so they outlive Syncs.
            "favourite" to "CREATE TABLE IF NOT EXISTS %s (profile INTEGER NOT NULL, key TEXT NOT NULL, added_at INTEGER NOT NULL, PRIMARY KEY (profile, key))",
            // The Feeds the Viewer marked "Not this game" (#255), by Game (League and schedule id) and Channel key, so they outlive Syncs.
            "not_this_game" to
                "CREATE TABLE IF NOT EXISTS %s (profile INTEGER NOT NULL, game_key TEXT NOT NULL, channel_key TEXT NOT NULL, dropped_at INTEGER NOT NULL, " +
                "PRIMARY KEY (profile, game_key, channel_key))",
            // Where the Viewer stopped in a Title, in milliseconds, out of its `duration` when the player knew it.
            "progress" to
                "CREATE TABLE IF NOT EXISTS %s (profile INTEGER NOT NULL, title_key TEXT NOT NULL, position INTEGER NOT NULL, duration INTEGER, " +
                "watched INTEGER NOT NULL, updated_at INTEGER NOT NULL, PRIMARY KEY (profile, title_key))",
            // The same for each Episode of a Show, by season and episode number.
            "episode_progress" to
                "CREATE TABLE IF NOT EXISTS %s (profile INTEGER NOT NULL, title_key TEXT NOT NULL, season INTEGER NOT NULL, number INTEGER NOT NULL, " +
                "position INTEGER NOT NULL, duration INTEGER, watched INTEGER NOT NULL, updated_at INTEGER NOT NULL, PRIMARY KEY (profile, title_key, season, number))",
            // What the Viewer chose while a Title played (TitleChoices as JSON), kept by Title so it outlives Syncs and carries to a Show's next Episode (#291).
            "title_choices" to
                "CREATE TABLE IF NOT EXISTS %s (profile INTEGER NOT NULL, title_key TEXT NOT NULL, body TEXT NOT NULL, updated_at INTEGER NOT NULL, " +
                "PRIMARY KEY (profile, title_key))",
            // The settings each Profile keeps its own (#440): what the app writes by name (ProfileSetting), with when it changed;
            // a null value is one the Viewer took back (no Leagues chosen), kept so the latest change still wins.
            "profile_setting" to
                "CREATE TABLE IF NOT EXISTS %s (profile INTEGER NOT NULL, key TEXT NOT NULL, value TEXT, updated_at INTEGER NOT NULL, PRIMARY KEY (profile, key))",
            // What the Viewer last picked in the player, by kind (`audio`, `subtitles`).
            "remembered_track" to "CREATE TABLE IF NOT EXISTS %s (profile INTEGER NOT NULL, kind TEXT NOT NULL, value TEXT NOT NULL, PRIMARY KEY (profile, kind))",
            // History sync (#435), by Title and Episode (a Movie's season and number are -1): the changes the Account hasn't
            // been sent yet, the Account's changes pulled and not yet landed (they land between screens), and when the Viewer
            // removed a Title from Continue watching, kept 30 days so an older stop from another TV doesn't bring it back.
            "history_outbox" to HISTORY_CHANGE_TABLE,
            "history_pulled" to HISTORY_CHANGE_TABLE,
            "history_tombstone" to
                "CREATE TABLE IF NOT EXISTS %s (profile INTEGER NOT NULL, title_key TEXT NOT NULL, season INTEGER NOT NULL, number INTEGER NOT NULL, " +
                "removed_at INTEGER NOT NULL, PRIMARY KEY (profile, title_key, season, number))",
        )
        /** A history change as [HistoryChange] holds it, by Title and Episode. */
        const val HISTORY_CHANGE_TABLE =
            "CREATE TABLE IF NOT EXISTS %s (profile INTEGER NOT NULL, title_key TEXT NOT NULL, season INTEGER NOT NULL, number INTEGER NOT NULL, " +
                "position INTEGER NOT NULL, duration INTEGER, watched INTEGER NOT NULL, removed INTEGER NOT NULL, updated_at INTEGER NOT NULL, " +
                "PRIMARY KEY (profile, title_key, season, number))"
        /** Where Favourites kept by stream id wait for the Sync that carries them over. */
        const val LEGACY_FAVOURITES = "favourite_by_stream"
        /** SQLite's limit on bound parameters in one statement is 999 in older builds. */
        const val MAX_BOUND = 900
        const val TITLE_TABLE = "CREATE TABLE IF NOT EXISTS %s (kind TEXT NOT NULL, position INTEGER NOT NULL, key TEXT NOT NULL, PRIMARY KEY (kind, position))"
        /** Wikidata's facts of a Title (#476), landed or waiting to. */
        const val TITLE_FACTS_TABLE = "CREATE TABLE IF NOT EXISTS %s (title_key TEXT PRIMARY KEY, found INTEGER NOT NULL, original_language TEXT, imdb_id TEXT, " +
            "enwiki TEXT, frwiki TEXT, fetched_at INTEGER NOT NULL)"
        /** Made after a swap: an index keeps its name when its table is renamed. */
        val TITLE_INDEXES = listOf(
            "CREATE INDEX IF NOT EXISTS listing_title ON listing (kind, title)",
            "CREATE UNIQUE INDEX IF NOT EXISTS title_key ON title (key)",
            // The Replays in the Provider's order, and a finished Game's Replay by its date.
            "CREATE INDEX IF NOT EXISTS replay_position ON replay (position)",
            "CREATE INDEX IF NOT EXISTS replay_date ON replay (date)",
        )
        val CHANNEL_INDEXES = listOf(
            "CREATE INDEX IF NOT EXISTS channel_position ON channel (position)",
            "CREATE INDEX IF NOT EXISTS channel_key ON channel (channel_key, position)",
            // The Channel browser's counts and Category lists, by Category: covered without touching the rows.
            "CREATE INDEX IF NOT EXISTS channel_category ON channel (category_id, channel_key)",
            // The lead variant of each Channel, in the Provider's order, for the search index's scan.
            "CREATE INDEX IF NOT EXISTS channel_lead ON channel (position) WHERE lead = 1",
            // The Event channels on, read for the Live tab's Games every couple of minutes.
            "CREATE INDEX IF NOT EXISTS channel_event_start ON channel (event_start)",
        )
        val INDEXES = TITLE_INDEXES + CHANNEL_INDEXES
        val LISTING_COLUMNS = "l.id, l.name, l.category, l.added, l.image, " + DETAILS_COLUMNS.joinToString(", ") { "l." + it.first }
        /** Each kind's Titles from the Listings in %s, listed by their newest Listing. */
        const val TITLES_BY_NEWEST_LISTING =
            "SELECT kind, row_number() OVER (PARTITION BY kind ORDER BY max(added) DESC, min(name), min(id)), title_key FROM %s GROUP BY kind, title_key"
        /** A Listing's Title key by its Title number, as [key] makes it. */
        const val PROVIDER_KEY = "CASE WHEN provider_tmdb IS NULL THEN kind || '/listing/' || id ELSE kind || '/' || provider_tmdb END"
        /** A Listing's Title key as an Unmatched Title, as [key] makes it. */
        const val LISTING_KEY = "kind || '/listing/' || id"
        const val UNKNOWN_PREFIX_TABLE =
            "CREATE TABLE IF NOT EXISTS %s (prefix TEXT PRIMARY KEY, listings INTEGER NOT NULL, example TEXT NOT NULL)"
        /** A Sync's candidate Tier tags (#439), kept like its unknown Prefixes. */
        const val TIER_TAG_TABLE =
            "CREATE TABLE IF NOT EXISTS %s (word TEXT PRIMARY KEY, listings INTEGER NOT NULL, example TEXT NOT NULL)"
        const val LAST_SYNC_AT = "last_sync_at"
        const val LAST_SYNC_MILLIS = "last_sync_millis"
        const val LAST_SYNC_LISTINGS = "last_sync_movie_listings"
        const val LAST_SYNC_SHOW_LISTINGS = "last_sync_show_listings"
        /** How many Unmatched Titles the year rule made; an older app kept how many Listings the cloud had Matched by name, under the second. */
        const val LAST_SYNC_MISMATCHES = "last_sync_mismatches"
        const val LAST_SYNC_REMATCHED = "last_sync_rematched_listings"
        const val LAST_SYNC_CHANNELS = "last_sync_channel_listings"
        /** When the Channels on screen landed: before the Titles on a first Sync. */
        const val CHANNELS_SYNCED_AT = "channels_synced_at"
        /** A staged catalog waiting to be swapped in, as [stageTitles] records it; all dropped by `pending_sync_%`. */
        const val PENDING_SYNC_AT = "pending_sync_at"
        const val PENDING_SYNC_MILLIS = "pending_sync_millis"
        const val PENDING_MOVIE_LISTINGS = "pending_sync_movie_listings"
        const val PENDING_SHOW_LISTINGS = "pending_sync_show_listings"
        const val PENDING_CHANNEL_LISTINGS = "pending_sync_channel_listings"
        const val PENDING_MISMATCHES = "pending_sync_mismatches"
        const val PENDING_PROVIDER_TABLE_FOR = "pending_sync_provider_table_for"
        const val PENDING_PROVIDER_TABLE = "pending_sync_provider_table"
        /** The Provider table kept ([providerTable]), and the key of the Provider it is for. */
        const val PROVIDER_TABLE_FOR = "provider_table_for"
        const val PROVIDER_TABLE = "provider_table"
        /** The token the cloud gave this device. Lost with the catalog's shape changes, the device just registers again. */
        const val DEVICE_TOKEN = "cloud_device_token"
        const val ACCOUNT_EMAIL = "account_email"
        const val SIGN_IN_CARD = "sign_in_card"
        const val PROFILE_CHIP_PULSE = "profile_chip_pulse"
        const val SIGN_OUT_PENDING = "account_sign_out_pending"
        /** History sync (#435): the Profile that syncs, the cursor of its last sync and the Provider key that was for, and a reset waiting to land. */
        const val SYNC_CURSOR = "sync_cursor"
        const val SYNC_PROVIDER = "sync_provider"
        const val SYNC_LINKED = "sync_linked"
        const val HISTORY_RESET = "history_reset"
        /** A Movie's season and number in the history tables, which key Movies and Episodes alike. */
        const val NO_EPISODE = -1
        const val HISTORY_COLUMNS = "title_key, season, number, position, duration, watched, removed, updated_at"
        const val REMEMBERED_TRACK = "remembered_track_"
        const val READING_DAY = "reading_day"
        const val LAST_FEATURED = "last_featured_"
        const val GUIDE_DAY = "guide_day"
    }
}

/** A Title as stored: its Listings, before the Prefix table describes them. */
internal class StoredTitle(val id: TitleId, val listings: List<StoredListing>)

/** A Title's cached metadata JSON (null: TMDB has no such Title) and when the cloud sent it. */
internal class StoredMetadata(val body: String?, val fetchedAt: Instant)

/** The cloud's Charts answer as JSON ([Charts]) and when it sent it. */
internal class StoredCharts(val body: String, val fetchedAt: Instant)

/** Trending's feed summed over its week ([CatalogStore.trending]): each wiki's article names with their views, and when the cloud sent it. */
internal class StoredTrending(val views: Map<String, Map<String, Long>>, val fetchedAt: Instant)

/** One League's Games in the cloud's answer, as JSON ([Games]), and when the cloud sent it. */
internal class StoredGames(val body: String, val fetchedAt: Instant)

/**
 * [added] is when a movie Listing was added, or when the Provider last changed a show Listing.
 * [rating] is the Provider's, on a 10 scale (null when it gives none), and [details] what a show
 * Listing says of its Show (ADR 0014); a movie Listing's details are kept apart ([StoredMovieDetails]).
 */
internal class StoredListing(val id: Long, val name: String, val category: String, val added: Long?, val image: String?, val rating: Double? = null, val details: ProviderDetails? = null)

/** What search reads of a Listing ([CatalogStore.forEachTitle]): its [name], its [category], and the Provider's [genre] field and [cast] as written, null when not read. */
internal class SearchListing(val name: String, val category: String, val genre: String?, val cast: String?)

/**
 * A movie Listing's kept Provider details: the [bitrate] they gave (null when none), what they say
 * of the Movie, when they were read, and the Listing's [added] then (null for details kept before
 * that was, which are read again).
 */
internal class StoredMovieDetails(val bitrate: Long?, val fetchedAt: Instant, val added: Long?, val details: ProviderDetails)

/** Binds the details columns ([CatalogStore.DETAILS_COLUMNS], in order) from [from]: [rating], then what [details] says. */
private fun SQLiteStatement.bindDetails(from: Int, rating: Double?, details: ProviderDetails?) {
    if (rating == null) bindNull(from) else bindDouble(from, rating)
    fun text(i: Int, value: String?) = if (value == null) bindNull(from + i) else bindText(from + i, value)
    text(1, details?.plot)
    text(2, details?.genre)
    text(3, details?.cast)
    text(4, details?.director)
    text(5, details?.releaseDate)
    text(6, details?.backdrops?.takeIf { it.isNotEmpty() }?.let { list -> Buffer().also { buffer -> JsonWriter.of(buffer).use { writer -> writer.beginArray(); list.forEach(writer::value); writer.endArray() } }.readUtf8() })
    text(7, details?.trailer)
}

/** Where the Viewer stopped in a Title, in milliseconds, out of its [duration] when the player knew it. */
/** A Profile as kept: [name] is null until the first Profile is renamed; [usedAt] is 0 for one never used. */
internal class StoredProfile(val id: Long, val name: String?, val kids: Boolean, val usedAt: Long, val uid: String, val changedAt: Long)

internal class StoredProgress(val id: TitleId, val position: Long, val duration: Long?, val watched: Boolean)

/**
 * One Movie's (no [episode]) or Episode's history as it syncs with the Account (#435): where the
 * Viewer stopped, whether they Watched it, or that they [removed] it from Continue watching, [at]
 * when, by the device's clock. The latest change per Movie or Episode wins.
 */
internal data class HistoryChange(
    val id: TitleId,
    val episode: EpisodeNumber?,
    val position: Long,
    val duration: Long?,
    val watched: Boolean,
    val removed: Boolean,
    val at: Instant,
)

/**
 * Where the device is in syncing with the Account (#440): the [cursor] its last sync got, the
 * Provider key it was got for, and whether the device's own data is [linked] into the Account yet
 * (signing in merges it once).
 */
internal data class SyncState(val cursor: Long, val provider: String?, val linked: Boolean)

/**
 * One thing besides history the Account keeps, as the sync sends it (cloud/src/sync.ts): of [kind],
 * of the Profile with Account id [profile] (null for the household's), keyed [key], [value] null for a
 * removal, changed [at]. The latest change per key wins.
 */
internal data class SyncEntry(val kind: String, val profile: String?, val key: String, val value: String?, val at: Instant) {
    companion object {
        const val PROFILE = "profile"
        const val FAVOURITE = "favourite"
        const val NOT_THIS_GAME = "not_this_game"
        const val SETTING = "setting"
        const val TITLE_CHOICES = "title_choices"
        const val HOUSEHOLD = "household"
        /** A Profile's remembered track of a kind is a setting named so. */
        const val TRACK = "track."
        const val REGION = "region"
        const val PIN = "pin"
    }
}

/** A Title's key as the history sync sends it: `movie/238`, `show/1399` or `movie/listing/123`. */
internal fun historyKey(id: TitleId): String = key(id)

/** The Title a history key names, or null for one this app doesn't make. */
internal fun historyTitleId(key: String): TitleId? = runCatching { titleId(key) }.getOrNull()

/** Where the Viewer stopped in a Show's [episode], as [StoredProgress] is for a Title. */
internal class StoredEpisodeProgress(val episode: EpisodeNumber, val position: Long, val duration: Long?, val watched: Boolean)

/** A show Listing's kept show details: when the Provider had last changed the Show, and when they were read. */
internal class StoredShowDetails(val modified: Long?, val fetchedAt: Instant, val episodes: List<ProviderEpisode>)

/** How a kind is spelled in the database, and at the start of a Title key. */
private fun kindKey(kind: TitleKind): String = kind.name.lowercase()

private fun kind(key: String): TitleKind = TitleKind.entries.single { kindKey(it) == key }

private fun kindKey(kind: CategoryKind): String = kind.name.lowercase()

private fun categoryKind(key: String): CategoryKind = CategoryKind.entries.single { kindKey(it) == key }

private fun key(id: TitleId): String = when (id) {
    is TitleId.Tmdb -> "${kindKey(id.kind)}/${id.tmdbId}"
    is TitleId.Unmatched -> "${kindKey(id.kind)}/listing/${id.listingId}"
}

private fun titleId(key: String): TitleId {
    val parts = key.split('/')
    val kind = kind(parts[0])
    return if (parts[1] == "listing") TitleId.Unmatched(kind, parts[2].toLong()) else TitleId.Tmdb(kind, parts[1].toLong())
}

/** The text in column [index], or null when it is NULL. */
private fun SQLiteStatement.textOrNull(index: Int): String? = if (isNull(index)) null else getText(index)

private fun SQLiteConnection.hasColumn(table: String, column: String): Boolean =
    prepare("SELECT count(*) FROM pragma_table_info('$table') WHERE name = '$column'").use { it.step(); it.getLong(0) > 0 }

/** A new Profile id for the Account (#440): 16 hex digits, made on the device. */
internal fun newProfileUid(): String = java.security.SecureRandom().let { random -> (1..8).joinToString("") { "%02x".format(random.nextInt(256)) } }

private fun SQLiteStatement.stepAndReset() {
    step()
    reset()
}
