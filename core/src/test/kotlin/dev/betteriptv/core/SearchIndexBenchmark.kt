package dev.betteriptv.core

import com.squareup.moshi.Moshi
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.lang.management.ManagementFactory
import java.time.Duration
import java.time.Instant
import kotlin.random.Random

/**
 * Times building the search index of a catalog the size of the real one (#106): about 58,000 Titles
 * with TMDB metadata cached for each, in about 145,000 Listings, and 45,000 Channels. Skipped unless
 * run with `-Pbenchmark`; prints each part's time and what the garbage collector did.
 */
class SearchIndexBenchmark {
    @get:Rule val folder = TemporaryFolder()
    private val random = Random(106)
    private val syllables = listOf("ka", "lo", "mi", "ne", "ra", "to", "su", "vel", "dor", "an", "ix", "por", "bel", "chi", "ste", "gru", "mon", "tra", "zé", "qui")

    private fun word() = (1..random.nextInt(2, 5)).joinToString("") { syllables.random(random) }

    private fun name(words: Int) = (1..words).joinToString(" ") { word().replaceFirstChar(Char::uppercase) }

    @Test fun `build the search index of a full-size catalog`() {
        assumeTrue("run with -Pbenchmark", System.getProperty("benchmark") != null)
        val store = CatalogStore(folder.root.resolve("catalog.db").path)
        fill(store)

        val prefixTable = PrefixTable.bundled()
        val gc = ManagementFactory.getGarbageCollectorMXBeans()
        val runtime = Runtime.getRuntime()
        repeat(3) { run ->
            System.gc()
            val before = runtime.totalMemory() - runtime.freeMemory()
            val collections = gc.sumOf { it.collectionCount }
            val collecting = gc.sumOf { it.collectionTime }
            val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
            val allocated = threads.currentThreadAllocatedBytes
            val phases = LinkedHashMap<String, String>()
            var phaseAllocated = allocated
            val started = System.nanoTime()
            val index = buildSearchIndex(store, prefixTable) { name, ms ->
                val now = threads.currentThreadAllocatedBytes
                phases[name] = "$ms ms/${(now - phaseAllocated) / (1024 * 1024)} MB"
                phaseAllocated = now
            }
            val total = (System.nanoTime() - started) / 1_000_000
            val allocatedMb = (threads.currentThreadAllocatedBytes - allocated) / (1024 * 1024)
            System.gc()
            val heldMb = (runtime.totalMemory() - runtime.freeMemory() - before) / (1024 * 1024)
            println(
                "Search index build ${run + 1}: $total ms (" + phases.entries.joinToString { "${it.key} ${it.value}" } + "), " +
                    "$allocatedMb MB allocated, ${gc.sumOf { it.collectionCount } - collections} GCs taking ${gc.sumOf { it.collectionTime } - collecting} ms, " +
                    "${index.titleCount} Titles, ${index.channels.size} Channels and ${index.peopleCount} people in $heldMb MB of heap",
            )
        }
        store.close()
    }

    /** Titles 1 to 58,000 (one in four a Show), two or three Listings each, all with metadata; then the Channels. */
    private fun fill(store: CatalogStore) {
        val titles = 58_000
        val cast = List(150_000) { name(2) }
        var listingId = 0L
        store.replaceListings(
            fill = { staging ->
                staging.category(TitleKind.Movie, "1", "EN | MOVIES")
                staging.category(TitleKind.Movie, "2", "FR | FILMS")
                staging.category(TitleKind.Show, "3", "EN | SERIES")
                for (tmdbId in 1L..titles) {
                    val kind = if (tmdbId % 4 == 0L) TitleKind.Show else TitleKind.Movie
                    val name = name(random.nextInt(1, 5))
                    val year = random.nextInt(1950, 2026)
                    repeat(random.nextInt(2, 4)) { version ->
                        val (prefix, category) = when {
                            kind == TitleKind.Show -> "EN" to "3"
                            version == 1 -> "FR" to "2"
                            else -> listOf("EN", "4K-EN", "NF").random(random) to "1"
                        }
                        val listing = "$prefix - $name ($year)"
                        staging.listing(++listingId, listing, category, listingId, null, false, MatchQuestion(kind, name, year, null, tmdbId))
                    }
                }
                repeat(45_000) { staging.channel(it + 1L, "${listOf("US", "CA", "FR", "UK", "AR").random(random)}: ${name(random.nextInt(1, 4))} ${listOf("HD", "FHD", "SD", "4K").random(random)}", null, null, null, "") }
            },
            completed = { movies, shows, _ -> CompletedSync(Instant.now(), Duration.ZERO, movies, emptyList(), showListingCount = shows) },
        )
        // Shaped like the cloud's answers: titles in each translation, a few other names, English and French descriptions, 12 cast.
        val json = Moshi.Builder().build().adapter(Any::class.java)
        for (batch in (1L..titles).chunked(2_000)) {
            val bodies = batch.associate { tmdbId ->
                val kind = if (tmdbId % 4 == 0L) TitleKind.Show else TitleKind.Movie
                val original = name(random.nextInt(1, 5))
                val translations = List(random.nextInt(5, 40)) { Triple(listOf("fr", "de", "es", "it", "pt", "ru", "ja", "zh", "ar", "nl").random(random), "XX", if (random.nextBoolean()) original else name(3)) }
                val body = tmdbTitle(
                    tmdbId, original, kind = kind.name.lowercase(),
                    titles = listOf(Triple("en", "US", original)) + translations,
                    descriptions = listOf("en", "fr").map { Triple(it, "", (1..60).joinToString(" ") { word() }) },
                    genres = listOf("Drama", "Comedy", "Thriller"),
                    releaseDate = "2001-02-03", runtimeMinutes = 104, posterPath = "/poster$tmdbId.jpg", backdropPath = "/backdrop$tmdbId.jpg",
                    cast = List(12) { cast.random(random) },
                    popularity = random.nextDouble(0.0, 200.0),
                    alternativeTitles = List(random.nextInt(0, 12)) { name(3) },
                )
                TitleId.Tmdb(kind, tmdbId) to json.toJson(body)
            }
            store.saveMetadata("en", bodies, emptyList(), Instant.now())
        }
    }
}
