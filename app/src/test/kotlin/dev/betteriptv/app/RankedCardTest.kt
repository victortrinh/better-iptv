package dev.betteriptv.app

import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.VectorDrawable
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import dev.betteriptv.app.ui.components.NUMERAL_TAG
import dev.betteriptv.app.ui.components.NumeralStyle
import dev.betteriptv.app.ui.components.Numerals
import dev.betteriptv.app.ui.components.RankedCard
import dev.betteriptv.app.ui.components.TitleCard
import dev.betteriptv.app.ui.theme.Cards
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
import org.robolectric.annotation.GraphicsMode

/**
 * A Top row card (#379): its numeral is a vector drawing, and its poster, focused, lifts from its
 * left edge and casts its shadow down and to the right, so neither covers the numeral. A poster on
 * any other row still lifts from its centre.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w960dp-h540dp-mdpi")
class RankedCardTest {
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
    private fun Poster() {
        // As on a TV: in touch mode a card can't take focus.
        val input = LocalInputModeManager.current
        SideEffect { input.requestInputMode(InputMode.Keyboard) }
        TitleCard(
            image = ColorPainter(Color.White),
            name = "Title",
            badges = emptyList(),
            onClick = {},
            modifier = Modifier.focusRequester(focus),
            named = false,
        )
    }

    /** A Top row's card at rank [rank] on a white ground. */
    private fun ranked(rank: Int = 3) {
        compose.setContent {
            Box(Modifier.fillMaxSize().background(Color.White).padding(start = 48.dp, top = 120.dp)) {
                RankedCard(rank) { Poster() }
            }
        }
    }

    private fun poster(): Rect = compose.onNodeWithContentDescription("Title", useUnmergedTree = true).getBoundsInRoot().let { Rect(it.left.value, it.top.value, it.right.value, it.bottom.value) }

    private fun focused(): Rect {
        compose.runOnIdle { focus.requestFocus() }
        compose.waitForIdle()
        return poster()
    }

    @Test
    fun everyRankInEveryStyleIsAVectorDrawing() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        for (style in NumeralStyle.entries) {
            for (rank in 0..10) {
                assertTrue("$style $rank", context.getDrawable(Numerals.of(rank, style)) is VectorDrawable)
            }
        }
    }

    @Test
    fun theNumeralIsDrawnAtItsHeightLevelWithThePostersTop() {
        ranked(rank = 7)
        val numeral = compose.onAllNodesWithTag(NUMERAL_TAG, useUnmergedTree = true)[0].getBoundsInRoot()
        val poster = poster()
        // The digits are numeralHeight tall; the drawable keeps a little room around them for its stroke.
        assertEquals(Cards.numeralHeight.value, (numeral.bottom - numeral.top).value, 4.5f)
        assertEquals(poster.top, numeral.top.value, 0.5f)
        // It reaches at least the overlap under the poster's left edge (a wide digit runs on under
        // the poster), and never reaches before the card.
        assertTrue(numeral.right.value >= poster.left + Cards.numeralOverlap.value - 0.5f)
        assertTrue(numeral.left.value >= 48f - 0.5f)
    }

    @Test
    fun aTenStartsWhereTheNumeralsRoomDoesAndRunsOnUnderThePoster() {
        ranked(rank = 10)
        val numeral = compose.onAllNodesWithTag(NUMERAL_TAG, useUnmergedTree = true)[0].getBoundsInRoot()
        assertEquals(48f, numeral.left.value, 0.5f)
        assertTrue(numeral.right.value > poster().left + Cards.numeralOverlap.value)
    }

    @Test
    fun aFocusedTopRowPosterLiftsFromItsLeftEdge() {
        ranked()
        val resting = poster()
        val lifted = focused()
        assertEquals(resting.left, lifted.left, 0.5f)
        assertTrue(lifted.width > resting.width)
    }

    @Test
    fun aPosterOnAnyOtherRowStillLiftsFromItsCentre() {
        compose.setContent {
            Row(Modifier.fillMaxSize().padding(start = 120.dp, top = 120.dp)) { Poster() }
        }
        val resting = poster()
        val lifted = focused()
        assertTrue(lifted.left < resting.left - 1f)
        assertEquals(resting.center.x, lifted.center.x, 0.5f)
    }

    @Test
    fun itsShadowFallsDownAndToTheRightNotOnTheNumeral() {
        ranked()
        val lifted = focused()
        val density = compose.density.density
        val y = (lifted.top + lifted.bottom) / 2
        // Just past the poster: dark on the right and below it. On the left, the numeral's side
        // (above the numeral, which is shorter than the poster), and above it, the white ground is untouched.
        val right = shade(lifted.right + 8, y, density)
        val below = shade((lifted.left + lifted.right) / 2, lifted.bottom + 8, density)
        val left = shade(lifted.left - 16, lifted.top + 10, density)
        val above = shade((lifted.left + lifted.right) / 2, lifted.top - 12, density)
        assertTrue("right $right", right < 0.9f)
        assertTrue("below $below", below < 0.9f)
        assertEquals("left", 1f, left, 0.03f)
        assertEquals("above", 1f, above, 0.03f)
    }

    /** The luminance of the screen at ([x], [y]) dp. */
    private fun shade(x: Float, y: Float, density: Float): Float {
        compose.waitForIdle()
        val screen = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(screen.width, screen.height, Bitmap.Config.ARGB_8888)
        screen.draw(Canvas(bitmap))
        return Color(bitmap.getPixel((x * density).toInt(), (y * density).toInt())).luminance()
    }
}
