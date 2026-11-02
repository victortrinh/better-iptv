package dev.betteriptv.app

import android.content.Context
import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.requestFocus
import androidx.test.core.app.ApplicationProvider
import dev.betteriptv.app.ui.components.GameCard
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

/** A Game's card: two logos or the short names, "Live" or the start, and a disabled card for a Game with no Feed. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-mdpi")
class GameCardTest {
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

    @Test fun withoutLogosTheCardShowsTheShortNamesVisitorsLeftAndItsCaption() {
        var opened = 0
        compose.setContent { GameCard(away = null, home = null, awayName = "Canadiens", homeName = "Maple Leafs", caption = "Live", onClick = { opened++ }) }

        val away = compose.onNodeWithText("Canadiens", useUnmergedTree = true).assertIsDisplayed().getBoundsInRoot()
        val home = compose.onNodeWithText("Maple Leafs", useUnmergedTree = true).assertIsDisplayed().getBoundsInRoot()
        assertTrue(away.right <= home.left)
        compose.onNodeWithText("Live").assertIsDisplayed()
        compose.onNodeWithText("Live").performClick()
        assertEquals(1, opened)
    }

    @Test fun aGameWithNoFeedIsDisabledSaysSoAndStillTakesFocus() {
        var opened = 0
        compose.setContent { GameCard(away = null, home = null, awayName = "Flames", homeName = "Oilers", caption = "7:00 PM", onClick = { opened++ }, disabled = "No stream found") }

        compose.onNodeWithText("No stream found").assertIsDisplayed()
        compose.onNodeWithText("7:00 PM").assertIsDisplayed()
        compose.onNodeWithText("7:00 PM").performClick()
        assertEquals(0, opened)
        compose.onNodeWithText("7:00 PM").requestFocus()
        compose.onNodeWithText("7:00 PM").assertIsFocused()
    }
}
