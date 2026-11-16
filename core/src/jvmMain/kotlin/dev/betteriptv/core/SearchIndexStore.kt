package dev.betteriptv.core

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.execSQL
import kotlinx.datetime.LocalDate
import kotlin.random.Random

/**
 * What the search index takes from a Listing, as a Prefix table reads it: the name it is found by and
 * its year, the language of its Versions, the genres its Category and the Provider's genre field
 * name, and the people in the Provider's cast.
 */
internal class SearchSources(private val table: PrefixTable) {
    /** A Category's genres are worked out once, not once per Listing filed under it. */
    private val categoryGenres = HashMap<String, List<String>>()

    fun clean(name: String): PrefixTable.CleanName = table.cleanName(name)

    fun language(name: String, category: String): String? = table.language(name, category)?.let(::primaryLanguage)

    fun genres(category: String, field: String?): List<String> = categoryGenres.getOrPut(category) { table.titleGenres(category) } + table.genres(field)

    fun cast(raw: String?): List<String> = castNames(raw)
}

/** One Listing of a Title, as the search index reads it; [clean] is its cleaned name when the Sync already worked it out. */
internal class IndexedListing(val name: String, val category: String, val genre: String?, val cast: String?, val clean: PrefixTable.CleanName? = null)

/** What a Title's Listings say of it, for the search index: the names it is found by, its languages, its cast and its genres (English names). */
internal class IndexedTitle(listings: List<IndexedListing>, sources: SearchSources) {
    val names: Set<String> = listings.flatMapTo(HashSet()) { listing ->
        val clean = listing.clean ?: sources.clean(listing.name)
        listOfNotNull(clean.name, clean.year?.let { "${clean.name} $it" })
    }
    val languages: Set<String> = listings.mapNotNullTo(HashSet()) { sources.language(it.name, it.category) }
    val cast: Set<String> = listings.flatMapTo(LinkedHashSet()) { sources.cast(it.cast) }
    val genres: List<String> = listings.flatMapTo(LinkedHashSet()) { sources.genres(it.category, it.genre) }.map(Genres::english).distinct()
}

/**
 * The search index in SQLite (#584, ADR 0020): built by a Sync with its stages and landed with the
 * catalog, so Search answers as soon as a catalog is there, with nothing built at launch.
 *
 * Two full-text tables hold the words: `search_channel`, one row per Channel, built with the Channels
 * stage, and `search_catalog`, built with the Titles stage, whose rows are Titles, people, genres and
 * Sports replays, told apart by their row ids ([SCOPE_SHIFT]). Both take words as the Core folds them
 * ([SearchIndex.words]), so SQLite's own notion of a word or an accent never matters. A query's words
 * must each match a word of a row: the full-text index finds whole words and started ones, and for
 * typos the Core scans the index's vocabulary with today's rule ([SearchIndex.distance]) and asks for
 * the words it tolerates. A row's distance is its worst word's, as before.
 *
 * Beside them, plain tables keep what ranking reads: each Title's language set, a hash of each of its
 * names (a whole name ranks first), each person's and each genre's Titles, and how many Titles a
 * genre has per language set.
 *
 * The caller holds the writer for what is named `stage`, `build` and `land`: they run inside the
 * Sync's own transactions. Everything else locks for itself, the writer before the reader.
 */
internal class SearchIndexStore(private val writer: SQLiteConnection, private val reader: SQLiteConnection) {
    /** The words kept by [words], by table and first letter, as of [wordsVersion]. Read and written with the reader held. */
    private val letterWords = HashMap<String, String>()
    private var wordsVersion = -1L

    // The tables

    /** Makes the index's tables when the database has none: empty, and not yet [ready]. */
    fun createLive() = (CATALOG_TABLES + CHANNEL_TABLES).forEach { (name, shape) -> writer.execSQL(shape.format(name)) }

    fun stageChannelTables() {
        dropStagedChannels()
        CHANNEL_TABLES.forEach { (name, shape) -> writer.execSQL(shape.format(STAGING + name)) }
    }

    fun dropStagedChannels() = CHANNEL_TABLES.forEach { (name, _) -> writer.execSQL("DROP TABLE IF EXISTS $STAGING$name") }

    fun stageTitleTables() {
        dropStagedTitles()
        CATALOG_TABLES.forEach { (name, shape) -> writer.execSQL(shape.format(STAGING + name)) }
    }

    fun dropStagedTitles() = CATALOG_TABLES.forEach { (name, _) -> writer.execSQL("DROP TABLE IF EXISTS $STAGING$name") }

    /** Indexes the staged Channels by their names, each Channel once, at its first variant's place in the Provider's order. */
    fun buildStagedChannels() {
        buildChannels(STAGING, "staging_channel")
        writeMeta(PENDING_CHANNELS, VERSION)
    }

    private fun buildChannels(prefix: String, table: String) {
        writer.prepare("INSERT INTO ${prefix}search_channel (rowid, words) VALUES (?, ?)").use { insert ->
            // min(position) picks the row the other columns come from: the Channel's first variant.
            writer.prepare("SELECT min(position), channel_name, country FROM $table GROUP BY channel_key ORDER BY 1").use {
                while (it.step()) {
                    val name = listOfNotNull(if (it.isNull(2)) null else it.getText(2), it.getText(1)).joinToString(": ")
                    insert.bindLong(1, it.getLong(0))
                    insert.bindText(2, SearchIndex.words(name).distinct().joinToString(" "))
                    insert.stepAndReset()
                }
            }
        }
        optimize("${prefix}search_channel")
    }

