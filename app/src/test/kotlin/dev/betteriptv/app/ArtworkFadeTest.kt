package dev.betteriptv.app

import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.ColorDrawable
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.paint
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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

/**
 * Artwork arrives, it doesn't pop: an image from disk or the network fades in, one already in the
 * memory cache shows on its first frame. Checked frame by frame on the test clock, by the alpha
 * the image is drawn at.
 */
@OptIn(DelicateCoilApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-mdpi")
class ArtworkFadeTest {
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

    /** The image every request gets: it notes the alpha of each time it is drawn. */
    private class Art : ColorDrawable(0xFFFF0000.toInt()) {
        val drawnAt = mutableListOf<Float>()
        override fun draw(canvas: Canvas) {
            drawnAt += alpha / 255f
        }
    }

    private val art = Art()
    private var source = DataSource.DISK

    @Before fun serveTheArt() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val serve = Interceptor { chain -> SuccessResult(art.asImage(), chain.request, dataSource = source) as ImageResult }
        SingletonImageLoader.setUnsafe(ImageLoader.Builder(context).components { add(serve) }.build())
    }

    @After fun forgetTheArt() = SingletonImageLoader.reset()

    private fun showArtwork(card: Boolean = false) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val artwork = rememberArtwork("https://image.tmdb.org/t/p/w342/poster.jpg", card)
            Box(Modifier.size(136.dp, 204.dp).then(if (artwork != null) Modifier.paint(artwork) else Modifier))
        }
    }

    /** Draws the screen as it is at this frame: the alpha the art is drawn at, 0 when it isn't drawn. */
    private fun alphaOnScreen(): Float {
        compose.waitForIdle()
        art.drawnAt.clear()
        val screen = compose.activity.window.decorView
        screen.draw(Canvas(Bitmap.createBitmap(screen.width, screen.height, Bitmap.Config.ARGB_8888)))
        return art.drawnAt.lastOrNull() ?: 0f
    }

    @Test fun anImageFromMemoryShowsOnItsFirstFrameWithNoFade() {
        source = DataSource.MEMORY_CACHE
        showArtwork(card = true)

        compose.mainClock.advanceTimeByFrame()
        assertEquals(1f, alphaOnScreen())
    }

    @Test fun anImageFromDiskStartsTransparentAndReachesFullOpacityAfterTheFade() {
        source = DataSource.DISK
        showArtwork()

        compose.mainClock.advanceTimeByFrame()
        assertEquals(0f, alphaOnScreen())

        // It grows more opaque every frame of the fade, and never jumps straight to full.
        var last = 0f
        val frames = Motion.imageFadeMillis / 16
        repeat(frames / 2) {
            compose.mainClock.advanceTimeByFrame()
            val now = alphaOnScreen()
            assertTrue("alpha $now after $last", now >= last && now < 1f)
            last = now
        }
        assertTrue("half way through the fade it shows, at $last", last > 0f)

        compose.mainClock.advanceTimeBy(Motion.imageFadeMillis.toLong())
        assertEquals(1f, alphaOnScreen())
    }

    @Test fun anImageFromTheNetworkFadesInToo() {
        source = DataSource.NETWORK
        showArtwork(card = true)

        compose.mainClock.advanceTimeByFrame()
        assertEquals(0f, alphaOnScreen())
        compose.mainClock.advanceTimeBy(Motion.imageFadeMillis.toLong() + 32)
        assertEquals(1f, alphaOnScreen())
    }
}
