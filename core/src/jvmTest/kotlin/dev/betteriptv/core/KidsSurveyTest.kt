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
 * The Kids catalog's rule measured on a real survey snapshot (scripts/survey.py writes it to the
 * git-ignored .survey/raw): on the 2026-10-24 catalog the Base table's Kids words file 35 movie
 * and 22 show Categories, 2,699 Movies (4.6%) and 950 Shows (5.6%), 2,657 and 890 of them Matched
 * (docs/roadmap.md, M5), and a change to the words must keep about that many. Skipped unless run
 * with `-Psurvey=<path to .survey/raw>`.
 */
class KidsSurveyTest {
    @get:Rule val folder = TemporaryFolder()
    private val provider = FakeProvider()
    private val library by lazy { openSurveyedLibrary(folder.root.resolve("catalog.db").path, InMemoryCredentials()) }

    @After fun tearDown() {
        library.close()
        provider.close()
    }

    @Test fun `the Kids catalog of the survey is about 2,700 Movies and 950 Shows`() = runBlocking {
        val survey = System.getProperty("survey")?.let(::File)
        assumeTrue("run with -Psurvey=<path to .survey/raw>", survey?.resolve("vod_streams.json")?.isFile == true)
        provider.catalogDirectory = survey
        library.logIn(provider.login)
        library.sync()

        val catalog = library.kidsCatalog()
        val movies = catalog.getValue(TitleKind.Movie).size
        val shows = catalog.getValue(TitleKind.Show).size
        println("Kids catalog: $movies Movies of ${library.titleCount(TitleKind.Movie)}, $shows Shows of ${library.titleCount(TitleKind.Show)}")
        assertTrue("$movies Movies", movies in 2_500..2_900)
        assertTrue("$shows Shows", shows in 850..1_050)
    }
}
