package dev.betteriptv.app

import android.content.Context
import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import dev.betteriptv.app.ui.theme.NuageTheme
import dev.betteriptv.core.ChartList
import dev.betteriptv.core.HomeRow
import dev.betteriptv.core.InProgress
import dev.betteriptv.core.TitleId
import dev.betteriptv.core.TitleKind
import dev.betteriptv.core.TitleSummary
import dev.betteriptv.core.VersionAttributes
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * A row never shows the same card twice (#378): the stick closed on Home with "Key
 * Tmdb(kind=Show, tmdbId=124364) was already used" when a row it read had one Title twice, whatever
 * list it came from. The rails key their cards by Title, so a repeated one shows once, where it
 * first came, on the rows Home, Movies, Shows and Live share ([RowsLayout]).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-mdpi")
class RowsDuplicateCardsTest {
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

    private var rows by mutableStateOf<List<HomeRow>>(emptyList())
    private var reading by mutableIntStateOf(0)

    private fun show(name: String, tmdbId: Long) = TitleSummary(TitleId.Tmdb(TitleKind.Show, tmdbId), name, 2016, null, VersionAttributes())
    private fun resume(title: TitleSummary) = InProgress(title, null, 0.5f)

    private val colony = show("Colony", 124364)
    private val others = (1L..3L).map { show("Other $it", it) }

    private val state = RowsState()

    private fun screen() = compose.setContent {
        NuageTheme {
            RowsLayout(
                state, restoreFocus = null, focusTab = false,
                reading = reading,
                read = { rows },
                empty = "",
                setFavourite = { _, _ -> },
                onScreen = { },
                info = { _, _, _ -> },
                appLanguage = "en",
                onOpen = { _, _, _ -> },
            ) { tab -> Box(Modifier.size(80.dp, 30.dp).testTag("tab").focusRequester(tab).focusable()) }
        }
    }

    /** The Titles of the row [at] the screen shows, by name, in order. */
    private fun shown(at: Int): List<String> = when (val row = state.rows.orEmpty()[at]) {
        is HomeRow.ContinueWatching -> row.titles.map { it.title.name }
        is HomeRow.Chart -> row.titles.map { it.name }
        is HomeRow.Genre -> row.titles.map { it.name }
        else -> error("no Titles in $row")
    }

    @Test
    fun aTitleTwiceInContinueWatchingShowsOnce() {
        rows = listOf(HomeRow.ContinueWatching(listOf(resume(colony), resume(others[0]), resume(colony))))
        screen()
        compose.waitForIdle()
        assertEquals(listOf("Colony", "Other 1"), shown(0))
    }

    @Test
    fun aTitleTwiceInATopRowShowsOnce() {
        // Top rows never loop, so their cards are keyed by Title alone, as the stick's crash was.
        rows = listOf(HomeRow.Chart(ChartList.TopShows, null, listOf(colony, others[0], colony, others[1])))
        screen()
        compose.waitForIdle()
        assertEquals(listOf("Colony", "Other 1", "Other 2"), shown(0))
    }

    @Test
    fun aTitleTwiceInRowsReadAgainShowsOnce() {
        rows = listOf(HomeRow.Genre("Drama", others))
        screen()
        compose.waitForIdle()
        rows = listOf(HomeRow.ContinueWatching(listOf(resume(colony), resume(colony))), HomeRow.Genre("Drama", others + others[0]))
        reading++
        compose.waitForIdle()
        assertEquals(listOf("Colony"), shown(0))
        assertEquals(listOf("Other 1", "Other 2", "Other 3"), shown(1))
    }
}
