package dev.betteriptv.app

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import android.content.Context
import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import org.junit.Rule
import org.junit.rules.TestRule
import org.junit.runners.model.Statement
import org.robolectric.Shadows.shadowOf
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Where focus lands when it goes down into a row of cards laid out as Home's: tabs across the top,
 * the focused one further right than the first card, then rows whose first card sits at the left
 * margin.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-mdpi")
class RowEntryTest {
    /**
     * The compose rule's host activity, which `ui-test-manifest` declares only in debug builds:
     * registered here so the release and benchmark unit tests run it too.
     */
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
    val compose = createComposeRule()

    /** Tabs over two rows of five cards; the first row loops when [loop] is given, opening at its start. */
    private fun rows(loop: RailLoop? = null) = compose.setContent {
        val tab = remember { FocusRequester() }
        Column(Modifier.width(960.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                repeat(4) {
                    val requester = if (it == 3) Modifier.focusRequester(tab) else Modifier
                    Box(Modifier.size(80.dp, 30.dp).testTag("tab$it").then(requester).focusable())
                }
            }
            CardRow("top", loop)
            CardRow("new", null)
        }
        LaunchedEffect(Unit) { tab.requestFocus() }
    }

    @Composable
    private fun CardRow(name: String, loop: RailLoop?) {
        val rail = remember { LazyListState(loop?.start ?: 0) }
        val entry = remember { FocusRequester() }
        LazyRow(
            state = rail,
            contentPadding = PaddingValues(horizontal = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier.rowFocus(entry),
        ) {
            items(loop?.count ?: 5) { place ->
                val card = loop?.card(place) ?: place
                Box(Modifier.size(136.dp, 204.dp).testTag("$name$card@$place").entryAt(rail, place, entry).focusable())
            }
        }
    }

    private fun press(keys: List<Key>) = keys.forEach { key ->
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    @Test
    fun downFromTheTabsFocusesTheFirstCard() {
        rows()
        compose.onNodeWithTag("tab3").assertIsFocused()
        press(listOf(Key.DirectionDown))

        compose.onNodeWithTag("top0@0").assertIsFocused()
    }

    @Test
    fun downFromARowFocusesTheNextRowsFirstCard() {
        rows()
        press(listOf(Key.DirectionDown, Key.DirectionRight, Key.DirectionRight, Key.DirectionDown))

        compose.onNodeWithTag("new0@0").assertIsFocused()
    }

    @Test
    fun aLoopingRowIsEnteredAtTheCardAfterTheOnePeeking() {
        val loop = RailLoop(5, seen = 3)
        rows(loop)
        press(listOf(Key.DirectionDown))

        compose.onNodeWithTag("top0@${loop.start}").assertIsFocused()
    }

    @Test
    fun aRowFocusedBeforeReturnsToItsLastCard() {
        rows()
        press(listOf(Key.DirectionDown, Key.DirectionRight, Key.DirectionRight, Key.DirectionDown, Key.DirectionUp))

        compose.onNodeWithTag("top2@2").assertIsFocused()
    }
}
