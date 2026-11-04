package dev.betteriptv.app

import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.ColorDrawable
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
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
 * While a Version starts, the player covers the picture with the Title's backdrop, dimmed, and its
 * logo, or a Channel's logo on glass; on the first frame the cover fades to the picture. The cover
 * alone, over a white stand-in for the picture: the player needs a Library, which the app's unit
 * tests can't open. Checked frame by frame on the test clock.
 */
@OptIn(DelicateCoilApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w960dp-h540dp-mdpi")
class StartingCoverTest {
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

    /** Every image is red, and comes from memory, as the Title page's backdrop does. */
    @Before fun serveTheArt() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val serve = Interceptor { chain -> SuccessResult(ColorDrawable(0xFFFF0000.toInt()).asImage(), chain.request, dataSource = DataSource.MEMORY_CACHE) as ImageResult }
        SingletonImageLoader.setUnsafe(ImageLoader.Builder(context).components { add(serve) }.build())
    }

    @After fun forgetTheArt() = SingletonImageLoader.reset()

    private var pictured by mutableStateOf(false)

    private fun start(art: StartingArt?, name: String = "Heat") {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            Box(Modifier.fillMaxSize().background(Color.White).testTag("picture")) {
                StartingCover(art, name, pictured)
            }
        }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
    }

    /** How much of the white picture shows through at the screen's corner, from 0 (covered) to 1. */
    private fun pictureShowing(): Float = corner().green

    /** The screen's corner as it is drawn at this frame. */
    private fun corner(): Color {
        compose.waitForIdle()
        val screen = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(screen.width, screen.height, Bitmap.Config.ARGB_8888)
        screen.draw(Canvas(bitmap))
        return Color(bitmap.getPixel(4, 4))
    }

    @Test fun aTitleShowsItsBackdropDimmedAndItsLogo() {
        start(StartingArt.Title(backdrop = "https://image.tmdb.org/t/p/w1280/heat.jpg", logo = "https://image.tmdb.org/t/p/w500/heat-logo.png"))

        compose.onNodeWithTag(STARTING_BACKDROP).assertExists()
        compose.onNodeWithContentDescription("Heat").assertExists()
        // The backdrop is drawn at once, from memory, and dimmed: red under the ground's veil, the picture hidden.
        val corner = corner()
        assertTrue("red shows, dimmed: $corner", corner.red > 0.1f && corner.red < 0.5f)
        assertEquals(0f, corner.green, 0.05f)
    }

    @Test fun aTitleWithoutALogoIsNamed() {
        start(StartingArt.Title(backdrop = "https://image.tmdb.org/t/p/w1280/heat.jpg", logo = null))

        compose.onNodeWithText("Heat").assertExists()
    }

    @Test fun aChannelShowsItsLogoOnGlass() {
        start(StartingArt.Channel(logo = "http://provider.example/tsn.png"), name = "TSN")

        compose.onNodeWithTag(STARTING_GLASS).assertExists()
        compose.onNodeWithContentDescription("TSN").assertExists()
        compose.onNodeWithTag(STARTING_BACKDROP).assertDoesNotExist()
    }

    @Test fun aChannelWithoutALogoIsNamedOnGlass() {
        start(StartingArt.Channel(logo = null), name = "TSN")

        compose.onNodeWithTag(STARTING_GLASS).assertExists()
        compose.onNodeWithText("TSN").assertExists()
    }

    @Test fun theCoverStaysUntilTheFirstFrameThenFadesToThePicture() {
        start(StartingArt.Title(backdrop = "https://image.tmdb.org/t/p/w1280/heat.jpg", logo = null))

        // However long the Version takes to start, the picture stays covered.
        compose.mainClock.advanceTimeBy(5_000)
        assertEquals(0f, pictureShowing(), 0.05f)

        pictured = true
        // It fades, every frame showing more of the picture, never cut.
        var last = 0f
        repeat(Motion.screenFadeMillis / 16 / 2) {
            compose.mainClock.advanceTimeByFrame()
            val now = pictureShowing()
            assertTrue("picture at $now after $last", now >= last && now < 1f)
            last = now
        }
        assertTrue("half way through the fade the picture shows, at $last", last > 0f)

        // Then it leaves the screen.
        compose.mainClock.advanceTimeBy(Motion.screenFadeMillis.toLong() + 32)
        compose.onNodeWithTag(STARTING_COVER).assertDoesNotExist()
        assertEquals(1f, pictureShowing(), 0.01f)
    }

    @Test fun aChannelFadesToThePictureToo() {
        start(StartingArt.Channel(logo = "http://provider.example/tsn.png"), name = "TSN")
        assertEquals(0f, pictureShowing(), 0.05f)

        pictured = true
        repeat(Motion.screenFadeMillis / 16 + 8) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
        }
        compose.onNodeWithTag(STARTING_COVER).assertDoesNotExist()
    }

    @Test fun aReplayWaitsOnTheBareGround() {
        start(art = null)

        compose.onNodeWithTag(STARTING_COVER).assertExists()
        assertEquals(0f, pictureShowing(), 0.05f)
    }
}
