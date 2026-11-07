package dev.betteriptv.core

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Titles grouped by the Title number alone, with a Mismatch told by its year (ADR 0014), seen
 * through the Library after a Sync of the fixture or of a few Listings, with the cloud asked nothing.
 */
class MatchingTest {
    @get:Rule val folder = TemporaryFolder()
    private val provider = FakeProvider()
    private val cloud = FakeCloud().servingSurveyedTable(provider)
    private val credentials = InMemoryCredentials()
    private val libraries = mutableListOf<Library>()
    private lateinit var library: Library
    private val path get() = folder.root.resolve("catalog.db").path

    private fun openLibrary() = openSurveyedLibrary(path, credentials, cloud.url).also { libraries += it }

    @Before fun logIn() = runBlocking {
        library = openLibrary()
        library.logIn(provider.login)
        Unit
    }

    @After fun tearDown() {
        libraries.forEach { it.close() }
        provider.close()
        cloud.close()
    }

    private val munnaBhai = TitleId.Tmdb(TitleKind.Movie, 19625)
    private val ballerina = TitleId.Tmdb(TitleKind.Movie, 541671)
    private val pose = TitleId.Tmdb(TitleKind.Movie, 1199193)

    private suspend fun cards(kind: TitleKind = TitleKind.Movie) = library.titles(0, 1000, listOf("en"), appLanguage = "en", kind = kind)

    private suspend fun versionNames(id: TitleId): List<String> {
        val details = library.title(id, listOf("en"), appLanguage = "en") ?: throw AssertionError("no Title $id")
        return (details.languages + details.otherLanguages).flatMap { group -> group.versions.flatMap { listOf(it) + it.backups } }.map { it.name }
    }

    /** Serves these movie Listings (name to Title number) in one Category, newest first, and no shows. */
    private fun serveMovies(vararg listings: Pair<String, Long?>) {
        provider.movieCategoriesOverride = """[{"category_id":"1","category_name":"MOVIES"}]"""
        provider.movieListOverride = listings.withIndex().joinToString(",", "[", "]") { (i, listing) ->
            val (name, number) = listing
            """{"stream_id":${i + 1},"name":"$name","tmdb":"${number ?: ""}","category_id":"1","added":"${1_700_000_000 - i}","stream_icon":"https://img.example/${i + 1}.jpg"}"""
        }
        provider.showCategoriesOverride = "[]"
        provider.showListOverride = "[]"
    }

    private val matchRequests get() = cloud.received.filter { it.path == "/v1/match" }

    @Test fun `Listings sharing a number are one Title, and the cloud is asked nothing about them, during or after a Sync`() = runBlocking {
        library.sync()
        library.close()
        library = openLibrary()
        library.resumeTitleFacts()

        assertEquals(1, cards().count { it.id == ballerina })
        assertEquals(32, versionNames(ballerina).size)
        assertEquals(emptyList<FakeCloud.Received>(), matchRequests)
    }

    @Test fun `a Listing dated more than a year from its siblings' year is an Unmatched Title of its own`() = runBlocking {
        serveMovies("EN - Pose  (2025)" to 1199193, "IR - Pose (2025) ژست مرموز" to 1199193, "PL - Poza (1970)" to 1199193)

        library.sync()

        assertEquals(listOf("EN - Pose  (2025)", "IR - Pose (2025) ژست مرموز"), versionNames(pose).sorted())
        val poza = cards().single { it.name == "Poza" }
        assertEquals(TitleId.Unmatched(TitleKind.Movie, 3), poza.id)
        assertEquals(2, cards().size)
    }

    @Test fun `Listings a year apart stay one Title`() = runBlocking {
        serveMovies("EN - Joker (2019)" to 475557, "FR - Joker (2019)" to 475557, "DE - Joker (2020)" to 475557)

        library.sync()

        assertEquals(TitleId.Tmdb(TitleKind.Movie, 475557), cards().single().id)
        assertEquals(0, library.syncStatus.value.last?.mismatches)
    }

