package dev.betteriptv.app

import android.content.Context
import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
import dev.betteriptv.app.ui.theme.NuageTheme
import dev.betteriptv.core.FeaturedTitle
import dev.betteriptv.core.HomeRow
import dev.betteriptv.core.TitleId
import dev.betteriptv.core.TitleKind
import dev.betteriptv.core.TitleSummary
import dev.betteriptv.core.VersionAttributes
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
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
 * Focus the Viewer put on the tabs stays there (#498), on the rows Home, Movies and Shows share
 * ([RowsLayout]), the Library stood in: rows and a hero that arrive or are read again while the
 * Viewer is on a tab never take focus from it, nor does the screen made again (the Activity
 * recreated by a keyboard or remote attaching), and rows that go away with focus in them leave it
 * on the tab. Never on the hero's Play, where one OK meant for a tab would start a stream.
 */
@OptIn(ExperimentalTestApi::class, DelicateCoilApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-mdpi")
class FocusOnTheTabsTest {
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

    /** Every image is served as from disk, at once: the hero's backdrop arrives with the rows. */
    @Before fun serveTheArt() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val serve = Interceptor { chain -> SuccessResult(SizedArt().asImage(), chain.request, dataSource = DataSource.DISK) as ImageResult }
        SingletonImageLoader.setUnsafe(ImageLoader.Builder(context).components { add(serve) }.build())
    }

    @After fun forgetTheArt() = SingletonImageLoader.reset()

    private val hero = FeaturedTitle(
        TitleId.Numbered(TitleKind.Movie, 1), "War", 2025, 112, listOf("Drama"), "About War.",
        backdrop = "https://provider.example/backdrop-war.jpg", badge = VersionAttributes(),
    )

    private fun title(name: String) = TitleSummary(TitleId.Unmatched(TitleKind.Movie, name.hashCode().toLong()), name, 2024, null, VersionAttributes())
    private fun genre(name: String) = HomeRow.Genre(name, List(10) { title("$name$it") })

    /** While set, the rows wait for it, as #497's slow genre rows did on the stick. */
    @Volatile private var rowsHeld: CompletableDeferred<Unit>? = null
    private var reading by mutableIntStateOf(0)
    private var rows = listOf(genre("Action"), genre("Drama"))

    /** Replaced as MainActivity replaces it, for another Profile. */
    private var state by mutableStateOf(RowsState())
    private val played = ArrayList<RowCard>()
    private val movies = FocusRequester()

    /**
     * The rows under two tabs, Home (selected) then Movies, with a hero. With [restoration], set
     * through it, so the screen can be made again as after a recreate, its [RowsState] new as
     * MainActivity's is.
     */
    private fun screen(restoration: StateRestorationTester? = null) {
        val content = @androidx.compose.runtime.Composable {
            NuageTheme {
                val rowsState = if (restoration != null) remember { RowsState() } else state
                RowsLayout(
                    rowsState, restoreFocus = null, focusTab = false,
                    reading = reading,
                    read = { rowsHeld?.await(); rows },
                    empty = "",
                    setFavourite = { _, _ -> },
                    onScreen = {},
                    info = { _, _, _ -> },
                    appLanguage = "en",
                    onOpen = { _, _, _ -> },
                    feature = { hero },
                    onPlayFeatured = { card, _ -> played += card },
                ) { tab ->
                    Row {
                        Box(Modifier.size(80.dp, 30.dp).testTag("home").focusRequester(tab).focusable())
                        Box(Modifier.size(80.dp, 30.dp).testTag("movies").focusRequester(movies).focusable())
                    }
                }
            }
        }
        if (restoration != null) restoration.setContent(content) else compose.setContent(content)
        compose.waitForIdle()
    }

    private fun press(keys: List<Key>) = keys.forEach { key ->
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    private fun onMovies() {
        press(listOf(Key.DirectionUp, Key.DirectionRight))
        compose.onNodeWithTag("movies").assertIsFocused()
    }

    @Test fun rowsReadAgainWhileTheViewerIsOnATabLeaveFocusThere() {
        screen()
        compose.onNodeWithText("Play").assertIsFocused()
        onMovies()

        rows = listOf(genre("Comedy"), genre("Action"))
        reading++
        compose.waitForIdle()

        compose.onNodeWithTag("movies").assertIsFocused()
        compose.onNodeWithText("Play").assertIsNotFocused()
    }

    @Test fun rowsAndHeroArrivingWhileTheViewerIsOnATabLeaveFocusThere() {
        val held = CompletableDeferred<Unit>().also { rowsHeld = it }
        screen()
        // Nothing to focus yet but the tabs: the Viewer goes to Movies.
        compose.runOnIdle { movies.requestFocus() }
        compose.waitForIdle()
        compose.onNodeWithTag("movies").assertIsFocused()

        held.complete(Unit)
        compose.waitForIdle()

        compose.onNodeWithText("Play").assertIsNotFocused()
        compose.onNodeWithTag("movies").assertIsFocused()
        press(listOf(Key.DirectionCenter))
        assert(played.isEmpty())
    }

    @Test fun theScreenMadeAgainWhileTheViewerWasOnATabFocusesTheTabNotPlay() {
        val restoration = StateRestorationTester(compose)
        screen(restoration)
        compose.onNodeWithText("Play").assertIsFocused()
        onMovies()

        // A keyboard or remote attaching recreates the Activity: the rows are read again, with a hero.
        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()

        compose.onNodeWithText("Play").assertIsNotFocused()
        compose.onNodeWithTag("home").assertIsFocused()
    }

    @Test fun rowsGoingAwayWithFocusInThemLeaveFocusOnTheTab() {
        screen()
        press(listOf(Key.DirectionDown))
        compose.onNodeWithText("Action0").assertIsFocused()

        // Another Profile: its rows are still to be read.
        rowsHeld = CompletableDeferred()
        state = RowsState()
        compose.waitForIdle()

        compose.onNodeWithTag("home").assertIsFocused()
    }

    @Test fun anotherProfilesRowsAndHeroArrivingWhileTheViewerIsOnATabLeaveFocusThere() {
        screen()
        onMovies()

        // A switch from the chip, which is on the tabs (#501): the new Profile's rows and hero are read as on a first entry.
        rows = listOf(genre("Comedy"))
        state = RowsState()
        compose.waitForIdle()

        compose.onNodeWithText("Comedy0").assertExists()
        compose.onNodeWithText("Play").assertIsNotFocused()
        compose.onNodeWithTag("movies").assertIsFocused()
    }
}
