package dev.betteriptv.app

import android.content.Context
import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.test.core.app.ApplicationProvider
import dev.betteriptv.app.ui.components.BadgeSpec
import dev.betteriptv.app.ui.components.CardInfo
import dev.betteriptv.app.ui.components.Certainty
import dev.betteriptv.app.ui.components.RailInfo
import dev.betteriptv.app.ui.theme.NuageTheme
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
 * The info under a focused Home row about a Title not started (#473, ADR 0014): one line, year ·
 * genre · the Provider's rating, with the compact badges, and nothing else: no description, no runtime.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-mdpi")
class RailInfoTest {
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

    private val badges = listOf(BadgeSpec("4K", Certainty.Confirmed), BadgeSpec("EN", Certainty.Likely))

    @Test fun theInfoIsOneLineOfYearGenreAndRatingWithTheBadges() {
        compose.setContent { NuageTheme { RailInfo(CardInfo.About(aboutLine(2024, listOf("Drama", "Crime"), 6.6)), badges, short = false) } }

        compose.onNodeWithText("2024 · Drama · ★ 6.6").assertIsDisplayed()
        compose.onNodeWithText("4K").assertIsDisplayed()
        compose.onNodeWithText("EN").assertIsDisplayed()
        // One line: the badges sit beside the text, and the whole info is no taller than a line.
        val line = compose.onNodeWithText("2024 · Drama · ★ 6.6").getBoundsInRoot()
        val badge = compose.onNodeWithText("4K").getBoundsInRoot()
        assertTrue("badge beside the line", badge.left >= line.right)
        assertTrue("one line tall: ${compose.onNodeWithTag("rail info").getBoundsInRoot().height}", compose.onNodeWithTag("rail info").getBoundsInRoot().height <= 24.dp)
        compose.onAllNodesWithText("Drama", substring = true).assertCountEquals(1)
    }

    @Test fun whatTheProviderDoesNotGiveIsLeftOut() {
        assertEquals("2024 · Drama", aboutLine(2024, listOf("Drama"), null))
        assertEquals("2024 · ★ 6.6", aboutLine(2024, emptyList(), 6.6))
        assertEquals("Drama · ★ 6.6", aboutLine(null, listOf("Drama"), 6.6))
        assertEquals("", aboutLine(null, emptyList(), null))
        assertEquals("★ 8.0", ratingLabel(8.0))
    }
}
