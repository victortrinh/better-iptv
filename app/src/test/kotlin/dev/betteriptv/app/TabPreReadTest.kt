package dev.betteriptv.app

import android.content.Context
import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import dev.betteriptv.app.ui.theme.NuageTheme
import dev.betteriptv.core.FeaturedTitle
import dev.betteriptv.core.HomeRow
import dev.betteriptv.core.TitleId
import dev.betteriptv.core.TitleKind
import dev.betteriptv.core.TitleSummary
import dev.betteriptv.core.VersionAttributes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The browsing tabs not shown are read ahead of their first visit ([preReadTabs]), so it shows at
 * once as a visit back does: one tab at a time, each once the remote rests, its rows and hero landing
 * together; a tab its screen has read is left to it; one read ahead for other languages or another
 * catalog is read again; and the screen entered on what was read ahead ([RowsLayout]) shows it at
 * once, asks only for the next entry's hero, and reads its rows again for what they are read for now.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-mdpi")
class TabPreReadTest {
    /** The compose rule's host activity, registered as [RowEntryTest] does so release unit tests run it too. */
    @get:Rule(order = 0)
    val host = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                val context = ApplicationProvider.getApplicationContext<Context>()
                shadowOf(context.packageManager).addOrUpdateActivity(ActivityInfo().apply { name = ComponentActivity::class.java.name; packageName = context.packageName })
                base.evaluate()
            }
        }
    }

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private fun movie(name: String, number: Long) = TitleSummary(TitleId.Numbered(TitleKind.Movie, number), name, 2020, null, VersionAttributes())
    private fun genre(name: String, vararg titles: String) = HomeRow.Genre(name, titles.mapIndexed { i, title -> movie(title, i + 1L) })
    private fun featured(name: String) = FeaturedTitle(TitleId.Numbered(TitleKind.Movie, name.hashCode().toLong()), name, 2020, null, emptyList(), null, "", null)

    private fun names(state: RowsState) = state.rows.orEmpty().map { (it as HomeRow.Genre).titles.map { title -> title.name } }

    /** What happened, in order: rests waited for, tabs read, heroes picked. */
    private val log = ArrayList<String>()

    private fun tab(name: String, state: RowsState, withHero: Boolean = true) = PreReadTab(
        state,
        read = { log += "read $name"; listOf(genre(name, "$name's")) },
        feature = if (withHero) { _ -> featured("$name hero") } else null,
    )

    private suspend fun preRead(tabs: List<PreReadTab>, readingFor: Any = "en") = preReadTabs(
        tabs, readingFor,
        idle = { log += "rest" },
        hero = { feature -> feature(true).also { log += "hero ${it?.name}" } },
    )

    @Test
    fun eachTabIsReadInTurnOnceTheRemoteRestsWithItsHeroAsTheNextEntrys() = runTest {
        val movies = RowsState()
        val live = RowsState()
        preRead(listOf(tab("Movies", movies), tab("Live", live, withHero = false)))
        assertEquals(listOf("rest", "read Movies", "hero Movies hero", "rest", "read Live"), log)
        assertEquals(listOf(listOf("Movies's")), names(movies))
        assertEquals("Movies hero", movies.upNext?.name)
        assertTrue(movies.upNextRead)
        // Live has no hero: its screen's own first entry is as before.
        assertEquals(listOf(listOf("Live's")), names(live))
        assertFalse(live.upNextRead)
    }

    @Test
    fun aTabItsScreenHasReadIsLeftToIt() = runTest {
        val shows = RowsState().apply { rows = listOf(genre("Shows", "Kept")) }
        preRead(listOf(tab("Shows", shows)))
        assertEquals(emptyList<String>(), log)
        assertEquals(listOf(listOf("Kept")), names(shows))
    }

    @Test
    fun aTabReadAheadIsReadAgainOnlyForSomethingElse() = runTest {
        val movies = RowsState()
        preRead(listOf(tab("Movies", movies)), readingFor = "en")
        preRead(listOf(tab("Movies", movies)), readingFor = "en")
        assertEquals("read once for the same languages", 1, log.count { it == "read Movies" })
        preRead(listOf(tab("Movies", movies)), readingFor = "fr")
        assertEquals("read again for others", 2, log.count { it == "read Movies" })
    }

    @Test
    fun aTabStoppedHalfwayKeepsNothingOfIt() = runTest {
        val movies = RowsState()
        val picking = CompletableDeferred<Unit>()
        val reading = launch {
            preReadTabs(listOf(PreReadTab(movies, { listOf(genre("Movies", "Half")) }, { _ -> picking.complete(Unit); awaitCancellation() })), "en", idle = {}, hero = { it(true) })
        }
        picking.await()
        reading.cancel()
        reading.join()
        // Its rows wait for its hero: a visit now reads both, as a first entry does.
        assertNull(movies.rows)
        assertFalse(movies.upNextRead)
        assertNull(movies.preReadFor)
    }

    @Test
    fun aTabEnteredOnWhatWasReadAheadShowsItAtOnceAndAsksOnlyForTheNextHero() {
        val state = RowsState()
        val readAhead = featured("Read ahead")
        kotlinx.coroutines.runBlocking { preReadTabs(listOf(PreReadTab(state, { listOf(genre("Movies", "Ahead's")) }, { readAhead })), "en", idle = {}, hero = { it(true) }) }
        // The screen's own reading waits, as on the stick: what was read ahead shows meanwhile.
        val fresh = CompletableDeferred<List<HomeRow>>()
        var asked = 0
        compose.setContent {
            NuageTheme {
                RowsLayout(
                    state, restoreFocus = null, focusTab = true,
                    reading = "same",
                    read = { fresh.await() },
                    empty = "",
                    setFavourite = { _, _ -> },
                    onScreen = { },
                    info = { _, _, _ -> },
                    appLanguage = "en",
                    onOpen = { _, _, _ -> },
                    feature = { readDetails -> if (readDetails) asked++; featured("Next") },
                ) { tab -> Box(Modifier.size(80.dp, 30.dp).testTag("tab").focusRequester(tab).focusable()) }
            }
        }
        compose.waitForIdle()
        assertSame("the hero read ahead features at once", readAhead, state.featured)
        assertEquals(listOf(listOf("Ahead's")), names(state))
        assertNull("the screen reads its rows itself from now on", state.preReadFor)
        fresh.complete(listOf(genre("Movies", "Fresh's")))
        compose.waitForIdle()
        assertEquals("its rows are read again on entry", listOf(listOf("Fresh's")), names(state))
        // A first entry not read ahead asks twice: for this entry's hero and for the next one's.
        assertEquals("only the next entry's hero is asked for", 1, asked)
        assertSame(readAhead, state.featured)
    }

    @Test
    fun aTabEnteredOnWhatWasReadAheadReadsItAgainWhenWhatItIsReadForChanges() {
        val state = RowsState()
        kotlinx.coroutines.runBlocking { preReadTabs(listOf(PreReadTab(state, { listOf(genre("Movies", "Ahead's")) })), "en", idle = {}, hero = { null }) }
        var reading by mutableStateOf("en")
        compose.setContent {
            NuageTheme {
                RowsLayout(
                    state, restoreFocus = null, focusTab = true,
                    reading = reading,
                    read = { listOf(genre("Movies", "Read for $reading")) },
                    empty = "",
                    setFavourite = { _, _ -> },
                    onScreen = { },
                    info = { _, _, _ -> },
                    appLanguage = "en",
                    onOpen = { _, _, _ -> },
                ) { tab -> Box(Modifier.size(80.dp, 30.dp).testTag("tab").focusRequester(tab).focusable()) }
            }
        }
        compose.waitForIdle()
        assertEquals(listOf(listOf("Read for en")), names(state))
        reading = "fr"
        compose.waitForIdle()
        assertEquals(listOf(listOf("Read for fr")), names(state))
    }
}
