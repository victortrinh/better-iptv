package dev.betteriptv.core

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
 * in about 145,000 Listings, each Show's Listing and each Movie's details naming 12 cast and a few
 * genres as the Provider does, and 45,000 Channels. Skipped unless run with `-Pbenchmark`; prints
 * each part's time and what the garbage collector did.
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

        val prefixTable = surveyedPrefixTable()
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

    /** Titles 1 to 58,000 (one in four a Show), two or three Listings each, every Listing with the Provider's cast and genres; then the Channels. */
    private fun fill(store: CatalogStore) {
        val titles = 58_000
        val cast = List(150_000) { name(2) }
        var listingId = 0L
        val genres = listOf("Drama", "Comedy", "Thriller", "Action & Adventure", "Crime", "Komödie", "Drame")
        val details = { ProviderDetails(genre = List(3) { genres.random(random) }.joinToString(" / "), cast = List(12) { cast.random(random) }.joinToString(", ")) }
        val movieListings = mutableListOf<Long>()
        store.stageChannels { staging ->
            repeat(45_000) {
                val country = listOf("US", "CA", "FR", "UK", "AR").random(random)
                val channel = name(random.nextInt(1, 4))
                val quality = listOf("HD", "FHD", "SD", "4K").random(random)
                staging.channel(it + 1L, "$country: $channel $quality", null, null, null, "", "$country: $channel".uppercase(), channel, country, Quality.HD, false)
            }
        }
        store.swapChannels(Instant.now())
        store.stageTitles(
            fill = { staging ->
                staging.category(TitleKind.Movie, "1", "EN | MOVIES")
                staging.category(TitleKind.Movie, "2", "FR | FILMS")
                staging.category(TitleKind.Show, "3", "EN | SERIES")
                for (number in 1L..titles) {
                    val kind = if (number % 4 == 0L) TitleKind.Show else TitleKind.Movie
                    val name = name(random.nextInt(1, 5))
                    val year = random.nextInt(1950, 2026)
                    repeat(random.nextInt(2, 4)) { version ->
                        val (prefix, category) = when {
                            kind == TitleKind.Show -> "EN" to "3"
                            version == 1 -> "FR" to "2"
                            else -> listOf("EN", "4K-EN", "NF").random(random) to "1"
                        }
                        val listing = "$prefix - $name ($year)"
                        if (kind == TitleKind.Movie) movieListings += listingId + 1
                        staging.listing(kind, ++listingId, listing, category, listingId, null, false, number, year, details = if (kind == TitleKind.Show) details() else null)
                    }
                }
            },
            onFilled = {},
            completed = { movies, shows, _ -> CompletedSync(Instant.now(), Duration.ZERO, movies, emptyList(), showListingCount = shows) },
        )
        store.swapTitles()
        // As if every Movie's page had been opened: its details kept with their cast and genres.
        movieListings.forEach { store.saveMovieDetails(it, ProviderMovieDetails(null, details()), it, Instant.now()) }
    }
}