    @Test fun `a Listing without a year stays with its number`() = runBlocking {
        serveMovies("EN - Heat (1995)" to 949, "FR - Heat" to 949, "DE - Heat (1995)" to 949)

        library.sync()

        assertEquals(listOf("DE - Heat (1995)", "EN - Heat (1995)", "FR - Heat"), versionNames(TitleId.Tmdb(TitleKind.Movie, 949)).sorted())
        assertEquals(1, cards().size)
    }

    @Test fun `when years tie, the earliest is the siblings' year`() = runBlocking {
        serveMovies("EN - Pose  (2025)" to 1199193, "PL - Poza (1970)" to 1199193)

        library.sync()

        assertEquals(listOf("PL - Poza (1970)"), versionNames(pose))
        assertTrue(cards().single { it.name == "Pose" }.id is TitleId.Unmatched)
    }

    @Test fun `a Show whose name carries no year is dated by its Listing's release date`() = runBlocking {
        provider.showCategoriesOverride = """[{"category_id":"1","category_name":"SERIES"}]"""
        provider.showListOverride = """[
            {"series_id":1,"name":"EN - Pose","tmdb":"70993","category_id":"1","last_modified":"1700000002","releaseDate":"2018-06-03"},
            {"series_id":2,"name":"FR - Pose (2018)","tmdb":"70993","category_id":"1","last_modified":"1700000001","releaseDate":""},
            {"series_id":3,"name":"PL - Poza","tmdb":"70993","category_id":"1","last_modified":"1700000000","releaseDate":"June 4, 1970"}
        ]"""

        library.sync()

        assertEquals(listOf("EN - Pose", "FR - Pose (2018)"), versionNames(TitleId.Tmdb(TitleKind.Show, 70993)).sorted())
        assertEquals(TitleId.Unmatched(TitleKind.Show, 3), cards(TitleKind.Show).single { it.name == "Poza" }.id)
    }

    @Test fun `the fixture's Mohgiben MBBS, filed under Munna Bhai MBBS' number, is told apart by its year`() = runBlocking {
        library.sync()

        assertEquals(listOf("IN - Munna Bhai M.B.B.S.  (2003)", "IN - Munna Bhai M.B.B.S."), versionNames(munnaBhai))
        assertTrue(cards().single { it.name == "Mohgiben MBBS" }.id is TitleId.Unmatched)
    }

    @Test fun `a Mismatch stays an Unmatched Title across Syncs, keeping where the Viewer stopped`() = runBlocking {
        serveMovies("EN - Pose  (2025)" to 1199193, "IR - Pose (2025) ژست مرموز" to 1199193, "PL - Poza (1970)" to 1199193)
        library.sync()
        val poza = cards().single { it.name == "Poza" }.id
        library.saveProgress(poza, 600_000, 6_000_000)

        library.sync()

        assertEquals(poza, cards().single { it.name == "Poza" }.id)
        assertEquals(listOf("Poza"), continueWatching())
    }

    @Test fun `a Listing without a number is an Unmatched Title, named and pictured by its Listing`() = runBlocking {
        serveMovies("EN - Heat (1995)" to 949, "EN - Lost Film (2020)" to null)

        library.sync()

        val lost = cards().single { it.id is TitleId.Unmatched }
        assertEquals(TitleId.Unmatched(TitleKind.Movie, 2), lost.id)
        assertEquals("Lost Film", lost.name)
        assertEquals(2020, lost.year)
        assertEquals("https://img.example/2.jpg", lost.image)
    }

    @Test fun `a Provider that doesn't copy TMDB's numbering gives one card per Listing`() = runBlocking {
        serveMovies("EN - Heat (1995)" to null, "FR - Heat (1995)" to null, "DE - Heat (1995)" to null)

        library.sync()

        assertEquals(3, cards().size)
        assertTrue(cards().all { it.id is TitleId.Unmatched })
    }

