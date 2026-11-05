package dev.betteriptv.app

import android.content.Context
import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.core.app.ApplicationProvider
import dev.betteriptv.app.ui.components.Certainty
import dev.betteriptv.core.ChartList
import dev.betteriptv.core.ChartRank
import dev.betteriptv.core.Episode
import dev.betteriptv.core.EpisodeNumber
import dev.betteriptv.core.Known
import dev.betteriptv.core.LanguageGroup
import dev.betteriptv.core.PlayPlan
import dev.betteriptv.core.Provenance
import dev.betteriptv.core.Quality
import dev.betteriptv.core.Season
import dev.betteriptv.core.ShowEpisodes
import dev.betteriptv.core.TitleDetails
import dev.betteriptv.core.TitleId
import dev.betteriptv.core.TitleKind
import dev.betteriptv.core.Version
import dev.betteriptv.core.VersionAttributes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * The Title page in two levels (#346): the page holds the Title and its actions, a Versions action
 * naming what Play starts, and OK on it opens the Versions screen; Back returns to the action. The
 * Library is stood in for by the states [TitlePage] is given, and the navigation by a host that
 * does what the main activity does.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-mdpi")
class TitleTwoLevelsTest {
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

    private val movie = TitleId.Tmdb(TitleKind.Movie, 949)
    private val show = TitleId.Tmdb(TitleKind.Show, 1396)

    private fun likely(value: String) = Known(value, Provenance.Likely)
    private fun fullHd(confirmed: Boolean = false) = Known(Quality.FullHD, if (confirmed) Provenance.Confirmed else Provenance.Likely)

    /** Nine English Versions: the best a 1080p one, Confirmed, with a Backup; and one in German behind Other languages. */
    private val best = Version(
        1, "EN - Heat (1995) 1080p", VersionAttributes(language = likely("en"), quality = fullHd(confirmed = true), source = likely("Netflix")),
        backups = listOf(Version(2, "EN - Heat (1995) 1080p NF", VersionAttributes(language = likely("en"), quality = fullHd(), source = likely("Netflix")))),
    )
    private val english = LanguageGroup("en", listOf(best) + (3L..9L).map { Version(it, "EN - Heat $it", VersionAttributes(language = likely("en"))) })
    private val german = LanguageGroup("de", listOf(Version(10, "DE - Heat", VersionAttributes(language = likely("de")))))

    private fun details(id: TitleId = movie, name: String = "Heat") = TitleDetails(
        id, name, 1995, null, listOf(english), listOf(german),
        description = "A group of professional bank robbers start to feel the heat from police when they unknowingly leave a clue at their latest heist. ".repeat(3),
        genres = listOf("Crime", "Drama", "Action"),
        runtimeMinutes = 170,
        cast = listOf("Al Pacino", "Robert De Niro", "Val Kilmer", "Jon Voight", "Tom Sizemore"),
        rank = ChartRank(1, ChartList.TopMovies, "US"),
        fromTmdb = true,
    )

    // Stopped partway: every action a Movie has (Play, Restart, Remove from Continue watching, Versions).
    private val stopped = PlayPlan(movie, best, listOf("en"), listOf(best), from = 62 * 60_000L, length = 170 * 60_000L)

    private var chosen: Version? = null

    /** The page as the main activity shows it: OK on an action opens its section, and Back closes it onto the page. */
    private fun open(id: TitleId = movie, loaded: Pair<TitleDetails, PlayPlan?> = details() to stopped, episodes: Pair<ShowEpisodes, PlayPlan?>? = null) {
        // Read after the page's first frame, as the Library's answers are.
        var read by mutableStateOf(false)
        compose.setContent {
            var section by remember { mutableStateOf<TitleSection?>(null) }
            var returnedFrom by remember { mutableStateOf<TitleSection?>(null) }
            BackHandler(enabled = section != null) {
                returnedFrom = section
                section = null
            }
            TitlePage(
                id, null, loaded.takeIf { read }, episodes.takeIf { read }, onPlay = { _, _ -> },
                section = section,
                returnedFrom = returnedFrom,
                onSection = {
                    section = it
                    returnedFrom = null
                },
                onChoose = { version, _ -> chosen = version },
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

    private fun shows(text: String) = compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
    private fun showing(text: String): SemanticsMatcher = hasText(text, substring = true) or hasAnyDescendant(hasText(text, substring = true))
    private fun focusedOn(text: String) = compose.onNode(isFocused()).assert(showing(text))

    /** Opens the Versions screen from the page, as the Viewer does: down to Versions, then OK. */
    private fun openVersions() {
        compose.onNodeWithTag("versions action").assertExists()
        repeat(3) { press(Key.DirectionDown) }
        focusedOn("Versions")
        press(Key.DirectionCenter)
    }

    @Test fun aMoviesPageShowsNoVersionRowsAndFitsOnOneScreen() {
        open()

        assertFalse("no Version row", shows("Netflix"))
        assertFalse("no legend", shows("read from the file"))
        assertFalse("no cast", shows("Al Pacino"))
        assertFalse("no notice", shows("TMDB API"))
        // Every action sits inside the bottom margin of the 960 × 540 dp screen, so nothing needs a scroll.
        val bottom = compose.onNodeWithTag("versions action").fetchSemanticsNode().boundsInRoot.bottom
        val density = compose.activity.resources.displayMetrics.density
        assertTrue("the last action ends at ${bottom / density} dp", bottom / density <= 540 - 27)
    }

    @Test fun theVersionsActionNamesTheLanguageQualityAndCountPlayStartsWith() {
        open()

        compose.onNodeWithTag("versions action").assert(showing("English · 1080p · 10 Versions"))
    }

    @Test fun okOnVersionsOpensTheVersionsScreenAndBackReturnsFocusToTheAction() {
        open()
        compose.onNodeWithTag("play").assertIsFocused()

        openVersions()
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()

        // The Versions screen: the legend and the table, focus on the first Version, which plays when chosen.
        assertTrue(shows("read from the file"))
        assertTrue(shows("Netflix"))
        press(Key.DirectionCenter)
        assertEquals(1L, chosen?.listingId)
        // Further down, Other languages, then the cast and TMDB's notice, which the last row brings into view.
        repeat(10) { press(Key.DirectionDown) }
        focusedOn("Other languages")
        assertTrue(shows("Al Pacino"))
        assertTrue(shows("TMDB API"))

        back()
        compose.onNodeWithTag("versions action").assertIsFocused()
        assertFalse(shows("read from the file"))
    }

    @Test fun theTableIsCalmerEmptyCellsNoRepeatedAudioBackupsUnderTheirVersion() {
        open()
        openVersions()

        // No dash for what isn't known, and no "English" Audio cell under the English heading.
        assertFalse(shows("—"))
        assertEquals(1, compose.onAllNodesWithText("English", substring = true).fetchSemanticsNodes().size)
        // No Version has subtitles or a bitrate, so neither column is there; Source and Quality are.
        assertFalse(shows("Subtitles"))
        assertFalse(shows("Bitrate"))
        assertFalse(shows("Audio"))
        assertTrue(shows("Source"))
        // The Backup follows its Version, and says so.
        press(Key.DirectionDown)
        focusedOn("Backup")
    }

    @Test fun badgesOnTheMetadataLineStillShowConfirmedAndLikely() {
        val badges = VersionAttributes(language = likely("en"), quality = fullHd(confirmed = true)).cardBadges()

        assertEquals(listOf("1080p" to Certainty.Confirmed, "EN" to Certainty.Likely), badges.map { it.text to it.certainty })
        open()
        assertTrue(shows("1080p"))
        assertTrue(shows("EN"))
    }

    @Test fun onAShowTheVersionsScreenHoldsTheVersionsOfTheEpisodePlayStarts() {
        val episodeVersion = Version(100, "EN - Breaking Bad S01E06", VersionAttributes(language = likely("en"), source = likely("AMC+")))
        val pilot = EpisodeNumber(1, 6)
        val episodes = ShowEpisodes(listOf(Season(1, listOf(Episode(1, 6, "Crazy Handful of Nothin'", listOf(LanguageGroup("en", listOf(episodeVersion))), emptyList())))), 0, next = pilot)
        val plan = PlayPlan(show, episodeVersion, listOf("en"), listOf(episodeVersion), episode = pilot)
        open(show, details(show, "Breaking Bad") to null, episodes to plan)

        compose.onNodeWithTag("versions action").assert(showing("English · 1 Version"))
        // Past Episodes, which comes first on a Show.
        press(Key.DirectionDown)
        focusedOn("Episodes")
        press(Key.DirectionDown)
        focusedOn("Versions")
        press(Key.DirectionCenter)
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()

        assertTrue(shows("Breaking Bad · S1 E6 · Crazy Handful of Nothin'"))
        assertTrue(shows("AMC+"))
        // The Show's own Versions aren't the Episode's.
        assertFalse(shows("Netflix"))
        back()
        compose.onNodeWithTag("versions action").assertIsFocused()
    }

    @Config(qualifiers = "fr-w960dp-h540dp-mdpi")
    @Test fun inFrenchThePageStillFitsAndTheVersionsLabelIsNeverCut() {
        val quebec = Version(1, "QC - Heat", VersionAttributes(language = likely("fr-CA"), quality = fullHd()))
        val french = LanguageGroup("fr", listOf(quebec) + (2L..12L).map { Version(it, "FR - Heat $it", VersionAttributes(language = likely("fr"))) })
        val title = details().copy(languages = listOf(french))
        open(loaded = title to stopped.copy(version = quebec, versions = listOf(quebec)))

        compose.onNodeWithTag("versions action").assert(showing("Français (Québec) · 1080p · 13 versions"))
        val bottom = compose.onNodeWithTag("versions action").fetchSemanticsNode().boundsInRoot.bottom
        assertTrue(bottom / compose.activity.resources.displayMetrics.density <= 540 - 27)
        // The label keeps its room: the value is what an ellipsis cuts.
        val label = compose.onAllNodesWithText("Versions", useUnmergedTree = true).fetchSemanticsNodes().single()
        val layouts = mutableListOf<TextLayoutResult>()
        label.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        assertFalse(layouts.single().hasVisualOverflow)
        assertTrue(label.config[SemanticsProperties.Text].isNotEmpty())
    }
}
