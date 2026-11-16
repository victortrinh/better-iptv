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
        val index = buildSearchIndex(store, prefixTable)
        // The same catalog's index as a Sync stores it (#584), built here as a database kept from before it builds it once.
        repeat(3) { run ->
            val started = System.nanoTime()
            store.search.rebuild(SearchSources(prefixTable))
            println("Stored search index build ${run + 1}: ${(System.nanoTime() - started) / 1_000_000} ms, catalog.db ${folder.root.resolve("catalog.db").length() / (1024 * 1024)} MB")
        }
        // What a keystroke costs each index: finding the query's Titles, people, genres, Channels and Replays, before ranking. Best of 5.
        val title = sampleTitle.lowercase()
        val person = samplePerson.lowercase()
        val queries = listOf(
            title.take(2), title.take(4), title.substringBefore(' '), title, title.replaceFirst(title[2].toString(), ""), "$title 1999",
            person.take(3), person, person.dropLast(1) + "x", "dr", "drama", "us " + sampleChannel.lowercase().take(3),
        ).distinct()
        for (query in queries) {
            fun best(find: () -> Unit) = (1..5).minOf { val started = System.nanoTime(); find(); (System.nanoTime() - started) / 1_000 } / 1000.0
            var inMemory = listOf<Map<*, Int>>()
            var stored = listOf<Map<*, Int>>()
            val memoryMs = best {
                inMemory = listOf(
                    index.findTitles(query).associate { index.titleId(it.index) to it.distance },
                    index.findPeople(query).associate { index.person(it.index) to it.distance },
                    index.findGenres(query).associate { index.genres[it.index] to it.distance },
                    index.findChannels(query).associate { index.channels[it.index] to it.distance },
                )
            }
            val storedMs = best {
                val found = store.search.find(query)
                val channels = store.search.findChannels(query)
                stored = listOf(found.titles, found.people, found.genres, channels)
            }
            // Both name what they found the same way, and must have found the same, as closely.
            val found = store.search.find(query)
            val ids = store.search.titleIds(found.titles.keys)
            val people = store.search.personNames(found.people.keys)
            val genres = store.search.genreNames(found.genres.keys)
            val channels = store.search.findChannels(query)
            val leads = store.search.channelIds(channels.keys)
            org.junit.Assert.assertEquals("Titles for $query", inMemory[0], found.titles.mapKeys { ids.getValue(it.key) })
            org.junit.Assert.assertEquals("people for $query", inMemory[1], found.people.mapKeys { people.getValue(it.key) })
            org.junit.Assert.assertEquals("genres for $query", inMemory[2], found.genres.mapKeys { genres.getValue(it.key) })
            org.junit.Assert.assertEquals("Channels for $query", inMemory[3], channels.mapKeys { leads.getValue(it.key) })
            println("Find \"$query\": in memory $memoryMs ms, stored $storedMs ms (${stored[0].size} Titles, ${stored[1].size} people, ${stored[2].size} genres, ${stored[3].size} Channels)")
        }
        store.close()
    }

    private var sampleTitle = ""
    private var samplePerson = ""
    private var sampleChannel = ""

    /** Titles 1 to 58,000 (one in four a Show), two or three Listings each, every Listing with the Provider's cast and genres; then the Channels. */
    private fun fill(store: CatalogStore) {
        val titles = 58_000
        val cast = List(150_000) { name(2) }
        samplePerson = cast[75_000]
        var listingId = 0L
        val genres = listOf("Drama", "Comedy", "Thriller", "Action & Adventure", "Crime", "Komödie", "Drame")
        val details = { ProviderDetails(genre = List(3) { genres.random(random) }.joinToString(" / "), cast = List(12) { cast.random(random) }.joinToString(", ")) }
        val movieListings = mutableListOf<Long>()
        store.stageChannels { staging ->
            repeat(45_000) {
                val country = listOf("US", "CA", "FR", "UK", "AR").random(random)
                val channel = name(random.nextInt(1, 4))
                if (it == 20_000) sampleChannel = channel
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
                    if (number == 30_000L) sampleTitle = name
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
