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
        // Keyed by Title, not by Listing, so it outlives Syncs and catalog shape changes.
        execSQL(
            "CREATE TABLE IF NOT EXISTS title_metadata (title_key TEXT NOT NULL, language TEXT NOT NULL, body TEXT, fetched_at INTEGER NOT NULL, " +
                "PRIMARY KEY (title_key, language))",
        )
    }
    private val reader: SQLiteConnection = driver.open(path)

    class Staging internal constructor(connection: SQLiteConnection) {
        private val insertCategory = connection.prepare("INSERT OR REPLACE INTO staging_category VALUES (?, ?)")
        private val insertListing = connection.prepare("INSERT INTO staging_listing VALUES (?, ?, ?, ?, ?, ?, ?)")

        fun category(id: String, name: String) = insertCategory.run {
            bindText(1, id)
            bindText(2, name)
            stepAndReset()
        }

        /**
         * Stores a movie Listing, Matched to its Title by [tmdbId], or standing alone without one.
         * A [hidden] one is kept out of the movie list and gets no Title.
         */
        fun movieListing(id: Long, name: String, categoryId: String?, added: Long?, tmdbId: Long?, image: String?, hidden: Boolean) = insertListing.run {
            bindLong(1, id)
            bindText(2, name)
            if (categoryId == null) bindNull(3) else bindText(3, categoryId)
            if (added == null) bindNull(4) else bindLong(4, added)
            bindLong(5, if (hidden) 1 else 0)
            if (image == null) bindNull(6) else bindText(6, image)
            bindText(7, key(tmdbId?.let { TitleId.Tmdb(TitleKind.Movie, it) } ?: TitleId.Unmatched(TitleKind.Movie, id)))
            stepAndReset()
        }

        internal fun close() {
            insertCategory.close()
            insertListing.close()
        }
    }

    /**
     * Replaces the movie Listings with what [fill] writes. [completed] runs just before the swap,
     * with the new Listing count, and the Sync it returns is recorded in the same transaction.
     */
    fun replaceMovieListings(fill: (Staging) -> Unit, completed: (listingCount: Int) -> CompletedSync) = synchronized(writer) {
        writer.execSQL("DROP TABLE IF EXISTS staging_category")
        writer.execSQL("DROP TABLE IF EXISTS staging_listing")
        writer.execSQL("DROP TABLE IF EXISTS staging_movie_listing")
        writer.execSQL("DROP TABLE IF EXISTS staging_title")
        writer.execSQL("CREATE TABLE staging_category (id TEXT PRIMARY KEY, name TEXT NOT NULL)")
        writer.execSQL("CREATE TABLE staging_listing (id INTEGER, name TEXT, category_id TEXT, added INTEGER, hidden INTEGER, image TEXT, title_key TEXT)")
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
                INSERT INTO staging_movie_listing (position, id, name, category, added, image, title)
                SELECT row_number() OVER (ORDER BY l.added DESC, l.name, l.id), l.id, l.name, coalesce(c.name, ''), l.added, l.image, t.position
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
        val sync = completed(count)
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
        )
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
        const val SCHEMA_VERSION = 1
        const val MOVIE_LISTING_TABLE =
            "CREATE TABLE IF NOT EXISTS %s (position INTEGER PRIMARY KEY, id INTEGER NOT NULL, name TEXT NOT NULL, category TEXT NOT NULL, " +
                "added INTEGER, image TEXT, title INTEGER NOT NULL)"
        const val TITLE_TABLE = "CREATE TABLE IF NOT EXISTS %s (position INTEGER PRIMARY KEY, key TEXT NOT NULL)"
        /** Made after a swap: an index keeps its name when its table is renamed. */
        val INDEXES = listOf(
            "CREATE INDEX IF NOT EXISTS movie_listing_title ON movie_listing (title)",
            "CREATE UNIQUE INDEX IF NOT EXISTS title_key ON title (key)",
        )
        const val LISTING_COLUMNS = "l.id, l.name, l.category, l.added, l.image"
        const val UNKNOWN_PREFIX_TABLE =
            "CREATE TABLE IF NOT EXISTS %s (prefix TEXT PRIMARY KEY, listings INTEGER NOT NULL, example TEXT NOT NULL)"
        const val LAST_SYNC_AT = "last_sync_at"
        const val LAST_SYNC_MILLIS = "last_sync_millis"
        const val LAST_SYNC_LISTINGS = "last_sync_movie_listings"
        /** The token the cloud gave this device. Lost with the catalog's shape changes, the device just registers again. */
        const val DEVICE_TOKEN = "cloud_device_token"
    }
}

/** A Title as stored: its Listings, before the Prefix table describes them. */
internal class StoredTitle(val id: TitleId, val listings: List<StoredListing>)

/** A Title's cached metadata JSON (null: TMDB has no such Title) and when the cloud sent it. */
internal class StoredMetadata(val body: String?, val fetchedAt: Instant)

internal class StoredListing(val id: Long, val name: String, val category: String, val added: Long?, val image: String?)

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