    /**
     * Indexes the staged Titles, in list order, with their people and genres, and the staged Sports
     * replays unless [replays] is false (a Head start lands none). Runs while the Titles stage still
     * has its raw Listings (`staging_listing`), so a name the Sync cleaned isn't cleaned again.
     */
    fun buildStagedTitles(sources: SearchSources, replays: Boolean) {
        val mark = writer.prepare("SELECT coalesce(max(fetched_at), 0) FROM movie_details").use { it.step(); it.getLong(0) }
        CatalogWriter(STAGING, sources).use { index ->
            writer.prepare(
                """
                SELECT t.kind, t.position, l.name, coalesce(c.name, ''), CASE WHEN l.kind = 'movie' THEN d.genre ELSE l.genre END,
                    CASE WHEN l.kind = 'movie' THEN d.actors ELSE l.actors END, l.clean_name, l.clean_year
                FROM staging_listing l JOIN staging_title t ON t.key = l.title_key
                LEFT JOIN staging_category c ON c.kind = l.kind AND c.id = l.category_id
                LEFT JOIN movie_details d ON l.kind = 'movie' AND d.listing_id = l.id
                WHERE l.hidden = 0 ORDER BY t.kind, t.position, l.added DESC, l.name, l.id
                """.trimIndent(),
            ).use { index.titles(it, cleaned = true) }
            if (replays) writer.prepare("SELECT position, name FROM staging_replay ORDER BY position").use { index.replays(it) }
            index.finish()
        }
        optimize("${STAGING}search_catalog")
        writeMeta(PENDING_TITLES, VERSION)
        writeMeta(PENDING_DETAILS, mark.toString())
    }

    /** Lands the staged Channels' index with them, inside their swap; one staged by an app without it leaves none, to be built ([rebuild]). */
    fun landChannels() = land(CHANNEL_TABLES, PENDING_CHANNELS, CHANNELS)

    /** Lands the staged Titles' index with them, inside their swap, as [landChannels] does. */
    fun landTitles() {
        land(CATALOG_TABLES, PENDING_TITLES, TITLES)
        meta(PENDING_DETAILS)?.let { writeMeta(DETAILS, it) }
        // An index being built from the catalog this one replaces stops at its next write ([rebuild]).
        deleteMeta(REBUILDING)
    }

    private fun land(tables: List<Pair<String, String>>, pending: String, landed: String) {
        val staged = meta(pending) == VERSION && tables.all { (name, _) -> tableExists(STAGING + name) }
        tables.forEach { (name, shape) ->
            if (staged) {
                writer.execSQL("DROP TABLE IF EXISTS $name")
                writer.execSQL("ALTER TABLE $STAGING$name RENAME TO $name")
            } else {
                writer.execSQL("DROP TABLE IF EXISTS $STAGING$name")
                writer.execSQL("DROP TABLE IF EXISTS $name")
                writer.execSQL(shape.format(name))
            }
        }
        if (staged) writeMeta(landed, VERSION) else deleteMeta(landed)
    }

    // Keeping it up to date

    /** Whether the index holds the catalog on screen: a Sync landed it, or it was built here once ([rebuild]). */
    val ready: Boolean
        get() = synchronized(reader) {
            reader.prepare("SELECT count(*) FROM meta WHERE key IN ('$TITLES', '$CHANNELS') AND value = '$VERSION'").use { it.step(); it.getLong(0) == 2L }
        }

    /**
     * Builds the index of the catalog on screen: what a database kept from before the index does once,
     * so its Search works without a new Sync. The catalog is read a page at a time and written a page
     * at a time, so neither other reads nor other writes wait for the whole of it. A Sync landing its
     * own catalog and index meanwhile stops it, and one that is [ready] is left as it is unless
     * [evenIfReady]. Returns whether the index is [ready] afterwards.
     */
    fun rebuild(sources: SearchSources, evenIfReady: Boolean = false): Boolean {
        val token = Random.nextLong().toString(16)
        val landed = synchronized(writer) {
            transaction {
                // A Sync that held the writer until now may have landed its own.
                if (!evenIfReady && meta(TITLES) == VERSION && meta(CHANNELS) == VERSION) return@transaction true
                (CATALOG_TABLES + CHANNEL_TABLES).forEach { (name, shape) ->
                    writer.execSQL("DROP TABLE IF EXISTS $name")
                    writer.execSQL(shape.format(name))
                }
                deleteMeta(TITLES)
                deleteMeta(CHANNELS)
                writeMeta(REBUILDING, token)
                false
            }
        }
        if (landed) return true
        var index: CatalogWriter? = null
        /** One write of the build, unless a Sync landed since it started. */
        fun write(step: () -> Unit): Boolean = synchronized(writer) {
            transaction {
                if (meta(REBUILDING) != token) return@transaction false
                step()
                true
            }
        }
        try {
            val going = write {
                buildChannels("", "channel")
                writeMeta(CHANNELS, VERSION)
                index = CatalogWriter("", sources).also { catalog -> writer.prepare("SELECT position, name FROM replay ORDER BY position").use { catalog.replays(it) } }
            }
            if (!going) return ready
            for (kind in listOf("movie", "show")) {
                var after = 0L
                while (true) {
                    val page = titlePage(kind, after, sources)
                    if (page.isEmpty()) break
                    if (!write { page.forEach { (position, listings) -> index!!.title(kind, position, listings) } }) return ready
                    after = page.last().first
                }
            }
            val mark = synchronized(reader) { reader.prepare("SELECT coalesce(max(fetched_at), 0) FROM movie_details").use { it.step(); it.getLong(0) } }
            write {
                index!!.finish()
                optimize("search_catalog")
                writeMeta(TITLES, VERSION)
                writeMeta(DETAILS, mark.toString())
                deleteMeta(REBUILDING)
            }
        } finally {
            synchronized(writer) { index?.close() }
        }
        return ready
    }

    /** The next [REBUILD_PAGE] Titles of [kind] after list position [after], each with its Listings, their names cleaned here, outside every lock. */
    private fun titlePage(kind: String, after: Long, sources: SearchSources): List<Pair<Long, List<IndexedListing>>> {
        val rows = synchronized(reader) {
            reader.prepare("$TITLE_LISTINGS WHERE t.kind = ? AND t.position > ? AND t.position <= ? ORDER BY t.position").use {
                it.bindText(1, kind)
                it.bindLong(2, after)
                it.bindLong(3, after + REBUILD_PAGE)
                buildList { while (it.step()) add(Triple(it.getLong(1), it.getLong(6), IndexedListing(it.getText(2), it.getText(3), it.textOrNull(4), it.textOrNull(5)))) }
            }
        }
        // A Title's Listings in list order, as a Sync reads them.
        return rows.groupBy { it.first }.map { (position, listings) ->
            position to listings.sortedBy { it.second }.map { (_, _, listing) -> IndexedListing(listing.name, listing.category, listing.genre, listing.cast, sources.clean(listing.name)) }
        }
    }

