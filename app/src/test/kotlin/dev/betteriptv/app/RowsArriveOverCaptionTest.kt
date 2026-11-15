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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * A first Sync's Head start lands on a tab that had no row (#561): the rows it brings show from the
 * top of the screen ([RowsLayout]), not above the caption that stood for them, out of sight.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-mdpi")
class RowsArriveOverCaptionTest {
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

    private fun title(name: String) = TitleSummary(TitleId.Unmatched(TitleKind.Movie, name.hashCode().toLong()), name, 2024, null, VersionAttributes())

    @Test
    fun rowsThatArriveWhereOnlyTheCaptionStoodShowFromTheTop() {
        compose.setContent {
            NuageTheme {
                RowsLayout(
                    remember { RowsState() }, restoreFocus = null, focusTab = true,
                    reading = reading,
                    read = { rows },
                    empty = "",
                    setFavourite = { _, _ -> },
                    onScreen = {},
                    info = { _, _, _ -> },
                    appLanguage = "en",
                    onOpen = { _, _ -> },
                    // As the Movies and Shows tabs': a caption while there is no row, then nothing.
                    trailing = { if (rows.isEmpty()) Text("Movies and shows arrive once syncing finishes.") },
                ) { tab -> Box(Modifier.size(80.dp, 30.dp).focusRequester(tab).focusable()) }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Movies and shows arrive once syncing finishes.").assertIsDisplayed()

        rows = listOf(HomeRow.Genre("Comedy", List(4) { title("Comedy $it") }))
        reading++
        compose.waitForIdle()

        compose.onNodeWithText("Comedy").assertIsDisplayed()
    }
}
