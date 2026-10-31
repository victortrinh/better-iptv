package dev.betteriptv.core

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import java.io.Closeable
import java.time.Duration
import java.time.Instant

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
 * A Listing is Matched by its Provider's TMDB ID unless it is a suspect: it has no ID, or its name
 * matches none of the other Listings of its kind with its ID. A suspect is Matched by the cloud's
 * answer about it, once there is one; answers are kept here, so the next Sync Matches it straight away.
 *
 * Title metadata from the cloud is cached here too, per Title and language, along with the
 * device's cloud token, and so are show Listings' show details, until the Provider changes the Show.
 * What movie Listings' files hold, from Header reads and Playback, and their bitrates from the
 * Provider's movie details, are kept forever.
 * So is where the Viewer stopped in each Title, keyed by Title so it outlives Syncs.
 *
 * Channels (live Listings, Clutter left out) are swapped in with the movie and show Listings, in
 * the Provider's order. Favourites are kept by Provider stream id, so they outlive Syncs; one the
 * Provider drops comes back if it lists the Channel again.
 */
internal class CatalogStore(path: String) : Closeable, DeviceTokenStore {
    private val driver = BundledSQLiteDriver()
    private val writer: SQLiteConnection = driver.open(path).apply {
        execSQL("PRAGMA journal_mode=WAL")
        execSQL("PRAGMA synchronous=NORMAL")
        // A big write grows the write-ahead log; once checkpointed, it is cut back to this.
        execSQL("PRAGMA journal_size_limit=$WAL_SIZE_LIMIT")
        execSQL("CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        if (prepare("PRAGMA user_version").use { it.step(); it.getLong(0) } < SCHEMA_VERSION) {
            // A catalog stored in an older shape is dropped; the next Sync stores it again.
            execSQL("DROP TABLE IF EXISTS movie_listing")
            execSQL("DROP TABLE IF EXISTS listing")
            execSQL("DROP TABLE IF EXISTS title")
            execSQL("DROP TABLE IF EXISTS show_details")
            execSQL("DROP TABLE IF EXISTS episode")
            execSQL("DROP TABLE IF EXISTS channel")
            execSQL("DELETE FROM meta")
            execSQL("PRAGMA user_version = $SCHEMA_VERSION")
        }
        execSQL(LISTING_TABLE.format("listing"))
        execSQL(TITLE_TABLE.format("title"))
        execSQL(CHANNEL_TABLE.format("channel"))
        INDEXES.forEach { execSQL(it) }
        // The Channels the Viewer saved, by Provider stream id, so they outlive Syncs.
        execSQL("CREATE TABLE IF NOT EXISTS favourite (id INTEGER PRIMARY KEY, added_at INTEGER NOT NULL)")
        execSQL(UNKNOWN_PREFIX_TABLE.format("unknown_prefix"))
        // Keyed by question (MatchQuestion.key), so it outlives Syncs. A null tmdb_id: TMDB has no Title by that name.
        execSQL("CREATE TABLE IF NOT EXISTS match_answer (key TEXT PRIMARY KEY, tmdb_id INTEGER, fetched_at INTEGER NOT NULL)")
        // Keyed by Title, not by Listing, so it outlives Syncs and catalog shape changes.
        execSQL(
            "CREATE TABLE IF NOT EXISTS title_metadata (title_key TEXT NOT NULL, language TEXT NOT NULL, body TEXT, fetched_at INTEGER NOT NULL, " +
                "PRIMARY KEY (title_key, language))",
        )
        // Episodes kept before their file extension was are asked again, so they can play.
        if (prepare("SELECT count(*) FROM pragma_table_info('episode') WHERE name = 'audio_language'").use { it.step(); it.getLong(0) } > 0 &&
            prepare("SELECT count(*) FROM pragma_table_info('episode') WHERE name = 'container'").use { it.step(); it.getLong(0) } == 0L
        ) {
            execSQL("DROP TABLE show_details")
            execSQL("DROP TABLE episode")
        }
        // A show Listing's show details as the Provider gave them while the Show was last changed at `modified`.
        execSQL("CREATE TABLE IF NOT EXISTS show_details (listing_id INTEGER PRIMARY KEY, modified INTEGER, fetched_at INTEGER NOT NULL)")
        // What a movie Listing's file holds, from a Header read, Playback or the cloud; a null body: its header can't be read.
        // `shared` says whether the cloud has it. Kept forever: a Listing's file doesn't change.
        execSQL("CREATE TABLE IF NOT EXISTS file_read (listing_id INTEGER PRIMARY KEY, body TEXT, read_at INTEGER NOT NULL, shared INTEGER NOT NULL)")
        // The bitrate a movie Listing's Provider details give, null when they give none.
        execSQL("CREATE TABLE IF NOT EXISTS movie_details (listing_id INTEGER PRIMARY KEY, bitrate INTEGER, fetched_at INTEGER NOT NULL)")
        execSQL(
            "CREATE TABLE IF NOT EXISTS episode (listing_id INTEGER NOT NULL, id INTEGER NOT NULL, season INTEGER NOT NULL, number INTEGER NOT NULL, " +
                "title TEXT NOT NULL, added INTEGER, width INTEGER, height INTEGER, audio_language TEXT, container TEXT, PRIMARY KEY (listing_id, id))",
        )
        // Where the Viewer stopped in a Title, in milliseconds, out of its `duration` when the player knew it.
        execSQL(
            "CREATE TABLE IF NOT EXISTS progress (title_key TEXT PRIMARY KEY, position INTEGER NOT NULL, duration INTEGER, watched INTEGER NOT NULL, " +
                "updated_at INTEGER NOT NULL)",
        )
        // The same for each Episode of a Show, by season and episode number.
        execSQL(
            "CREATE TABLE IF NOT EXISTS episode_progress (title_key TEXT NOT NULL, season INTEGER NOT NULL, number INTEGER NOT NULL, position INTEGER NOT NULL, " +
                "duration INTEGER, watched INTEGER NOT NULL, updated_at INTEGER NOT NULL, PRIMARY KEY (title_key, season, number))",
        )
    }
    private val reader: SQLiteConnection = driver.open(path)

