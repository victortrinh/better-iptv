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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import coil3.asImage
import coil3.decode.DataSource
import coil3.intercept.Interceptor
import coil3.request.ImageResult
import coil3.request.SuccessResult
import dev.betteriptv.app.ui.theme.NuageTheme
import dev.betteriptv.core.FeaturedTitle
import dev.betteriptv.core.HomeRow
import dev.betteriptv.core.TitleId
import dev.betteriptv.core.TitleKind
import dev.betteriptv.core.TitleSummary
import dev.betteriptv.core.VersionAttributes
import org.junit.After
import org.junit.Assert.assertEquals
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
 * A first Sync's full catalog lands over its Head start without moving focus (#563, ADR 0019), on
 * the rows Home, Movies and Shows share ([RowsLayout]), the Library stood in. The screen is as
 * those show it during the Head start: a New row alone, under a featured Title when the Head start
 * gave one, over the empty slot kept while the Titles are coming. With the Viewer on a New card, the
 * landing brings Trending above and the genre rows below, the card and its row staying where they
 * are; the New row takes its own change once focus leaves it, and no row comes twice.
 */
@OptIn(ExperimentalTestApi::class, DelicateCoilApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-mdpi")
class CatalogLandsUnderFocusTest {
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
    val compose = createAndroidComposeRule<ComponentActivity>()

    /** Every image is served as from disk, at once: the hero's poster arrives with the rows. */
    @Before fun serveTheArt() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val serve = Interceptor { chain -> SuccessResult(SizedArt().asImage(), chain.request, dataSource = DataSource.DISK) as ImageResult }
        SingletonImageLoader.setUnsafe(ImageLoader.Builder(context).components { add(serve) }.build())
    }

    @After fun forgetTheArt() = SingletonImageLoader.reset()

    private fun title(name: String, year: Int = 2026) = TitleSummary(TitleId.Unmatched(TitleKind.Movie, name.hashCode().toLong()), name, year, null, VersionAttributes())
    private fun titles(prefix: String) = List(10) { title("$prefix$it") }
    private fun featured(name: String) = FeaturedTitle(
        TitleId.Numbered(TitleKind.Movie, name.hashCode().toLong()), name, 2026, 112, listOf("Drama"), "About $name.",
        badge = VersionAttributes(), posters = listOf("https://provider.example/poster-$name.jpg"),
    )

    private var rows by mutableStateOf<List<HomeRow>>(emptyList())
    private var hero by mutableStateOf<FeaturedTitle?>(null)

    /** Null once the first Sync finished, as the status' last Sync is on Home and the tabs. */
    private var titlesComing by mutableStateOf(true)
    private var reading by mutableIntStateOf(0)

    /** The screen as Home, Movies and Shows have it while a first Sync runs. */
    private fun screen() {
        compose.setContent {
            NuageTheme {
                RowsLayout(
                    remember { RowsState() }, restoreFocus = null, focusTab = false,
                    reading = reading,
                    read = { rows },
                    empty = "",
                    setFavourite = { _, _ -> },
                    onScreen = {},
                    info = { _, _, _ -> },
                    appLanguage = "en",
                    onOpen = { _, _ -> },
                    trailing = if (titlesComing) {
                        {}
                    } else null,
                    feature = { hero },
                    heroMayArrive = true,
                ) { tab -> Box(Modifier.size(80.dp, 30.dp).testTag("tab").focusRequester(tab).focusable()) }
            }
        }
        compose.waitForIdle()
    }

    private fun press(keys: List<Key>) = keys.forEach { key ->
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    /** The full catalog lands: the Sync is finished, and the rows are read again. */
    private fun land(fresh: List<HomeRow>, featuring: FeaturedTitle? = hero) {
        rows = fresh
        hero = featuring
        titlesComing = false
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

    @Test fun theLandingOnHomeLeavesTheFocusedNewCardAndItsRowWhereTheyAre() {
        hero = featured("War")
        rows = listOf(HomeRow.NewOnYourService(titles("Fresh")))
        screen()
        compose.onNodeWithText("Play").assertIsFocused()
        press(listOf(Key.DirectionDown, Key.DirectionRight, Key.DirectionRight))
        val before = focusedCard("Fresh2")

        // Trending arrives above, the genres below, and New would open on a Title the Head start didn't have.
        land(
            listOf(
                HomeRow.Trending(titles("Hit")),
                HomeRow.NewOnYourService(listOf(title("Newest")) + titles("Fresh").take(9)),
                HomeRow.Genre("Comedy", titles("Comedy")),
                HomeRow.Genre("Drama", titles("Drama")),
            ),
        )

        assertEquals(before, focusedCard("Fresh2"))
        // The row is still the Head start's: its next card is the one that was there.
        press(listOf(Key.DirectionRight))
        focusedCard("Fresh3")
        compose.onNodeWithText("Newest").assertDoesNotExist()
        press(listOf(Key.DirectionUp))
        focusedCard("Hit0")
    }

    @Test fun theNewRowTakesItsChangeOnceFocusLeavesItAndNoRowComesTwice() {
        hero = featured("War")
        rows = listOf(HomeRow.NewOnYourService(titles("Fresh")))
        screen()
        press(listOf(Key.DirectionDown, Key.DirectionRight))
        focusedCard("Fresh1")

        land(
            listOf(
                HomeRow.Trending(titles("Hit")),
                HomeRow.NewOnYourService(listOf(title("Newest")) + titles("Fresh").take(9)),
                HomeRow.Genre("Comedy", titles("Comedy")),
            ),
        )
        press(listOf(Key.DirectionDown))

        focusedCard("Comedy0")
        compose.onAllNodesWithText("New on your service").assertCountEquals(1)
        compose.onAllNodesWithText("Comedy").assertCountEquals(1)
        press(listOf(Key.DirectionUp))
        // Back on New, on the card that was left, the row now the full catalog's: its new Title leads it.
        focusedCard("Fresh1")
        press(listOf(Key.DirectionLeft, Key.DirectionLeft))
        focusedCard("Newest")
        compose.onAllNodesWithText("New on your service").assertCountEquals(1)
        press(listOf(Key.DirectionUp))
        focusedCard("Hit0")
        compose.onAllNodesWithText("Trending this week").assertCountEquals(1)
    }

    @Test fun aTabWhoseHeadStartGaveNoFeaturedTitleGetsOneAboveTheFocusedCardWithoutMovingIt() {
        rows = listOf(HomeRow.NewOnYourService(titles("Fresh"), TitleKind.Movie))
        screen()
        press(listOf(Key.DirectionRight, Key.DirectionRight))
        val before = focusedCard("Fresh2")

        land(
            listOf(
                HomeRow.Trending(titles("Hit"), TitleKind.Movie),
                HomeRow.NewOnYourService(titles("Fresh"), TitleKind.Movie),
                HomeRow.Genre("Comedy", titles("Comedy")),
            ),
            featuring = featured("War"),
        )

        assertEquals(before, focusedCard("Fresh2"))
        // Up finds Trending, then the featured Title.
        press(listOf(Key.DirectionUp))
        focusedCard("Hit0")
        press(listOf(Key.DirectionUp))
        compose.onNodeWithText("Play").assertIsFocused()
    }

    @Test fun aCardOfTheFocusedRowTakesWhatTheFullCatalogKnowsOfItInPlace() {
        rows = listOf(HomeRow.NewOnYourService(listOf(title("Fresh0", year = 2025)) + titles("Fresh").drop(1)))
        var info: TitleSummary? = null
        compose.setContent {
            NuageTheme {
                RowsLayout(
                    remember { RowsState() }, restoreFocus = null, focusTab = false,
                    reading = reading,
                    read = { rows },
                    empty = "",
                    setFavourite = { _, _ -> },
                    onScreen = {},
                    info = { row, card, _ -> if (card != null) info = (row as HomeRow.NewOnYourService).titles.first { it.id.toString() == card.key } },
                    appLanguage = "en",
                    onOpen = { _, _ -> },
                ) { tab -> Box(Modifier.size(80.dp, 30.dp).testTag("tab").focusRequester(tab).focusable()) }
            }
        }
        compose.waitForIdle()
        focusedCard("Fresh0")
        assertEquals(2025, info?.year)

        // The row changes (a Title leads it now), so it waits; the focused card's Title is read again all the same.
        land(listOf(HomeRow.NewOnYourService(listOf(title("Newest")) + titles("Fresh").take(9))))

        focusedCard("Fresh0")
        assertEquals(2026, info?.year)
    }
}
