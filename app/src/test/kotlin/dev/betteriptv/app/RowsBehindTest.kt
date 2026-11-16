package dev.betteriptv.app

import android.content.Context
import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.tv.material3.Text
import dev.betteriptv.app.ui.theme.NuageTheme
import dev.betteriptv.core.HomeRow
import dev.betteriptv.core.TitleId
import dev.betteriptv.core.TitleKind
import dev.betteriptv.core.TitleSummary
import dev.betteriptv.core.VersionAttributes
import kotlinx.coroutines.CompletableDeferred
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * A screen of rows tells its tabs while its rows are behind what they are read for ([LocalRowsBehind]),
 * so the Catalog pill doesn't say "Up to date" over a Head start's rows while the full catalog's are
 * still being read (#605): the genre rows of a big catalog take seconds to work out.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-mdpi")
class RowsBehindTest {
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
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun titles(prefix: String) = List(10) { TitleSummary(TitleId.Unmatched(TitleKind.Movie, "$prefix$it".hashCode().toLong()), "$prefix$it", 2026, null, VersionAttributes()) }

    private var reading by mutableIntStateOf(0)

    @Test fun theTabsAreToldTheRowsAreBehindFromTheCatalogLandingUntilItsRowsShow() {
        val headStart = listOf(HomeRow.NewOnYourService(titles("Fresh")))
        val whole = headStart + HomeRow.Genre("Drama", titles("Drama"))
        // The full catalog's rows, which take a while to read.
        val slow = CompletableDeferred<List<HomeRow>>()
        compose.setContent {
            NuageTheme {
                RowsLayout(
                    remember { RowsState() }, restoreFocus = null, focusTab = false,
                    reading = reading,
                    read = { if (reading == 0) headStart else slow.await() },
                    empty = "",
                    setFavourite = { _, _ -> },
                    onScreen = {},
                    info = { _, _, _ -> },
                    appLanguage = "en",
                    onOpen = { _, _ -> },
                ) { tab ->
                    Box(Modifier.size(80.dp, 30.dp).focusRequester(tab).focusable())
                    Text(if (LocalRowsBehind.current) "rows behind" else "rows as read")
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("rows as read").assertExists()

        // The full catalog lands: the rows are read again, and until they show the screen is behind it.
        reading++
        compose.waitForIdle()
        compose.onNodeWithText("rows behind").assertExists()
        compose.onNodeWithText("Drama").assertDoesNotExist()

        slow.complete(whole)
        compose.waitForIdle()
        compose.onNodeWithText("Drama").assertExists()
        compose.onNodeWithText("rows as read").assertExists()
    }
}
