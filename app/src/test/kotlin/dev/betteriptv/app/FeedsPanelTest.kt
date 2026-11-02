package dev.betteriptv.app

import android.content.Context
import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import dev.betteriptv.core.Channel
import dev.betteriptv.core.ChannelVariant
import dev.betteriptv.core.Feed
import dev.betteriptv.core.FeedKind
import dev.betteriptv.core.Game
import dev.betteriptv.core.GameFeeds
import dev.betteriptv.core.GameState
import dev.betteriptv.core.GameTeam
import dev.betteriptv.core.Quality
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

/**
 * The player's Feed list, which the menu key opens while a Game's Feed plays: every Feed as the Game
 * page ranks it, the one playing noted, picking another switching to it, and "Not this game" last.
 * The panel alone: the player needs a Library, which the app's unit tests can't open.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h1600dp-mdpi")
class FeedsPanelTest {
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
    private val event = Channel("us: canadiens vs maple leafs", "Canadiens vs Maple Leafs", "US", null, false, listOf(ChannelVariant(12, "US: Canadiens vs Maple Leafs ᴴᴰ", Quality.HD, false)))
    private val tsn = Channel("ca: tsn", "TSN", "CA", null, false, listOf(ChannelVariant(21, "CA: TSN", null, false)))
    private val rds = Channel("ca fr: rds", "RDS", "CA FR", null, false, listOf(ChannelVariant(31, "CA FR: RDS", null, false)))
    private val feeds = listOf(Feed(event, FeedKind.EventChannel, "en"), Feed(tsn, FeedKind.Broadcaster, "en", "TSN"), Feed(rds, FeedKind.Broadcaster, "fr", "RDS", dropped = true))

    private class Picked {
        var feed: Feed? = null
        var notThisGame = 0
        var closed = 0
    }

    private fun open(playing: Feed): Picked {
        val picked = Picked()
        compose.setContent {
            FeedsPanel(
                GameFeeds(game, feeds), playing, engine = null,
                onTryOtherPlayer = {},
                onPick = { picked.feed = it },
                onNotThisGame = { picked.notThisGame++ },
                onClose = { picked.closed++ },
            )
        }
        return picked
    }

    @Test fun everyFeedIsListedInRankOrderThePlayingOneNotedAndPickingAnotherSwitchesToIt() {
        val picked = open(playing = feeds[0])

        compose.onNodeWithTag("feed us: canadiens vs maple leafs").assertIsFocused()
        compose.onNodeWithTag("feed us: canadiens vs maple leafs").assert(hasText("Canadiens vs Maple Leafs")).assert(hasText("Playing · Event channel · English"))
        compose.onNodeWithTag("feed ca: tsn").assert(hasText("Broadcaster · TSN · English"))
        compose.onNodeWithTag("feed ca fr: rds").assert(hasText("Broadcaster · RDS · Not this game · Français"))
        val rows = listOf("feed us: canadiens vs maple leafs", "feed ca: tsn", "feed ca fr: rds", "not-this-game").map { compose.onNodeWithTag(it).fetchSemanticsNode().positionInRoot.y }
        assertEquals(rows, rows.sorted())

        // Picking the one playing just closes the list.
        compose.onNodeWithTag("feed us: canadiens vs maple leafs").performClick()
        assertNull(picked.feed)
        assertEquals(1, picked.closed)
        compose.onNodeWithTag("feed ca: tsn").performClick()
        assertEquals(tsn, picked.feed!!.channel)
    }

    @Test fun notThisGameDropsThePlayingFeedAndSaysWhatComesNext() {
        val picked = open(playing = feeds[0])
        compose.onNodeWithTag("not-this-game").assert(hasText("Not this game")).assert(hasText("Drops this Feed for this Game and plays the next"))
        compose.onNodeWithTag("not-this-game").performClick()
        assertEquals(1, picked.notThisGame)
    }

    @Test fun withNoFeedLeftAfterItNotThisGameSaysTheGamePageReturns() {
        // TSN plays and the Event channel was dropped already: nothing would be left.
        compose.setContent {
            FeedsPanel(
                GameFeeds(game, listOf(Feed(tsn, FeedKind.Broadcaster, "en", "TSN"), Feed(event, FeedKind.EventChannel, "en", dropped = true))), Feed(tsn, FeedKind.Broadcaster, "en", "TSN"),
                engine = null, onTryOtherPlayer = {}, onPick = {}, onNotThisGame = {}, onClose = {},
            )
        }
        compose.onNodeWithTag("not-this-game").assert(hasText("Drops this Feed for this Game; none is left, so the Game page returns"))
        compose.onNodeWithText("Feeds").assertExists()
    }
}
