package dev.betteriptv.app

import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.ColorDrawable
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.Uri
import coil3.annotation.DelicateCoilApi
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import dev.betteriptv.app.ui.theme.NuageTheme
import dev.betteriptv.core.HomeRow
import dev.betteriptv.core.TitleAbout
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
import java.util.Collections

/**
 * The art of the cards beside the focused one is loaded ahead (#343), on the rows Home, Movies and
 * Shows share ([RowsLayout]), with a real image loader and memory cache whose images come from
 * memory, not the network: once focus rests on a card, its neighbours' grown backdrops are in the
 * memory cache; Right after a rest grows straight into the next card's backdrop, at full opacity on
 * its first grown frame; and nothing is preloaded while presses come faster than the settle.
 */
@OptIn(ExperimentalTestApi::class, DelicateCoilApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-mdpi")
class PreloadNeighboursTest {
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

    /** An image that notes the alpha of each time it is drawn. */
    private class Art : ColorDrawable(0xFFFF0000.toInt()) {
        val drawnAt: MutableList<Float> = Collections.synchronizedList(ArrayList())
        override fun draw(canvas: Canvas) {
            drawnAt += alpha / 255f
        }
    }

    private fun backdrop(i: Int) = "https://image.tmdb.org/t/p/w780/backdrop$i.jpg"
    private val art = (0 until 10).associate { backdrop(it) to Art() }

    /** Every url the loader fetched, in order: what would have gone to disk or the network. */
    private val fetched: MutableList<String> = Collections.synchronizedList(ArrayList())
    private lateinit var loader: ImageLoader

    @Before fun serveTheArt() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val serve = Fetcher.Factory<Uri> { data, _, _ ->
            Fetcher {
                val url = data.toString()
                fetched += url
                ImageFetchResult(art.getValue(url).asImage(shareable = true), isSampled = false, dataSource = DataSource.NETWORK)
            }
        }
        loader = ImageLoader.Builder(context).components { add(serve) }.build()
        SingletonImageLoader.setUnsafe(loader)
    }

    @After fun forgetTheArt() = SingletonImageLoader.reset()

    /** Ten posters, more than the screen shows so the rail loops, each growing into its own backdrop. */
    private val titles = (0 until 10).map { i ->
        TitleSummary(TitleId.Unmatched(TitleKind.Movie, i.toLong()), "Film$i", 2024, null, VersionAttributes(), TitleAbout(textlessBackdrop = backdrop(i)))
    }

    private fun screen() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            NuageTheme {
                RowsLayout(
                    remember { RowsState() }, restoreFocus = null, focusTab = false,
                    reading = 0,
                    read = { listOf(HomeRow.Genre("Action", titles)) },
                    empty = "",
                    setFavourite = { _, _ -> },
                    onScreen = {},
                    info = { _, _, _ -> },
                    appLanguage = "en",
                    onOpen = { _, _, _ -> },
                ) { tab -> Box(Modifier.size(80.dp, 30.dp).focusRequester(tab).focusable()) }
            }
        }
        // The rows are read and the first card takes focus.
        frames(10)
    }

    private fun frames(count: Int) = repeat(count) {
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
    }

    private fun right() {
        compose.onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        compose.waitForIdle()
    }

    private fun inMemory(url: String) = loader.memoryCache?.keys?.any { it.key == url } == true

    /** Waits, in real time, for the loader's background work to land: it doesn't run on the test clock. */
    private fun eventually(what: String, condition: () -> Boolean) {
        val until = System.currentTimeMillis() + 5_000
        while (!condition()) {
            assertTrue("timed out waiting until $what", System.currentTimeMillis() < until)
            Thread.sleep(10)
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    /** Draws the screen as it is at this frame. */
    private fun draw() {
        compose.waitForIdle()
        art.values.forEach { it.drawnAt.clear() }
        val screen = compose.activity.window.decorView
        screen.draw(Canvas(Bitmap.createBitmap(screen.width, screen.height, Bitmap.Config.ARGB_8888)))
    }

    @Test fun afterFocusRestsOnACardItsNeighboursGrownBackdropsAreInMemory() {
        screen()
        assertTrue(!inMemory(backdrop(1)) && !inMemory(backdrop(9)))

        compose.mainClock.advanceTimeBy(SETTLE_MILLIS + 50)
        compose.waitForIdle()

        // The rail loops: the first card's neighbours are the second and the last.
        eventually("both neighbours are in memory") { inMemory(backdrop(1)) && inMemory(backdrop(9)) }
        // Only the neighbours: no other card's backdrop is read.
        assertEquals(emptyList<String>(), fetched.filter { it != backdrop(0) && it != backdrop(1) && it != backdrop(9) })
    }

    @Test fun rightAfterARestShowsTheGrownCardsBackdropOnItsFirstGrownFrame() {
        screen()
        compose.mainClock.advanceTimeBy(SETTLE_MILLIS + 50)
        compose.waitForIdle()
        eventually("the next card's backdrop is in memory") { inMemory(backdrop(1)) }
        val next = art.getValue(backdrop(1))

        right()
        // Frame by frame from the press: the first frame the next card draws its backdrop, it draws it whole.
        var first: Float? = null
        repeat(6) {
            if (first != null) return@repeat
            compose.mainClock.advanceTimeByFrame()
            draw()
            first = next.drawnAt.lastOrNull()
        }
        assertEquals("the grown card's backdrop on its first frame", 1f, first)
    }

    @Test fun withoutARestTheNextCardGrowsAsGlassAndItsBackdropFadesIn() {
        screen()
        // Right at once, before the settle: nothing was loaded ahead for the second card.
        right()
        val next = art.getValue(backdrop(1))
        var first: Float? = null
        repeat(6) {
            if (first != null) return@repeat
            compose.mainClock.advanceTimeByFrame()
            draw()
            first = next.drawnAt.lastOrNull()
            // Its own load is in the background: let it land between frames.
            Thread.sleep(20)
            shadowOf(Looper.getMainLooper()).idle()
        }
        assertTrue("it fades in from transparent, at ${first ?: "nothing"}", first == null || first!! < 1f)
    }

    @Test fun nothingIsPreloadedWhilePressesComeFasterThanTheSettle() {
        screen()
        // Six Rights, each well within the settle of the one before.
        repeat(6) {
            right()
            compose.mainClock.advanceTimeBy(SETTLE_MILLIS / 2)
            compose.waitForIdle()
        }
        // Give anything wrongly started time to land.
        Thread.sleep(200)
        shadowOf(Looper.getMainLooper()).idle()
        // Focus passed over cards 0 to 6: only those grew, so only their backdrops were read. The
        // cards beside the last one, and the last card of the loop beside the first, were not.
        val focusedOver = (0..6).map(::backdrop)
        assertEquals(emptyList<String>(), fetched.filter { it !in focusedOver })

        // Now it rests: the cards beside the seventh are loaded.
        compose.mainClock.advanceTimeBy(SETTLE_MILLIS + 50)
        compose.waitForIdle()
        eventually("the seventh card's neighbours are in memory") { inMemory(backdrop(5)) && inMemory(backdrop(7)) }
    }
}
