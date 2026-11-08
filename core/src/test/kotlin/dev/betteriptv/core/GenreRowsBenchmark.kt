package dev.betteriptv.core

import kotlinx.coroutines.runBlocking
import com.squareup.moshi.JsonReader
import okio.buffer
import okio.source
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Duration
import java.time.Instant
import kotlin.random.Random

/**
 * Times Home's and the tabs' genre rows on a catalog the size of the real one (#497): about 70,000
 * Titles in about 150,000 Listings under 330 Categories, every Show's Listing and every Movie's
 * details with a genre field as the Provider writes them. With `-Psurvey=/path/to/.survey/raw` the
 * fields are the surveyed Provider's own (4,000-odd distinct ones); without, made up from genre
 * words in its languages. Skipped unless run with `-Pbenchmark`:
 *
 *     ./gradlew :core:test --tests '*GenreRowsBenchmark' -Pbenchmark -Psurvey=/path/to/.survey/raw
 *
 * It times a cold start's first Home (the genre index built), a second landing on each screen, a
 * landing after Background reading keeps one more Movie's details, and the parse of every distinct
 * Category and genre field alone.
 */
class GenreRowsBenchmark {
    @get:Rule val folder = TemporaryFolder()
    private val random = Random(497)
    private val syllables = listOf("ka", "lo", "mi", "ne", "ra", "to", "su", "vel", "dor", "an", "ix", "por", "bel", "chi", "ste", "gru", "mon", "tra", "zé", "qui")

    private fun word() = (1..random.nextInt(2, 5)).joinToString("") { syllables.random(random) }

    private fun name(words: Int) = (1..words).joinToString(" ") { word().replaceFirstChar(Char::uppercase) }

    private val madeUpPieces = listOf(
        "Drama", "Comedy", "Thriller", "Action & Adventure", "Crime", "Komödie", "Drame", "Comedia", "Sci-Fi & Fantasy", "Documentary",
        "Documental", "Reality", "Animation", "Animación", "Family", "Familial", "Kids", "Mystery", "Mysterie", "Misdaad", "Horror",
        "Épouvante-horreur", "Romance", "Romantik", "War & Politics", "Western", "History", "Histoire", "Music", "Musique", "Δράμα",
        "Κωμωδία", "Αστυνομική", "דרמה", "פשע", "Dram", "Aksiyon & Macera", "Bilim Kurgu & Fantazi", "Gerilim", "Belgesel", "Soap", "Talk", "News",
    )

    /** The genre fields to hand out: the surveyed Provider's Shows' when given the survey, else made up. */
    private fun genreFields(): List<String> {
        val survey = System.getProperty("survey")?.let(::File)?.resolve("series_streams.json")
        if (survey != null && survey.exists()) {
            val found = mutableListOf<String>()
            JsonReader.of(survey.source().buffer()).use { json ->
                json.beginArray()
                while (json.hasNext()) {
                    json.beginObject()
                    while (json.hasNext()) {
                        if (json.nextName() == "genre" && json.peek() == JsonReader.Token.STRING) found += json.nextString() else json.skipValue()
                    }
                    json.endObject()
                }
            }
            return found.filter { it.isNotBlank() }
        }
        return List(40_000) {
            List(random.nextInt(1, 4)) { if (random.nextInt(10) == 0) name(1) else madeUpPieces.random(random) }.joinToString(listOf(" / ", ", ").random(random))
        }
    }

    /** Categories as the Provider names them: a language tag and a few words, some of them genre words. */
    private fun categories(kind: TitleKind): List<String> {
        val genres = listOf("DRAMA", "COMEDIE", "ACTION", "HORROR", "KIDS", "DOCUMENTARY", "THRILLER", "ANIMATION", "FAMILIE", "BELGESEL", "ROMANCE", "")
        val tags = listOf("EN", "FR", "DE", "ES", "TR", "GR", "NL", "IT", "AR", "4K-EN", "NF")
        val what = if (kind == TitleKind.Movie) "MOVIES" else "SERIES"
        return List(if (kind == TitleKind.Movie) 260 else 70) { "${tags.random(random)} - ${genres.random(random)} ${name(1).uppercase()} $what".replace("  ", " ") }
    }

