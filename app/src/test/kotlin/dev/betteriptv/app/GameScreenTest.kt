package dev.betteriptv.app

import android.content.Context
import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import dev.betteriptv.core.Channel
import dev.betteriptv.core.ChannelVariant
import dev.betteriptv.core.DeviceProfile
import dev.betteriptv.core.Feed
import dev.betteriptv.core.FeedKind
import dev.betteriptv.core.Game
import dev.betteriptv.core.GameFeeds
import dev.betteriptv.core.GameState
import dev.betteriptv.core.GameTeam
import dev.betteriptv.core.Library
import dev.betteriptv.core.PlayPlan
import dev.betteriptv.core.Quality
import dev.betteriptv.core.Version
import dev.betteriptv.core.VersionAttributes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Instant

/** The Game page: Play focused first and naming the best Feed, which it plays; the Feeds listed with their variants. [Library] is stood in for by [planFor]. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h1600dp-mdpi")
class GameScreenTest {
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
    val compose = createComposeRule()

    private fun team(name: String, short: String) = GameTeam(name, short, short.take(3).uppercase(), listOf(name, short), null)
    private val game = Game("nhl", "1", Instant.parse("2026-11-01T18:00:00Z"), GameState.Live, home = team("Toronto Maple Leafs", "Maple Leafs"), away = team("Montreal Canadiens", "Canadiens"), broadcasters = emptyList())
    private val event = Channel(
        "us: canadiens vs maple leafs", "Canadiens vs Maple Leafs", "US", null, false,
        listOf(ChannelVariant(11, "US: Canadiens vs Maple Leafs SD", Quality.SD, false), ChannelVariant(12, "US: Canadiens vs Maple Leafs ᴴᴰ", Quality.HD, false)),
    )
    private val tsn = Channel("ca: tsn", "TSN", "CA", null, false, listOf(ChannelVariant(21, "CA: TSN", null, false)))
    private val stick = DeviceProfile(maxHeight = 1080, hdr = emptySet())

    /** What watching a Channel starts, as [Library.channelPlayPlan] shapes it: HD before SD, each variant as TS with the chain after it as Backups. */
    private fun planFor(channel: Channel, device: DeviceProfile): PlayPlan {
        assertEquals(stick, device)
        val ranked = channel.variants.sortedByDescending { it.quality?.ordinal ?: -1 }
        fun chain(from: Int) = ranked.drop(from).flatMap { variant -> listOf("ts", "m3u8").map { Version(variant.id, variant.name, VersionAttributes(), liveFormat = it) } }
        val versions = ranked.indices.map { chain(it).let { chain -> chain.first().copy(backups = chain.drop(1)) } }
        return PlayPlan(null, versions.first(), listOf("en"), versions)
    }

    private fun open(entry: GameFeeds, onPlay: (PlayPlan, String) -> Unit = { _, _ -> }) =
        compose.setContent { GameScreen(entry, deviceProfile = { stick }, planFor = ::planFor, onPlay = onPlay) }

    @Test fun playTakesFirstFocusNamesTheBestFeedAndPlaysItsVariantsBestFirst() {
        var played: Pair<PlayPlan, String>? = null
        open(GameFeeds(game, listOf(Feed(event, FeedKind.EventChannel, "en"), Feed(tsn, FeedKind.Broadcaster, "en", "TSN"))), onPlay = { plan, name -> played = plan to name })

        compose.onNodeWithTag("play").assertIsFocused()
        compose.onNodeWithTag("play-label").assertTextContains("Canadiens vs Maple Leafs · English")
        compose.onNodeWithTag("game-header").assertTextContains("NHL · Live · 2 Feeds")
        compose.onNodeWithTag("play").performClick()
        compose.waitUntil { played != null }
        val (plan, name) = played!!
        assertEquals("Canadiens at Maple Leafs", name)
        // The HD variant leads, the SD one its Backup, as watching the Channel would.
        assertEquals(12L, plan.version.listingId)
        assertEquals(listOf(12L, 12L, 11L, 11L), listOf(plan.version.listingId) + plan.version.backups.map { it.listingId })
    }

    @Test fun theFeedsAreListedWithTheirVariantsAndAVariantRowPlaysFromIt() {
        var played: PlayPlan? = null
        open(GameFeeds(game, listOf(Feed(event, FeedKind.EventChannel, "en"), Feed(tsn, FeedKind.Broadcaster, "en", "TSN"))), onPlay = { plan, _ -> played = plan })

        compose.onNodeWithText("English · 2").assertExists()
        compose.onNodeWithText("Event channel").assertExists()
        compose.onNodeWithText("Broadcaster · TSN").assertExists()
        compose.waitUntil { compose.onAllNodesWithText("US: Canadiens vs Maple Leafs SD").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("variant 11").assert(hasText("US: Canadiens vs Maple Leafs SD"))
        compose.onNodeWithTag("variant 12").assertExists()
        compose.onNodeWithTag("variant 21").assertExists()
        compose.onNodeWithTag("variant 11").performClick()
        compose.waitUntil { played != null }
        assertEquals(11L, played!!.version.listingId)
    }

    @Test fun withNoFeedPlayIsDisabledAndSaysNoStreamWasFound() {
        var played = 0
        open(GameFeeds(game, emptyList()), onPlay = { _, _ -> played++ })

        compose.onNodeWithTag("play").assertIsFocused()
        compose.onNodeWithTag("play").performClick()
        compose.waitForIdle()
        assertEquals(0, played)
        compose.onNodeWithTag("play-label").assertTextContains("No stream found")
        compose.onNodeWithTag("game-header").assertTextContains("NHL · Live · No stream found")
        assertNull(compose.onAllNodesWithText("Feeds").fetchSemanticsNodes().firstOrNull())
    }
}
