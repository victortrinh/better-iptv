package dev.betteriptv.app

import android.content.Context
import dev.betteriptv.app.ui.components.CardInfo
import dev.betteriptv.core.GameTeam
import dev.betteriptv.core.League
import dev.betteriptv.core.LeagueReplay
import dev.betteriptv.core.Replay
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.LocalDate

/** How a Sports replay is named on its card and under its rail: the date in its name, and the teams as a Game's. */
@RunWith(RobolectricTestRunner::class)
class ReplayLabelsTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun team(name: String, short: String) = GameTeam(name, short, short.take(3).uppercase(), listOf(name, short), null)
    private fun replay(name: String, date: LocalDate?, year: Int? = date?.year) =
        Replay(77, name, "SOCCER HOCKEY", null, "Boston Bruins", "Toronto Maple Leafs", date, year)

    @Test fun theCaptionIsTheDateShortWithTheYearWhenItIsntThisYears() {
        val replay = replay("SOC - Boston Bruins at Toronto Maple Leafs 09.11.25", LocalDate.of(2025, 11, 9))
        assertEquals("Nov 9", context.replayCaption(replay, today = LocalDate.of(2025, 12, 1)))
        assertEquals("Nov 9, 2025", context.replayCaption(replay, today = LocalDate.of(2026, 11, 1)))
    }

    @Test fun aYearAloneAndNoDateAtAllReadAsTheyAre() {
        assertEquals("2025", context.replayCaption(replay("SOC - UFC 321 Aspinall vs. Gane (2025)", null, 2025)))
        assertEquals("Replay", context.replayCaption(replay("SOC - Saracens v Northampton Saints", null)))
    }

    @Test fun theInfoUnderTheRailNamesTheLeagueAndTeamsThenTheDateInFull() {
        val entry = LeagueReplay(replay("SOC - Boston Bruins at Toronto Maple Leafs 09.11.25", LocalDate.of(2025, 11, 9)), League.Nhl, away = team("Boston Bruins", "Bruins"), home = team("Toronto Maple Leafs", "Maple Leafs"))
        assertEquals(CardInfo.Game("NHL · Bruins at Maple Leafs", "November 9, 2025"), context.replayInfo(entry))
        assertEquals("Bruins at Maple Leafs", context.matchup(entry))
        assertEquals("Boston Bruins at Toronto Maple Leafs 09.11.25", entry.replay.title)
    }
}