    class Staging internal constructor(connection: SQLiteConnection) {
        private val insertCategory = connection.prepare("INSERT OR REPLACE INTO staging_category VALUES (?, ?, ?)")
        private val insertListing = connection.prepare("INSERT INTO staging_listing VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?)")
        private val insertChannel = connection.prepare("INSERT OR REPLACE INTO staging_channel VALUES (?, ?, ?, ?, ?)")
        private var channelPosition = 0L

        /** Stores a Category of [kind]'s Listings: movie and show Categories are numbered apart. */
        fun category(kind: TitleKind, id: String, name: String) = insertCategory.run {
            bindText(1, kindKey(kind))
            bindText(2, id)
            bindText(3, name)
            stepAndReset()
        }

        /**
         * Stores a Listing of [match]'s kind, Matched to its Title by the Provider's TMDB ID in
         * [match], or standing alone without one, until the cloud answers [match] otherwise. A
         * [hidden] one is kept out of the lists and gets no Title. [added] orders the list, newest
         * first: for a Show, it is when the Provider last changed it. [container] is a movie file's
         * extension (`mkv`), which its stream location ends with.
         */
        fun listing(id: Long, name: String, categoryId: String?, added: Long?, image: String?, hidden: Boolean, match: MatchQuestion, container: String? = null) = insertListing.run {
            bindText(1, kindKey(match.kind))
            bindLong(2, id)
            bindText(3, name)
            if (categoryId == null) bindNull(4) else bindText(4, categoryId)
            if (added == null) bindNull(5) else bindLong(5, added)
            bindLong(6, if (hidden) 1 else 0)
            if (image == null) bindNull(7) else bindText(7, image)
            bindText(8, key(match.tmdbId?.let { TitleId.Tmdb(match.kind, it) } ?: TitleId.Unmatched(match.kind, id)))
            if (match.tmdbId == null) bindNull(9) else bindLong(9, match.tmdbId)
            bindText(10, matchName(match.name))
            bindText(11, match.key)
            if (container == null) bindNull(12) else bindText(12, container)
            stepAndReset()
        }

        /**
         * Stores a Channel (a live Listing that isn't Clutter), in the Provider's order. [eventStart]
         * is when the Game an Event channel carries starts, so it drops out of the lists once finished.
         */
        fun channel(id: Long, name: String, image: String?, eventStart: Instant?) = insertChannel.run {
            bindLong(1, id)
            bindLong(2, ++channelPosition)
            bindText(3, name)
            if (image == null) bindNull(4) else bindText(4, image)
            if (eventStart == null) bindNull(5) else bindLong(5, eventStart.epochSecond)
            stepAndReset()
        }

        internal fun close() {
            insertCategory.close()
            insertListing.close()
            insertChannel.close()
        }
    }

