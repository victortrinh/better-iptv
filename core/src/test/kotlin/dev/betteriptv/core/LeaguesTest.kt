package dev.betteriptv.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The Leagues offered and which are on: the Region's defaults until the Viewer chooses. */
class LeaguesTest {
    @Test fun `fifteen Leagues are offered, in Settings' order, by stable ESPN keys`() {
        assertEquals(
            listOf(
                "nhl", "nfl", "nba", "mlb", "cfl", "usa.1", "eng.1", "uefa.champions", "esp.1", "ger.1", "ita.1", "fra.1",
                "college-football", "mens-college-basketball", "wnba",
            ),
            League.entries.map { it.key },
        )
        assertEquals(League.entries.toList(), League.entries.map { League.byKey(it.key) })
        assertNull(League.byKey("f1"))
    }

    @Test fun `Canada's defaults are the seven from CONTEXT`() {
        assertEquals(
            setOf(League.Nhl, League.Nfl, League.Nba, League.Mlb, League.Cfl, League.Mls, League.PremierLeague),
            League.defaults("CA"),
        )
    }

    @Test fun `other Regions get their own defaults, and the rest of the world the four watched most widely`() {
        assertEquals(setOf(League.Nfl, League.Nba, League.Mlb, League.Nhl, League.NcaaFootball, League.NcaaMensBasketball, League.Mls), League.defaults("US"))
        assertEquals(setOf(League.Ligue1, League.ChampionsLeague, League.PremierLeague, League.Nba), League.defaults("FR"))
        assertEquals(setOf(League.PremierLeague, League.ChampionsLeague, League.Nba, League.Nfl), League.defaults("JP"))
        // Settings keeps the Region upper case, but the device's country may not be.
        assertEquals(League.defaults("CA"), League.defaults("ca"))
    }

    @Test fun `the Leagues on are the Region's defaults until the Viewer chooses, then their choice, even none`() {
        assertEquals(League.defaults("CA"), League.on(null, "CA"))
        assertEquals(setOf(League.Nhl, League.LaLiga), League.on(setOf(League.Nhl, League.LaLiga), "CA"))
        assertEquals(emptySet<League>(), League.on(emptySet(), "CA"))
    }
}
