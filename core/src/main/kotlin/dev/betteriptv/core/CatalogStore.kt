package dev.betteriptv.core

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import java.io.Closeable

/**
 * The device's copy of the Provider catalog, in SQLite.
 *
 * A Sync fills staging tables and swaps them in with one transaction, so readers see either the
 * previous catalog or the new one, never a mix, and a failed Sync leaves the previous one untouched.
 * Movies are stored with their list position as the primary key, so any slice of a 144k-row list is
 * an index lookup.
 */
internal class CatalogStore(path: String) : Closeable {
    private val driver = BundledSQLiteDriver()
    private val writer: SQLiteConnection = driver.open(path).apply {
        execSQL("PRAGMA journal_mode=WAL")
        execSQL("PRAGMA synchronous=NORMAL")
        execSQL("CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        execSQL(MOVIE_TABLE.format("movie"))
    }
    private val reader: SQLiteConnection = driver.open(path)

    class Staging internal constructor(private val connection: SQLiteConnection) {
        private val insertCategory = connection.prepare("INSERT OR REPLACE INTO staging_category VALUES (?, ?)")
        private val insertMovie = connection.prepare("INSERT INTO staging_listing VALUES (?, ?, ?, ?)")

        fun category(id: String, name: String) = insertCategory.run {
            bindText(1, id); bindText(2, name); step(); reset()
        }

        fun movie(id: Long, name: String, categoryId: String?, added: Long?) = insertMovie.run {
            bindLong(1, id)
            bindText(2, name)
            if (categoryId == null) bindNull(3) else bindText(3, categoryId)
            if (added == null) bindNull(4) else bindLong(4, added)
            step()
            reset()
        }

        internal fun close() {
            insertCategory.close()
            insertMovie.close()
        }
    }

    /**
     * Replaces the movie catalog with what [fill] writes. [meta] runs before the swap with the new
     * movie count, and its entries are committed together with the new catalog.
     */
    fun replaceMovies(fill: (Staging) -> Unit, meta: (movieCount: Int) -> Map<String, String>) = synchronized(writer) {
        writer.execSQL("DROP TABLE IF EXISTS staging_category")
        writer.execSQL("DROP TABLE IF EXISTS staging_listing")
        writer.execSQL("DROP TABLE IF EXISTS staging_movie")
        writer.execSQL("CREATE TABLE staging_category (id TEXT PRIMARY KEY, name TEXT NOT NULL)")
        writer.execSQL("CREATE TABLE staging_listing (id INTEGER, name TEXT, category_id TEXT, added INTEGER)")
        writer.execSQL(MOVIE_TABLE.format("staging_movie"))
        try {
            writer.execSQL("BEGIN")
            val staging = Staging(writer)
            try {
                fill(staging)
            } finally {
                staging.close()
            }
            writer.execSQL(
                """
                INSERT INTO staging_movie (position, id, name, category)
                SELECT row_number() OVER (ORDER BY l.added DESC, l.name, l.id), l.id, l.name, coalesce(c.name, '')
                FROM staging_listing l LEFT JOIN staging_category c ON c.id = l.category_id
                """.trimIndent(),
            )
            writer.execSQL("DROP TABLE staging_listing")
            writer.execSQL("DROP TABLE staging_category")
            writer.execSQL("COMMIT")
        } catch (e: Throwable) {
            if (writer.inTransaction()) writer.execSQL("ROLLBACK")
            writer.execSQL("DROP TABLE IF EXISTS staging_movie")
            throw e
        }

        val count = writer.prepare("SELECT count(*) FROM staging_movie").use { it.step(); it.getLong(0).toInt() }
        val entries = meta(count)
        writer.execSQL("BEGIN IMMEDIATE")
        try {
            writer.execSQL("DROP TABLE movie")
            writer.execSQL("ALTER TABLE staging_movie RENAME TO movie")
            writer.prepare("INSERT OR REPLACE INTO meta VALUES (?, ?)").use { insert ->
                entries.forEach { (key, value) -> insert.bindText(1, key); insert.bindText(2, value); insert.step(); insert.reset() }
            }
            writer.execSQL("COMMIT")
        } catch (e: Throwable) {
            writer.execSQL("ROLLBACK")
            throw e
        }
    }

    fun movieCount(): Int = synchronized(reader) {
        reader.prepare("SELECT coalesce(max(position), 0) FROM movie").use { it.step(); it.getLong(0).toInt() }
    }

    fun movies(offset: Int, limit: Int): List<MovieListing> = synchronized(reader) {
        reader.prepare("SELECT id, name, category FROM movie WHERE position > ? AND position <= ? ORDER BY position").use {
            it.bindLong(1, offset.toLong())
            it.bindLong(2, offset.toLong() + limit)
            buildList { while (it.step()) add(MovieListing(it.getLong(0), it.getText(1), it.getText(2))) }
        }
    }

    fun meta(key: String): String? = synchronized(reader) {
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
        const val MOVIE_TABLE = "CREATE TABLE IF NOT EXISTS %s (position INTEGER PRIMARY KEY, id INTEGER NOT NULL, name TEXT NOT NULL, category TEXT NOT NULL)"
    }
}