    /**
     * Works out again the people and genres of the Title [id], once movie details were kept for one
     * of its Listings: what a Movie's cast and genre field add to the index (ADR 0014). Its names
     * stand: only a Sync changes them. Nothing while the index isn't built: the build reads the details.
     */
    fun reindex(id: TitleId, sources: SearchSources) = synchronized(writer) {
        transaction {
            if (meta(TITLES) != VERSION) return@transaction
            val at = writer.prepare("SELECT kind, position FROM title WHERE key = ?").use {
                it.bindText(1, historyKey(id))
                if (it.step()) it.getText(0) to it.getLong(1) else return@transaction
            }
            reindex(at.first, at.second, sources)
        }
    }

    /**
     * [reindex] for every Title with movie details kept since the index was built or last caught up:
     * those kept while a Sync's catalog waited to land, which its index, built before them, lacks.
     */
    fun catchUp(sources: SearchSources) = synchronized(writer) {
        transaction {
            if (meta(TITLES) != VERSION) return@transaction
            val mark = meta(DETAILS)?.toLongOrNull() ?: 0
            val kept = writer.prepare("SELECT listing_id, fetched_at FROM movie_details WHERE fetched_at > ?").use {
                it.bindLong(1, mark)
                buildList { while (it.step()) add(it.getLong(0) to it.getLong(1)) }
            }
            if (kept.isEmpty()) return@transaction
            val titles = LinkedHashSet<Long>()
            kept.map { it.first }.chunked(MAX_BOUND).forEach { chunk ->
                writer.prepare("SELECT DISTINCT title FROM listing WHERE kind = 'movie' AND id IN (${chunk.joinToString(",") { "?" }})").use {
                    chunk.forEachIndexed { i, listing -> it.bindLong(i + 1, listing) }
                    while (it.step()) titles += it.getLong(0)
                }
            }
            titles.forEach { reindex("movie", it, sources) }
            writeMeta(DETAILS, kept.maxOf { it.second }.toString())
        }
    }

    private fun reindex(kind: String, position: Long, sources: SearchSources) {
        val row = titleRow(kind, position)
        val listings = writer.prepare("$TITLE_LISTINGS WHERE t.kind = ? AND t.position = ?").use {
            it.bindText(1, kind)
            it.bindLong(2, position)
            buildList { while (it.step()) add(it.getLong(6) to IndexedListing(it.getText(2), it.getText(3), it.textOrNull(4), it.textOrNull(5))) }
        }.sortedBy { it.first }.map { it.second }
        val languages = writer.prepare("SELECT languages FROM search_title WHERE id = ?").use {
            it.bindLong(1, row)
            if (it.step()) it.getLong(0) else return
        }
        val title = IndexedTitle(listings, sources)

        fun kept(table: String, column: String) = writer.prepare("SELECT $column FROM $table WHERE title = ?").use {
            it.bindLong(1, row)
            buildSet { while (it.step()) add(it.getLong(0)) }
        }
        /** The id [table] keeps [name] under, a new one, indexed by [words], when it has none. */
        fun id(table: String, scope: Long, name: String, words: () -> List<String>): Long {
            writer.prepare("SELECT id FROM $table WHERE name = ?").use {
                it.bindText(1, name)
                if (it.step()) return it.getLong(0)
            }
            val next = writer.prepare("SELECT coalesce(max(id), -1) + 1 FROM $table").use { it.step(); it.getLong(0) }
            writer.prepare("INSERT INTO $table VALUES (?, ?)").use {
                it.bindLong(1, next)
                it.bindText(2, name)
                it.step()
            }
            val found = words().distinct()
            writer.prepare("INSERT INTO search_catalog (rowid, words) VALUES (?, ?)").use {
                it.bindLong(1, scope shl SCOPE_SHIFT or next)
                it.bindText(2, found.joinToString(" "))
                it.step()
            }
            writer.prepare("INSERT OR IGNORE INTO search_catalog_word VALUES (?)").use { insert ->
                found.forEach {
                    insert.bindText(1, it)
                    insert.stepAndReset()
                }
            }
            return next
        }
        fun change(sql: String, vararg values: Long) = writer.prepare(sql).use {
            values.forEachIndexed { i, value -> it.bindLong(i + 1, value) }
            it.step()
        }

        val people = title.cast.mapTo(LinkedHashSet()) { name -> id("search_person", PERSON, name) { SearchIndex.words(name) } }
        val peopleKept = kept("search_cast", "person")
        (peopleKept - people).forEach {
            change("DELETE FROM search_cast WHERE title = ? AND person = ?", row, it)
            change("DELETE FROM search_person_title WHERE title = ? AND person = ?", row, it)
        }
        (people - peopleKept).forEach {
            change("INSERT INTO search_cast VALUES (?, ?)", row, it)
            change("INSERT INTO search_person_title VALUES (?, ?)", it, row)
        }

        val genres = title.genres.mapTo(LinkedHashSet()) { name -> id("search_genre", GENRE, name) { Genres.every(name).flatMap(SearchIndex::words) } }
        val genresKept = kept("search_title_genre", "genre")
        (genresKept - genres).forEach {
            change("DELETE FROM search_title_genre WHERE title = ? AND genre = ?", row, it)
            change("DELETE FROM search_genre_title WHERE title = ? AND genre = ?", row, it)
            change("UPDATE search_genre_count SET titles = titles - 1 WHERE genre = ? AND languages = ?", it, languages)
        }
        (genres - genresKept).forEach {
            change("INSERT INTO search_title_genre VALUES (?, ?)", row, it)
            change("INSERT INTO search_genre_title VALUES (?, ?)", it, row)
            change("INSERT INTO search_genre_count VALUES (?, ?, 1) ON CONFLICT (genre, languages) DO UPDATE SET titles = titles + 1", it, languages)
        }
    }

