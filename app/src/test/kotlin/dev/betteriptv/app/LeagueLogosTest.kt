package dev.betteriptv.app

import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import coil3.asImage
import coil3.decode.DataSource
import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import coil3.request.ImageResult
import coil3.request.SuccessResult
import dev.betteriptv.core.Game
import dev.betteriptv.core.GameFeeds
import dev.betteriptv.core.GameState
import dev.betteriptv.core.GameTeam
import dev.betteriptv.core.HomeRow
import dev.betteriptv.core.League
import org.junit.After
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
import java.time.Instant

/**
 * League logos on Live (#350): a League's row heading shows its logo before its name, or the name
 * alone when the logo can't load; the cards of the rows that mix Leagues (Live now, Today) carry it
 * small at the top left, a League's own row's don't. Images come from a stand-in loader that has
 * every URL but the [broken] ones.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-mdpi")
class LeagueLogosTest {
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

    private val broken = mutableSetOf<String>()

    private fun images() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val loader = ImageLoader.Builder(context).components {
            add(object : Interceptor {
                override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
                    val url = chain.request.data.toString()
                    return if (url in broken) ErrorResult(null, chain.request, IllegalStateException("404 $url"))
                    else SuccessResult(ColorDrawable(Color.WHITE).asImage(), chain.request, DataSource.NETWORK)
                }
            })
        }.build()
        @OptIn(DelicateCoilApi::class)
        SingletonImageLoader.setUnsafe(loader)
    }

    @OptIn(DelicateCoilApi::class)
    @After fun resetImages() = SingletonImageLoader.reset()

    private fun team(name: String, short: String) = GameTeam(name, short, short.take(3).uppercase(), listOf(name, short), null)
    private val game = Game("nhl", "1", Instant.now(), GameState.Live, home = team("Toronto Maple Leafs", "Maple Leafs"), away = team("Montreal Canadiens", "Canadiens"), broadcasters = emptyList())

    @Test fun aLeaguesRowHeadingShowsItsLogoBeforeItsName() {
        images()
        compose.setContent { HomeRow.LeagueGames(League.Nhl, emptyList()).Heading() }

        val logo = compose.onNodeWithTag("heading-logo", useUnmergedTree = true).assertIsDisplayed().getBoundsInRoot()
        val name = compose.onNodeWithText("NHL", useUnmergedTree = true).assertIsDisplayed().getBoundsInRoot()
        assertTrue(logo.right <= name.left)
    }

    @Test fun aLogoThatCantLoadLeavesTheHeadingAsPlainText() {
        broken += League.Nhl.logo
        images()
        compose.setContent { HomeRow.LeagueGames(League.Nhl, emptyList()).Heading() }

        compose.waitUntil { compose.onAllNodesWithTag("heading-logo", useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("NHL", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun otherRowsHeadingsHaveNoLogo() {
        images()
        compose.setContent { HomeRow.LiveNow(listOf(GameFeeds(game, emptyList()))).Heading() }

        compose.onNodeWithText("Live now", useUnmergedTree = true).assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithTag("heading-logo", useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    @Test fun onlyLiveNowAndTodayMixLeagues() {
        val games = listOf(GameFeeds(game, emptyList()))
        assertTrue(HomeRow.LiveNow(games).mixesLeagues)
        assertTrue(HomeRow.Today(games).mixesLeagues)
        assertFalse(HomeRow.LeagueGames(League.Nhl, games).mixesLeagues)
    }

    @Test fun aCardInARowThatMixesLeaguesCarriesTheLeaguesLogoSmallAtTheTopLeft() {
        images()
        compose.setContent { GameCard(GameFeeds(game, emptyList()), onOpen = {}, league = true) }

        val badge = compose.onNodeWithTag("league-badge", useUnmergedTree = true).assertIsDisplayed().getBoundsInRoot()
        val away = compose.onNodeWithTag("away", useUnmergedTree = true).getBoundsInRoot()
        // At the card's top left, smaller than a team's logo.
        assertTrue(badge.left <= away.left && badge.top <= away.top)
        assertTrue(badge.bottom - badge.top < away.bottom - away.top)
    }

    @Test fun aCardInALeaguesOwnRowHasNoLeagueLogo() {
        images()
        compose.setContent { GameCard(GameFeeds(game, emptyList()), onOpen = {}) }

        compose.onNodeWithTag("away", useUnmergedTree = true).assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithTag("league-badge", useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    @Test fun aCardsLeagueLogoThatCantLoadIsLeftOut() {
        broken += League.Nhl.logo
        images()
        compose.setContent { GameCard(GameFeeds(game, emptyList()), onOpen = {}, league = true) }

        compose.waitUntil { compose.onAllNodesWithTag("league-badge", useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("Canadiens", useUnmergedTree = true).assertIsDisplayed()
    }
}