    /**
     * Replaces the movie and show Listings and the Channels with what [fill] writes, Matching suspects by the answers
     * kept. [completed] runs just before the swap, with the new Listing counts and how many Listings
     * were Matched by name, and the Sync it returns is recorded in the same transaction.
     */
    fun replaceListings(fill: (Staging) -> Unit, completed: (movieCount: Int, showCount: Int, rematched: Int) -> CompletedSync) = synchronized(writer) {
        writer.execSQL("DROP TABLE IF EXISTS staging_category")
        writer.execSQL("DROP TABLE IF EXISTS staging_listing")
        writer.execSQL("DROP TABLE IF EXISTS staging_kept_listing")
        writer.execSQL("DROP TABLE IF EXISTS staging_title")
        writer.execSQL("DROP TABLE IF EXISTS staging_channel")
        writer.execSQL(CHANNEL_TABLE.format("staging_channel"))
        writer.execSQL("CREATE TABLE staging_category (kind TEXT, id TEXT, name TEXT NOT NULL, PRIMARY KEY (kind, id))")
        writer.execSQL(
            "CREATE TABLE staging_listing (kind TEXT, id INTEGER, name TEXT, category_id TEXT, added INTEGER, hidden INTEGER, image TEXT, title_key TEXT, " +
                "provider_tmdb INTEGER, match_name TEXT, match_key TEXT, suspect INTEGER, container TEXT)",
        )
        writer.execSQL(LISTING_TABLE.format("staging_kept_listing"))
        writer.execSQL(TITLE_TABLE.format("staging_title"))
        try {
            writer.execSQL("BEGIN")
            val staging = Staging(writer)
            try {
                fill(staging)
            } finally {
                staging.close()
            }
            // A Listing whose name no sibling shares may be a Mismatch, and one without an ID needs looking up.
            writer.execSQL("CREATE INDEX staging_listing_siblings ON staging_listing (kind, provider_tmdb, match_name)")
            writer.execSQL(
                """
                UPDATE staging_listing SET suspect = 1 WHERE hidden = 0 AND (provider_tmdb IS NULL OR NOT EXISTS (
                    SELECT 1 FROM staging_listing o
                    WHERE o.kind = staging_listing.kind AND o.provider_tmdb = staging_listing.provider_tmdb AND o.match_name = staging_listing.match_name
                        AND o.hidden = 0 AND o.rowid <> staging_listing.rowid
                ))
                """.trimIndent(),
            )
            writer.execSQL("UPDATE staging_listing SET title_key = $ANSWERED_KEY WHERE suspect = 1 AND match_key IN (SELECT key FROM match_answer)")
            // Titles are listed by their newest Listing.
            writer.execSQL("INSERT INTO staging_title (kind, position, key) $TITLES_BY_NEWEST_LISTING".format("staging_listing WHERE hidden = 0"))
            writer.execSQL("CREATE INDEX staging_title_key ON staging_title (key)")
            writer.execSQL(
                """
                INSERT INTO staging_kept_listing (kind, position, id, name, category, added, image, title, title_key, provider_tmdb, match_key, suspect, container)
                SELECT l.kind, row_number() OVER (PARTITION BY l.kind ORDER BY l.added DESC, l.name, l.id), l.id, l.name, coalesce(c.name, ''), l.added, l.image,
                    t.position, l.title_key, l.provider_tmdb, l.match_key, l.suspect, l.container
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
        } catch (e: Throwable) {
            if (writer.inTransaction()) writer.execSQL("ROLLBACK")
            writer.execSQL("DROP TABLE IF EXISTS staging_kept_listing")
            writer.execSQL("DROP TABLE IF EXISTS staging_title")
            writer.execSQL("DROP TABLE IF EXISTS staging_channel")
            throw e
        }

        fun count(kind: TitleKind) = writer.prepare("SELECT count(*) FROM staging_kept_listing WHERE kind = ?").use {
            it.bindText(1, kindKey(kind))
            it.step()
            it.getLong(0).toInt()
        }
        val sync = completed(count(TitleKind.Movie), count(TitleKind.Show), rematchedCount("staging_kept_listing"))
        writer.execSQL("BEGIN IMMEDIATE")
        try {
            writer.execSQL("DROP TABLE listing")
            writer.execSQL("DROP TABLE title")
            writer.execSQL("ALTER TABLE staging_kept_listing RENAME TO listing")
            writer.execSQL("ALTER TABLE staging_title RENAME TO title")
            writer.execSQL("DROP TABLE channel")
            writer.execSQL("ALTER TABLE staging_channel RENAME TO channel")
            INDEXES.forEach { writer.execSQL(it) }
            // Show details of Listings the Provider no longer has won't be asked for again.
            writer.execSQL("DELETE FROM show_details WHERE listing_id NOT IN (SELECT id FROM listing WHERE kind = 'show')")
            writer.execSQL("DELETE FROM episode WHERE listing_id NOT IN (SELECT listing_id FROM show_details)")
            writer.execSQL("DELETE FROM unknown_prefix")
            writer.prepare("INSERT INTO unknown_prefix VALUES (?, ?, ?)").use { insert ->
                sync.unknownPrefixes.forEach {
                    insert.bindText(1, it.prefix)
                    insert.bindLong(2, it.listingCount.toLong())
                    insert.bindText(3, it.example)
                    insert.stepAndReset()
                }
            }
            writer.prepare("INSERT OR REPLACE INTO meta VALUES (?, ?)").use { insert ->
                mapOf(
                    LAST_SYNC_AT to sync.finishedAt.toString(),
                    LAST_SYNC_MILLIS to sync.duration.toMillis().toString(),
                    LAST_SYNC_LISTINGS to sync.movieListingCount.toString(),
                    LAST_SYNC_SHOW_LISTINGS to sync.showListingCount.toString(),
                    LAST_SYNC_REMATCHED to sync.rematchedListings.toString(),
                ).forEach { (key, value) ->
                    insert.bindText(1, key)
                    insert.bindText(2, value)
                    insert.stepAndReset()
                }
            }
            writer.execSQL("COMMIT")
        } catch (e: Throwable) {
            writer.execSQL("ROLLBACK")
            throw e
        }
        truncateLog()
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
            meta(LAST_SYNC_REMATCHED)?.toInt() ?: 0,
            meta(LAST_SYNC_SHOW_LISTINGS)?.toInt() ?: 0,
        )
    }

    /** How many of [table]'s Listings are Matched otherwise than by their Provider's TMDB ID. */
    private fun rematchedCount(table: String): Int =
        writer.prepare("SELECT count(*) FROM $table WHERE title_key <> $PROVIDER_KEY").use { it.step(); it.getLong(0).toInt() }

    /**
     * The suspects the cloud should be asked about: those without an answer, or whose answer was
     * given before [staleBefore] ([notFoundStaleBefore] when TMDB found nothing).
     */
    fun unansweredSuspects(staleBefore: Instant, notFoundStaleBefore: Instant): List<StoredSuspect> = synchronized(reader) {
        reader.prepare(
            """
            SELECT l.kind, l.name, l.category, l.provider_tmdb FROM listing l LEFT JOIN match_answer a ON a.key = l.match_key
            WHERE l.suspect = 1 AND (a.key IS NULL OR a.fetched_at < CASE WHEN a.tmdb_id IS NULL THEN ? ELSE ? END)
            ORDER BY l.kind, l.position
            """.trimIndent(),
        ).use {
            it.bindLong(1, notFoundStaleBefore.toEpochMilli())
            it.bindLong(2, staleBefore.toEpochMilli())
            buildList {
                while (it.step()) add(StoredSuspect(kind(it.getText(0)), it.getText(1), it.getText(2), if (it.isNull(3)) null else it.getLong(3)))
            }
        }
    }

    /** Keeps the cloud's [answers], by question key (a null TMDB ID: TMDB has no Title by that name), given at [at]. */
    fun saveMatchAnswers(answers: Map<String, Long?>, at: Instant) = synchronized(writer) {
        writer.execSQL("BEGIN")
        try {
            writer.prepare("INSERT OR REPLACE INTO match_answer VALUES (?, ?, ?)").use { insert ->
                answers.forEach { (key, tmdbId) ->
                    insert.bindText(1, key)
                    if (tmdbId == null) insert.bindNull(2) else insert.bindLong(2, tmdbId)
                    insert.bindLong(3, at.toEpochMilli())
                    insert.stepAndReset()
                }
            }
            writer.execSQL("COMMIT")
        } catch (e: Throwable) {
            writer.execSQL("ROLLBACK")
            throw e
        }
    }

    /**
     * Matches the stored catalog's suspects by the answers kept, regrouping its Titles if that moves
     * any Listing, and returns the Sync with its new count of Listings Matched by name.
     */
    fun applyMatchAnswers(): CompletedSync? = synchronized(writer) {
        writer.execSQL("BEGIN IMMEDIATE")
        try {
            writer.execSQL(
                "UPDATE listing SET title_key = $ANSWERED_KEY WHERE suspect = 1 AND match_key IN (SELECT key FROM match_answer) AND title_key IS NOT $ANSWERED_KEY",
            )
            val moved = writer.prepare("SELECT changes()").use { it.step(); it.getLong(0) }
            if (moved > 0) {
                writer.execSQL("DELETE FROM title")
                writer.execSQL("INSERT INTO title (kind, position, key) $TITLES_BY_NEWEST_LISTING".format("listing"))
                writer.execSQL("UPDATE listing SET title = (SELECT position FROM title WHERE key = listing.title_key)")
                writer.prepare("INSERT OR REPLACE INTO meta VALUES (?, ?)").use {
                    it.bindText(1, LAST_SYNC_REMATCHED)
                    it.bindText(2, rematchedCount("listing").toString())
                    it.step()
                }
            }
            writer.execSQL("COMMIT")
        } catch (e: Throwable) {
            writer.execSQL("ROLLBACK")
            throw e
        }
        truncateLog()
        lastSync()
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

    /** Gives [each] every Channel's id and name, in the Provider's order, a page at a time like [forEachTitle]. */
    fun forEachChannel(each: (id: Long, name: String) -> Unit) {
        var after = 0L
        while (true) {
            val page = synchronized(reader) {
                reader.prepare("SELECT position, id, name FROM channel WHERE position > ? ORDER BY position LIMIT $SCAN_PAGE").use {
                    it.bindLong(1, after)
                    buildList { while (it.step()) add(Triple(it.getLong(0), it.getLong(1), it.getText(2))) }
                }
            }
            page.forEach { (_, id, name) -> each(id, name) }
            if (page.size < SCAN_PAGE) return
            after = page.last().first
        }
    }

    /** The Channels among [ids] that are on: every one but Event channels whose Game started before [startedBefore]. */
    fun channels(ids: Collection<Long>, startedBefore: Instant): Map<Long, Channel> = synchronized(reader) {
        if (ids.isEmpty()) return emptyMap()
        ids.chunked(MAX_BOUND).flatMap { chunk ->
            reader.prepare(
                "SELECT $CHANNEL_COLUMNS FROM channel c LEFT JOIN favourite f ON f.id = c.id " +
                    "WHERE (c.event_start IS NULL OR c.event_start >= ?) AND c.id IN (${chunk.joinToString(",") { "?" }})",
            ).use {
                it.bindLong(1, startedBefore.epochSecond)
                chunk.forEachIndexed { i, id -> it.bindLong(i + 2, id) }
                buildList { while (it.step()) add(it.channel()) }
            }
        }.associateBy { it.id }
    }

    /** The Viewer's Favourites that are on (as [channels] tells), in the order they were added. */
    fun favourites(startedBefore: Instant): List<Channel> = synchronized(reader) {
        reader.prepare(
            "SELECT $CHANNEL_COLUMNS FROM favourite f JOIN channel c ON c.id = f.id " +
                "WHERE c.event_start IS NULL OR c.event_start >= ? ORDER BY f.added_at, f.id",
        ).use {
            it.bindLong(1, startedBefore.epochSecond)
            buildList { while (it.step()) add(it.channel()) }
        }
    }

    /** The ids of the Viewer's Favourites, whether or not the Provider still has them. */
    fun favouriteIds(): Set<Long> = synchronized(reader) {
        reader.prepare("SELECT id FROM favourite").use { buildSet { while (it.step()) add(it.getLong(0)) } }
    }

    /** Saves Channel [id] as a Favourite at [at], or forgets it. */
    fun setFavourite(id: Long, favourite: Boolean, at: Instant) = synchronized(writer) {
        // Two added within a millisecond still keep their order.
        val insert = "INSERT OR IGNORE INTO favourite VALUES (?, max(?, (SELECT coalesce(max(added_at), 0) + 1 FROM favourite)))"
        writer.prepare(if (favourite) insert else "DELETE FROM favourite WHERE id = ?").use {
            it.bindLong(1, id)
            if (favourite) it.bindLong(2, at.toEpochMilli())
            it.step()
        }
        Unit
    }

    private fun SQLiteStatement.channel() = Channel(getLong(0), getText(1), if (isNull(2)) null else getText(2), !isNull(3))

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

    /**
     * Calls [each] with every Title of [kind], in list order, and its Listings' names and Categories.
     * Read a page at a time, so other reads go on while [each] works through one.
     */
    fun forEachTitle(kind: TitleKind, each: (TitleId, List<Pair<String, String>>) -> Unit) {
        val count = titleCount(kind)
        for (offset in 0 until count step SCAN_PAGE) {
            titleNames(kind, offset, SCAN_PAGE).forEach { (key, listings) -> each(titleId(key), listings) }
        }
    }

    private fun titleNames(kind: TitleKind, offset: Int, limit: Int): Map<String, List<Pair<String, String>>> = synchronized(reader) {
        reader.prepare(
            """
            SELECT t.key, l.name, l.category FROM title t JOIN listing l ON l.kind = t.kind AND l.title = t.position
            WHERE t.kind = ? AND t.position > ? AND t.position <= ? ORDER BY t.position
            """.trimIndent(),
        ).use {
            it.bindText(1, kindKey(kind))
            it.bindLong(2, offset.toLong())
            it.bindLong(3, offset.toLong() + limit)
            val titles = LinkedHashMap<String, MutableList<Pair<String, String>>>()
            while (it.step()) titles.getOrPut(it.getText(0)) { mutableListOf() } += it.getText(1) to it.getText(2)
            titles
        }
    }

    /**
     * Calls [each] with what search needs of the metadata cached for each Title TMDB has, in
     * whichever language was cached, a page at a time like [forEachTitle]: the names it's found by
     * (as released, in each language TMDB has, and its other names, some more than once), its cast,
     * its genres (in the cached language) and its popularity.
     * SQLite picks them out of the JSON ([TitleMetadata]'s shape), so the bodies, descriptions and
     * all, never reach the heap: for the whole catalog they're hundreds of MB (#106).
     */
    fun forEachSearchMetadata(each: (id: TitleId.Tmdb, names: List<String>, cast: List<String>, genres: List<String>, popularity: Double) -> Unit) {
        class Row(val key: String, val names: List<String>, val cast: List<String>, val genres: List<String>, val popularity: Double)
        val listed = { column: Int, it: SQLiteStatement -> if (it.isNull(column)) emptyList() else it.getText(column).split(SEPARATOR) }
        var after = ""
        while (true) {
            val page = synchronized(reader) {
                reader.prepare(
                    """
                    SELECT key, json_extract(body, '$.originalTitle'), json_extract(body, '$.popularity'),
                        (SELECT group_concat(json_extract(value, '$.title'), char(31)) FROM json_each(body, '$.titles')),
                        (SELECT group_concat(value, char(31)) FROM json_each(body, '$.alternativeTitles')),
                        (SELECT group_concat(json_extract(value, '$.name'), char(31)) FROM json_each(body, '$.cast')),
                        (SELECT group_concat(value, char(31)) FROM json_each(body, '$.genres'))
                    FROM (
                        SELECT title_key AS key, jsonb(max(body)) AS body FROM title_metadata WHERE body IS NOT NULL AND title_key > ?
                        GROUP BY title_key ORDER BY title_key LIMIT $SCAN_PAGE
                    )
                    """.trimIndent(),
                ).use {
                    it.bindText(1, after)
                    buildList {
                        while (it.step()) {
                            val names = listOfNotNull(if (it.isNull(1)) null else it.getText(1)) + listed(3, it) + listed(4, it)
                            add(Row(it.getText(0), names.distinct(), listed(5, it), listed(6, it), if (it.isNull(2)) 0.0 else it.getDouble(2)))
                        }
                    }
                }
            }
            page.forEach { row -> (titleId(row.key) as? TitleId.Tmdb)?.let { id -> each(id, row.names, row.cast, row.genres, row.popularity) } }
            if (page.size < SCAN_PAGE) return
            after = page.last().key
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

    fun saveReadingDay(day: String) = synchronized(writer) {
        writer.prepare("INSERT OR REPLACE INTO meta VALUES (?, ?)").use {
            it.bindText(1, READING_DAY)
            it.bindText(2, day)
            it.step()
        }
        Unit
    }

    /** What the Viewer last picked in the player, by kind (`audio`, `subtitles`): a language, or what turning subtitles off is kept as. */
    fun rememberedTrack(kind: String): String? = meta(REMEMBERED_TRACK + kind)

    fun rememberTrack(kind: String, value: String) = synchronized(writer) {
        writer.prepare("INSERT OR REPLACE INTO meta VALUES (?, ?)").use {
            it.bindText(1, REMEMBERED_TRACK + kind)
            it.bindText(2, value)
            it.step()
        }
        Unit
    }

    /** The file extension of movie Listing [id] (`mkv`), or null when the Provider didn't say or the catalog doesn't have it. */
    fun container(id: Long): String? = synchronized(reader) {
        reader.prepare("SELECT container FROM listing WHERE kind = 'movie' AND id = ?").use {
            it.bindLong(1, id)
            if (it.step() && !it.isNull(0)) it.getText(0) else null
        }
    }

    private fun SQLiteStatement.listing(from: Int) = StoredListing(
        id = getLong(from),
        name = getText(from + 1),
        category = getText(from + 2),
        added = if (isNull(from + 3)) null else getLong(from + 3),
        image = if (isNull(from + 4)) null else getText(from + 4),
    )

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
            "SELECT listing_id, id, season, number, title, added, width, height, audio_language, container FROM episode WHERE listing_id IN ($marks) ORDER BY season, number, id",
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
            writer.prepare("INSERT OR REPLACE INTO episode VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)").use { insert ->
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
    fun saveProgress(id: TitleId, position: Long, duration: Long?, watched: Boolean, at: Instant) = synchronized(writer) {
        writer.prepare("INSERT OR REPLACE INTO progress VALUES (?, ?, ?, ?, ?)").use {
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
    fun saveEpisodeProgress(id: TitleId, episode: EpisodeNumber, position: Long, duration: Long?, watched: Boolean, at: Instant) = synchronized(writer) {
        writer.prepare("INSERT OR REPLACE INTO episode_progress VALUES (?, ?, ?, ?, ?, ?, ?)").use {
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
    fun episodeProgress(id: TitleId): List<StoredEpisodeProgress> = synchronized(reader) {
        reader.prepare("SELECT season, number, position, duration, watched FROM episode_progress WHERE title_key = ? ORDER BY updated_at DESC").use {
            it.bindText(1, key(id))
            buildList {
                while (it.step()) add(StoredEpisodeProgress(EpisodeNumber(it.getInt(0), it.getInt(1)), it.getLong(2), if (it.isNull(3)) null else it.getLong(3), it.getLong(4) != 0L))
            }
        }
    }

    /** Forgets where the Viewer stopped in [id], and whether they finished it. */
    fun forgetProgress(id: TitleId) = synchronized(writer) {
        writer.prepare("DELETE FROM progress WHERE title_key = ?").use {
            it.bindText(1, key(id))
            it.step()
        }
        Unit
    }

    /** The file reads the cloud doesn't have yet, as JSON by Listing id. */
    fun unsharedFileReads(): Map<Long, String> = synchronized(reader) {
        reader.prepare("SELECT listing_id, body FROM file_read WHERE shared = 0 AND body IS NOT NULL").use {
            buildMap { while (it.step()) put(it.getLong(0), it.getText(1)) }
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

    fun saveMovieBitrates(bitrates: Map<Long, Long?>, at: Instant) = synchronized(writer) {
        writer.prepare("INSERT OR REPLACE INTO movie_details VALUES (?, ?, ?)").use { insert ->
            bitrates.forEach { (id, bitrate) ->
                insert.bindLong(1, id)
                if (bitrate == null) insert.bindNull(2) else insert.bindLong(2, bitrate)
                insert.bindLong(3, at.toEpochMilli())
                insert.stepAndReset()
            }
        }
    }
    /** Where the Viewer stopped in [id], or null when they never played it. */
    fun progress(id: TitleId): StoredProgress? = synchronized(reader) {
        reader.prepare("SELECT title_key, position, duration, watched FROM progress WHERE title_key = ?").use {
            it.bindText(1, key(id))
            if (it.step()) it.progress() else null
        }
    }

    /** The Titles the Viewer stopped in without finishing, last played first, at most [limit]. */
    fun inProgress(limit: Int): List<StoredProgress> = synchronized(reader) {
        reader.prepare("SELECT title_key, position, duration, watched FROM progress WHERE watched = 0 ORDER BY updated_at DESC LIMIT ?").use {
            it.bindLong(1, limit.toLong())
            buildList { while (it.step()) add(it.progress()) }
        }
    }

    private fun SQLiteStatement.progress() =
        StoredProgress(titleId(getText(0)), getLong(1), if (isNull(2)) null else getLong(2), getLong(3) != 0L)

    /** When the Viewer last played each Title in [inProgress] and each Show they played an Episode of, last played first, at most [limit]. */
    fun lastPlayed(limit: Int): List<Pair<TitleId, Instant>> = synchronized(reader) {
        reader.prepare(
            "SELECT title_key, updated_at FROM progress WHERE watched = 0 " +
                "UNION ALL SELECT title_key, MAX(updated_at) FROM episode_progress GROUP BY title_key ORDER BY 2 DESC LIMIT ?",
        ).use {
            it.bindLong(1, limit.toLong())
            buildList { while (it.step()) add(titleId(it.getText(0)) to Instant.ofEpochMilli(it.getLong(1))) }
        }
    }

    override fun deviceToken(): String? = meta(DEVICE_TOKEN)

    override fun saveDeviceToken(token: String) = synchronized(writer) {
        writer.prepare("INSERT OR REPLACE INTO meta VALUES (?, ?)").use {
            it.bindText(1, DEVICE_TOKEN)
            it.bindText(2, token)
            it.step()
        }
        Unit
    }

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

    private companion object {
        /** Raised whenever the stored catalog's shape changes, so an older one is dropped and Synced again. */
        const val SCHEMA_VERSION = 4
        /** Titles read per lock by a whole-catalog scan: a few milliseconds, even on the stick. */
        const val SCAN_PAGE = 2_000
        /** What [forEachSearchMetadata] joins names with: the ASCII unit separator, which no name has. */
        const val SEPARATOR = '\u001f'
        /** The most the write-ahead log keeps on the device's storage between checkpoints, in bytes. */
        const val WAL_SIZE_LIMIT = 4L * 1024 * 1024
        const val LISTING_TABLE =
            "CREATE TABLE IF NOT EXISTS %s (kind TEXT NOT NULL, position INTEGER NOT NULL, id INTEGER NOT NULL, name TEXT NOT NULL, category TEXT NOT NULL, " +
                "added INTEGER, image TEXT, title INTEGER NOT NULL, title_key TEXT NOT NULL, provider_tmdb INTEGER, match_key TEXT NOT NULL, suspect INTEGER NOT NULL, " +
                "container TEXT, PRIMARY KEY (kind, position))"
        const val CHANNEL_TABLE =
            "CREATE TABLE IF NOT EXISTS %s (id INTEGER PRIMARY KEY, position INTEGER NOT NULL, name TEXT NOT NULL, image TEXT, event_start INTEGER)"
        const val CHANNEL_COLUMNS = "c.id, c.name, c.image, f.id"
        /** SQLite's limit on bound parameters in one statement is 999 in older builds. */
        const val MAX_BOUND = 900
        const val TITLE_TABLE = "CREATE TABLE IF NOT EXISTS %s (kind TEXT NOT NULL, position INTEGER NOT NULL, key TEXT NOT NULL, PRIMARY KEY (kind, position))"
        /** Made after a swap: an index keeps its name when its table is renamed. */
        val INDEXES = listOf(
            "CREATE INDEX IF NOT EXISTS listing_title ON listing (kind, title)",
            "CREATE UNIQUE INDEX IF NOT EXISTS title_key ON title (key)",
            "CREATE INDEX IF NOT EXISTS channel_position ON channel (position)",
        )
        const val LISTING_COLUMNS = "l.id, l.name, l.category, l.added, l.image"
        /** Each kind's Titles from the Listings in %s, listed by their newest Listing. */
        const val TITLES_BY_NEWEST_LISTING =
            "SELECT kind, row_number() OVER (PARTITION BY kind ORDER BY max(added) DESC, min(name), min(id)), title_key FROM %s GROUP BY kind, title_key"
        /** A Listing's Title key by its Provider's TMDB ID, as [key] makes it. */
        const val PROVIDER_KEY = "CASE WHEN provider_tmdb IS NULL THEN kind || '/listing/' || id ELSE kind || '/' || provider_tmdb END"
        /** A suspect Listing's Title key by the answer kept for it, as [key] makes it. */
        const val ANSWERED_KEY =
            "(SELECT CASE WHEN a.tmdb_id IS NULL THEN kind || '/listing/' || id ELSE kind || '/' || a.tmdb_id END FROM match_answer a WHERE a.key = match_key)"
        const val UNKNOWN_PREFIX_TABLE =
            "CREATE TABLE IF NOT EXISTS %s (prefix TEXT PRIMARY KEY, listings INTEGER NOT NULL, example TEXT NOT NULL)"
        const val LAST_SYNC_AT = "last_sync_at"
        const val LAST_SYNC_MILLIS = "last_sync_millis"
        const val LAST_SYNC_LISTINGS = "last_sync_movie_listings"
        const val LAST_SYNC_SHOW_LISTINGS = "last_sync_show_listings"
        const val LAST_SYNC_REMATCHED = "last_sync_rematched_listings"
        /** The token the cloud gave this device. Lost with the catalog's shape changes, the device just registers again. */
        const val DEVICE_TOKEN = "cloud_device_token"
        const val REMEMBERED_TRACK = "remembered_track_"
        const val READING_DAY = "reading_day"
    }
}

/** A Title as stored: its Listings, before the Prefix table describes them. */
internal class StoredTitle(val id: TitleId, val listings: List<StoredListing>)

/** A Title's cached metadata JSON (null: TMDB has no such Title) and when the cloud sent it. */
internal class StoredMetadata(val body: String?, val fetchedAt: Instant)

/** [added] is when a movie Listing was added, or when the Provider last changed a show Listing. */
internal class StoredListing(val id: Long, val name: String, val category: String, val added: Long?, val image: String?)

/** A Listing whose Match is checked by name, with the TMDB ID its Provider gave it. */
internal class StoredSuspect(val kind: TitleKind, val name: String, val category: String, val providerTmdbId: Long?)

/** Where the Viewer stopped in a Title, in milliseconds, out of its [duration] when the player knew it. */
internal class StoredProgress(val id: TitleId, val position: Long, val duration: Long?, val watched: Boolean)

/** Where the Viewer stopped in a Show's [episode], as [StoredProgress] is for a Title. */
internal class StoredEpisodeProgress(val episode: EpisodeNumber, val position: Long, val duration: Long?, val watched: Boolean)

/** A show Listing's kept show details: when the Provider had last changed the Show, and when they were read. */
internal class StoredShowDetails(val modified: Long?, val fetchedAt: Instant, val episodes: List<ProviderEpisode>)

/** How a kind is spelled in the database, and at the start of a Title key. */
private fun kindKey(kind: TitleKind): String = kind.name.lowercase()

private fun kind(key: String): TitleKind = TitleKind.entries.single { kindKey(it) == key }

private fun key(id: TitleId): String = when (id) {
    is TitleId.Tmdb -> "${kindKey(id.kind)}/${id.tmdbId}"
    is TitleId.Unmatched -> "${kindKey(id.kind)}/listing/${id.listingId}"
}

private fun titleId(key: String): TitleId {
    val parts = key.split('/')
    val kind = kind(parts[0])
    return if (parts[1] == "listing") TitleId.Unmatched(kind, parts[2].toLong()) else TitleId.Tmdb(kind, parts[1].toLong())
}

private fun SQLiteStatement.stepAndReset() {
    step()
    reset()
}
