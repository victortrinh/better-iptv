package dev.betteriptv.core

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * Syncs a real survey snapshot (scripts/survey.py writes it to the git-ignored .survey/raw) and
 * prints, for each League, how many of its Event channels the catalog has (named by one of the
 * League's names and by no other League's), with examples; then, for a few competitions the app
 * doesn't offer yet, how many Event channels name them. With `-PsurveyCloud=<cloud address>` it
 * also matches that cloud's Games to the survey and prints each Game's Feeds. Skipped unless run
 * with `-Psurvey=<path to .survey/raw>` (#375):
 *
 * `./gradlew :core:test --tests '*SurveyLeaguesTest' -Psurvey=$PWD/.survey/raw -i`
 */
class SurveyLeaguesTest {
    @get:Rule val folder = TemporaryFolder()
    private val provider = FakeProvider()
    private val database by lazy { folder.root.resolve("catalog.db").path }
    private val credentials = InMemoryCredentials()

    // No cloud for the Sync: it would ask the cloud to match the whole catalog's Titles.
    private val library by lazy { openSurveyedLibrary(database, credentials) }

    @After fun tearDown() {
        library.close()
        provider.close()
    }

    @Test fun `report each League's Event channels in the survey`() = runBlocking {
        val survey = System.getProperty("survey")?.let(::File)
        assumeTrue("run with -Psurvey=<path to .survey/raw>", survey?.resolve("live_streams.json")?.isFile == true)
        provider.catalogDirectory = survey
        library.logIn(provider.login)
        println("Sync: ${library.sync()}")

        val now = Instant.now()
        val events = CatalogStore(database).eventChannels(now - Duration.ofDays(1), now + Duration.ofDays(9)).map { it.first.variants.first().name }
        val words = events.map(SearchIndex::words)
        fun holding(phrases: Collection<String>) = events.indices.filterTo(HashSet()) { i -> phrases.any { containsPhrase(words[i], SearchIndex.words(it)) } }
        println("Event channels in the window: ${events.size}")
        League.entries.forEach { league ->
            val own = league.names.map(SearchIndex::words)
            val others = League.entries.filter { it != league }.flatMap { it.names }.filter { name -> own.none { containsPhrase(it, SearchIndex.words(name)) } }
            val named = (holding(league.names) - holding(others)).sorted()
            println("LEAGUE ${league.key}\t${named.size}")
            named.take(6).forEach { println("    ${events[it]}") }
        }
        CANDIDATES.forEach { (label, names) ->
            val named = holding(names).sorted()
            println("CANDIDATE $label\t${named.size}")
            named.take(10).forEach { println("    ${events[it]}") }
        }

        val cloud = System.getProperty("surveyCloud") ?: return@runBlocking
        library.close()
        openSurveyedLibrary(database, credentials, cloud).use { withCloud ->
            withCloud.liveRows(League.entries, "CA", listOf("en"), now, ZoneId.of("America/Toronto"))
                .flatMap { row ->
                    when (row) {
                        is HomeRow.LiveNow -> row.games
                        is HomeRow.Today -> row.games
                        is HomeRow.LeagueGames -> row.games
                        else -> emptyList()
                    }
                }
                .distinctBy { it.key }
                .forEach { g ->
                    println("GAME ${g.game.league}\t${g.game.away?.name} at ${g.game.home?.name}${g.game.event?.name.orEmpty()}\t${g.game.start}\t${g.feeds.count { it.kind == FeedKind.EventChannel }} Event channels")
                    g.feeds.filter { it.kind == FeedKind.EventChannel }.take(3).forEach { println("    ${it.name}") }
                }
        }
    }

    private companion object {
        /** Competitions #375 asks about, by the names an Event channel might give them. */
        val CANDIDATES = mapOf(
            "europa-league" to listOf("Europa League", "UEL", "UEFA Europa"),
            "conference-league" to listOf("Conference League", "UECL", "UEFA Conference"),
            "liga-mx" to listOf("Liga MX", "LigaMX"),
            "premiership-rugby" to listOf("Premiership Rugby", "Gallagher Premiership", "Prem Rugby"),
            "top-14" to listOf("Top 14", "Top14"),
            "urc" to listOf("URC", "United Rugby Championship"),
            "six-nations" to listOf("Six Nations", "6 Nations"),
            "nations-championship" to listOf("Nations Championship"),
            "rugby" to listOf("Rugby"),
        )
    }
}
