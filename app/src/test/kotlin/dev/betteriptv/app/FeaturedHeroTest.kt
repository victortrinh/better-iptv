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
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.test.onAllNodesWithText
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
import dev.betteriptv.core.Known
import dev.betteriptv.core.Provenance
import dev.betteriptv.core.Quality
import dev.betteriptv.core.TitleId
import dev.betteriptv.core.TitleKind
import dev.betteriptv.core.TitleSummary
import dev.betteriptv.core.VersionAttributes
import kotlinx.coroutines.CompletableDeferred
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
import org.robolectric.annotation.GraphicsMode

/**
 * The featured hero on Home, Movies and Shows (#348, #475, ADR 0018), on the rows they share
 * ([RowsLayout]), the Library stood in: it names its Title in Display type, with two lines of plot,
 * and shows its poster at the right on the bare ground; it takes first focus on Play, the first Down
 * scrolls it away under the tabs and Up brings it back, Back goes to the tab, Play and Details do
 * what they say, each entry features the next Title and none changes while the Viewer is on the
 * screen, and its poster fades in.
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

    /** A poster that notes the alpha it is drawn at each time. */
    private class Art : SizedArt(width = 600, height = 900) {
        val drawnAt = mutableListOf<Float>()
        override fun draw(canvas: Canvas) {
            drawnAt += alpha / 255f
        }
    }

    private val poster = Art()

    /** Every image asked for, in order. */
    private val asked = java.util.Collections.synchronizedList(ArrayList<String>())

    /** While set, every image waits for it, as on a slow network. */
    @Volatile private var artHeld: CompletableDeferred<Unit>? = null

    /** While set, the rows wait for it, as a slow Library read. */
    @Volatile private var rowsHeld: CompletableDeferred<Unit>? = null

    /** Every image is served as from disk, at once unless [artHeld]: the posters as [poster]. */
    @Before fun serveTheArt() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val serve = Interceptor { chain ->
            asked += chain.request.data.toString()
            artHeld?.await()
            SuccessResult(poster.asImage(), chain.request, dataSource = DataSource.DISK) as ImageResult
        }
        SingletonImageLoader.setUnsafe(ImageLoader.Builder(context).components { add(serve) }.build())
    }

    @After fun forgetTheArt() = SingletonImageLoader.reset()

    private fun featured(name: String, posters: List<String> = listOf("https://provider.example/poster-$name.jpg")) = FeaturedTitle(
        TitleId.Numbered(TitleKind.Movie, name.hashCode().toLong()), name, 2025, 112, listOf("Drama"),
        "About $name. ".repeat(40).trim(),
        badge = VersionAttributes(), posters = posters,
    )

    /** The Titles [feature] hands out, one per ask, in turn, as the Library's rotation does. */
    private val titles = listOf(featured("Dune"), featured("Arrival"), featured("Sicario"))
    private var featuredCount = 0

    /** Whether each ask let the Library read a Movie's details. */
    private val readDetails = mutableListOf<Boolean>()
    private val feature: suspend (Boolean) -> FeaturedTitle? = { read -> readDetails += read; titles[featuredCount++ % titles.size] }

    private fun title(name: String) = TitleSummary(TitleId.Unmatched(TitleKind.Movie, name.hashCode().toLong()), name, 2024, null, VersionAttributes())
    private fun genre(name: String) = HomeRow.Genre(name, List(10) { title("$name$it") })
    private val rows = listOf(genre("Action"), genre("Drama"))

    private var reading by mutableIntStateOf(0)
    private var entry by mutableIntStateOf(0)
    private var restoreFocus by mutableStateOf<RowCard?>(null)
    private val played = ArrayList<Pair<RowCard, FeaturedTitle>>()
    private val opened = ArrayList<Pair<RowCard, TitleId>>()

    /** The rows under a tab, with a hero; bumping [entry] enters the screen again, coming back to [restoreFocus] when set. */
    private fun screen(
        feature: (suspend (Boolean) -> FeaturedTitle?)? = this.feature,
        featureBadge: (suspend (TitleId) -> VersionAttributes?)? = null,
    ) = compose.setContent {
        NuageTheme {
            val state = remember { RowsState() }
            key(entry) {
                RowsLayout(
                    state, restoreFocus, focusTab = false,
                    reading = reading,
                    read = { rowsHeld?.await(); rows },
                    empty = "",
                    setFavourite = { _, _ -> },
                    onScreen = {},
                    info = { _, _, _ -> },
                    appLanguage = "en",
                    onOpen = { card, id -> opened += card to id },
                    feature = feature,
                    featureBadge = featureBadge,
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

    /** The hero shows [name]'s Title: its name, and its description. */
    private fun heroShows(name: String) {
        compose.onNodeWithText(name).assertIsDisplayed()
        compose.onNodeWithText("About $name.", substring = true).assertIsDisplayed()
    }

    @Test fun theScreenOpensOnTheFeaturedTitleWithFocusOnPlay() {
        screen()

        heroShows("Dune")
        // Named in Display type, never by a logo (#475).
        assertEquals(displaySize, compose.onNodeWithText("Dune").fontSize())
        compose.onNodeWithContentDescription("Dune").assertDoesNotExist()
        compose.onNodeWithText("2025 · 1 h 52 min · Drama").assertIsDisplayed()
        focusedOn("Play")
        compose.onNodeWithText("Details").assertIsDisplayed()
        // The first row's heading peeks under it.
        compose.onNodeWithText("Action").assertIsDisplayed()
    }

    @Test fun theHeroShowsItsPosterAtTheRightOfItsText() {
        screen()

        val poster = compose.onNodeWithTag(HERO_POSTER).fetchSemanticsNode().boundsInRoot
        val name = compose.onNodeWithText("Dune").fetchSemanticsNode().boundsInRoot
        val density = compose.activity.resources.displayMetrics.density
        assertTrue("the poster ($poster) is right of the text ($name)", poster.left > name.right)
        // At the right margin, top-aligned with the name, a card's size.
        assertEquals(960f - 48f, poster.right / density, 1f)
        assertEquals(name.top / density, poster.top / density, 1f)
        assertEquals(136f, poster.width / density, 1f)
    }

    @Test fun aTitleWithoutAPosterShowsNone() {
        screen(feature = { featured("Dune", posters = emptyList()) })

        heroShows("Dune")
        compose.onNodeWithTag(HERO_POSTER).assertDoesNotExist()
    }

    // Text is measured, so it is laid out in lines.
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test fun theHeroShowsTwoLinesOfTheProvidersPlotHoweverLongItIs() {
        screen()

        assertEquals(2, compose.onNodeWithText("About Dune.", substring = true).lineCount())
    }

    @Test fun theFirstDownScrollsTheHeroAwayUnderTheTabsAndUpBringsItBack() {
        screen()

        press(listOf(Key.DirectionDown))
        focusedOn("Action0")
        compose.onNodeWithText("Play").assertIsNotDisplayed()
        compose.onNodeWithText("About Dune.", substring = true).assertIsNotDisplayed()
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

    /** How many lines this node's text is laid out on. */
    private fun androidx.compose.ui.test.SemanticsNodeInteraction.lineCount(): Int {
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        return layouts.single().lineCount
    }

    /** Advances the test clock a frame, giving the image loader's own threads a moment to answer. */
    private fun frame() {
        compose.mainClock.advanceTimeByFrame()
        Thread.sleep(2)
        compose.waitForIdle()
    }

    private fun exists(text: String) = compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    // It draws the screen, whose vector icons need real bitmaps.
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test fun onTheFirstEntryTheHeroWaitsForItsArtThenArrivesWholeWithTheRows() {
        val held = CompletableDeferred<Unit>()
        artHeld = held
        compose.mainClock.autoAdvance = false
        screen()

        // The Title and the rows are read, but not the poster: nothing of the hero shows, and the
        // placeholder rows stay, rather than its text alone on the ground.
        repeat(Motion.pageArrivalMillis / 16 / 2) {
            frame()
            assertTrue("frame $it", !exists("About Dune.") && !exists("Action"))
        }
        held.complete(Unit)
        var frames = 0
        while (!exists("About Dune.")) {
            assertTrue("waited $frames frames past the art", frames++ < 10)
            assertTrue(!exists("Action"))
            frame()
        }
        // The text, the rows and the poster arrive together, and the hero fades in as one.
        assertTrue(exists("Action"))
        val first = posterAlpha()
        assertTrue("on the hero's first frame its poster is at $first", first < 0.5f)
        compose.mainClock.advanceTimeBy(Motion.screenFadeMillis + Motion.imageFadeMillis + 100L)
        assertEquals(1f, posterAlpha())
        focusedOn("Play")
    }

    // It draws the screen, whose vector icons need real bitmaps.
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test fun theScreenIsBareForThePageArrivalTimeThenWaitsWithTheDotUntilTheRowsFadeInOverIt() {
        val held = CompletableDeferred<Unit>()
        rowsHeld = held
        compose.mainClock.autoAdvance = false
        screen()

        // Bare under the tabs for the page arrival time: no rows, and no dot either.
        var waitedMillis = 0L
        while (waitedMillis < Motion.pageArrivalMillis - 32) {
            compose.onNodeWithTag(WAITING).assertDoesNotExist()
            assertTrue(!exists("Action"))
            frame()
            waitedMillis += 16
        }
        // Then the dot, which fades in, alone.
        compose.mainClock.advanceTimeBy(Motion.screenFadeMillis + 100L)
        compose.onNodeWithTag(WAITING).assertExists()
        assertTrue(!exists("Action"))

        held.complete(Unit)
        while (!exists("About Dune.")) frame()
        // On their first frame, the rows and the hero are there (focus is on Play) but not yet seen:
        // the screen is still the dot over the ground, rather than the rows over an empty hero (#398).
        assertTrue(exists("Action"))
        focusedOn("Play")
        val first = posterAlpha()
        assertTrue("on the rows' first frame the poster is at $first", first < 0.5f)
        // Then they fade in as one, the dot crossfading out under them, and it goes once they have.
        compose.onNodeWithTag(WAITING).assertExists()
        compose.mainClock.advanceTimeBy(Motion.screenFadeMillis + 100L)
        compose.onNodeWithTag(WAITING).assertDoesNotExist()
        assertEquals(1f, posterAlpha())
    }

    @Test fun rowsKeptFromTheLastEntryShowAtOnce() {
        screen()
        heroShows("Dune")
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { entry++ }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        // Coming back to the screen, its rows are there on the first frame, with no wait and no dot.
        compose.onNodeWithTag(WAITING).assertDoesNotExist()
        compose.onNodeWithText("Action").assertExists()
    }

    // It draws the screen, whose vector icons need real bitmaps.
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test fun artSlowerThanThePageArrivalTimeDoesNotHoldTheHero() {
        val held = CompletableDeferred<Unit>()
        artHeld = held
        compose.mainClock.autoAdvance = false
        screen()

        var waitedMillis = 0L
        while (!exists("About Dune.")) {
            assertTrue("waited $waitedMillis ms", waitedMillis <= Motion.pageArrivalMillis + 64)
            frame()
            waitedMillis += 16
        }
        assertTrue("it waited for the art", waitedMillis >= Motion.pageArrivalMillis - 32)
        assertTrue(exists("Action"))
        assertEquals(0f, posterAlpha())

        // The art fades in once it comes.
        held.complete(Unit)
        repeat(4) { frame() }
        compose.mainClock.advanceTimeBy(Motion.imageFadeMillis + 100L)
        assertEquals(1f, posterAlpha())
    }

    @Test fun theNextEntrysArtIsLoadedDuringThisOne() {
        screen()
        heroShows("Dune")
        compose.waitUntil(2_000) { asked.any { "poster-Arrival" in it } }
    }

    @Test fun aTitleReadAfterTheRowsIsLeftForTheNextEntry() {
        val late = CompletableDeferred<Unit>()
        screen(feature = { late.await(); feature(it) })

        // The rows don't wait for it past the arrival time: the screen opens on its first card.
        compose.mainClock.advanceTimeBy(Motion.pageArrivalMillis + 100L)
        focusedOn("Action0")
        compose.onNodeWithText("Play").assertDoesNotExist()

        late.complete(Unit)
        compose.waitForIdle()
        compose.onNodeWithText("Play").assertDoesNotExist()
        entry++
        compose.waitForIdle()
        heroShows("Dune")
        focusedOn("Play")
    }

    /** Draws the screen as it is at this frame: the alpha the poster is drawn at, 0 when it isn't drawn. */
    private fun posterAlpha(): Float {
        compose.waitForIdle()
        poster.drawnAt.clear()
        val screen = compose.activity.window.decorView
        screen.draw(Canvas(Bitmap.createBitmap(screen.width, screen.height, Bitmap.Config.ARGB_8888)))
        return poster.drawnAt.lastOrNull() ?: 0f
    }

    // #521: the hero badges the Version its Play starts, as the Title page does.

    /** How many nodes read exactly [text], as a badge does. */
    private fun badges(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().size

    private val fullHd = VersionAttributes(language = Known("en", Provenance.Likely), quality = Known(Quality.FullHD, Provenance.Confirmed))

    @Test fun aShowWhoseEpisodesWereNeverReadIsShownWithNoBadge() {
        screen(feature = { featured("Severance").copy(badge = null) }, featureBadge = { null })

        heroShows("Severance")
        compose.onNodeWithText("2025 · 1 h 52 min · Drama").assertIsDisplayed()
        assertEquals(0, badges("EN"))
    }

    @Test fun comingBackFromTheTitlePageTheHeroBadgesWhatThePageRead() {
        // A Show never opened: no badge, until its page reads the Episodes, and Back shows theirs.
        var kept: VersionAttributes? = null
        val asked = ArrayList<TitleId>()
        val severance = featured("Severance").copy(badge = null)
        screen(feature = { severance }, featureBadge = { id -> asked += id; kept })
        assertEquals(0, badges("1080p"))

        press(listOf(Key.DirectionRight))
        kept = fullHd
        restoreFocus = RowCard(HERO_ROW, HERO_DETAILS)
        entry++
        compose.waitForIdle()

        heroShows("Severance")
        assertEquals(1, badges("1080p"))
        assertEquals(1, badges("EN"))
        assertTrue(asked.all { it == severance.id })
        focusedOn("Details")
    }

    @Test fun theTitleReadForThisEntryDuringTheLastIsBadgedAsItIsNow() {
        // Arrival is read for the next entry while Dune shows, its page opened meanwhile.
        screen(featureBadge = { id -> if (id == titles[1].id) fullHd else null })
        compose.runOnIdle { entry++ }
        compose.waitForIdle()

        heroShows("Arrival")
        assertEquals(1, badges("1080p"))
    }

    @Test fun theHeroNamesNoGuessedQualityAndNothingMovesWhenItIsConfirmed() {
        // A `4K-EN` Listing whose file is really 1080p (#546): its 4K is the Prefix's guess.
        var badge by mutableStateOf(VersionAttributes(language = Known("en", Provenance.Likely), quality = Known(Quality.UHD, Provenance.Likely)))
        screen(feature = { featured("Dune").copy(badge = null) }, featureBadge = { badge })

        assertEquals(0, badges("4K"))
        assertEquals(1, badges("EN"))
        val playTop = compose.onNodeWithText("Play").fetchSemanticsNode().boundsInRoot.top

        badge = fullHd
        entry++
        compose.waitForIdle()
        assertEquals(1, badges("1080p"))
        assertEquals(playTop, compose.onNodeWithText("Play").fetchSemanticsNode().boundsInRoot.top)
    }

    @Test fun aHeroWithOnlyGuessesKeepsItsBadgeRowsHeight() {
        var badge by mutableStateOf<VersionAttributes?>(VersionAttributes(quality = Known(Quality.UHD, Provenance.Likely)))
        screen(feature = { featured("Dune").copy(badge = null) }, featureBadge = { badge })
        assertEquals(0, badges("4K"))
        val playTop = compose.onNodeWithText("Play").fetchSemanticsNode().boundsInRoot.top

        badge = VersionAttributes(quality = Known(Quality.UHD, Provenance.Confirmed))
        entry++
        compose.waitForIdle()
        assertEquals(1, badges("4K"))
        assertEquals(playTop, compose.onNodeWithText("Play").fetchSemanticsNode().boundsInRoot.top)
    }
}
