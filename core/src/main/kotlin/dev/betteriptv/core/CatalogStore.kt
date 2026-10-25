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
 * Movie Listings and Titles are stored with their list position as the primary key, so any slice
 * of a 144k-row list is an index lookup. Each Listing points at its Title's position, and a Title is
 * found by its key (`movie/238`, or `movie/listing/1001` for an Unmatched one).
 * Hidden Listings (Clutter, Sports replays) get no position and no Title.
 *
 * A Listing is Matched by its Provider's TMDB ID unless it is a suspect: it has no ID, or its name
 * matches none of the other Listings with its ID. A suspect is Matched by the cloud's answer about
 * it, once there is one; answers are kept here, so the next Sync Matches it straight away.
 *
 * Title metadata from the cloud is cached here too, per Title and language, along with the
 * device's cloud token.
 */
internal class CatalogStore(path: String) : Closeable, DeviceTokenStore {
    private val driver = BundledSQLiteDriver()
    private val writer: SQLiteConnection = driver.open(path).apply {
        execSQL("PRAGMA journal_mode=WAL")
        execSQL("PRAGMA synchronous=NORMAL")
        execSQL("CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        if (prepare("PRAGMA user_version").use { it.step(); it.getLong(0) } < SCHEMA_VERSION) {
            // A catalog stored in an older shape is dropped; the next Sync stores it again.
            execSQL("DROP TABLE IF EXISTS movie_listing")
            execSQL("DROP TABLE IF EXISTS title")
            execSQL("DELETE FROM meta")
            execSQL("PRAGMA user_version = $SCHEMA_VERSION")
        }
        execSQL(MOVIE_LISTING_TABLE.format("movie_listing"))
        execSQL(TITLE_TABLE.format("title"))
        INDEXES.forEach { execSQL(it) }
        execSQL(UNKNOWN_PREFIX_TABLE.format("unknown_prefix"))
        // Keyed by question (MatchQuestion.key), so it outlives Syncs. A null tmdb_id: TMDB has no Title by that name.
        execSQL("CREATE TABLE IF NOT EXISTS match_answer (key TEXT PRIMARY KEY, tmdb_id INTEGER, fetched_at INTEGER NOT NULL)")
        // Keyed by Title, not by Listing, so it outlives Syncs and catalog shape changes.
        execSQL(
            "CREATE TABLE IF NOT EXISTS title_metadata (title_key TEXT NOT NULL, language TEXT NOT NULL, body TEXT, fetched_at INTEGER NOT NULL, " +
                "PRIMARY KEY (title_key, language))",
        )
    }
    private val reader: SQLiteConnection = driver.open(path)

    class Staging internal constructor(connection: SQLiteConnection) {
        private val insertCategory = connection.prepare("INSERT OR REPLACE INTO staging_category VALUES (?, ?)")
        private val insertListing = connection.prepare("INSERT INTO staging_listing VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?)")

        fun category(id: String, name: String) = insertCategory.run {
            bindText(1, id)
            bindText(2, name)
            stepAndReset()
        }

        /**
         * Stores a movie Listing, Matched to its Title by the Provider's TMDB ID in [match], or standing
         * alone without one, until the cloud answers [match] otherwise. A [hidden] one is kept out of
         * the movie list and gets no Title. [container] is its file's extension (`mkv`), which its
         * stream location ends with.
         */
        fun movieListing(id: Long, name: String, categoryId: String?, added: Long?, image: String?, hidden: Boolean, match: MatchQuestion, container: String?) = insertListing.run {
            bindLong(1, id)
            bindText(2, name)
            if (categoryId == null) bindNull(3) else bindText(3, categoryId)
            if (added == null) bindNull(4) else bindLong(4, added)
            bindLong(5, if (hidden) 1 else 0)
            if (image == null) bindNull(6) else bindText(6, image)
            bindText(7, key(match.tmdbId?.let { TitleId.Tmdb(TitleKind.Movie, it) } ?: TitleId.Unmatched(TitleKind.Movie, id)))
            if (match.tmdbId == null) bindNull(8) else bindLong(8, match.tmdbId)
            bindText(9, matchName(match.name))
            bindText(10, match.key)
            if (container == null) bindNull(11) else bindText(11, container)
            stepAndReset()
        }