    /** Writes the Titles' part of an index into the tables named with [prefix], on the writer, the Titles in list order. */
    private inner class CatalogWriter(private val prefix: String, private val sources: SearchSources) : AutoCloseable {
        private val insertWords = writer.prepare("INSERT INTO ${prefix}search_catalog (rowid, words) VALUES (?, ?)")
        private val insertTitle = writer.prepare("INSERT INTO ${prefix}search_title VALUES (?, ?)")
        private val insertName = writer.prepare("INSERT OR IGNORE INTO ${prefix}search_name VALUES (?, ?)")
        private val insertPerson = writer.prepare("INSERT INTO ${prefix}search_person VALUES (?, ?)")
        private val insertCast = writer.prepare("INSERT OR IGNORE INTO ${prefix}search_cast VALUES (?, ?)")
        private val insertGenre = writer.prepare("INSERT INTO ${prefix}search_genre VALUES (?, ?)")
        private val insertGenreTitle = writer.prepare("INSERT OR IGNORE INTO ${prefix}search_title_genre VALUES (?, ?)")
        private val insertLanguages = writer.prepare("INSERT INTO ${prefix}search_languages VALUES (?, ?)")
        private val insertGenreCount = writer.prepare("INSERT INTO ${prefix}search_genre_count VALUES (?, ?, ?)")
        private val people = HashMap<String, Long>()
        private val genres = HashMap<String, Long>()
        private val languageSets = LinkedHashMap<Set<String>, Long>()
        /** How many Titles each genre has per language set, by [countKey]. */
        private val genreCounts = HashMap<Long, Long>()
        /** Each name's hash and its Title, two numbers a name: written in hash order once all are known ([finish]). */
        private var names = LongArray(1 shl 12)
        private var nameCount = 0

        private fun words(row: Long, words: Collection<String>) {
            insertWords.bindLong(1, row)
            insertWords.bindText(2, words.joinToString(" "))
            insertWords.stepAndReset()
        }

        private fun pair(insert: SQLiteStatement, first: Long, second: Long) {
            insert.bindLong(1, first)
            insert.bindLong(2, second)
            insert.stepAndReset()
        }

        private fun named(insert: SQLiteStatement, id: Long, name: String) {
            insert.bindLong(1, id)
            insert.bindText(2, name)
            insert.stepAndReset()
        }

        /** Every Title of [rows] (kind, position, then a Listing: name, Category, genre field, cast and, when [cleaned], its clean name and year), a Title's rows together. */
        fun titles(rows: SQLiteStatement, cleaned: Boolean) {
            var kind = ""
            var position = -1L
            val listings = ArrayList<IndexedListing>()
            while (rows.step()) {
                if (rows.getLong(1) != position || rows.getText(0) != kind) {
                    if (listings.isNotEmpty()) title(kind, position, listings)
                    listings.clear()
                    kind = rows.getText(0)
                    position = rows.getLong(1)
                }
                val clean = if (cleaned && !rows.isNull(6)) PrefixTable.CleanName(rows.getText(6), if (rows.isNull(7)) null else rows.getLong(7).toInt()) else null
                listings += IndexedListing(rows.getText(2), rows.getText(3), rows.textOrNull(4), rows.textOrNull(5), clean)
            }
            if (listings.isNotEmpty()) title(kind, position, listings)
        }

        fun title(kind: String, position: Long, listings: List<IndexedListing>) {
            val row = titleRow(kind, position)
            val title = IndexedTitle(listings, sources)
            val found = LinkedHashSet<String>()
            for (name in title.names) {
                val words = SearchIndex.words(name)
                found += words
                if (nameCount * 2 == names.size) names = names.copyOf(names.size * 2)
                names[nameCount * 2] = nameHash(words)
                names[nameCount++ * 2 + 1] = row
            }
            words(row, found)
            val languages = languageSets.getOrPut(title.languages) { languageSets.size.toLong() }
            pair(insertTitle, row, languages)
            for (name in title.cast) {
                val person = people.getOrPut(name) {
                    people.size.toLong().also { id ->
                        named(insertPerson, id, name)
                        words(PERSON shl SCOPE_SHIFT or id, SearchIndex.words(name).distinct())
                    }
                }
                pair(insertCast, row, person)
            }
            for (name in title.genres) {
                val genre = genres.getOrPut(name) {
                    genres.size.toLong().also { id ->
                        named(insertGenre, id, name)
                        words(GENRE shl SCOPE_SHIFT or id, Genres.every(name).flatMap(SearchIndex::words).distinct())
                    }
                }
                pair(insertGenreTitle, row, genre)
                countKey(genre, languages).let { genreCounts[it] = (genreCounts[it] ?: 0) + 1 }
            }
        }

        /** Every Sports replay of [rows] (position, name), found by its name without its Prefix. */
        fun replays(rows: SQLiteStatement) {
            while (rows.step()) words(REPLAY shl SCOPE_SHIFT or rows.getLong(0), SearchIndex.words(ReplayNames.body(rows.getText(1))).distinct())
        }

        fun finish() {
            (0 until nameCount).sortedWith(compareBy({ names[it * 2] }, { names[it * 2 + 1] })).forEach { pair(insertName, names[it * 2], names[it * 2 + 1]) }
            // The links the other way round, each in its own order.
            writer.execSQL("INSERT INTO ${prefix}search_person_title SELECT person, title FROM ${prefix}search_cast ORDER BY person, title")
            writer.execSQL("INSERT INTO ${prefix}search_genre_title SELECT genre, title FROM ${prefix}search_title_genre ORDER BY genre, title")
            languageSets.forEach { (languages, id) -> named(insertLanguages, id, languages.sorted().joinToString(" ")) }
            genreCounts.forEach { (key, titles) ->
                insertGenreCount.bindLong(1, key shr COUNT_SHIFT)
                insertGenreCount.bindLong(2, key and ((1L shl COUNT_SHIFT) - 1))
                insertGenreCount.bindLong(3, titles)
                insertGenreCount.stepAndReset()
            }
        }

        private fun countKey(genre: Long, languages: Long) = genre shl COUNT_SHIFT or languages

        override fun close() =
            listOf(insertWords, insertTitle, insertName, insertPerson, insertCast, insertGenre, insertGenreTitle, insertLanguages, insertGenreCount).forEach { it.close() }
    }