    @Test fun `the Sync counts the Unmatched Titles the year rule made, and keeps the count`() = runBlocking {
        serveMovies(
            "EN - Pose  (2025)" to 1199193, "IR - Pose (2025) ژست مرموز" to 1199193, "PL - Poza (1970)" to 1199193,
            "EN - Joker (2019)" to 475557, "FR - Joker (2019)" to 475557, "DE - Joker (1989)" to 475557,
            "EN - Lost Film (2020)" to null,
        )

        val status = library.sync() as SyncStatus.Synced

        assertEquals(2, status.last.mismatches)
        library.close()
        library = openLibrary()
        assertEquals(2, library.syncStatus.value.last?.mismatches)
    }

    @Test fun `a catalog stored before Matching went on-device opens without a Sync, its cloud-matched Listings Unmatched Titles again`() = runBlocking {
        serveMovies("EN - Heat (1995)" to 949, "FR - Heat (1995)" to 949, "EN - Lost Film (2020)" to null, "IN-GU - Mohgiben.MBBS.2023" to 19625)
        library.sync()
        library.close()
        // As the app stored it before: the cloud had Matched Lost Film to a number of its own and found nothing for
        // Mohgiben, and the Viewer stopped partway through Lost Film under the number the cloud gave it.
        BundledSQLiteDriver().open(path).apply {
            execSQL("ALTER TABLE listing ADD COLUMN match_key TEXT NOT NULL DEFAULT ''")
            execSQL("ALTER TABLE listing ADD COLUMN suspect INTEGER NOT NULL DEFAULT 0")
            execSQL("UPDATE listing SET suspect = 1 WHERE id IN (3, 4)")
            execSQL("UPDATE listing SET title_key = 'movie/5000' WHERE id = 3")
            execSQL("UPDATE title SET key = 'movie/5000' WHERE key = 'movie/listing/3'")
            execSQL("UPDATE title SET key = 'movie/listing/4' WHERE key = 'movie/19625'")
            execSQL("UPDATE listing SET title_key = 'movie/listing/4' WHERE id = 4")
            execSQL("CREATE TABLE match_answer (key TEXT PRIMARY KEY, tmdb_id INTEGER, fetched_at INTEGER NOT NULL)")
            execSQL("INSERT INTO match_answer VALUES ('movie||lost film|2020|en', 5000, 0)")
            execSQL("INSERT INTO meta VALUES ('last_sync_rematched_listings', '2')")
            execSQL("INSERT INTO progress VALUES (1, 'movie/5000', 600000, 6000000, 0, ${System.currentTimeMillis()})")
            close()
        }
        val actions = java.util.concurrent.CopyOnWriteArrayList<String?>()
        provider.onRequest = { actions += it }

        library = openLibrary()

        assertTrue(library.syncStatus.value is SyncStatus.Synced)
        assertEquals(
            setOf(TitleId.Tmdb(TitleKind.Movie, 949), TitleId.Unmatched(TitleKind.Movie, 3), TitleId.Unmatched(TitleKind.Movie, 4)),
            cards().map { it.id }.toSet(),
        )
        assertEquals(listOf("Lost Film"), continueWatching())
        assertEquals(0, library.syncStatus.value.last?.mismatches)
        BundledSQLiteDriver().open(path).apply {
            prepare("SELECT count(*) FROM sqlite_master WHERE name = 'match_answer'").use { it.step(); assertEquals(0L, it.getLong(0)) }
            prepare("SELECT count(*) FROM pragma_table_info('listing') WHERE name IN ('suspect', 'match_key')").use { it.step(); assertEquals(0L, it.getLong(0)) }
            close()
        }
        assertEquals(emptyList<String?>(), actions)
    }

    private suspend fun continueWatching() =
        library.homeRows(listOf("en"), "en").filterIsInstance<HomeRow.ContinueWatching>().singleOrNull()?.titles.orEmpty().map { it.title.name }
}
