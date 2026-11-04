package dev.betteriptv.app

import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import dev.betteriptv.app.ui.theme.Colors
import dev.betteriptv.app.ui.theme.Motion
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
 * Screens fade in over the ground (#345): stepping the test clock frame by frame after a screen change, the new
 * screen goes from the ground to itself over [Motion.screenFadeMillis], the old one is gone on the first frame,
 * only one screen is ever composed, and focus lands on the new screen's first frame. What's on screen is read off
 * the drawn pixels, as the Viewer would see it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w960dp-h540dp-mdpi")
class ScreenFadeTest {
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

    private enum class Screen { Rows, Title, Player }

    private var screen by mutableStateOf(Screen.Rows)

    /** Every screen is white, so on the ground its brightness says how far it has faded in. */
    @Composable
    private fun Page(name: Screen) {
        val first = remember { FocusRequester() }
        LaunchedEffect(Unit) { first.requestFocus() }
        Box(Modifier.fillMaxSize().background(Color.White).testTag("screen")) {
            Box(Modifier.size(40.dp).focusRequester(first).focusable().testTag("$name button"))
        }
    }

    /** As the main activity does: the screens, then the fade over them; the player is shown as is. */
    private fun showNavigation() {
        compose.setContent {
            Box(Modifier.fillMaxSize().background(Colors.ground)) {
                // A branch per screen, as the main activity's, so a new screen is composed afresh.
                when (screen) {
                    Screen.Rows -> Page(Screen.Rows)
                    Screen.Title -> Page(Screen.Title)
                    Screen.Player -> Page(Screen.Player)
                }
                ScreenFade(screen, asIs = { it == Screen.Player })
            }
        }
        compose.waitForIdle()
    }

    /** How far the screen has come in from the ground, 0 to 1, from the red channel of a pixel in its middle. */
    private fun shown(): Float {
        compose.waitForIdle()
        val screen = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(screen.width, screen.height, Bitmap.Config.ARGB_8888)
        screen.draw(Canvas(bitmap))
        val red = Color(bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)).red
        return (red - Colors.ground.red) / (1f - Colors.ground.red)
    }

    private fun go(to: Screen) {
        compose.mainClock.autoAdvance = false
        screen = to
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeByFrame()
    }

    @Test fun aNewScreenFadesInOverTheGround() {
        showNavigation()
        go(Screen.Title)

        assertTrue("on its first frame the screen is still the ground, at ${shown()}", shown() < 0.1f)
        var last = shown()
        repeat(Motion.screenFadeMillis / 16 - 1) {
            compose.mainClock.advanceTimeByFrame()
            val now = shown()
            assertTrue("shown $now after $last", now >= last - 0.01f)
            last = now
        }
        assertTrue("it has shown part way through the fade, at $last", last > 0.3f)
        compose.mainClock.advanceTimeBy(Motion.screenFadeMillis.toLong())
        assertEquals(1f, shown(), 0.01f)
    }

    @Test fun onlyOneScreenIsComposedDuringTheFade() {
        showNavigation()
        go(Screen.Title)

        repeat(Motion.screenFadeMillis / 16 + 2) {
            compose.onAllNodesWithTag("screen").fetchSemanticsNodes().let { assertEquals(1, it.size) }
            compose.onNodeWithTag("Rows button").assertDoesNotExist()
            compose.onNodeWithTag("Title button").assertExists()
            compose.mainClock.advanceTimeByFrame()
        }
    }

    @Test fun focusLandsOnTheFirstFrameOfTheFade() {
        showNavigation()
        go(Screen.Title)

        compose.onNodeWithTag("Title button").assertIsFocused()
        assertTrue("still fading", shown() < 0.5f)
    }

    @Test fun goingBackFadesInToo() {
        showNavigation()
        go(Screen.Title)
        compose.mainClock.advanceTimeBy(Motion.screenFadeMillis.toLong() * 2)
        go(Screen.Rows)

        assertTrue(shown() < 0.1f)
        compose.onNodeWithTag("Rows button").assertIsFocused()
        compose.mainClock.advanceTimeBy(Motion.screenFadeMillis.toLong() * 2)
        assertEquals(1f, shown(), 0.01f)
    }

    @Test fun thePlayerOpensAndClosesWithNoFade() {
        showNavigation()
        go(Screen.Player)
        assertEquals(1f, shown(), 0.01f)

        go(Screen.Title)
        assertEquals(1f, shown(), 0.01f)
        compose.onNodeWithTag("Title button").assertIsFocused()
    }

    @Test fun aListInsideAScreenShowsWithItAndFadesInWhenOpened() {
        // Settings' lists: the screen itself has faded in already, so its first list shows as is.
        var list by mutableStateOf("settings")
        compose.mainClock.autoAdvance = false
        compose.setContent {
            Box(Modifier.fillMaxSize().background(Colors.ground)) {
                when (list) {
                    "settings" -> Page(Screen.Rows)
                    else -> Page(Screen.Title)
                }
                ScreenFade(list, firstFades = false)
            }
        }
        compose.mainClock.advanceTimeByFrame()
        assertEquals(1f, shown(), 0.01f)

        list = "Region"
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeByFrame()
        assertTrue(shown() < 0.1f)
        compose.onNodeWithTag("Title button").assertIsFocused()
        compose.mainClock.advanceTimeBy(Motion.screenFadeMillis.toLong() * 2)
        assertEquals(1f, shown(), 0.01f)
    }
}
