package dev.betteriptv.core

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Syncs a real survey snapshot (scripts/survey.py writes it to the git-ignored .survey/raw) and
 * prints what the Prefix table makes of it. Skipped unless run with `-Psurvey=<path to .survey/raw>`.
 */
class SurveyCoverageTest {
    @get:Rule val folder = TemporaryFolder()
    private val provider = FakeProvider()
    private val library by lazy { Library.open(folder.root.resolve("catalog.db").path, InMemoryCredentials()) }

    @After fun tearDown() {
        library.close()
        provider.close()
    }

    @Test fun `report the survey's unknown Prefixes`() = runBlocking {
        val survey = System.getProperty("survey")?.let(::File)
        assumeTrue("run with -Psurvey=<path to .survey/raw>", survey?.resolve("vod_streams.json")?.isFile == true)
        provider.catalogDirectory = survey
        library.logIn(provider.login)

        val last = (library.sync() as SyncStatus.Synced).last

        println("Movie list: ${last.movieListingCount} Listings")
        println("Show list: ${last.showListingCount} Listings, ${library.titleCount(TitleKind.Show)} Shows, in ${last.duration.toMillis()} ms")
        println("Unknown Prefixes: ${last.unknownPrefixes.size}")
        last.unknownPrefixes.forEach { println("  ${it.prefix}\t${it.listingCount}\t${it.example}") }
    }
}
