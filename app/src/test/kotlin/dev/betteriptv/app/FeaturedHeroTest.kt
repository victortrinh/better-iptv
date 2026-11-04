package dev.betteriptv.app

import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.ColorDrawable
import androidx.activity.ComponentActivity
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
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
import dev.betteriptv.app.ui.theme.Motion
import dev.betteriptv.app.ui.theme.NuageTheme
import dev.betteriptv.core.FeaturedTitle
import dev.betteriptv.core.HomeRow
import dev.betteriptv.core.TitleId
import dev.betteriptv.core.TitleKind
import dev.betteriptv.core.TitleSummary
import dev.betteriptv.core.VersionAttributes
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
 * The featured hero on Home, Movies and Shows (#348), on the rows they share ([RowsLayout]), the
 * Library stood in: it takes first focus on Play, the first Down scrolls it away under the tabs and
 * Up brings it back, Back goes to the tab, Play and Details do what they say, each entry features
 * the next Title and none changes while the Viewer is on the screen, and its backdrop fades in.
 */
@OptIn(ExperimentalTestApi::class, DelicateCoilApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-mdpi")
class FeaturedHeroTest {
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

    /** A backdrop that notes the alpha it is drawn at each time. */
    private class Art : ColorDrawable(0xFFFF0000.toInt()) {
        val drawnAt = mutableListOf<Float>()
        override fun draw(canvas: Canvas) {
            drawnAt += alpha / 255f
        }
    }

    private val backdrop = Art()

    /** Every image is served at once, as from disk: the backdrops as [backdrop], the logos as plain colour. */
    @Before fun serveTheArt() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val serve = Interceptor { chain ->
            val art = if ("backdrop" in chain.request.data.toString()) backdrop else ColorDrawable(0xFFFFFFFF.toInt())
            SuccessResult(art.asImage(), chain.request, dataSource = DataSource.DISK) as ImageResult
        }
        SingletonImageLoader.setUnsafe(ImageLoader.Builder(context).components { add(serve) }.build())
    }

    @After fun forgetTheArt() = SingletonImageLoader.reset()

    private fun featured(name: String) = FeaturedTitle(
        TitleId.Tmdb(TitleKind.Movie, name.hashCode().toLong()), name, 2025, 112, listOf("Drama"), "About $name.",
        backdrop = "https://image.tmdb.org/t/p/w1280/backdrop-$name.jpg", logo = "https://image.tmdb.org/t/p/w500/logo-$name.png", best = VersionAttributes(),
    )

    /** The Titles [feature] hands out, one per ask, in turn, as the Library's rotation does. */
    private val titles = listOf(featured("Dune"), featured("Arrival"), featured("Sicario"))
    private var asked = 0
    private val feature: suspend () -> FeaturedTitle? = { titles[asked++ % titles.size] }

    private fun title(name: String) = TitleSummary(TitleId.Unmatched(TitleKind.Movie, name.hashCode().toLong()), name, 2024, null, VersionAttributes())
    private fun genre(name: String) = HomeRow.Genre(name, List(10) { title("$name$it") })
    private val rows = listOf(genre("Action"), genre("Drama"))

    private var reading by mutableIntStateOf(0)
    private var entry by mutableIntStateOf(0)
    private var restoreFocus by mutableStateOf<RowCard?>(null)
    private val played = ArrayList<Pair<RowCard, FeaturedTitle>>()
    private val opened = ArrayList<Pair<RowCard, TitleId>>()

    /** The rows under a tab, with a hero; bumping [entry] enters the screen again, coming back to [restoreFocus] when set. */
    private fun screen(feature: (suspend () -> FeaturedTitle?)? = this.feature) = compose.setContent {
        NuageTheme {
            val state = remember { RowsState() }
            key(entry) {
                RowsLayout(
                    state, restoreFocus, focusTab = false,
                    reading = reading,
                    read = { rows },
                    empty = "",
                    setFavourite = { _, _ -> },
                    onScreen = {},
                    info = { _, _, _ -> },
                    appLanguage = "en",
                    onOpen = { card, id -> opened += card to id },
                    feature = feature,
                    onPlayFeatured = { card, title -> played += card to title },
                ) { tab -> Box(Modifier.size(80.dp, 30.dp).testTag("tab").focusRequester(tab).focusable()) }
            }
        }
    }

    private fun press(keys: List<Key>) = keys.forEach { key ->
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    private fun showing(text: String): SemanticsMatcher = hasText(text) or hasAnyDescendant(hasText(text))

    private fun focusedOn(text: String) = compose.onNode(isFocused()).assert(showing(text))

    /** The hero shows [name]'s Title: its logo, named for it, and its description. */
    private fun heroShows(name: String) {
        compose.onNodeWithContentDescription(name).assertIsDisplayed()
        compose.onNodeWithText("About $name.").assertIsDisplayed()
    }

    @Test fun theScreenOpensOnTheFeaturedTitleWithFocusOnPlay() {
        screen()

        heroShows("Dune")
        compose.onNodeWithText("2025 · 1 h 52 min · Drama").assertIsDisplayed()
        focusedOn("Play")
        compose.onNodeWithText("Details").assertIsDisplayed()
        // The first row's heading peeks under it.
        compose.onNodeWithText("Action").assertIsDisplayed()
    }

    @Test fun theFirstDownScrollsTheHeroAwayUnderTheTabsAndUpBringsItBack() {
        screen()

        press(listOf(Key.DirectionDown))
        focusedOn("Action0")
        compose.onNodeWithText("Play").assertIsNotDisplayed()
        compose.onNodeWithText("About Dune.").assertIsNotDisplayed()
        compose.onNodeWithTag("tab").assertIsDisplayed()

        press(listOf(Key.DirectionDown, Key.DirectionUp))
        focusedOn("Action0")
        press(listOf(Key.DirectionUp))
        focusedOn("Play")
        heroShows("Dune")
    }

    @Test fun upFromTheHeroGoesToTheTabsAndBackFromItOrTheRowsToo() {
        screen()
        press(listOf(Key.DirectionUp))
        compose.onNodeWithTag("tab").assertIsFocused()

        press(listOf(Key.DirectionDown))
        focusedOn("Play")
        press(listOf(Key.DirectionRight))
        focusedOn("Details")
        press(listOf(Key.Back))
        compose.onNodeWithTag("tab").assertIsFocused()
        // Down comes back to where focus was.
        press(listOf(Key.DirectionDown))
        focusedOn("Details")

        press(listOf(Key.DirectionDown, Key.Back))
        compose.onNodeWithTag("tab").assertIsFocused()
    }

    @Test fun playPlaysTheFeaturedTitleAndDetailsOpensIt() {
        screen()

        press(listOf(Key.DirectionCenter))
        assertEquals(listOf(RowCard(HERO_ROW, HERO_PLAY) to titles[0]), played)

        press(listOf(Key.DirectionRight, Key.DirectionCenter))
        assertEquals(listOf(RowCard(HERO_ROW, HERO_DETAILS) to titles[0].id), opened)
    }

    @Test fun eachEntryFeaturesAnotherTitleWhichNeverChangesWhileTheViewerIsOnTheScreen() {
        screen()
        heroShows("Dune")

        // Read again while the Viewer is on it (a Sync landing, Games refreshing): the same Title.
        reading++
        compose.waitForIdle()
        heroShows("Dune")

        entry++
        compose.waitForIdle()
        heroShows("Arrival")
        focusedOn("Play")

        entry++
        compose.waitForIdle()
        heroShows("Sicario")
    }

    @Test fun comingBackFromTheTitlePageItOpenedKeepsTheTitleWithFocusOnDetails() {
        screen()
        press(listOf(Key.DirectionRight))
        focusedOn("Details")

        restoreFocus = RowCard(HERO_ROW, HERO_DETAILS)
        entry++
        compose.waitForIdle()

        heroShows("Dune")
        focusedOn("Details")
    }

    @Test fun comingBackToACardKeepsTheTitleAndTheCard() {
        screen()
        press(listOf(Key.DirectionDown, Key.DirectionRight))
        focusedOn("Action1")

        restoreFocus = RowCard("genre/Action", title("Action1").id.toString())
        entry++
        compose.waitForIdle()

        focusedOn("Action1")
        restoreFocus = null
        press(listOf(Key.DirectionUp))
        heroShows("Dune")
    }

    @Test fun withNoFeaturedTitleTheScreenOpensOnItsFirstCard() {
        screen(feature = { null })

        focusedOn("Action0")
        compose.onNodeWithText("Play").assertDoesNotExist()
    }

    @Test fun theBackdropFadesInAsItArrives() {
        compose.mainClock.autoAdvance = false
        screen()
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        assertEquals(0f, backdropAlpha())

        compose.mainClock.advanceTimeBy(Motion.imageFadeMillis / 2L)
        val half = backdropAlpha()
        assertTrue("half way through the fade it is at $half", half > 0f && half < 1f)

        compose.mainClock.advanceTimeBy(Motion.imageFadeMillis.toLong())
        assertEquals(1f, backdropAlpha())
    }

    /** Draws the screen as it is at this frame: the alpha the backdrop is drawn at, 0 when it isn't drawn. */
    private fun backdropAlpha(): Float {
        compose.waitForIdle()
        backdrop.drawnAt.clear()
        val screen = compose.activity.window.decorView
        screen.draw(Canvas(Bitmap.createBitmap(screen.width, screen.height, Bitmap.Config.ARGB_8888)))
        return backdrop.drawnAt.lastOrNull() ?: 0f
    }
}
