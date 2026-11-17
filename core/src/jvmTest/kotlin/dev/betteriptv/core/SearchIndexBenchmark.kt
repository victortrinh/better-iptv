package dev.betteriptv.core

import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.time.Duration
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.random.Random

/**
 * Times building the stored search index of a catalog the size of the real one (#106, #584): about
 * 58,000 Titles in about 145,000 Listings, each Show's Listing and each Movie's details naming 12
 * cast and a few genres as the Provider does, and 45,000 Channels, then what finding a few queries
 * costs. Skipped unless run with `-Pbenchmark`.
 */
class SearchIndexBenchmark {
    @get:Rule val folder = TemporaryFolder()
    private val random = Random(106)
    private val syllables = listOf("ka", "lo", "mi", "ne", "ra", "to", "su", "vel", "dor", "an", "ix", "por", "bel", "chi", "ste", "gru", "mon", "tra", "zé", "qui")

    private fun word() = (1..random.nextInt(2, 5)).joinToString("") { syllables.random(random) }

    private fun name(words: Int) = (1..words).joinToString(" ") { word().replaceFirstChar(Char::uppercase) }

    @Test fun `build and search the stored index of a full-size catalog`() {
        assumeTrue("run with -Pbenchmark", System.getProperty("benchmark") != null)
        val store = CatalogStore(folder.root.resolve("catalog.db").path)
        fill(store)

        val prefixTable = surveyedPrefixTable()
        // The catalog's index as a Sync stores it (#584), built here as a database kept from before it builds it once.
        repeat(3) { run ->
            val started = System.nanoTime()
            store.search.rebuild(SearchSources(prefixTable), evenIfReady = true)
            println("Stored search index build ${run + 1}: ${(System.nanoTime() - started) / 1_000_000} ms, catalog.db ${folder.root.resolve("catalog.db").length() / (1024 * 1024)} MB")
        }
        // What a keystroke costs: finding the query's Titles, people, genres and Channels, before ranking. Best of 5.
        val title = sampleTitle.lowercase()
        val person = samplePerson.lowercase()
        val queries = listOf(
            title.take(2), title.take(4), title.substringBefore(' '), title, title.replaceFirst(title[2].toString(), ""), "$title 1999",
            person.take(3), person, person.dropLast(1) + "x", "dr", "drama", "us " + sampleChannel.lowercase().take(3),
        ).distinct()
        for (query in queries) {
            fun best(find: () -> Unit) = (1..5).minOf { val started = System.nanoTime(); find(); (System.nanoTime() - started) / 1_000 } / 1000.0
            var found = listOf<Map<*, Int>>()
            val ms = best {
                val titles = store.search.find(query)
                found = listOf(titles.titles, titles.people, titles.genres, store.search.findChannels(query))
            }
            println("Find \"$query\": $ms ms (${found[0].size} Titles, ${found[1].size} people, ${found[2].size} genres, ${found[3].size} Channels)")
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
        store.swapChannels(Clock.System.now())
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
            completed = { movies, shows, _ -> CompletedSync(Clock.System.now(), Duration.ZERO, movies, emptyList(), showListingCount = shows) },
        )
        store.swapTitles()
        // As if every Movie's page had been opened: its details kept with their cast and genres.
        movieListings.forEach { store.saveMovieDetails(it, ProviderMovieDetails(null, details()), it, Clock.System.now()) }
    }
}
