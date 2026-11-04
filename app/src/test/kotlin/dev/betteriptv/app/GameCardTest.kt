package dev.betteriptv.app

import android.content.Context
import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.requestFocus
import androidx.test.core.app.ApplicationProvider
import dev.betteriptv.app.ui.components.EventGameCard
import dev.betteriptv.app.ui.components.GameCard
import dev.betteriptv.app.ui.components.TeamTint
import dev.betteriptv.core.Game
import dev.betteriptv.core.GameFeeds
import dev.betteriptv.core.GameState
import dev.betteriptv.core.GameTeam
import org.junit.Assert.assertEquals
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

/** A Game's card: two logos or the short names over the teams' colours, "Live" or the start, and a disabled card for a Game with no Feed. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-mdpi")
class GameCardTest {
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

    @Test fun withoutLogosTheCardShowsTheShortNamesVisitorsLeftAndItsCaption() {
        var opened = 0
        compose.setContent { GameCard(away = null, home = null, awayName = "Canadiens", homeName = "Maple Leafs", caption = "Live", onClick = { opened++ }) }

        val away = compose.onNodeWithText("Canadiens", useUnmergedTree = true).assertIsDisplayed().getBoundsInRoot()
        val home = compose.onNodeWithText("Maple Leafs", useUnmergedTree = true).assertIsDisplayed().getBoundsInRoot()
        assertTrue(away.right <= home.left)
        compose.onNodeWithText("Live").assertIsDisplayed()
        compose.onNodeWithText("Live").performClick()
        assertEquals(1, opened)
    }

    @Test fun aGameWithNoFeedIsDisabledSaysSoAndStillTakesFocus() {
        var opened = 0
        compose.setContent { GameCard(away = null, home = null, awayName = "Flames", homeName = "Oilers", caption = "7:00 PM", onClick = { opened++ }, disabled = "No stream found") }

        compose.onNodeWithText("No stream found").assertIsDisplayed()
        compose.onNodeWithText("7:00 PM").assertIsDisplayed()
        compose.onNodeWithText("7:00 PM").performClick()
        assertEquals(0, opened)
        compose.onNodeWithText("7:00 PM").requestFocus()
        compose.onNodeWithText("7:00 PM").assertIsFocused()
    }

    @Test fun aGamesGlassCarriesTheAwayTeamsColourLeftAndTheHomeTeamsRight() {
        val game = Game(
            "nhl", "1", Instant.parse("2026-11-01T18:00:00Z"), GameState.Live,
            home = team("Toronto Maple Leafs", "Maple Leafs", color = 0xFF00205B.toInt()),
            away = team("Montreal Canadiens", "Canadiens", color = 0xFFAF1E2D.toInt()),
            broadcasters = emptyList(),
        )
        compose.setContent { dev.betteriptv.app.GameCard(GameFeeds(game, emptyList()), onOpen = {}) }

        val away = compose.onNodeWithTag("away-colour", useUnmergedTree = true).assert(tinted(0xFFAF1E2D)).getBoundsInRoot()
        val home = compose.onNodeWithTag("home-colour", useUnmergedTree = true).assert(tinted(0xFF00205B)).getBoundsInRoot()
        assertTrue(away.right <= home.left)
        // Each half is half the card, under the team's logo or short name.
        assertEquals(away.width, home.width)
        val awayName = compose.onNodeWithText("Canadiens", useUnmergedTree = true).getBoundsInRoot()
        assertTrue(awayName.left >= away.left && awayName.right <= away.right)
    }

    @Test fun aBlackTeamColourGivesWayToItsAlternateAndATeamWithNoneLeavesItsHalfClear() {
        val game = Game(
            "nfl", "2", Instant.parse("2026-11-01T18:00:00Z"), GameState.Upcoming,
            home = team("Las Vegas Raiders", "Raiders", color = 0xFF000000.toInt(), alternate = 0xFFA5ACAF.toInt()),
            away = team("Denver Broncos", "Broncos"),
            broadcasters = emptyList(),
        )
        compose.setContent { dev.betteriptv.app.GameCard(GameFeeds(game, emptyList()), onOpen = {}) }

        compose.onNodeWithTag("home-colour", useUnmergedTree = true).assert(tinted(0xFFA5ACAF))
        compose.onNodeWithTag("away-colour", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun aGameWhoseTeamsHaveNoColoursIsPlainGlass() {
        val game = Game("nhl", "3", Instant.parse("2026-11-01T18:00:00Z"), GameState.Live, home = team("Boston Bruins", "Bruins"), away = team("Ottawa Senators", "Senators"), broadcasters = emptyList())
        compose.setContent { dev.betteriptv.app.GameCard(GameFeeds(game, emptyList()), onOpen = {}) }

        compose.onNodeWithText("Senators", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("away-colour", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("home-colour", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun anF1SessionsCardShowsTheLeaguesLogoLeftAndTheGrandPrixAndSessionRight() {
        var opened = 0
        compose.setContent { EventGameCard(logo = null, leagueName = "F1", name = "MSC Cruises São Paulo GP", session = "Race", caption = "Live", onClick = { opened++ }) }

        val league = compose.onNodeWithText("F1", useUnmergedTree = true).assertIsDisplayed().getBoundsInRoot()
        val name = compose.onNodeWithText("MSC Cruises São Paulo GP", useUnmergedTree = true).assertIsDisplayed().getBoundsInRoot()
        assertTrue(league.right <= name.left)
        compose.onNodeWithText("Race", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Live").performClick()
        assertEquals(1, opened)
    }

    private fun team(name: String, short: String, color: Int? = null, alternate: Int? = null) =
        GameTeam(name, short, short.take(3).uppercase(), listOf(name, short), null, color, alternate)

    private fun tinted(argb: Long) = SemanticsMatcher.expectValue(TeamTint, Color(argb))
}