    /** Merges a full-text table's index into one piece, which a search reads fastest, and lists its words beside it, for [started] and [typos]. */
    private fun optimize(table: String) {
        writer.execSQL("INSERT INTO $table ($table) VALUES ('optimize')")
        writer.execSQL("CREATE VIRTUAL TABLE temp.search_terms USING fts5vocab(main, $table, row)")
        writer.execSQL("INSERT INTO ${table}_word SELECT term FROM temp.search_terms")
        writer.execSQL("DROP TABLE temp.search_terms")
    }

    private inline fun <T> transaction(block: () -> T): T {
        writer.execSQL("BEGIN IMMEDIATE")
        try {
            return block().also { writer.execSQL("COMMIT") }
        } catch (e: Throwable) {
            if (writer.inTransaction()) writer.execSQL("ROLLBACK")
            throw e
        }
    }

    private fun tableExists(name: String): Boolean =
        writer.prepare("SELECT count(*) FROM sqlite_master WHERE name = ?").use { it.bindText(1, name); it.step(); it.getLong(0) > 0 }

    private fun meta(key: String): String? = writer.prepare("SELECT value FROM meta WHERE key = ?").use {
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

    // Reading it

    /** What a query found in the catalog's index: each row's distance ([SearchIndex.distance], the worst word's), by scope. */
    class Found(
        /** The Titles found, by row ([titleRow]), a whole name already ranked first ([SearchIndex.WHOLE_NAME]) and the rest a step after. */
        val titles: Map<Long, Int>,
        /** Each found Title's language set ([languageSets]). */
        val titleLanguages: Map<Long, Int>,
        val people: Map<Long, Int>,
        val genres: Map<Long, Int>,
        /** The Sports replays found, by their place in the Provider's order. */
        val replays: Map<Long, Int>,
    )

    /** The Titles, people, genres and Sports replays with a word matching every word of [query]. */
    fun find(query: String): Found = synchronized(reader) {
        val wanted = SearchIndex.words(query)
        val titles = HashMap<Long, Int>()
        val titleLanguages = HashMap<Long, Int>()
        val people = HashMap<Long, Int>()
        val genres = HashMap<Long, Int>()
        val replays = HashMap<Long, Int>()
        if (wanted.isEmpty()) return Found(titles, titleLanguages, people, genres, replays)
        val whole = reader.prepare("SELECT title FROM search_name WHERE hash = ?").use {
            it.bindLong(1, nameHash(wanted))
            buildSet { while (it.step()) add(it.getLong(0)) }
        }
        matches("search_catalog", wanted).forEachIndexed { distance, expression ->
            if (expression == null) return@forEachIndexed
            reader.prepare(
                "SELECT search_catalog.rowid, t.languages FROM search_catalog LEFT JOIN search_title t ON t.id = search_catalog.rowid WHERE search_catalog MATCH ?",
            ).use {
                it.bindText(1, expression)
                while (it.step()) {
                    val row = it.getLong(0)
                    val local = row and ((1L shl SCOPE_SHIFT) - 1)
                    when (row shr SCOPE_SHIFT) {
                        TITLE -> {
                            titles[row] = if (row in whole) SearchIndex.WHOLE_NAME else distance + 1
                            titleLanguages[row] = if (it.isNull(1)) -1 else it.getLong(1).toInt()
                        }
                        PERSON -> people[local] = distance
                        GENRE -> genres[local] = distance
                        REPLAY -> replays[local] = distance
                    }
                }
            }
        }
        Found(titles, titleLanguages, people, genres, replays)
    }

    /** The Channels whose names match [query], by their first variant's place in the Provider's order, each with its distance. */
    fun findChannels(query: String): Map<Long, Int> = synchronized(reader) {
        val wanted = SearchIndex.words(query)
        val found = HashMap<Long, Int>()
        if (wanted.isEmpty()) return found
        matches("search_channel", wanted).forEachIndexed { distance, expression ->
            if (expression == null) return@forEachIndexed
            reader.prepare("SELECT rowid FROM search_channel WHERE search_channel MATCH ?").use {
                it.bindText(1, expression)
                while (it.step()) found[it.getLong(0)] = distance
            }
        }
        found
    }

    /**
     * The full-text queries that find [table]'s rows matching every word of [wanted], by distance:
     * the first finds the rows with every word whole, the second those with every word at least
     * started and not found by the first, then those reached with one tolerated typo more each. Null
     * where no word tolerates that many.
     */
    private fun matches(table: String, wanted: List<String>): List<String?> {
        fun quoted(word: String) = "\"$word\""
        val started = wanted.map { word -> started(table, word)?.let { words -> listOf(quoted(word)) + words.map(::quoted) } ?: listOf(quoted(word) + "*") }
        val typos = wanted.map { families(it, typos(table, it)) }
        fun all(words: (Int) -> List<String>) = wanted.indices.joinToString(" AND ") { words(it).joinToString(" OR ", "(", ")") }
        val whole = wanted.joinToString(" AND ", transform = ::quoted)
        var before = all { started[it] }
        val expressions = arrayListOf<String?>(whole, "($before) NOT ($whole)")
        for (distance in SearchIndex.STARTED_WORD + 1..SearchIndex.STARTED_WORD + 2) {
            if (typos.none { of -> of.any { it.second == distance } }) {
                expressions += null
                continue
            }
            val reached = all { word -> started[word] + typos[word].filter { it.second <= distance }.map { it.first } }
            expressions += "($reached) NOT ($before)"
            before = reached
        }
        return expressions
    }

    /**
     * [typos] as what to ask the full-text index for, each with its distance. Words that begin with the
     * same letters as far as [typed] goes are equally far from it by that beginning, so where
     * [MIN_FAMILY] of them or more are that far, the beginning is asked for once, as a started word,
     * instead of each of them by name. One of them that is closer as a whole word is also asked for by
     * name, and so found a step earlier.
     */
    private fun families(typed: String, typos: List<Pair<String, Int>>): List<Pair<String, Int>> {
        fun quoted(word: String) = "\"$word\""
        val length = typed.length
        val byStart = typos.groupBy { (word, distance) ->
            // A beginning never ends inside a letter that takes two places.
            if (word.length < length || word[length - 1].isHighSurrogate()) null
            else word.substring(0, length).takeIf { SearchIndex.distance(typed, it) == distance }
        }
        return byStart.flatMap { (start, words) ->
            if (start == null || words.size < MIN_FAMILY) words.map { quoted(it.first) to it.second } else listOf(quoted(start) + "*" to words.first().second)
        }
    }

    /**
     * The words of [table] that [typed] starts, without [typed] itself, or null when they are more than
     * [MAX_STARTED]: a few words are asked for by name, which the full-text index answers faster than
     * it does everything a prefix starts.
     */
    private fun started(table: String, typed: String): List<String>? {
        val last = typed.length.let { if (it > 1 && typed[it - 1].isLowSurrogate()) codePointAt(typed, it - 2) else typed[it - 1].code }
        val end = after(last) ?: return null
        val words = reader.prepare("SELECT group_concat(word, ' ') FROM (SELECT word FROM ${table}_word WHERE word > ?1 AND word < ?2 LIMIT ${MAX_STARTED + 1})").use {
            it.bindText(1, typed)
            it.bindText(2, typed.substring(0, typed.length - charCount(last)) + end)
            it.step()
            if (it.isNull(0)) emptyList() else it.getText(0).split(' ')
        }
        return words.takeIf { it.size <= MAX_STARTED }
    }

    /**
     * The words of [table] that [typed] reaches only with typos, each with its distance: they share its
     * first letter, so only that letter's words are judged ([SearchIndex.distance]).
     */
    private fun typos(table: String, typed: String): List<Pair<String, Int>> {
        // Shorter words tolerate none ([SearchIndex.distance]).
        if (typed.length < TYPO_LETTERS) return emptyList()
        val words = words(table, codePointAt(typed, 0))
        val found = ArrayList<Pair<String, Int>>()
        var from = 0
        while (from < words.length) {
            val to = words.indexOf(' ', from).let { if (it < 0) words.length else it }
            val distance = SearchIndex.distance(typed, words.subSequence(from, to))
            if (distance != null && distance > SearchIndex.STARTED_WORD) found += words.substring(from, to) to distance
            from = to + 1
        }
        return found
    }

    /**
     * [table]'s words that begin with the letter [first], in one piece with a space between them, so
     * they cross from SQLite in one call. The last few letters' are kept, for the next keystrokes of a
     * word being typed, until anything is written to the database.
     */
    private fun words(table: String, first: Int): String {
        val version = reader.prepare("PRAGMA data_version").use { it.step(); it.getLong(0) }
        if (version != wordsVersion || letterWords.size >= LETTERS_KEPT) letterWords.clear()
        wordsVersion = version
        return letterWords.getOrPut("$table ${first.toString(16)}") {
            val end = after(first)
            reader.prepare("SELECT group_concat(word, ' ') FROM ${table}_word WHERE word >= ?1 AND (?2 IS NULL OR word < ?2)").use {
                it.bindText(1, codePointString(first)!!)
                if (end == null) it.bindNull(2) else it.bindText(2, end)
                it.step()
                if (it.isNull(0)) "" else it.getText(0)
            }
        }
    }

    /** The letter after [letter] as text sorts, or null after the last one. */
    private fun after(letter: Int): String? {
        // A surrogate is no letter on its own.
        val next = if (letter + 1 in 0xD800..0xDFFF) 0xE000 else letter + 1
        return codePointString(next)
    }

    /** The language sets Titles have, by id: the primary languages of a Title's Versions. */
    fun languageSets(): Map<Int, Set<String>> = synchronized(reader) {
        reader.prepare("SELECT id, languages FROM search_languages").use {
            buildMap { while (it.step()) put(it.getLong(0).toInt(), it.getText(1).split(' ').filterTo(HashSet()) { language -> language.isNotEmpty() }) }
        }
    }

    /** The Titles at [rows], for those the catalog has. */
    fun titleIds(rows: Collection<Long>): Map<Long, TitleId> = synchronized(reader) {
        val found = HashMap<Long, TitleId>()
        rows.groupBy { it shr KIND_SHIFT }.forEach { (kind, ofKind) ->
            ofKind.chunked(MAX_BOUND).forEach { chunk ->
                reader.prepare("SELECT position, key FROM title WHERE kind = ? AND position IN (${chunk.joinToString(",") { "?" }})").use {
                    it.bindText(1, KINDS[kind.toInt()])
                    chunk.forEachIndexed { i, row -> it.bindLong(i + 2, row and ((1L shl KIND_SHIFT) - 1)) }
                    while (it.step()) historyTitleId(it.getText(1))?.let { id -> found[titleRow(KINDS[kind.toInt()], it.getLong(0))] = id }
                }
            }
        }
        found
    }

    /** The rows of the Titles [ids], for those the catalog has. */
    fun titleRows(ids: Collection<TitleId>): Set<Long> = synchronized(reader) {
        val rows = HashSet<Long>()
        ids.chunked(MAX_BOUND).forEach { chunk ->
            reader.prepare("SELECT kind, position FROM title WHERE key IN (${chunk.joinToString(",") { "?" }})").use {
                chunk.forEachIndexed { i, id -> it.bindText(i + 1, historyKey(id)) }
                while (it.step()) rows += titleRow(it.getText(0), it.getLong(1))
            }
        }
        rows
    }

    /** Each of [people]'s Titles, as rows, in list order. */
    fun castTitles(people: Collection<Long>): Map<Long, List<Long>> = synchronized(reader) {
        val titles = HashMap<Long, MutableList<Long>>()
        people.chunked(MAX_BOUND).forEach { chunk ->
            reader.prepare("SELECT person, title FROM search_person_title WHERE person IN (${chunk.joinToString(",") { "?" }}) ORDER BY person, title").use {
                chunk.forEachIndexed { i, person -> it.bindLong(i + 1, person) }
                while (it.step()) titles.getOrPut(it.getLong(0)) { ArrayList() } += it.getLong(1)
            }
        }
        titles
    }

    /** Each of [people]'s first Title in list order, as a row: all that ranking them needs when every Title counts. */
    fun firstTitles(people: Collection<Long>): Map<Long, Long> = synchronized(reader) {
        val first = HashMap<Long, Long>()
        people.chunked(MAX_BOUND).forEach { chunk ->
            reader.prepare("SELECT person, min(title) FROM search_person_title WHERE person IN (${chunk.joinToString(",") { "?" }}) GROUP BY person").use {
                chunk.forEachIndexed { i, person -> it.bindLong(i + 1, person) }
                while (it.step()) first[it.getLong(0)] = it.getLong(1)
            }
        }
        first
    }

    fun personNames(people: Collection<Long>): Map<Long, String> = synchronized(reader) { names("search_person", people) }

    /** The genres' names, in English ([Genres]). */
    fun genreNames(genres: Collection<Long>): Map<Long, String> = synchronized(reader) { names("search_genre", genres) }

    private fun names(table: String, ids: Collection<Long>): Map<Long, String> {
        val names = HashMap<Long, String>()
        ids.chunked(MAX_BOUND).forEach { chunk ->
            reader.prepare("SELECT id, name FROM $table WHERE id IN (${chunk.joinToString(",") { "?" }})").use {
                chunk.forEachIndexed { i, id -> it.bindLong(i + 1, id) }
                while (it.step()) names[it.getLong(0)] = it.getText(1)
            }
        }
        return names
    }

    /** The language set of each Title at [rows]. */
    fun titleLanguages(rows: Collection<Long>): Map<Long, Int> = synchronized(reader) {
        val languages = HashMap<Long, Int>()
        rows.chunked(MAX_BOUND).forEach { chunk ->
            reader.prepare("SELECT id, languages FROM search_title WHERE id IN (${chunk.joinToString(",") { "?" }})").use {
                chunk.forEachIndexed { i, row -> it.bindLong(i + 1, row) }
                while (it.step()) languages[it.getLong(0)] = it.getLong(1).toInt()
            }
        }
        languages
    }

    /** How many Titles a genre has in a language set. */
    class GenreCount(val genre: Long, val name: String, val languages: Int, val titles: Int)

    /** Every genre's Title counts, per language set, the genres in the order they were first met. */
    fun genreCounts(): List<GenreCount> = synchronized(reader) {
        reader.prepare("SELECT g.id, g.name, c.languages, c.titles FROM search_genre g JOIN search_genre_count c ON c.genre = g.id WHERE c.titles > 0 ORDER BY g.id").use {
            buildList { while (it.step()) add(GenreCount(it.getLong(0), it.getText(1), it.getLong(2).toInt(), it.getLong(3).toInt())) }
        }
    }

    /** The genres of the Titles at [rows], one [GenreCount] of one Title each: what a Kids profile's genres are counted from. */
    fun genresOf(rows: Collection<Long>): List<GenreCount> = synchronized(reader) {
        buildList {
            rows.chunked(MAX_BOUND).forEach { chunk ->
                reader.prepare(
                    "SELECT g.id, g.name, t.languages FROM search_title_genre gt JOIN search_genre g ON g.id = gt.genre JOIN search_title t ON t.id = gt.title " +
                        "WHERE gt.title IN (${chunk.joinToString(",") { "?" }})",
                ).use {
                    chunk.forEachIndexed { i, row -> it.bindLong(i + 1, row) }
                    while (it.step()) add(GenreCount(it.getLong(0), it.getText(1), it.getLong(2).toInt(), 1))
                }
            }
        }
    }

    /** Gives [each] the Titles of [genre], as rows with their language sets, in list order, until it answers false. */
    fun genreTitles(genre: Long, each: (row: Long, languages: Int) -> Boolean) = synchronized(reader) {
        reader.prepare("SELECT gt.title, t.languages FROM search_genre_title gt JOIN search_title t ON t.id = gt.title WHERE gt.genre = ? ORDER BY gt.title").use {
            it.bindLong(1, genre)
            while (it.step()) if (!each(it.getLong(0), it.getLong(1).toInt())) break
        }
    }

    /** The lead ids ([CatalogStore.forEachChannel]) of the Channels whose first variants are at [positions]. */
    fun channelIds(positions: Collection<Long>): Map<Long, Long> = synchronized(reader) {
        val ids = HashMap<Long, Long>()
        positions.chunked(MAX_BOUND).forEach { chunk ->
            reader.prepare("SELECT position, id FROM channel WHERE lead = 1 AND position IN (${chunk.joinToString(",") { "?" }})").use {
                chunk.forEachIndexed { i, position -> it.bindLong(i + 1, position) }
                while (it.step()) ids[it.getLong(0)] = it.getLong(1)
            }
        }
        ids
    }

    /** The Sports replays at [positions]: each one's Listing id and day (epoch days, [SearchIndex.NO_DAY] without a date). */
    fun replayDays(positions: Collection<Long>): Map<Long, Pair<Long, Int>> = synchronized(reader) {
        val replays = HashMap<Long, Pair<Long, Int>>()
        positions.chunked(MAX_BOUND).forEach { chunk ->
            reader.prepare("SELECT position, id, date, year FROM replay WHERE position IN (${chunk.joinToString(",") { "?" }})").use {
                chunk.forEachIndexed { i, position -> it.bindLong(i + 1, position) }
                while (it.step()) {
                    val day = if (!it.isNull(2)) LocalDate.parse(it.getText(2)) else if (!it.isNull(3)) LocalDate(it.getLong(3).toInt(), 1, 1) else null
                    replays[it.getLong(0)] = it.getLong(1) to (day?.toEpochDays()?.toInt() ?: SearchIndex.NO_DAY)
                }
            }
        }
        replays
    }

    companion object {
        /** Raised whenever the index's shape or what it holds changes: an index of another version is built again ([rebuild]). */
        const val VERSION = "1"
        const val STAGING = "staging_"
        /** A `search_catalog` row id's scope, above this bit: a Title, a person, a genre or a Sports replay. */
        const val SCOPE_SHIFT = 40
        const val TITLE = 0L
        const val PERSON = 1L
        const val GENRE = 2L
        const val REPLAY = 3L
        /** A Title's row is its kind above this bit and its list position below, so rows sort as the lists do: Movies, then Shows. */
        const val KIND_SHIFT = 32
        private val KINDS = TitleKind.entries.map { it.name.lowercase() }
        private const val COUNT_SHIFT = 20
        /** A word tolerates a typo from this many letters ([SearchIndex.distance]). */
        private const val TYPO_LETTERS = 4
        private const val LETTERS_KEPT = 8
        /** At most this many words a typed one starts are asked for by name ([started]). */
        private const val MAX_STARTED = 64
        /** From this many typo words sharing a beginning, the beginning is asked for instead ([families]). */
        private const val MIN_FAMILY = 4
        private const val MAX_BOUND = 900
        private const val REBUILD_PAGE = 2_000
        /** Whether each part of the index holds what is on screen, as its [VERSION]. */
        private const val TITLES = "search_titles"
        private const val CHANNELS = "search_channels"
        /** When the latest movie details the index has read were kept (epoch milliseconds). */
        private const val DETAILS = "search_details"
        /** The build under way ([rebuild]), which a Sync landing its own index stops. */
        private const val REBUILDING = "search_rebuilding"
        private const val PENDING_TITLES = "pending_sync_search_titles"
        private const val PENDING_CHANNELS = "pending_sync_search_channels"
        private const val PENDING_DETAILS = "pending_sync_search_details"

        /**
         * A full-text table of words alone: no copy of the text, no positions and no sizes, since a row
         * is only ever found, never shown or scored. Its `ascii` tokenizer splits on the spaces the Core
         * puts between words and folds nothing: the words come folded.
         */
        private const val WORDS = "CREATE VIRTUAL TABLE IF NOT EXISTS %s USING fts5(words, content='', detail=none, columnsize=0, tokenize='ascii')"
        /**
         * A link read from its first column. A Title's people and genres are linked both ways, in a table
         * each, so each is written in its own order: a second index on one table would be filled at random,
         * which is most of what building it costs.
         */
        private const val LINK = "CREATE TABLE IF NOT EXISTS %%s (%1\$s INTEGER NOT NULL, %2\$s INTEGER NOT NULL, PRIMARY KEY (%1\$s, %2\$s)) WITHOUT ROWID"
        private const val NAMED = "CREATE TABLE IF NOT EXISTS %s (id INTEGER PRIMARY KEY, name TEXT NOT NULL UNIQUE)"
        /** A full-text table's words, each once: SQLite keeps no list of them that is quick to read. */
        private const val WORD_LIST = "CREATE TABLE IF NOT EXISTS %s (word TEXT PRIMARY KEY) WITHOUT ROWID"
        val CHANNEL_TABLES = listOf("search_channel" to WORDS, "search_channel_word" to WORD_LIST)
        val CATALOG_TABLES = listOf(
            "search_catalog" to WORDS,
            "search_catalog_word" to WORD_LIST,
            // Each Title's language set, and the sets.
            "search_title" to "CREATE TABLE IF NOT EXISTS %s (id INTEGER PRIMARY KEY, languages INTEGER NOT NULL)",
            "search_languages" to "CREATE TABLE IF NOT EXISTS %s (id INTEGER PRIMARY KEY, languages TEXT NOT NULL)",
            // Each name of a Title, as a hash of its words.
            "search_name" to "CREATE TABLE IF NOT EXISTS %s (hash INTEGER NOT NULL, title INTEGER NOT NULL, PRIMARY KEY (hash, title)) WITHOUT ROWID",
            "search_person" to NAMED,
            "search_cast" to LINK.format("title", "person"),
            "search_person_title" to LINK.format("person", "title"),
            "search_genre" to NAMED,
            "search_title_genre" to LINK.format("title", "genre"),
            "search_genre_title" to LINK.format("genre", "title"),
            "search_genre_count" to
                "CREATE TABLE IF NOT EXISTS %s (genre INTEGER NOT NULL, languages INTEGER NOT NULL, titles INTEGER NOT NULL, PRIMARY KEY (genre, languages)) WITHOUT ROWID",
        )
        /** A Title's Listings as the index reads them, in the catalog on screen: kind, position, then a Listing's name, Category, genre field, cast and list position. */
        private const val TITLE_LISTINGS =
            "SELECT t.kind, t.position, l.name, l.category, CASE WHEN l.kind = 'movie' THEN d.genre ELSE l.genre END, CASE WHEN l.kind = 'movie' THEN d.actors ELSE l.actors END, " +
                "l.position " +
                "FROM title t JOIN listing l ON l.kind = t.kind AND l.title = t.position LEFT JOIN movie_details d ON l.kind = 'movie' AND d.listing_id = l.id"

        fun titleRow(kind: String, position: Long): Long = KINDS.indexOf(kind).toLong() shl KIND_SHIFT or position

        fun kind(row: Long): TitleKind = TitleKind.entries[(row shr KIND_SHIFT).toInt()]

        /** A name's words as one number, the same on every device and platform: two names of the same words have the same one. */
        fun nameHash(words: List<String>): Long {
            var hash = -0x340d631b7bdddcdbL
            for (word in words) {
                for (letter in word) hash = (hash xor letter.code.toLong()) * 0x100000001b3L
                hash = (hash xor ' '.code.toLong()) * 0x100000001b3L
            }
            return hash
        }
    }
}

private fun SQLiteStatement.textOrNull(index: Int): String? = if (isNull(index)) null else getText(index)

private fun SQLiteStatement.stepAndReset() {
    step()
    reset()
}