    @Test fun `genre rows on a full-size catalog`() {
        assumeTrue("run with -Pbenchmark", System.getProperty("benchmark") != null)
        val path = folder.root.resolve("catalog.db").path
        val fields = genreFields()
        val store = CatalogStore(path)
        val movieListings = fill(store, fields)
        store.close()

        val library = Library.open(path, InMemoryCredentials(), null)
        fun time(what: String, block: suspend () -> Any) {
            val started = System.nanoTime()
            runBlocking { block() }
            println("Genre rows: $what ${(System.nanoTime() - started) / 1_000_000} ms")
        }
        time("Home, cold start") { library.homeRows(listOf("en"), "en") }
        time("Home, second landing") { library.homeRows(listOf("en"), "en") }
        time("Movies tab") { library.tabRows(TitleKind.Movie, listOf("en"), "en") }
        time("Shows tab") { library.tabRows(TitleKind.Show, listOf("en"), "en") }
        repeat(3) { round ->
            // As Background reading does about every 30 s: one more Movie's details kept, with a genre field.
            val listing = movieListings.random(random)
            val keeper = CatalogStore(path)
            keeper.saveMovieDetails(listing, ProviderMovieDetails(null, ProviderDetails(genre = fields.random(random))), listing, Instant.now())
            keeper.close()
            time("Home after one more Movie's details (${round + 1})") { library.homeRows(listOf("en"), "en") }
        }

        val table = PrefixTable.base()
        val reading = CatalogStore(path)
        val distinctCategories = HashSet<String>()
        val distinctFields = HashSet<String>()
        var rows = 0
        repeat(2) { time("the catalog's pass alone, warming up") { reading.forEachGenreSource { _, _, _, _, _, _ -> } } }
        time("the catalog's pass alone, nothing done with it") { reading.forEachGenreSource { _, _, _, _, _, _ -> } }
        time("the catalog's pass alone") {
            reading.forEachGenreSource { _, _, category, field, _, _ ->
                rows++
                distinctCategories += category
                if (field != null) distinctFields += field
            }
        }
        time("parsing ${distinctCategories.size} Categories and ${distinctFields.size} genre fields of $rows Listings") {
            distinctCategories.forEach(table::titleGenres)
            distinctFields.forEach(table::genres)
        }
        reading.close()
        library.close()
    }

    /** Titles 1 to 70,000 (one in four a Show), two or three Listings each, every Listing with a genre field; returns the Movies' Listings. */
    private fun fill(store: CatalogStore, fields: List<String>): List<Long> {
        val titles = 70_000
        var listingId = 0L
        val movieListings = mutableListOf<Long>()
        val movieCategories = categories(TitleKind.Movie)
        val showCategories = categories(TitleKind.Show)
        store.stageTitles(
            fill = { staging ->
                movieCategories.forEachIndexed { i, name -> staging.category(TitleKind.Movie, "m$i", name) }
                showCategories.forEachIndexed { i, name -> staging.category(TitleKind.Show, "s$i", name) }
                for (number in 1L..titles) {
                    val kind = if (number % 4 == 0L) TitleKind.Show else TitleKind.Movie
                    val name = name(random.nextInt(1, 5))
                    val year = random.nextInt(1950, 2026)
                    repeat(random.nextInt(2, 4)) {
                        val category = if (kind == TitleKind.Show) "s${random.nextInt(showCategories.size)}" else "m${random.nextInt(movieCategories.size)}"
                        val prefix = listOf("EN", "FR", "4K-EN", "NF", "DE").random(random)
                        if (kind == TitleKind.Movie) movieListings += listingId + 1
                        val details = if (kind == TitleKind.Show) ProviderDetails(genre = fields.random(random)) else null
                        staging.listing(kind, ++listingId, "$prefix - $name ($year)", category, listingId, null, false, number, year, details = details)
                    }
                }
            },
            onFilled = {},
            completed = { movies, shows, _ -> CompletedSync(Instant.now(), Duration.ZERO, movies, emptyList(), showListingCount = shows) },
        )
        store.swapTitles()
        // As if Background reading had confirmed every Movie: its details kept with their genre field.
        movieListings.forEach { store.saveMovieDetails(it, ProviderMovieDetails(null, ProviderDetails(genre = fields.random(random))), it, Instant.now()) }
        return movieListings
    }
}
