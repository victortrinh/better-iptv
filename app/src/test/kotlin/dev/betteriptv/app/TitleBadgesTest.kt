package dev.betteriptv.app

import android.content.Context
import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.core.app.ApplicationProvider
import dev.betteriptv.core.Episode
import dev.betteriptv.core.EpisodeNumber
import dev.betteriptv.core.Hdr
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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The badges on the Title page's metadata line name the Version Play starts, as the Versions value
 * does, so the two never disagree (#515); the best Version's only when nothing plays.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-mdpi")
class TitleBadgesTest {
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

    private val movie = TitleId.Numbered(TitleKind.Movie, 16907)
    private val show = TitleId.Numbered(TitleKind.Show, 13879)

    private fun <T> likely(value: T) = Known(value, Provenance.Likely)
    private fun <T> confirmed(value: T) = Known(value, Provenance.Confirmed)

    // The New Look: a 4K Dolby Vision copy listed, a 1080p one the TV plays.
    private val dolbyVision = Version(1, "4K-A+ - The New Look", VersionAttributes(language = likely("en"), quality = likely(Quality.UHD), hdr = likely(Hdr.DolbyVision)))
    private val fullHd = Version(2, "EN - The New Look", VersionAttributes(language = likely("en"), quality = confirmed(Quality.FullHD)))

    private fun details(id: TitleId, versions: List<Version>) = TitleDetails(id, "The New Look", 2024, null, listOf(LanguageGroup("en", versions)), emptyList())

    private fun open(id: TitleId, loaded: Pair<TitleDetails, PlayPlan?>, episodes: Pair<ShowEpisodes, PlayPlan?>? = null) {
        var read by mutableStateOf(false)
        compose.setContent { TitlePage(id, loaded.takeIf { read }, episodes.takeIf { read }, onPlay = { _, _ -> }) }
        compose.waitForIdle()
        read = true
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
    }

    /** How many nodes read exactly [text]: a badge does, the Versions value ("English · 1080p · 2 Versions") doesn't. */
    private fun badges(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().size

    @Test fun aMovieBadgesTheVersionPlayStartsNotABetterOneTheTvCantPlay() {
        open(movie, details(movie, listOf(dolbyVision, fullHd)) to PlayPlan(movie, fullHd, listOf("en"), listOf(fullHd)))

        assertEquals(1, badges("1080p"))
        assertEquals(0, badges("4K"))
        assertEquals(0, badges("DV"))
    }

    @Test fun aShowBadgesTheEpisodeVersionPlayStartsNotItsListing() {
        // The Burning Girls: its Listing says 720p, the Episode Play starts is Confirmed 1080p.
        val listing = Version(13879, "EN - The Burning Girls", VersionAttributes(language = likely("en"), quality = likely(Quality.HD)))
        val episodeVersion = Version(500, "The Burning Girls S01E01", VersionAttributes(language = likely("en"), quality = confirmed(Quality.FullHD)))
        val pilot = EpisodeNumber(1, 1)
        val episodes = ShowEpisodes(listOf(Season(1, listOf(Episode(1, 1, "Pilot", listOf(LanguageGroup("en", listOf(episodeVersion))), emptyList())))), 0, next = pilot)
        val plan = PlayPlan(show, episodeVersion, listOf("en"), listOf(episodeVersion), episode = pilot)

        open(show, details(show, listOf(listing)) to null, episodes to plan)

        assertEquals(1, badges("1080p"))
        assertEquals(0, badges("720p"))
    }

    @Test fun withNothingToPlayTheBestVersionIsBadged() {
        open(movie, details(movie, listOf(dolbyVision, fullHd)) to null)

        assertEquals(1, badges("4K"))
        assertEquals(1, badges("DV"))
    }
}
