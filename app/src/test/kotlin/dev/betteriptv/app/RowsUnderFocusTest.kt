package dev.betteriptv.app

import android.content.Context
import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import dev.betteriptv.app.ui.theme.NuageTheme
import dev.betteriptv.core.Channel
import dev.betteriptv.core.ChannelVariant
import dev.betteriptv.core.Feed
import dev.betteriptv.core.FeedKind
import dev.betteriptv.core.Game
import dev.betteriptv.core.GameFeeds
import dev.betteriptv.core.GameState
import dev.betteriptv.core.GameTeam
import dev.betteriptv.core.HomeRow
import dev.betteriptv.core.TitleId
import dev.betteriptv.core.TitleKind
import dev.betteriptv.core.TitleSummary
import dev.betteriptv.core.VersionAttributes
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
import java.time.Instant

/**
 * No row moves under focus (#342), on the rows Home, Movies, Shows and Live share ([RowsLayout]),
 * the Library stood in: rows read again while the Viewer is on a card come and go around the
 * focused row, which stays where it is with its card; a change to the focused row waits until focus
 * leaves it; a Game going live says so on its card in place; and a looping rail read again with
 * other Titles never shows a card composed for another Title.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-mdpi")
class RowsUnderFocusTest {
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

    /** Titles a card of which, composed for one Title, was later asked to show another. */
    private val reused = ArrayList<Pair<TitleId, TitleId>>()

    private fun title(name: String) = TitleSummary(TitleId.Unmatched(TitleKind.Movie, name.hashCode().toLong()), name, 2024, null, VersionAttributes())
    private fun genre(name: String, vararg titles: String) = HomeRow.Genre(name, titles.map(::title))

    /** Ten posters, more than the screen shows, so the rail loops. */
    private fun longGenre(name: String, prefix: String = name) = genre(name, *Array(10) { "$prefix$it" })

    private fun team(name: String) = GameTeam(name, name, name.take(3).uppercase(), listOf(name), null)
    private val feed = Feed(Channel("us: espn", "ESPN", "US", null, false, listOf(ChannelVariant(1, "US: ESPN", null, false))), FeedKind.Broadcaster, "en", "ESPN")
    private fun game(id: String, home: String, away: String, state: GameState = GameState.Upcoming) =
        GameFeeds(Game("nhl", id, Instant.parse("2026-11-04T23:00:00Z"), state, home = team(home), away = team(away), broadcasters = emptyList()), listOf(feed))

    /** The rows, as [rows] holds them, under a tab; bumping [reading] reads them again, as a Sync or a Games refresh does. */
    private fun screen() = compose.setContent {
        NuageTheme {
            RowsLayout(
                remember { RowsState() }, restoreFocus = null, focusTab = false,
                reading = reading,
                read = { rows },
                empty = "",
                setFavourite = { _, _ -> },
                onScreen = { id ->
                    val first = remember { id }
                    if (first != id) reused += first to id
                },
                info = { _, _, _ -> },
                appLanguage = "en",
                onOpen = { _, _, _ -> },
            ) { tab -> Box(Modifier.size(80.dp, 30.dp).testTag("tab").focusRequester(tab).focusable()) }
        }
    }

    private fun press(keys: List<Key>) = keys.forEach { key ->
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    private fun readAgain(fresh: List<HomeRow>) {
        rows = fresh
        reading++
        compose.waitForIdle()
    }

    private fun showing(text: String): SemanticsMatcher = hasText(text) or hasAnyDescendant(hasText(text))

    /** The focused card's bounds, after checking it is the card showing [text]. */
    private fun focusedCard(text: String): Rect {
        val node = compose.onNode(isFocused())
        node.assert(showing(text))
        return node.getBoundsInRoot().let { Rect(it.left.value, it.top.value, it.right.value, it.bottom.value) }
    }

    @Test
    fun aRowInsertedAboveTheFocusedOneLeavesItAndItsCardWhereTheyAre() {
        rows = listOf(longGenre("Action"), longGenre("Drama"), longGenre("Comedy"))
        screen()
        press(listOf(Key.DirectionDown, Key.DirectionRight, Key.DirectionRight))
        val before = focusedCard("Drama2")

        readAgain(listOf(longGenre("Thriller"), longGenre("Action"), longGenre("Drama"), longGenre("Comedy")))

        assertEquals(before, focusedCard("Drama2"))
        // The new row is there, above.
        press(listOf(Key.DirectionUp))
        focusedCard("Action0")
    }

    @Test
    fun aRowRemovedAboveOrBelowTheFocusedOneLeavesItAndItsCardWhereTheyAre() {
        rows = listOf(longGenre("Action"), longGenre("Drama"), longGenre("Comedy"))
        screen()
        press(listOf(Key.DirectionDown, Key.DirectionRight))
        val before = focusedCard("Drama1")

        readAgain(listOf(longGenre("Drama"), longGenre("Western")))

        assertEquals(before, focusedCard("Drama1"))
        press(listOf(Key.DirectionDown))
        focusedCard("Western0")
    }

    @Test
    fun aChangeToTheFocusedRowWaitsUntilFocusLeavesIt() {
        rows = listOf(longGenre("Action"), longGenre("Drama"), longGenre("Comedy"))
        screen()
        press(listOf(Key.DirectionDown, Key.DirectionRight, Key.DirectionRight))
        val before = focusedCard("Drama2")

        // Drama2 leaves its row, which comes back with others in its place.
        readAgain(listOf(longGenre("Action"), longGenre("Drama", "Crime"), longGenre("Comedy")))

        assertEquals(before, focusedCard("Drama2"))
        press(listOf(Key.DirectionRight))
        focusedCard("Drama3")
        compose.onNodeWithText("Crime0").assertDoesNotExist()

        // Once focus leaves it, the row takes its change.
        press(listOf(Key.DirectionDown))
        focusedCard("Comedy0")
        compose.onNodeWithText("Drama2").assertDoesNotExist()
        // Back up, the row has its new cards, focus on the one in the place it left.
        press(listOf(Key.DirectionUp))
        focusedCard("Crime3")
    }

    @Test
    fun aFocusedRowThatGoneWaitsInItsPlaceUntilFocusLeavesIt() {
        rows = listOf(longGenre("Action"), longGenre("Drama"), longGenre("Comedy"))
        screen()
        press(listOf(Key.DirectionDown))
        val before = focusedCard("Drama0")

        readAgain(listOf(longGenre("Action"), longGenre("Comedy")))

        assertEquals(before, focusedCard("Drama0"))
        press(listOf(Key.DirectionDown))
        focusedCard("Comedy0")
        compose.onNodeWithText("Drama0").assertDoesNotExist()
    }

    @Test
    fun aGameGoingLiveSaysSoOnItsCardInPlaceWhileLiveNowArrivesAbove() {
        val leafs = game("1", "Maple Leafs", "Canadiens")
        val oilers = game("2", "Oilers", "Flames")
        rows = listOf(HomeRow.Today(listOf(leafs, oilers)), longGenre("Action"))
        screen()
        val before = focusedCard("Canadiens")
        compose.onNode(isFocused()).assert(!showing("Live"))

        // It went live: Live now arrives above with it, and Today would lose it.
        val live = game("1", "Maple Leafs", "Canadiens", GameState.Live)
        readAgain(listOf(HomeRow.LiveNow(listOf(live)), HomeRow.Today(listOf(oilers)), longGenre("Action")))

        assertEquals(before, focusedCard("Canadiens"))
        compose.onNode(isFocused()).assert(showing("Live"))
        // Today keeps it until focus leaves the row.
        press(listOf(Key.DirectionRight))
        focusedCard("Flames")
        press(listOf(Key.DirectionUp))
        focusedCard("Canadiens")
        compose.onNode(isFocused()).assert(showing("Live"))
        press(listOf(Key.DirectionDown))
        focusedCard("Flames")
    }

    @Test
    fun aLoopingRailReadAgainWithOtherTitlesShowsEachTitlesOwnCard() {
        rows = listOf(longGenre("Action"), longGenre("Drama"))
        screen()
        compose.onNode(isFocused()).assert(showing("Action0"))

        // Drama, not focused, comes back with ten other Titles: its rail keeps its count and place.
        readAgain(listOf(longGenre("Action"), longGenre("Drama", "Noir")))
        press(listOf(Key.DirectionDown))
        focusedCard("Noir0")
        press(listOf(Key.DirectionRight))
        focusedCard("Noir1")

        assertTrue("cards composed for one Title showed another: $reused", reused.isEmpty())
    }
}
