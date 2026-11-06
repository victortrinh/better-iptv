package dev.betteriptv.app

import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.core.app.ApplicationProvider
import androidx.tv.material3.Text
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import coil3.asImage
import coil3.decode.DataSource
import coil3.intercept.Interceptor
import coil3.request.ImageResult
import coil3.request.SuccessResult
import dev.betteriptv.core.League
import dev.betteriptv.core.Sport
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Settings' League picker (#353): the Leagues as logo tiles under their sports' headings, a sport
 * rail beside them, OK toggling a League and the Leagues row's value following. Logos come from a
 * stand-in loader that has every URL.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-mdpi")
class LeaguePickerTest {
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

    @Before fun images() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val loader = ImageLoader.Builder(context).components {
            add(object : Interceptor {
                override suspend fun intercept(chain: Interceptor.Chain): ImageResult =
                    SuccessResult(ColorDrawable(Color.WHITE).asImage(), chain.request, DataSource.NETWORK)
            })
        }.build()
        @OptIn(DelicateCoilApi::class)
        SingletonImageLoader.setUnsafe(loader)
    }

    @OptIn(DelicateCoilApi::class)
    @After fun resetImages() = SingletonImageLoader.reset()

    private var on by mutableStateOf(setOf(League.Nhl, League.Mls))

    /** The picker under the Leagues row's value, as Settings shows it once Back returns to the row. */
    private fun picker() = compose.setContent {
        Column {
            Text(leaguesValue(on), Modifier.testTag("value"))
            LeaguePicker(on, onChange = { on = it })
        }
    }

    private fun press(key: Key) = press(listOf(key))

    private fun press(keys: List<Key>) = keys.forEach { key ->
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    private fun tile(league: League) = compose.onNodeWithTag("league:${league.key}")

    /** How many nodes tagged [tag] lie inside [league]'s tile. */
    private fun inTile(league: League, tag: String): Int {
        val bounds = tile(league).getBoundsInRoot()
        return compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().count { node ->
            val r = node.boundsInRoot
            r.left >= bounds.left.value && r.right <= bounds.right.value && r.top >= bounds.top.value && r.bottom <= bounds.bottom.value
        }
    }

    @Config(qualifiers = "w960dp-h3200dp-mdpi")
    @Test fun theGridGroupsLeaguesBySportInLeaguesOrderWithinEach() {
        picker()
        compose.waitForIdle()

        // Read off the screen: each heading, then its sport's tiles, line by line, left to right.
        val headings = Sport.entries.filter { sport -> League.entries.any { it.sport == sport } }
        val seen = (headings.map { "heading:${it.name}" } + League.entries.map { "league:${it.key}" })
            .map { tag -> tag to compose.onNodeWithTag(tag).getBoundsInRoot() }
            .sortedWith(compareBy({ it.second.top }, { it.second.left }))
            .map { it.first }
        val expected = headings.flatMap { sport -> listOf("heading:${sport.name}") + League.entries.filter { it.sport == sport }.map { "league:${it.key}" } }
        assertEquals(expected, seen)
        // #354's NRL and AFL under the last two headings.
        assertEquals(listOf(Sport.Rugby, Sport.AustralianFootball), headings.takeLast(2))
        assertTrue(tile(League.Nrl).getBoundsInRoot().top > compose.onNodeWithTag("heading:Rugby").getBoundsInRoot().top)
        assertTrue(tile(League.Afl).getBoundsInRoot().top > compose.onNodeWithTag("heading:AustralianFootball").getBoundsInRoot().top)
        // About six across: Soccer's twelve take two lines.
        val soccer = League.entries.filter { it.sport == Sport.Soccer }.map { tile(it).getBoundsInRoot().top }
        assertEquals(listOf(6, 6), soccer.groupBy { it }.values.map { it.size })
    }

    @Test fun theFirstTileTakesFocusAndItsNameShowsUnderIt() {
        picker()

        tile(League.Nhl).assertIsFocused()
        assertEquals(1, compose.onAllNodesWithTag("league-name", useUnmergedTree = true).fetchSemanticsNodes().size)
        val name = compose.onNodeWithTag("league-name", useUnmergedTree = true).assertTextEquals("NHL").getBoundsInRoot()
        assertTrue(name.top >= tile(League.Nhl).getBoundsInRoot().bottom)

        press(Key.DirectionDown)
        tile(League.Nfl).assertIsFocused()
        compose.onNodeWithTag("league-name", useUnmergedTree = true).assertTextEquals("NFL")
    }

    @Test fun anOnLeagueShowsACheckAndAnOffOneNone() {
        on = setOf(League.Nhl, League.Cfl)
        picker()
        compose.waitForIdle()

        assertEquals(1, inTile(League.Nhl, "league-on"))
        assertEquals(1, inTile(League.Cfl, "league-on"))
        assertEquals(0, inTile(League.Nfl, "league-on"))
    }

    @Test fun okTogglesALeagueAndTheLeaguesRowsValueFollows() {
        picker()
        compose.onNodeWithTag("value").assertTextEquals("NHL, MLS")

        press(Key.DirectionCenter)
        assertEquals(setOf(League.Mls), on)
        compose.onNodeWithTag("value").assertTextEquals("MLS")
        assertEquals(0, inTile(League.Nhl, "league-on"))

        press(listOf(Key.DirectionDown, Key.DirectionCenter))
        assertEquals(setOf(League.Nfl, League.Mls), on)
        compose.onNodeWithTag("value").assertTextEquals("NFL, MLS")
        assertEquals(1, inTile(League.Nfl, "league-on"))
        // Focus stays on the tile toggled.
        tile(League.Nfl).assertIsFocused()
    }

    @Test fun everyLeagueCanBeOff() {
        on = setOf(League.Nhl)
        picker()

        press(Key.DirectionCenter)
        assertEquals(emptySet<League>(), on)
        compose.onNodeWithTag("value").assertTextEquals("None")
    }

    @Test fun leftFromATileMovesToItsSportOnTheRail() {
        picker()
        press(Key.DirectionDown)
        tile(League.Nfl).assertIsFocused()

        press(Key.DirectionLeft)
        compose.onNodeWithTag("sport:Football").assertIsFocused()
    }

    @Test fun leftFromATileAfterTheFirstMovesAlongItsLine() {
        picker()
        press(listOf(Key.DirectionDown, Key.DirectionRight))
        tile(League.Cfl).assertIsFocused()

        press(Key.DirectionLeft)
        tile(League.Nfl).assertIsFocused()
    }

    @Test fun okOnASportJumpsToItsFirstTile() {
        picker()
        press(Key.DirectionLeft)
        compose.onNodeWithTag("sport:Hockey").assertIsFocused()

        // Down the rail to Fighting, the last sport, whose tile is below the screen.
        repeat(7) { press(Key.DirectionDown) }
        compose.onNodeWithTag("sport:Fighting").assertIsFocused()
        press(Key.DirectionCenter)
        compose.waitForIdle()
        tile(League.Ufc).assertIsFocused().assertIsDisplayed()
    }

    @Test fun rightOnASportJumpsToItsFirstTile() {
        picker()
        press(listOf(Key.DirectionLeft, Key.DirectionDown, Key.DirectionDown, Key.DirectionDown, Key.DirectionDown))
        compose.onNodeWithTag("sport:Soccer").assertIsFocused()

        press(Key.DirectionRight)
        compose.waitForIdle()
        tile(League.Mls).assertIsFocused().assertIsDisplayed()
    }

    @Config(qualifiers = "fr-w960dp-h540dp-mdpi")
    @Test fun inFrenchTheSportsAndLeaguesAreNamedInFrench() {
        picker()

        compose.onNodeWithTag("sport:Motorsport").assertTextEquals("Sport automobile")
        compose.onNodeWithTag("sport:Fighting").assertTextEquals("Combat")
        compose.onNodeWithTag("heading:Football").assertTextEquals("Football")
        compose.onNodeWithTag("league-name", useUnmergedTree = true).assertTextEquals("LNH")
        compose.onNodeWithTag("value").assertTextEquals("LNH, MLS")
        press(Key.DirectionCenter)
        compose.onNodeWithTag("value").assertTextEquals("MLS")
    }
}
