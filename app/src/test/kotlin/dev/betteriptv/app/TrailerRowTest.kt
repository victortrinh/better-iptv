package dev.betteriptv.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.core.app.ApplicationProvider
import dev.betteriptv.core.Known
import dev.betteriptv.core.LanguageGroup
import dev.betteriptv.core.PlayPlan
import dev.betteriptv.core.Provenance
import dev.betteriptv.core.TitleDetails
import dev.betteriptv.core.TitleId
import dev.betteriptv.core.TitleKind
import dev.betteriptv.core.Version
import dev.betteriptv.core.VersionAttributes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * A Title page's Trailer row (#355, #385, ADR 0012): after Restart and Remove, it plays the Title's
 * trailer inside Zugig, in YouTube's IFrame player on a screen of its own, so Back returns to the page
 * with focus on Trailer. A trailer YouTube won't let others embed opens in the device's YouTube app
 * instead. The row is left out when the Title has no trailer. The device's apps are Robolectric's
 * package manager, and the player's events are sent as its page would send them; YouTube itself only
 * plays on the stick.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-mdpi")
class TrailerRowTest {
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

    private val packageManager get() = ApplicationProvider.getApplicationContext<Context>().packageManager

    private val movie = TitleId.Numbered(TitleKind.Movie, 949)
    private val best = Version(1, "EN - Heat (1995) 1080p", VersionAttributes(language = Known("en", Provenance.Likely)))
    private fun details(trailer: String?) = TitleDetails(
        movie, "Heat", 1995, null, listOf(LanguageGroup("en", listOf(best))), emptyList(),
        description = "A group of professional bank robbers start to feel the heat from police. ".repeat(4),
        genres = listOf("Crime", "Drama"),
        runtimeMinutes = 170,
        trailer = trailer,
    )

    // Stopped partway, so the page has every action a Movie has: Play, Restart, Remove, Trailer and Versions.
    private val stopped = PlayPlan(movie, best, listOf("en"), listOf(best), from = 62 * 60_000L, length = 170 * 60_000L)

