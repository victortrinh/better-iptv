package dev.betteriptv.core

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Times Syncs of a real survey snapshot, printing each one's [SyncTiming] block: the JVM's view of
 * where a Sync of the real catalog spends its time, statement by statement (#567). The first Sync
 * fills an empty database; the later ones are "Sync now" on a device that has a catalog, as on the
 * stick. Skipped unless run with `-Psurvey=<path to .survey/raw>`:
 *
 *     ./gradlew :core:jvmTest --tests '*SurveySyncTimingBenchmark' -Psurvey=/path/to/.survey/raw
 */
class SurveySyncTimingBenchmark {
    @get:Rule val folder = TemporaryFolder()
    private val provider = FakeProvider()
    private val library by lazy { openSurveyedLibrary(folder.root.resolve("catalog.db").path, InMemoryCredentials()) }

    @After fun tearDown() {
        library.close()
        provider.close()
    }

    @Test fun `time Syncs of the survey`() = runBlocking {
        val survey = System.getProperty("survey")?.let(::File)
        assumeTrue("run with -Psurvey=<path to .survey/raw>", survey?.resolve("vod_streams.json")?.isFile == true)
        provider.catalogDirectory = survey
        library.logIn(provider.login)
        library.onSyncTimed = { println(it) }

        repeat(4) { assertTrue(library.sync() is SyncStatus.Synced) }
    }
}