        internal fun close() {
            insertCategory.close()
            insertListing.close()
        }
    }

    /**
     * Replaces the movie Listings with what [fill] writes, Matching suspects by the answers kept.
     * [completed] runs just before the swap, with the new Listing count and how many Listings were
     * Matched by name, and the Sync it returns is recorded in the same transaction.
     */
    fun replaceMovieListings(fill: (Staging) -> Unit, completed: (listingCount: Int, rematched: Int) -> CompletedSync) = synchronized(writer) {
        writer.execSQL("DROP TABLE IF EXISTS staging_category")
        writer.execSQL("DROP TABLE IF EXISTS staging_listing")
        writer.execSQL("DROP TABLE IF EXISTS staging_movie_listing")
        writer.execSQL("DROP TABLE IF EXISTS staging_title")
        writer.execSQL("CREATE TABLE staging_category (id TEXT PRIMARY KEY, name TEXT NOT NULL)")
        writer.execSQL(
            "CREATE TABLE staging_listing (id INTEGER, name TEXT, category_id TEXT, added INTEGER, hidden INTEGER, image TEXT, title_key TEXT, " +
                "provider_tmdb INTEGER, match_name TEXT, match_key TEXT, suspect INTEGER, container TEXT)",
        )
        writer.execSQL(MOVIE_LISTING_TABLE.format("staging_movie_listing"))
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
            writer.execSQL("CREATE INDEX staging_listing_siblings ON staging_listing (provider_tmdb, match_name)")
            writer.execSQL(
                """
                UPDATE staging_listing SET suspect = 1 WHERE hidden = 0 AND (provider_tmdb IS NULL OR NOT EXISTS (
                    SELECT 1 FROM staging_listing o
                    WHERE o.provider_tmdb = staging_listing.provider_tmdb AND o.match_name = staging_listing.match_name AND o.hidden = 0 AND o.rowid <> staging_listing.rowid
                ))
                """.trimIndent(),
            )
            writer.execSQL("UPDATE staging_listing SET title_key = $ANSWERED_KEY WHERE suspect = 1 AND match_key IN (SELECT key FROM match_answer)")
            // Titles are listed by their newest Listing.
            writer.execSQL(
                """
                INSERT INTO staging_title (position, key)
                SELECT row_number() OVER (ORDER BY max(added) DESC, min(name), min(id)), title_key
                FROM staging_listing WHERE hidden = 0 GROUP BY title_key
                """.trimIndent(),
            )
            writer.execSQL("CREATE INDEX staging_title_key ON staging_title (key)")
            writer.execSQL(
                """
                INSERT INTO staging_movie_listing (position, id, name, category, added, image, title, title_key, provider_tmdb, match_key, suspect, container)
                SELECT row_number() OVER (ORDER BY l.added DESC, l.name, l.id), l.id, l.name, coalesce(c.name, ''), l.added, l.image, t.position,
                    l.title_key, l.provider_tmdb, l.match_key, l.suspect, l.container
                FROM staging_listing l
                LEFT JOIN staging_category c ON c.id = l.category_id
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
            writer.execSQL("DROP TABLE IF EXISTS staging_movie_listing")
            writer.execSQL("DROP TABLE IF EXISTS staging_title")
            throw e
        }

        val count = writer.prepare("SELECT count(*) FROM staging_movie_listing").use { it.step(); it.getLong(0).toInt() }
        val sync = completed(count, rematchedCount("staging_movie_listing"))
        writer.execSQL("BEGIN IMMEDIATE")
        try {
            writer.execSQL("DROP TABLE movie_listing")
            writer.execSQL("DROP TABLE title")
            writer.execSQL("ALTER TABLE staging_movie_listing RENAME TO movie_listing")
            writer.execSQL("ALTER TABLE staging_title RENAME TO title")
            INDEXES.forEach { writer.execSQL(it) }
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
        )
    }

    /** How many of [table]'s Listings are Matched otherwise than by their Provider's TMDB ID. */
    private fun rematchedCount(table: String): Int =
        writer.prepare("SELECT count(*) FROM $table WHERE title_key <> $PROVIDER_KEY").use { it.step(); it.getLong(0).toInt() }

    /**
     * The suspects the cloud should be asked about: those without an answer, or whose answer was
     * given before [staleBefore] ([notFoundStaleBefore] when TMDB found nothing).
     */
    fun unansweredSuspects(staleBefore: Instant, notFoundStaleBefore: Instant): List<StoredListing> = synchronized(reader) {
        reader.prepare(
            """
            SELECT l.id, l.name, l.category, l.added, l.provider_tmdb FROM movie_listing l LEFT JOIN match_answer a ON a.key = l.match_key
            WHERE l.suspect = 1 AND (a.key IS NULL OR a.fetched_at < CASE WHEN a.tmdb_id IS NULL THEN ? ELSE ? END)
            ORDER BY l.position
            """.trimIndent(),
        ).use {
            it.bindLong(1, notFoundStaleBefore.toEpochMilli())
            it.bindLong(2, staleBefore.toEpochMilli())
            buildList {
                while (it.step()) {
                    add(StoredListing(it.getLong(0), it.getText(1), it.getText(2), if (it.isNull(3)) null else it.getLong(3), image = null, providerTmdbId = if (it.isNull(4)) null else it.getLong(4)))
                }
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
                "UPDATE movie_listing SET title_key = $ANSWERED_KEY WHERE suspect = 1 AND match_key IN (SELECT key FROM match_answer) AND title_key IS NOT $ANSWERED_KEY",
            )
            val moved = writer.prepare("SELECT changes()").use { it.step(); it.getLong(0) }
            if (moved > 0) {
                writer.execSQL("DELETE FROM title")
                writer.execSQL(
                    "INSERT INTO title (position, key) SELECT row_number() OVER (ORDER BY max(added) DESC, min(name), min(id)), title_key FROM movie_listing GROUP BY title_key",
                )
                writer.execSQL("UPDATE movie_listing SET title = (SELECT position FROM title WHERE key = movie_listing.title_key)")
                writer.prepare("INSERT OR REPLACE INTO meta VALUES (?, ?)").use {
                    it.bindText(1, LAST_SYNC_REMATCHED)
                    it.bindText(2, rematchedCount("movie_listing").toString())
                    it.step()
                }
            }
            writer.execSQL("COMMIT")
        } catch (e: Throwable) {
            writer.execSQL("ROLLBACK")
            throw e
        }
        lastSync()
    }

    private fun unknownPrefixes(): List<UnknownPrefix> = synchronized(reader) {
        reader.prepare("SELECT prefix, listings, example FROM unknown_prefix ORDER BY listings DESC, prefix").use {
            buildList { while (it.step()) add(UnknownPrefix(it.getText(0), it.getLong(1).toInt(), it.getText(2))) }
        }
    }

    fun movieListingCount(): Int = synchronized(reader) {
        reader.prepare("SELECT coalesce(max(position), 0) FROM movie_listing").use { it.step(); it.getLong(0).toInt() }
    }

    /** Movie Listings from [offset], with [attributes] working out each one's Version attributes from its name and Category. */
    fun movieListings(offset: Int, limit: Int, attributes: (name: String, category: String) -> VersionAttributes): List<MovieListing> = synchronized(reader) {
        reader.prepare("SELECT id, name, category FROM movie_listing WHERE position > ? AND position <= ? ORDER BY position").use {
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

    fun titleCount(): Int = synchronized(reader) {
        reader.prepare("SELECT coalesce(max(position), 0) FROM title").use { it.step(); it.getLong(0).toInt() }
    }

    /** Titles from [offset] (0-based), each with its Listings. */
    fun titles(offset: Int, limit: Int): List<StoredTitle> = synchronized(reader) {
        reader.prepare(
            """
            SELECT t.key, $LISTING_COLUMNS FROM title t JOIN movie_listing l ON l.title = t.position
            WHERE t.position > ? AND t.position <= ? ORDER BY t.position
            """.trimIndent(),
        ).use {
            it.bindLong(1, offset.toLong())
            it.bindLong(2, offset.toLong() + limit)
            val titles = LinkedHashMap<String, MutableList<StoredListing>>()
            while (it.step()) titles.getOrPut(it.getText(0)) { mutableListOf() } += it.listing(1)
            titles.map { (key, listings) -> StoredTitle(titleId(key), listings) }
        }
    }

    /** The Title [id] with its Listings, or null when the catalog doesn't have it. */
    fun title(id: TitleId): StoredTitle? = synchronized(reader) {
        reader.prepare(
            "SELECT $LISTING_COLUMNS FROM title t JOIN movie_listing l ON l.title = t.position WHERE t.key = ?",
        ).use {
            it.bindText(1, key(id))
            val listings = buildList { while (it.step()) add(it.listing(0)) }
            listings.takeIf { it.isNotEmpty() }?.let { StoredTitle(id, it) }
        }
    }

    /** The file extension of movie Listing [id] (`mkv`), or null when the Provider didn't say or the catalog doesn't have it. */
    fun container(id: Long): String? = synchronized(reader) {
        reader.prepare("SELECT container FROM movie_listing WHERE id = ?").use {
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
        const val SCHEMA_VERSION = 3
        const val MOVIE_LISTING_TABLE =
            "CREATE TABLE IF NOT EXISTS %s (position INTEGER PRIMARY KEY, id INTEGER NOT NULL, name TEXT NOT NULL, category TEXT NOT NULL, " +
                "added INTEGER, image TEXT, title INTEGER NOT NULL, title_key TEXT NOT NULL, provider_tmdb INTEGER, match_key TEXT NOT NULL, suspect INTEGER NOT NULL, " +
                "container TEXT)"
        const val TITLE_TABLE = "CREATE TABLE IF NOT EXISTS %s (position INTEGER PRIMARY KEY, key TEXT NOT NULL)"
        /** Made after a swap: an index keeps its name when its table is renamed. */
        val INDEXES = listOf(
            "CREATE INDEX IF NOT EXISTS movie_listing_title ON movie_listing (title)",
            "CREATE UNIQUE INDEX IF NOT EXISTS title_key ON title (key)",
        )
        const val LISTING_COLUMNS = "l.id, l.name, l.category, l.added, l.image"
        /** A Listing's Title key by its Provider's TMDB ID, as [key] makes it. */
        const val PROVIDER_KEY = "CASE WHEN provider_tmdb IS NULL THEN 'movie/listing/' || id ELSE 'movie/' || provider_tmdb END"
        /** A suspect Listing's Title key by the answer kept for it, as [key] makes it. */
        const val ANSWERED_KEY = "(SELECT CASE WHEN a.tmdb_id IS NULL THEN 'movie/listing/' || id ELSE 'movie/' || a.tmdb_id END FROM match_answer a WHERE a.key = match_key)"
        const val UNKNOWN_PREFIX_TABLE =
            "CREATE TABLE IF NOT EXISTS %s (prefix TEXT PRIMARY KEY, listings INTEGER NOT NULL, example TEXT NOT NULL)"
        const val LAST_SYNC_AT = "last_sync_at"
        const val LAST_SYNC_MILLIS = "last_sync_millis"
        const val LAST_SYNC_LISTINGS = "last_sync_movie_listings"
        const val LAST_SYNC_REMATCHED = "last_sync_rematched_listings"
        /** The token the cloud gave this device. Lost with the catalog's shape changes, the device just registers again. */
        const val DEVICE_TOKEN = "cloud_device_token"
    }
}

/** A Title as stored: its Listings, before the Prefix table describes them. */
internal class StoredTitle(val id: TitleId, val listings: List<StoredListing>)

/** A Title's cached metadata JSON (null: TMDB has no such Title) and when the cloud sent it. */
internal class StoredMetadata(val body: String?, val fetchedAt: Instant)

internal class StoredListing(val id: Long, val name: String, val category: String, val added: Long?, val image: String?, val providerTmdbId: Long? = null)

private fun key(id: TitleId): String = when (id) {
    is TitleId.Tmdb -> "${id.kind.name.lowercase()}/${id.tmdbId}"
    is TitleId.Unmatched -> "${id.kind.name.lowercase()}/listing/${id.listingId}"
}

private fun titleId(key: String): TitleId {
    val parts = key.split('/')
    val kind = TitleKind.entries.single { it.name.lowercase() == parts[0] }
    return if (parts[1] == "listing") TitleId.Unmatched(kind, parts[2].toLong()) else TitleId.Tmdb(kind, parts[1].toLong())
}

private fun SQLiteStatement.stepAndReset() {
    step()
    reset()
}
