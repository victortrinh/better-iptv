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
import androidx.test.core.app.ApplicationProvider
import dev.betteriptv.core.Episode
import dev.betteriptv.core.EpisodeNumber
import dev.betteriptv.core.Known
import dev.betteriptv.core.LanguageGroup
import dev.betteriptv.core.PlayPlan
import dev.betteriptv.core.Provenance
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
 * The Episode picker (#347): a Show's Episodes action says where the Viewer is, OK opens the picker
 * on the next Episode to watch, in its season; Up and Down in the seasons change the Episodes, Left
 * from an Episode returns to its season, OK plays it, and Back returns to the Episodes action. The
 * Library is stood in for by the states [TitlePage] is given, and the navigation by a host that
 * does what the main activity does.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-mdpi")
class EpisodePickerTest {
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

    private val show = TitleId.Numbered(TitleKind.Show, 123250)
    private fun likely(value: String) = Known(value, Provenance.Likely)
    private fun version(id: Long, language: String) = Version(id, "${language.uppercase()} - Love Is Blind", VersionAttributes(language = likely(language)))
    private fun both(id: Long) = listOf(LanguageGroup("en", listOf(version(id, "en"))), LanguageGroup("fr", listOf(version(id + 1000, "fr"))))

    /**
     * Love is Blind: three seasons of 14, 12 and 10 Episodes in English and French. Season 2's E3 is in
     * English alone, its E4 carries the Provider's still and runtime; the Viewer watched up to S2 E3 and
     * is partway into E4.
     */
    private fun seasons(): List<Season> {
        val counts = mapOf(1 to 14, 2 to 12, 3 to 10)
        return counts.map { (season, count) ->
            Season(season, (1..count).map { number ->
                val id = season * 100L + number
                when {
                    season == 2 && number == 3 -> Episode(season, number, "Pods", listOf(LanguageGroup("en", listOf(version(id, "en")))), emptyList(), progress = 1f, watched = true)
                    season == 1 || (season == 2 && number < 3) -> Episode(season, number, "Episode name $season.$number", both(id), emptyList(), progress = 1f, watched = true)
                    season == 2 && number == 4 -> Episode(season, number, "The Reveal", both(id), emptyList(), progress = 0.4f, still = "https://stills.example/reveal.jpg", runtimeMinutes = 52)
                    else -> Episode(season, number, "Episode name $season.$number", both(id), emptyList())
                }
            })
        }
    }

    private val details = TitleDetails(show, "Love Is Blind", 2020, null, both(1), emptyList(), description = "Singles hoping to find love.")

    private var played: Episode? = null

    private fun open(next: EpisodeNumber? = EpisodeNumber(2, 4), seasons: List<Season> = seasons()) {
        val episodes = ShowEpisodes(seasons, 0, next = next)
        val plan = next?.let { PlayPlan(show, version(1, "en"), listOf("en"), listOf(version(1, "en")), from = 60_000, length = 600_000, episode = it) }
        var read by mutableStateOf(false)
        compose.setContent {
            var section by remember { mutableStateOf<TitleSection?>(null) }
            var returnedFrom by remember { mutableStateOf<TitleSection?>(null) }
            BackHandler(enabled = section != null) {
                returnedFrom = section
                section = null
            }
            TitlePage(
                show, null, (details to null).takeIf { read }, (episodes to plan).takeIf { read }, onPlay = { _, _ -> },
                section = section,
                returnedFrom = returnedFrom,
                onSection = {
                    section = it
                    returnedFrom = null
                },
                onEpisode = { episode, _ -> played = episode },
            )
        }
        compose.waitForIdle()
        read = true
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
    }

    private fun press(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
    }

    private fun back() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
    }

    private fun shows(text: String) = compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
    private fun showing(text: String): SemanticsMatcher = hasText(text, substring = true) or hasAnyDescendant(hasText(text, substring = true))
    private fun focusedOn(text: String) = compose.onNode(isFocused()).assert(showing(text))

    /** Down from Play (and Restart, as the Viewer stopped partway) to Episodes, then OK. */
    private fun openPicker() {
        compose.onNodeWithTag("play").assertIsFocused()
        repeat(2) { press(Key.DirectionDown) }
        compose.onNodeWithTag("episodes action").assertIsFocused()
        press(Key.DirectionCenter)
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
    }

    @Test fun theEpisodesActionSaysWhereTheViewerIsAndThePageNoLongerListsEpisodes() {
        open()

        compose.onNodeWithTag("episodes action").assert(showing("Season 2 · E4"))
        // The header names the Episode Play starts; no Episode row is on the page.
        assertTrue(shows("S2 E4 · The Reveal"))
        assertFalse(shows("Pods"))
        assertFalse(shows("Episode name"))
    }

    @Test fun neverPlayedTheActionNamesTheFirstSeasonAndItsEpisodeCount() {
        val unplayed = seasons().map { season -> season.copy(episodes = season.episodes.map { it.copy(progress = null, watched = false) }) }
        open(EpisodeNumber(1, 1), unplayed)

        compose.onNodeWithTag("episodes action").assert(showing("Season 1 · 14 Episodes"))
    }

    @Test fun thePickerOpensOnTheNextEpisodeInItsSeason() {
        open()
        openPicker()

        focusedOn("4. The Reveal")
        // Season 2's Episodes are listed, the one before it peeking above.
        assertTrue(shows("3. Pods"))
        assertFalse("not season 1's", shows("Episode name 1.1"))
        // An Episode's runtime comes with it, from the Provider's show details (#473).
        assertTrue(shows("52 min"))
    }

    @Test fun downAndUpTheSeasonsChangeTheEpisodesAndLeftReturnsToTheSeason() {
        open()
        openPicker()

        // Left from an Episode lands on its season, not the row beside it.
        press(Key.DirectionDown)
        focusedOn("5. Episode name 2.5")
        press(Key.DirectionLeft)
        focusedOn("Season 2")

        press(Key.DirectionDown)
        focusedOn("Season 3")
        assertTrue(shows("1. Episode name 3.1"))
        assertFalse(shows("The Reveal"))
        press(Key.DirectionUp)
        press(Key.DirectionUp)
        focusedOn("Season 1")
        assertTrue(shows("1. Episode name 1.1"))
        assertFalse(shows("Episode name 3.1"))

        // Right goes into the season's Episodes, from its first.
        press(Key.DirectionRight)
        focusedOn("1. Episode name 1.1")
        press(Key.DirectionLeft)
        focusedOn("Season 1")
    }

    @Test fun anEpisodeNamesItsLanguagesOnlyWhenTheyDifferAndThePickerListsTheProvidersEpisodesOnly() {
        open()
        openPicker()

        // Every other Episode is in English and French: only Pods, in English alone, says so.
        assertEquals(1, compose.onAllNodesWithText("English", substring = true).fetchSemanticsNodes().size)
        compose.onAllNodesWithText("3. Pods", substring = true).fetchSemanticsNodes().single()
        assertFalse(shows("Français"))
        assertFalse(shows("Language not named"))
        // Season 2 has the 12 Episodes the Provider has, and nothing promised for later.
        repeat(8) { press(Key.DirectionDown) }
        focusedOn("12. Episode name 2.12")
        assertFalse(shows("Available"))
    }

    @Test fun okOnAnEpisodePlaysItAndBackReturnsToTheEpisodesAction() {
        open()
        openPicker()

        press(Key.DirectionUp)
        focusedOn("3. Pods")
        press(Key.DirectionCenter)
        assertEquals(EpisodeNumber(2, 3), played?.id)

        back()
        compose.onNodeWithTag("episodes action").assertIsFocused()
        assertFalse(shows("Pods"))
    }

    @Config(qualifiers = "fr-w960dp-h540dp-mdpi")
    @Test fun inFrenchTheActionAndThePickerSpeakFrench() {
        open()

        compose.onNodeWithTag("episodes action").assert(showing("Saison 2 · É4"))
        openPicker()
        focusedOn("4. The Reveal")
        assertTrue(shows("Saison 1"))
        assertTrue(shows("English"))
    }
}
