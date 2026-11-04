package dev.betteriptv.app

import android.content.Context
import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.height
import androidx.test.core.app.ApplicationProvider
import dev.betteriptv.app.ui.components.TeamTint
import dev.betteriptv.core.Channel
import dev.betteriptv.core.ChannelVariant
import dev.betteriptv.core.DeviceProfile
import dev.betteriptv.core.Feed
import dev.betteriptv.core.FeedKind
import dev.betteriptv.core.Game
import dev.betteriptv.core.GameFeeds
import dev.betteriptv.core.GameEvent
import dev.betteriptv.core.GameFight
import dev.betteriptv.core.GameFighter
import dev.betteriptv.core.GameSession
import dev.betteriptv.core.GameState
import dev.betteriptv.core.GameTeam
import dev.betteriptv.core.Library
import dev.betteriptv.core.PlayPlan
import dev.betteriptv.core.Quality
import dev.betteriptv.core.Replay
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
import java.time.Instant
import java.time.LocalDate

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

    private fun open(entry: GameFeeds, onPlay: (PlayPlan, String, Feed) -> Unit = { _, _, _ -> }) =
        compose.setContent { GameScreen(entry, deviceProfile = { stick }, planFor = ::planFor, onPlay = onPlay) }

    @Test fun playTakesFirstFocusNamesTheBestFeedAndPlaysItsVariantsBestFirst() {
        var played: Triple<PlayPlan, String, Feed>? = null
        open(GameFeeds(game, listOf(Feed(event, FeedKind.EventChannel, "en"), Feed(tsn, FeedKind.Broadcaster, "en", "TSN"))), onPlay = { plan, name, feed -> played = Triple(plan, name, feed) })

        compose.onNodeWithTag("play").assertIsFocused()
        compose.onNodeWithTag("play-label").assertTextContains("Canadiens vs Maple Leafs · English")
        compose.onNodeWithTag("game-header").assertTextContains("NHL · Live · 2 Feeds")
        compose.onNodeWithTag("play").performClick()
        compose.waitUntil { played != null }
        val (plan, name, feed) = played!!
        assertEquals("Canadiens at Maple Leafs", name)
        assertEquals(event, feed.channel)
        // The HD variant leads, the SD one its Backup, as watching the Channel would.
        assertEquals(12L, plan.version.listingId)
        assertEquals(listOf(12L, 12L, 11L, 11L), listOf(plan.version.listingId) + plan.version.backups.map { it.listingId })
    }

    @Test fun theFeedsAreListedWithTheirVariantsAndAVariantRowPlaysFromIt() {
        var played: PlayPlan? = null
        open(GameFeeds(game, listOf(Feed(event, FeedKind.EventChannel, "en"), Feed(tsn, FeedKind.Broadcaster, "en", "TSN"))), onPlay = { plan, _, _ -> played = plan })

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

    @Test fun aFinishedGamesReplayShowsAsARowNamedAndDatedWhichPlaysItsListingsVersion() {
        val over = Game("nhl", "1", Instant.parse("2025-11-10T00:00:00Z"), GameState.Over, home = game.home, away = game.away, broadcasters = emptyList())
        val replay = Replay(77, "SOC - Canadiens at Toronto Maple Leafs 09.11.25", "SOCCER HOCKEY", null, "Canadiens", "Toronto Maple Leafs", LocalDate.of(2025, 11, 9), 2025)
        val version = Version(77, replay.name, VersionAttributes())
        val plan = PlayPlan(TitleId.Unmatched(TitleKind.Movie, 77), version, listOf("en"), listOf(version))
        var played: Pair<PlayPlan, String>? = null
        compose.setContent {
            GameScreen(GameFeeds(over, emptyList()), deviceProfile = { stick }, planFor = ::planFor, onPlay = { _, _, _ -> }, replayFor = { replay }, replayPlanFor = { plan }, onPlayReplay = { p, n -> played = p to n })
        }

        compose.waitUntil { compose.onAllNodesWithTag("replay").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("replay-title").assertTextContains("Replay")
        compose.onNodeWithText("Canadiens at Toronto Maple Leafs 09.11.25").assertExists()
        compose.onNodeWithText("November 9, 2025").assertExists()
        compose.onNodeWithTag("replay").performClick()
        compose.waitUntil { played != null }
        assertEquals(77L, played!!.first.version.listingId)
        assertEquals("Canadiens at Maple Leafs", played!!.second)
    }

    @Test fun thePageLiesOnTheTeamsColoursFullScreenAwayLeftAndHomeRight() {
        val coloured = Game(
            "nhl", "1", Instant.parse("2026-11-01T18:00:00Z"), GameState.Live,
            home = GameTeam("Toronto Maple Leafs", "Maple Leafs", "TOR", listOf("Toronto Maple Leafs"), null, color = 0xFF00205B.toInt()),
            away = GameTeam("Montreal Canadiens", "Canadiens", "MTL", listOf("Montreal Canadiens"), null, color = 0xFFAF1E2D.toInt()),
            broadcasters = emptyList(),
        )
        open(GameFeeds(coloured, listOf(Feed(event, FeedKind.EventChannel, "en"))))

        val root = compose.onRoot().getBoundsInRoot()
        val away = compose.onNodeWithTag("away-colour", useUnmergedTree = true).assert(SemanticsMatcher.expectValue(TeamTint, Color(0xFFAF1E2D))).getBoundsInRoot()
        val home = compose.onNodeWithTag("home-colour", useUnmergedTree = true).assert(SemanticsMatcher.expectValue(TeamTint, Color(0xFF00205B))).getBoundsInRoot()
        assertEquals(root.left, away.left)
        assertEquals(away.right, home.left)
        assertEquals(root.right, home.right)
        assertEquals(root.height, away.height)
        // Focus stays on Play, white as ever.
        compose.onNodeWithTag("play").assertIsFocused()
    }

    @Test fun aGameWhoseTeamsHaveNoColoursKeepsTheBareGround() {
        open(GameFeeds(game, listOf(Feed(event, FeedKind.EventChannel, "en"))))

        compose.onNodeWithTag("play").assertIsFocused()
        compose.onNodeWithTag("away-colour", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("home-colour", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun anF1SessionsPageNamesItsGrandPrixAndSessionWithTheLeaguesLogoInPlaceOfTheTeams() {
        val race = Game(
            "f1", "405", Instant.parse("2026-11-01T17:00:00Z"), GameState.Live, home = null, away = null, broadcasters = emptyList(),
            event = GameEvent("MSC Cruises São Paulo Grand Prix", "MSC Cruises São Paulo GP", GameSession.Race, listOf("São Paulo"), null),
        )
        open(GameFeeds(race, listOf(Feed(tsn, FeedKind.EventChannel, "en"))))

        compose.onNodeWithTag("play").assertIsFocused()
        compose.onNodeWithTag("game-header").assertTextContains("F1 · Live · 1 Feed")
        compose.onNodeWithText("MSC Cruises São Paulo GP · Race").assertExists()
        compose.onNodeWithText("MSC Cruises São Paulo Grand Prix · Race").assertExists()
        // The League's name stands in for its logo while it hasn't loaded.
        compose.onNodeWithText("F1", useUnmergedTree = true).assertExists()
    }

    @Test fun aUfcCardsPageNamesItsMainEventWithBothFightersInPlaceOfTheTeams() {
        val card = Game(
            "ufc", "600061400", Instant.parse("2026-11-01T22:00:00Z"), GameState.Live, home = null, away = null, broadcasters = emptyList(),
            fight = GameFight("UFC 334: Gane vs. Hokit", "UFC 334", Instant.parse("2026-11-02T02:00:00Z"), listOf(GameFighter("Ciryl Gane", "Gane", listOf("Gane"), null), GameFighter("Josh Hokit", "Hokit", listOf("Hokit"), null))),
        )
        open(GameFeeds(card, listOf(Feed(tsn, FeedKind.EventChannel, "en"))))

        compose.onNodeWithTag("play").assertIsFocused()
        compose.onNodeWithTag("game-header").assertTextContains("UFC 334 · Live · Main card", substring = true)
        compose.onNodeWithText("Gane vs. Hokit").assertExists()
        compose.onNodeWithText("UFC 334: Gane vs. Hokit").assertExists()
        // Each fighter's surname stands in for their headshot while it hasn't loaded, the first-named left.
        val gane = compose.onNodeWithText("Gane", useUnmergedTree = true).assertExists().getBoundsInRoot()
        val hokit = compose.onNodeWithText("Hokit", useUnmergedTree = true).assertExists().getBoundsInRoot()
        assertTrue(gane.right <= hokit.left)
    }

    @Test fun withNoFeedPlayIsDisabledAndSaysNoStreamWasFound() {
        var played = 0
        open(GameFeeds(game, emptyList()), onPlay = { _, _, _ -> played++ })

        compose.onNodeWithTag("play").assertIsFocused()
        compose.onNodeWithTag("play").performClick()
        compose.waitForIdle()
        assertEquals(0, played)
        compose.onNodeWithTag("play-label").assertTextContains("No stream found")
        compose.onNodeWithTag("game-header").assertTextContains("NHL · Live · No stream found")
        assertNull(compose.onAllNodesWithText("Feeds").fetchSemanticsNodes().firstOrNull())
        // Without a Replay in the catalog, no Replay row either.
        assertNull(compose.onAllNodesWithTag("replay-title").fetchSemanticsNodes().firstOrNull())
    }

    @Test fun aDroppedFeedListsLastInItsOwnGroupMarkedAndPlayStartsTheNextOne() {
        var played: Feed? = null
        val rds = Channel("ca fr: rds", "RDS", "CA FR", null, false, listOf(ChannelVariant(31, "CA FR: RDS", null, false)))
        open(
            GameFeeds(game, listOf(Feed(tsn, FeedKind.Broadcaster, "en", "TSN"), Feed(rds, FeedKind.Broadcaster, "fr", "RDS"), Feed(event, FeedKind.EventChannel, "en", dropped = true))),
            onPlay = { _, _, feed -> played = feed },
        )

        compose.onNodeWithTag("play-label").assertTextContains("TSN · English")
        // The dropped Feed is not counted.
        compose.onNodeWithTag("game-header").assertTextContains("NHL · Live · 2 Feeds")
        compose.onNodeWithText("English · 1").assertExists()
        compose.onNodeWithText("Français · 1").assertExists()
        compose.onNodeWithText("Not this game · 1").assertExists()
        compose.onNodeWithText("Event channel · Not this game").assertExists()
        // The groups come in rank order: the dropped one after the languages.
        val headings = listOf("English · 1", "Français · 1", "Not this game · 1").map { compose.onNodeWithText(it).fetchSemanticsNode().positionInRoot.y }
        assertEquals(headings, headings.sorted())
        compose.onNodeWithTag("play").performClick()
        compose.waitUntil { played != null }
        assertEquals(tsn, played!!.channel)
    }

    @Test fun withEveryFeedDroppedPlayIsDisabledAndTheFeedsStayListed() {
        var played = 0
        open(GameFeeds(game, listOf(Feed(event, FeedKind.EventChannel, "en", dropped = true))), onPlay = { _, _, _ -> played++ })

        compose.onNodeWithTag("play").performClick()
        compose.waitForIdle()
        assertEquals(0, played)
        compose.onNodeWithTag("play-label").assertTextContains("No stream found")
        compose.onNodeWithTag("game-header").assertTextContains("NHL · Live · No stream found")
        compose.onNodeWithText("Not this game · 1").assertExists()
    }
}
