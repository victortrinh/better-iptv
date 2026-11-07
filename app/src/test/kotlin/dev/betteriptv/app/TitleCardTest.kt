package dev.betteriptv.app

import android.content.Context
import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import dev.betteriptv.app.ui.components.TitleCard
import dev.betteriptv.app.ui.theme.Cards
import dev.betteriptv.app.ui.theme.FocusScale
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A poster card stays a poster (#474): focused, it lifts 1.12x from its centre and its row never
 * widens; on a named row its name shows under it; a Title with no poster is its name on glass.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w960dp-h540dp-mdpi")
class TitleCardTest {
    /** The compose rule's host activity, which `ui-test-manifest` declares only in debug builds. */
    @get:Rule(order = 0)
    val host = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                val context = ApplicationProvider.getApplicationContext<Context>()
                shadowOf(context.packageManager).addOrUpdateActivity(
                    ActivityInfo().apply {
                        name = ComponentActivity::class.java.name
                        packageName = context.packageName
                    },
                )
                base.evaluate()
            }
        }
    }

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val focus = FocusRequester()

    @Composable
    private fun Poster(named: Boolean, poster: Boolean = true) {
        // As on a TV: in touch mode a card can't take focus.
        val input = LocalInputModeManager.current
        SideEffect { input.requestInputMode(InputMode.Keyboard) }
        TitleCard(
            image = if (poster) ColorPainter(Color.Gray) else null,
            name = "Before I Wake",
            badges = emptyList(),
            onClick = {},
            modifier = Modifier.focusRequester(focus).testTag("card"),
            named = named,
        )
    }

    private fun row(named: Boolean, poster: Boolean = true) = compose.setContent {
        Row(Modifier.fillMaxSize().padding(start = 120.dp, top = 120.dp)) { Poster(named, poster) }
    }

    private fun focus() {
        compose.runOnIdle { focus.requestFocus() }
        compose.waitForIdle()
    }

    private fun art() = compose.onNodeWithContentDescription("Before I Wake", useUnmergedTree = true).getBoundsInRoot()

    @Test
    fun aFocusedPosterLiftsFromItsCentreAndItsRowNeverWidens() {
        row(named = false)
        val resting = art()
        val slot = compose.onNodeWithTag("card").getBoundsInRoot()
        focus()
        val lifted = art()

        assertEquals(Cards.posterWidth.value * FocusScale.card, (lifted.right - lifted.left).value, 0.5f)
        assertEquals(Cards.posterHeight.value * FocusScale.card, (lifted.bottom - lifted.top).value, 0.5f)
        assertEquals(((resting.left + resting.right) / 2).value, ((lifted.left + lifted.right) / 2).value, 0.5f)
        // It stays a poster: the card takes no more room than at rest.
        val focusedSlot = compose.onNodeWithTag("card").getBoundsInRoot()
        assertEquals(slot, focusedSlot)
        assertEquals(Cards.posterWidth.value, (focusedSlot.right - focusedSlot.left).value, 0.5f)
    }

    @Test
    fun onANamedRowAFocusedPosterShowsItsNameUnderIt() {
        row(named = true)
        compose.onNodeWithText("Before I Wake").assertDoesNotExist()
        val resting = art()
        focus()

        val name = compose.onNodeWithText("Before I Wake").assertIsDisplayed().getBoundsInRoot()
        assert(name.top >= resting.bottom) { "the name is under the poster" }
    }

    @Test
    fun onAnUnnamedRowTheFocusedPosterIsNamedByItsArtAlone() {
        row(named = false)
        focus()

        compose.onNodeWithText("Before I Wake").assertDoesNotExist()
    }

    @Test
    fun aTitleWithNoPosterIsItsNameOnGlass() {
        row(named = false, poster = false)

        val name = compose.onNodeWithText("Before I Wake").assertIsDisplayed().getBoundsInRoot()
        val card = compose.onNodeWithTag("card").getBoundsInRoot()
        assert(name.top >= card.top && name.bottom <= card.bottom) { "the name is on the card" }
    }
}
