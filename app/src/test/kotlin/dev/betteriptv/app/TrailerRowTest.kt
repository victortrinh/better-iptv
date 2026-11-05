package dev.betteriptv.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.lifecycle.Lifecycle
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
 * A Title page's Trailer row (#355, ADR 0012): after Restart and Remove, it opens the Title's trailer
 * in the device's YouTube app, and is left out when the Title has no trailer or no YouTube app can
 * take it. The device's apps are Robolectric's package manager; YouTube itself only runs on the stick.
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

    private val movie = TitleId.Tmdb(TitleKind.Movie, 949)
    private val best = Version(1, "EN - Heat (1995) 1080p", VersionAttributes(language = Known("en", Provenance.Likely)))
    private fun details(trailer: String?) = TitleDetails(
        movie, "Heat", 1995, null, listOf(LanguageGroup("en", listOf(best))), emptyList(),
        description = "A group of professional bank robbers start to feel the heat from police. ".repeat(4),
        genres = listOf("Crime", "Drama"),
        runtimeMinutes = 170,
        fromTmdb = true,
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

    private fun open(details: TitleDetails) {
        // Read after the page's first frame, as the Library's answers are.
        var read by mutableStateOf(false)
        compose.setContent { TitlePage(movie, null, (details to stopped).takeIf { read }, null, onPlay = { _, _ -> }) }
        compose.waitForIdle()
        read = true
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
    }

    private fun press(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    private fun showing(text: String): SemanticsMatcher = hasText(text, substring = true) or hasAnyDescendant(hasText(text, substring = true))

    @Test fun theTrailerRowComesAfterRestartAndRemoveAndOpensTheTrailerInYouTube() {
        installFireTvYouTube()
        open(details(trailer = "KzSm2Q9h3nM"))

        compose.onNodeWithTag("play").assertIsFocused()
        repeat(3) { press(Key.DirectionDown) }
        compose.onNode(isFocused()).assert(showing("Trailer"))
        compose.onNodeWithTag("trailer action").assertIsFocused()
        press(Key.DirectionCenter)

        val started = shadowOf(compose.activity).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertEquals("com.amazon.firetv.youtube", started.`package`)
        assertEquals("https://www.youtube.com/watch?v=KzSm2Q9h3nM", started.dataString)
        // Versions comes after it.
        press(Key.DirectionDown)
        compose.onNodeWithTag("versions action").assertIsFocused()
    }

    @Test fun withEveryActionTheMoviesPageStillFitsOnOneScreen() {
        installFireTvYouTube()
        open(details(trailer = "KzSm2Q9h3nM"))

        val bottom = compose.onNodeWithTag("versions action").fetchSemanticsNode().boundsInRoot.bottom
        val density = compose.activity.resources.displayMetrics.density
        assertTrue("the last action ends at ${bottom / density} dp", bottom / density <= 540 - 27)
    }

    @Test fun backFromYouTubeReturnsToTheTitlePageOnTheTrailerRow() {
        installFireTvYouTube()
        open(details(trailer = "KzSm2Q9h3nM"))
        repeat(3) { press(Key.DirectionDown) }
        press(Key.DirectionCenter)

        // YouTube comes in front, then Back from it brings Zugig back.
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle()

        compose.onNodeWithTag("trailer action").assertIsFocused()
    }

    @Test fun aTitleWithoutATrailerHasNoTrailerRow() {
        installFireTvYouTube()
        open(details(trailer = null))

        compose.onNodeWithTag("trailer action").assertDoesNotExist()
        compose.onNodeWithTag("versions action").assertExists()
    }

    @Test fun withoutAYouTubeAppThereIsNoTrailerRowEvenWhenABrowserCouldOpenTheLink() {
        installApp("com.amazon.cloud9", "https")
        open(details(trailer = "KzSm2Q9h3nM"))

        compose.onNodeWithTag("trailer action").assertDoesNotExist()
        compose.onNodeWithTag("versions action").assertExists()
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
