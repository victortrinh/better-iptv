package dev.betteriptv.app

import android.content.Context
import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import dev.betteriptv.app.ui.components.RankedCard
import dev.betteriptv.app.ui.components.TitleCard
import dev.betteriptv.app.ui.theme.Cards
import dev.betteriptv.app.ui.theme.Spacing
import kotlinx.coroutines.launch
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
 * Left and Right along a rail of growing posters, as Home's rows are laid out: the focused card
 * slides to the left margin exactly once (#341). Watched frame by frame on the test clock, it never
 * goes past the margin, and it ends on it.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-mdpi")
class RailGlideTest {
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
    val compose = createComposeRule()

    /**
     * A rail of [cards] posters that grow on focus, as RowsScreen draws it, with the card at [first]
     * focused at the margin. A [ranked] rail is a Top row, its posters beside their numerals.
     */
    private fun rail(cards: Int, ranked: Boolean = false, first: Int = 0) {
        val loop = RailLoop(cards, seen = 7, ranked = ranked)
        val opening = FocusRequester()
        val cardWidth = if (ranked) Cards.rankedWidth else Cards.posterWidth
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val rail = remember { LazyListState(loop.start + first) }
            val scope = rememberCoroutineScope()
            val resting = with(LocalDensity.current) { cardWidth.roundToPx() }
            LazyRow(
                state = rail,
                contentPadding = PaddingValues(
                    start = Spacing.screenX,
                    end = if (loop.loops) Spacing.screenX else 960.dp - Spacing.screenX - cardWidth,
                ),
                horizontalArrangement = Arrangement.spacedBy(Cards.gap),
                modifier = Modifier.width(960.dp),
            ) {
                items(loop.count, key = { it }) { place ->
                    val index = loop.card(place)
                    val tag = Modifier.testTag("at${place - loop.start}").then(if (place == loop.start + first) Modifier.focusRequester(opening) else Modifier)
                    val poster = @androidx.compose.runtime.Composable { modifier: Modifier ->
                        TitleCard(
                            image = null,
                            name = "Title $index",
                            badges = emptyList(),
                            onClick = {},
                            modifier = modifier,
                            named = false,
                            onFocus = { scope.launch { rail.glideTo(place, resting) } },
                            grown = { Box(Modifier) },
                        )
                    }
                    if (ranked) RankedCard(index + 1, tag) { poster(Modifier) } else poster(tag)
                }
            }
        }
        // Once the rail has laid out its cards: before, the opening card isn't there to take focus.
        settle()
        compose.runOnIdle { opening.requestFocus() }
        settle()
    }

    private fun settle() {
        repeat(60) { compose.mainClock.advanceTimeByFrame() }
    }

    /**
     * Where the card at [place] (from the rail's start) has its left edge, from the left margin, in
     * dp: where it is laid out, not clipped by the rail's edge.
     */
    private fun fromMargin(place: Int): Float {
        val x = compose.onNodeWithTag("at$place").fetchSemanticsNode().positionInRoot.x
        return with(compose.density) { x.toDp() - Spacing.screenX }.value
    }

    /**
     * Presses [key], then steps the clock a frame at a time until the slide is over, checking the
     * card newly focused, at [place], comes to the margin from the side it starts on and never
     * goes past it; it ends on it.
     */
    private fun pressAndWatch(key: Key, place: Int) {
        val start = fromMargin(place)
        compose.onRoot().performKeyInput { pressKey(key) }
        val offsets = (1..40).map {
            compose.mainClock.advanceTimeByFrame()
            fromMargin(place)
        }
        val side = if (key == Key.DirectionRight) 1f else -1f
        assertTrue("the card at $place starts $start dp from the margin", start * side > 0f)
        offsets.forEachIndexed { frame, offset ->
            assertTrue("frame ${frame + 1}: the card at $place went past the margin, to $offset dp: $offsets", offset * side > -0.5f)
        }
        assertEquals(0f, offsets.last(), 0.5f)
    }

    @Test
    fun rightLandsOnTheMarginOnce() {
        rail(cards = 5)
        assertEquals(0f, fromMargin(0), 0.5f)

        pressAndWatch(Key.DirectionRight, place = 1)
        pressAndWatch(Key.DirectionRight, place = 2)
    }

    @Test
    fun leftLandsOnTheMarginOnce() {
        rail(cards = 5, first = 2)

        pressAndWatch(Key.DirectionLeft, place = 1)
        pressAndWatch(Key.DirectionLeft, place = 0)
    }

    @Test
    fun aLoopingRailLandsOnTheMarginOnce() {
        rail(cards = 12)

        pressAndWatch(Key.DirectionRight, place = 1)
        pressAndWatch(Key.DirectionLeft, place = 0)
        // Past its start, onto the last card's copy peeking in the margin.
        pressAndWatch(Key.DirectionLeft, place = -1)
    }

    @Test
    fun aTopRowLandsOnTheMarginOnce() {
        rail(cards = 10, ranked = true)

        pressAndWatch(Key.DirectionRight, place = 1)
        pressAndWatch(Key.DirectionRight, place = 2)
        pressAndWatch(Key.DirectionLeft, place = 1)
    }
}
