package dev.betteriptv.app

import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.ColorDrawable
import androidx.activity.ComponentActivity
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
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
import dev.betteriptv.core.Episode
import dev.betteriptv.core.EpisodeNumber
import dev.betteriptv.core.LanguageGroup
import dev.betteriptv.core.PlayPlan
import dev.betteriptv.core.Season
import dev.betteriptv.core.ShowEpisodes
import dev.betteriptv.core.TitleDetails
import dev.betteriptv.core.TitleId
import dev.betteriptv.core.TitleKind
import dev.betteriptv.core.Version
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
import org.robolectric.annotation.GraphicsMode

/**
 * A Title page arrives whole: the header, its poster and the actions appear together once read; a
 * Show's Play waits disabled, with focus on it, until its Episodes are read. Checked frame by frame
 * on the test clock, by what is drawn and what is on screen at each frame. The Library is stood in
 * for by the states [TitlePage] is given.
 */
@OptIn(DelicateCoilApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-mdpi")
class TitlePageArrivalTest {
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

    /** A picture that notes the alpha of each time it is drawn. */
    private class Art : ColorDrawable(0xFFFF0000.toInt()) {
        val drawnAt = mutableListOf<Float>()
        override fun getIntrinsicWidth() = 160
        override fun getIntrinsicHeight() = 90
        override fun draw(canvas: Canvas) {
            drawnAt += alpha / 255f
        }
    }

    private val poster = "https://img.example/heat.jpg"
    private val arts = mapOf(poster to Art())

    @Before fun serveTheArt() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // Anything not in memory comes from disk, so it fades in.
        val serve = Interceptor { chain -> SuccessResult(arts.getValue(chain.request.data.toString()).asImage(), chain.request, dataSource = DataSource.DISK) as ImageResult }
        SingletonImageLoader.setUnsafe(ImageLoader.Builder(context).components { add(serve) }.build())
    }

    @After fun forgetTheArt() = SingletonImageLoader.reset()

    private val version = Version(1, "Heat 1080p", VersionAttributes())
    private val english = LanguageGroup("en", listOf(version))
    private val movie = TitleId.Numbered(TitleKind.Movie, 949)
    private val show = TitleId.Numbered(TitleKind.Show, 1396)

    private fun details(id: TitleId, name: String) = TitleDetails(id, name, 1995, poster, listOf(english), emptyList())

    private fun open(
        id: TitleId,
        loaded: MutableState<Pair<TitleDetails, PlayPlan?>?>,
        episodes: MutableState<Pair<ShowEpisodes, PlayPlan?>?> = mutableStateOf(null),
    ) {
        compose.mainClock.autoAdvance = false
        compose.setContent { TitlePage(id, loaded.value, episodes.value, onPlay = { _, _ -> }) }
    }

    /** Draws the screen as it is at this frame: the alpha each picture is drawn at, 0 when it isn't drawn. */
    private fun drawn(): Map<String, Float> {
        compose.waitForIdle()
        arts.values.forEach { it.drawnAt.clear() }
        val screen = compose.activity.window.decorView
        screen.draw(Canvas(Bitmap.createBitmap(screen.width, screen.height, Bitmap.Config.ARGB_8888)))
        return arts.mapValues { (_, art) -> art.drawnAt.lastOrNull() ?: 0f }
    }

    private fun shows(text: String) = compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
    private fun showsPlay() = compose.onAllNodesWithTag("play").fetchSemanticsNodes().isNotEmpty()

    @Test fun theHeaderAndTheActionsAppearTogether() {
        val loaded = mutableStateOf<Pair<TitleDetails, PlayPlan?>?>(null)
        open(movie, loaded)

        repeat(40) { frame ->
            if (frame == 6) loaded.value = details(movie, "Heat") to PlayPlan(movie, version, listOf("en"), listOf(version))
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            assertEquals("frame $frame", shows("Heat"), showsPlay())
        }
        assertTrue(shows("Heat"))
        compose.onNodeWithTag("play").assertIsFocused()
    }

    @Test fun aShowWaitsForItsEpisodesUpToThePageArrivalTimeWithFocusOnADisabledPlay() {
        val loaded = mutableStateOf<Pair<TitleDetails, PlayPlan?>?>(null)
        val episodes = mutableStateOf<Pair<ShowEpisodes, PlayPlan?>?>(null)
        open(show, loaded, episodes)
        loaded.value = details(show, "Breaking Bad") to null

        // The details alone don't make the page: it waits for the Episodes, up to the arrival time.
        var waitedMillis = 0L
        while (!shows("Breaking Bad")) {
            assertTrue("waited $waitedMillis ms", waitedMillis <= Motion.pageArrivalMillis + 32)
            assertTrue(!showsPlay())
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            waitedMillis += 16
        }
        assertTrue("it waited for the Episodes", waitedMillis >= Motion.pageArrivalMillis - 32)
        // The header and Play arrive together, Play disabled and focused from that first frame.
        compose.onNodeWithTag("play").assertIsFocused().assertIsNotEnabled()

        val pilot = EpisodeNumber(1, 1)
        episodes.value = ShowEpisodes(listOf(Season(1, listOf(Episode(1, 1, "Pilot", listOf(english), emptyList())))), 0, next = pilot) to
            PlayPlan(show, version, listOf("en"), listOf(version), episode = pilot)
        compose.mainClock.advanceTimeBy(100)
        compose.waitForIdle()
        // Play enables where it is: focus didn't move.
        compose.onNodeWithTag("play").assertIsFocused().assertIsEnabled().assertTextContains("Play S1 E1")
    }

    @Test fun aShowWhoseEpisodesArriveInTimeOpensWholeWithPlayEnabled() {
        val loaded = mutableStateOf<Pair<TitleDetails, PlayPlan?>?>(null)
        val episodes = mutableStateOf<Pair<ShowEpisodes, PlayPlan?>?>(null)
        open(show, loaded, episodes)
        loaded.value = details(show, "Breaking Bad") to null
        compose.mainClock.advanceTimeBy(100)
        compose.waitForIdle()
        assertTrue(!shows("Breaking Bad"))

        val pilot = EpisodeNumber(1, 1)
        episodes.value = ShowEpisodes(listOf(Season(1, listOf(Episode(1, 1, "Pilot", listOf(english), emptyList())))), 0, next = pilot) to
            PlayPlan(show, version, listOf("en"), listOf(version), episode = pilot)
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.onNodeWithTag("play").assertIsFocused().assertIsEnabled().assertTextContains("Play S1 E1")
        assertTrue(shows("S1 E1 · Pilot"))
    }

    // It draws the page, whose vector icons need real bitmaps.
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test fun thePageOpensOnTheBareGroundThenShowsThePosterBesideTheHeader() {
        val loaded = mutableStateOf<Pair<TitleDetails, PlayPlan?>?>(null)
        open(movie, loaded)
        compose.mainClock.advanceTimeByFrame()
        // Nothing fills the screen while the details are read: no backdrop, ever (ADR 0018).
        assertEquals(0f, drawn().values.max())

        loaded.value = details(movie, "Heat") to PlayPlan(movie, version, listOf("en"), listOf(version))
        compose.mainClock.advanceTimeBy(Motion.screenFadeMillis + Motion.imageFadeMillis + 200L)
        assertEquals(1f, drawn().getValue(poster))
        assertTrue(shows("Heat"))
    }
}
