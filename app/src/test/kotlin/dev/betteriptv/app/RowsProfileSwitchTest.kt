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
import org.junit.Assert.assertEquals
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
 * Switching Profile while Home shows reads the new Profile's rows (#501). The root screen gives
 * each Profile a [RowsState] of its own, so a switch from the chip's "Who's watching?" hands the
 * still-composed screen a fresh one with nothing read into it, while what the rows are read for
 * ([RowsLayout]'s `reading`) may stay the same: Home waited on its dot until the Viewer left it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-mdpi")
class RowsProfileSwitchTest {
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

    /** The rows of the Profile the device is on. */
    private var rows = listOf(HomeRow.Genre("Drama", listOf(movie("First's", 1))))
    private var featured: FeaturedTitle? = null
    private var asked = 0
    private var state by mutableStateOf(RowsState())

    private fun screen(feature: (suspend (Boolean) -> FeaturedTitle?)? = null) = compose.setContent {
        NuageTheme {
            RowsLayout(
                state, restoreFocus = null, focusTab = true,
                reading = "same",
                read = { rows },
                empty = "",
                setFavourite = { _, _ -> },
                onScreen = { },
                info = { _, _, _ -> },
                appLanguage = "en",
                onOpen = { _, _ -> },
                feature = feature,
            ) { tab -> Box(Modifier.size(80.dp, 30.dp).testTag("tab").focusRequester(tab).focusable()) }
        }
    }

    private fun names(state: RowsState) = state.rows.orEmpty().map { (it as HomeRow.Genre).titles.map { title -> title.name } }

    @Test
    fun aFreshStateIsReadIntoWithNothingElseChanged() {
        screen()
        compose.waitForIdle()
        assertEquals(listOf(listOf("First's")), names(state))
        rows = listOf(HomeRow.Genre("Comedy", listOf(movie("Second's", 2))))
        state = RowsState()
        compose.waitForIdle()
        assertEquals(listOf(listOf("Second's")), names(state))
    }

    @Test
    fun aFreshStateAsksForItsHero() {
        screen(feature = { asked++; featured })
        compose.waitForIdle()
        val before = asked
        state = RowsState()
        compose.waitForIdle()
        assertTrue("the new Profile's hero is asked for", asked > before)
        assertTrue(state.upNextRead)
    }
}