    /** An app on the device whose activity takes VIEW links of [scheme] (on [host], when given). */
    private fun installApp(packageName: String, scheme: String, host: String? = null) {
        val activity = ComponentName(packageName, "$packageName.Main")
        shadowOf(packageManager).addActivityIfNotPresent(activity)
        shadowOf(packageManager).addIntentFilterForActivity(
            activity,
            IntentFilter(Intent.ACTION_VIEW).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                addDataScheme(scheme)
                if (host != null) addDataAuthority(host, null)
            },
        )
    }

    private fun installFireTvYouTube() = installApp("com.amazon.firetv.youtube", "https", "www.youtube.com")

    /** The page as the main activity shows it: OK on an action opens its section, and Back, or the section itself, closes it onto the page. */
    private fun open(details: TitleDetails, kids: Boolean = false) {
        // Read after the page's first frame, as the Library's answers are.
        var read by mutableStateOf(false)
        compose.setContent {
            var section by remember { mutableStateOf<TitleSection?>(null) }
            var returnedFrom by remember { mutableStateOf<TitleSection?>(null) }
            val close = {
                returnedFrom = section
                section = null
            }
            BackHandler(enabled = section != null, onBack = close)
            TitlePage(
                movie, null, (details to stopped).takeIf { read }, null, onPlay = { _, _ -> },
                section = section,
                returnedFrom = returnedFrom,
                onSection = {
                    section = it
                    returnedFrom = null
                },
                onCloseSection = close,
                kids = kids,
            )
        }
        compose.waitForIdle()
        read = true
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
    }

    private fun press(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    private fun back() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    private fun showing(text: String): SemanticsMatcher = hasText(text, substring = true) or hasAnyDescendant(hasText(text, substring = true))
    private fun shows(text: String) = compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    /** Down to Trailer, then OK, as the Viewer does. */
    private fun openTrailer() {
        compose.onNodeWithTag("play").assertIsFocused()
        repeat(3) { press(Key.DirectionDown) }
        compose.onNode(isFocused()).assert(showing("Trailer"))
        compose.onNodeWithTag("trailer action").assertIsFocused()
        press(Key.DirectionCenter)
    }

    private fun webView(): WebView? {
        fun find(view: View): WebView? = when (view) {
            is WebView -> view
            is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { find(view.getChildAt(it)) }
            else -> null
        }
        return find(compose.activity.window.decorView)
    }

    /** The trailer's player tells the screen something, as its page does through the bridge. */
    private fun player(event: TrailerEvents.() -> Unit) {
        val events = shadowOf(webView()!!).getJavascriptInterface(TRAILER_BRIDGE) as TrailerEvents
        events.event()
        shadowOf(Looper.getMainLooper()).idle()
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
    }

    @Test fun aKidsProfilesPageHasNoTrailerRowAndNoVersionsAndPlayStaysFirst() {
        open(details(trailer = "KzSm2Q9h3nM"), kids = true)

        compose.onNodeWithTag("play").assertIsFocused()
        assertEquals(0, compose.onAllNodesWithTag("trailer action").fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithTag("versions action").fetchSemanticsNodes().size)
        assertFalse(shows("Trailer"))
        assertFalse(shows("Versions"))
        // Restart and Remove are there, as on any profile.
        assertTrue(shows("Restart"))
    }

    @Test fun theTrailerRowComesAfterRestartAndRemoveAndPlaysTheTrailerInZugig() {
        open(details(trailer = "KzSm2Q9h3nM"))
        openTrailer()

        // Nothing leaves the app: the trailer plays in YouTube's IFrame player, on a page of an https origin YouTube accepts.
        assertNull(shadowOf(compose.activity).nextStartedActivity)
        val page = shadowOf(webView()!!).lastLoadDataWithBaseURL
        assertNotNull(page)
        assertTrue(page.baseUrl, page.baseUrl.startsWith("https://"))
        assertTrue(page.data, page.data.contains("https://www.youtube.com/iframe_api"))
        assertTrue(page.data, page.data.contains("\"KzSm2Q9h3nM\""))
    }

    @Test fun okOnTrailerShowsTheCoverAtOnceAndBuildsThePlayerBehindIt() {
        open(details(trailer = "KzSm2Q9h3nM"))
        compose.mainClock.autoAdvance = false
        openTrailer()

        // The first frame after OK is the Title's backdrop and name, as when a Version starts.
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag(STARTING_COVER).assertExists()
        // The WebView, which can hold the main thread for seconds the first time, comes once the cover is on screen.
        assertNull(webView())
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        assertNotNull(webView())
    }

    @Test fun thePlayerFadesInFromUnderItsCoverOnceItPlays() {
        open(details(trailer = "KzSm2Q9h3nM"))
        openTrailer()
        val web = webView()!!

        // The player is drawn from the start, under a cover of its own: a view's fade over a WebView is
        // a plain one, where the WebView's own fade cut in on the stick (#399).
        val content = web.parent as ViewGroup
        val cover = content.getChildAt(content.indexOfChild(web) + 1)
        assertTrue("$cover", cover is ComposeView)
        // The remote's keys still go to the player.
        assertFalse(cover.isFocusable)
        assertEquals(ViewGroup.FOCUS_BLOCK_DESCENDANTS, (cover as ViewGroup).descendantFocusability)
        assertEquals(1f, web.alpha)
        assertEquals(View.VISIBLE, web.visibility)
        assertEquals(2, compose.onAllNodesWithTag(STARTING_COVER).fetchSemanticsNodes().size)

        // YouTube says it's playing before the stick draws its first frame (#407): the cover stays.
        player { state(TrailerEvents.PLAYING) }
        assertEquals(2, compose.onAllNodesWithTag(STARTING_COVER).fetchSemanticsNodes().size)

        // Once the trailer's time has moved on, its frames are on screen, and the cover fades.
        player { pictured() }

        // Only the cover under the player, which nothing sees, is left.
        assertEquals(1, compose.onAllNodesWithTag(STARTING_COVER).fetchSemanticsNodes().size)
    }

    @Test fun thePageSaysItsPicturedOnlyOnceTheTrailersTimeMovesOn() {
        val page = trailerPage("KzSm2Q9h3nM", "en", "https://dev.betteriptv")
        assertTrue(page, page.contains("getCurrentTime()"))
        assertTrue(page, page.contains("$TRAILER_BRIDGE.pictured()"))
    }

    @Test fun backCoversThePlayerAtOnceSoNothingOfYouTubesShows() {
        open(details(trailer = "KzSm2Q9h3nM"))
        openTrailer()
        player { state(TrailerEvents.PLAYING) }
        player { pictured() }
        val web = webView()!!
        assertEquals(1, compose.onAllNodesWithTag(STARTING_COVER).fetchSemanticsNodes().size)

        // Back's press, before the app closes the screen on its release: the cover is back over the player,
        // so YouTube's bar never shows on black as the WebView goes (#407).
        web.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_BACK))
        compose.waitForIdle()
        assertEquals(2, compose.onAllNodesWithTag(STARTING_COVER).fetchSemanticsNodes().size)
        assertEquals("mute()", shadowOf(web).lastEvaluatedJavascript)
    }

    @Test fun youTubesOwnControlsAreHidden() {
        val page = trailerPage("KzSm2Q9h3nM", "en", "https://dev.betteriptv")
        assertTrue(page, page.contains("controls: 0"))
        assertFalse(page, page.contains("controls: 1"))
    }

    @Test fun theRemotesKeysDriveThePlayer() {
        open(details(trailer = "KzSm2Q9h3nM"))
        openTrailer()
        player { state(TrailerEvents.PLAYING) }
        player { pictured() }
        val web = webView()!!

        fun key(code: Int): Boolean = web.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, code)).also {
            web.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, code))
        }
        assertTrue(key(android.view.KeyEvent.KEYCODE_DPAD_CENTER))
        assertEquals("toggle()", shadowOf(web).lastEvaluatedJavascript)
        assertTrue(key(android.view.KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals("seek(-10)", shadowOf(web).lastEvaluatedJavascript)
        assertTrue(key(android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD))
        assertEquals("seek(10)", shadowOf(web).lastEvaluatedJavascript)
        // Back is left to the app, once: the WebView never sees it.
        assertFalse(key(android.view.KeyEvent.KEYCODE_BACK))
    }

    @Test fun backFromTheTrailerReturnsToTheTitlePageOnTheTrailerRow() {
        open(details(trailer = "KzSm2Q9h3nM"))
        openTrailer()
        player { state(TrailerEvents.PLAYING) }

        val web = webView()!!

        back()

        // Its sound stops at once, and it goes; the WebView is destroyed once the page is back.
        assertEquals("mute()", shadowOf(web).lastEvaluatedJavascript)
        assertNull(webView())
        compose.onNodeWithTag("trailer action").assertIsFocused()
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(2))
        assertTrue(shadowOf(web).wasDestroyCalled())
        // Versions comes after it.
        press(Key.DirectionDown)
        compose.onNodeWithTag("versions action").assertIsFocused()
    }

    @Test fun whenTheTrailerEndsThePageComesBackOnTheTrailerRow() {
        open(details(trailer = "KzSm2Q9h3nM"))
        openTrailer()
        player { state(TrailerEvents.PLAYING) }

        player { state(TrailerEvents.ENDED) }

        assertNull(webView())
        compose.onNodeWithTag("trailer action").assertIsFocused()
    }

    @Test fun aTrailerYouTubeWontLetZugigEmbedOpensInTheYouTubeApp() {
        installFireTvYouTube()
        open(details(trailer = "KzSm2Q9h3nM"))
        openTrailer()

        player { error(150) }

        val started = shadowOf(compose.activity).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertEquals("com.amazon.firetv.youtube", started.`package`)
        assertEquals("https://www.youtube.com/watch?v=KzSm2Q9h3nM", started.dataString)
        // Coming back from YouTube, the page is there with focus on Trailer.
        assertNull(webView())
        compose.onNodeWithTag("trailer action").assertIsFocused()
    }

    @Test fun aTrailerThatCantPlaySaysSoAndBackReturnsToTheRow() {
        // Embedding refused, and no YouTube app to open it in.
        open(details(trailer = "KzSm2Q9h3nM"))
        openTrailer()

        player { error(101) }

        assertNull(shadowOf(compose.activity).nextStartedActivity)
        assertTrue(shows("This trailer can’t play here"))
        back()
        compose.onNodeWithTag("trailer action").assertIsFocused()
    }

    @Test fun aRemovedVideoSaysSoRatherThanOpeningYouTube() {
        installFireTvYouTube()
        open(details(trailer = "KzSm2Q9h3nM"))
        openTrailer()

        player { error(100) }

        assertNull(shadowOf(compose.activity).nextStartedActivity)
        assertTrue(shows("This trailer can’t play here"))
    }

    @Test fun withEveryActionTheMoviesPageStillFitsOnOneScreen() {
        open(details(trailer = "KzSm2Q9h3nM"))

        val bottom = compose.onNodeWithTag("versions action").fetchSemanticsNode().boundsInRoot.bottom
        val density = compose.activity.resources.displayMetrics.density
        assertTrue("the last action ends at ${bottom / density} dp", bottom / density <= 540 - 27)
    }

    @Test fun aTitleWithoutATrailerHasNoTrailerRow() {
        open(details(trailer = null))

        compose.onNodeWithTag("trailer action").assertDoesNotExist()
        compose.onNodeWithTag("versions action").assertExists()
    }

    @Test fun theTrailerRowIsThereWithoutAnyYouTubeApp() {
        open(details(trailer = "KzSm2Q9h3nM"))

        assertNull(trailerIntent(packageManager, "KzSm2Q9h3nM"))
        compose.onNodeWithTag("trailer action").assertExists()
    }

    @Test fun aPausedTrailerSaysItsPaused() {
        // Driven from the remote, YouTube's player shows nothing when paused: the page shows a pause sign over it.
        val page = trailerPage("KzSm2Q9h3nM", "en", "https://dev.betteriptv")
        assertTrue(page, page.contains("id=\"paused\""))
        assertTrue(page, page.contains("e.data == 2"))
    }

    @Test fun theTrailersPageCarriesOnlyTheVideosIdWhateverTheProviderSent() {
        // A key with quotes or tags can't break out of the page's script.
        val page = trailerPage("x\"</script><script>alert(1)", "en", "https://dev.betteriptv")
        assertFalse(page, page.contains("</script><script>alert"))
        assertFalse(page, page.contains("x\"<"))
    }

    @Test fun aBrowserIsNeverTheFallbackForATrailerYouTubeWontEmbed() {
        installApp("com.amazon.cloud9", "https")
        assertNull(trailerIntent(packageManager, "KzSm2Q9h3nM"))
    }

    @Test fun anAppTakingYouTubesOwnLinksOpensTheTrailerWhenNoKnownYouTubeAppIsThere() {
        assertNull(trailerIntent(packageManager, "KzSm2Q9h3nM"))
        installApp("org.example.tube", "vnd.youtube")

        val intent = trailerIntent(packageManager, "KzSm2Q9h3nM")
        assertEquals("vnd.youtube:KzSm2Q9h3nM", intent?.dataString)
        assertNull(intent?.`package`)
    }

    @Test fun fireTvsYouTubeIsAskedBeforeGooglesWhenBothAreThere() {
        installApp("com.google.android.youtube.tv", "https", "www.youtube.com")
        installFireTvYouTube()

        assertEquals("com.amazon.firetv.youtube", trailerIntent(packageManager, "KzSm2Q9h3nM")?.`package`)
    }
}

/**
 * The cover over the trailer fades once frames flow again: on the emulator the main thread stalled
 * ~0.5 s just after YouTube's player said it played, and a fade run through it was a cut (#399).
 */
class SteadyFramesTest {
    /** A frame clock that answers with these frame times, in milliseconds, and counts what was asked. */
    private class Frames(vararg millis: Long) : androidx.compose.runtime.MonotonicFrameClock {
        val left = ArrayDeque(millis.toList())
        override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R = onFrame(left.removeFirst() * 1_000_000)
    }

    @Test fun itWaitsOutAStall() {
        val frames = Frames(0, 16, 520, 536, 552, 568, 584, 600)
        kotlinx.coroutines.runBlocking(frames) { awaitSteadyFrames() }
        // The stall from 16 to 520 ms starts the count again; three steady frames after it end the wait.
        assertEquals(listOf(584L, 600L), frames.left.toList())
    }

    @Test fun itGivesUpOnAMainThreadThatNeverSettles() {
        val frames = Frames(*LongArray(20) { it * 200L })
        kotlinx.coroutines.runBlocking(frames) { awaitSteadyFrames() }
        assertTrue(frames.left.size > 0)
    }
}
