package dev.betteriptv.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Leagues offered and which are on: the Region's defaults until the Viewer chooses. */
class LeaguesTest {
    @Test fun `twenty-one Leagues are offered, in Settings' order, by the keys the cloud's Games call takes`() {
        // The cloud's LEAGUES (cloud/src/schedule.ts), in its order: a key the cloud doesn't know gets no Games.
        assertEquals(
            listOf(
                "nhl", "nfl", "nba", "mlb", "cfl", "mls", "premier-league", "champions-league", "la-liga", "bundesliga", "serie-a", "ligue-1",
                "ncaa-football", "ncaa-basketball", "wnba", "f1", "nascar", "indycar", "pga", "lpga", "ufc",
            ),
            League.entries.map { it.key },
        )
        assertEquals(League.entries.toList(), League.entries.map { League.byKey(it.key) })
        assertNull(League.byKey("boxing"))
    }

    @Test fun `every League has its own logo, ESPN's dark-background one, without a Game`() {
        // Live's League rows and mixed rows' cards show it before any Game is read (#350).
        for (league in League.entries) assertTrue(league.name, league.logo.startsWith("https://a.espncdn.com/") && league.logo.endsWith(".png"))
        assertEquals("https://a.espncdn.com/i/teamlogos/leagues/500-dark/nhl.png", League.Nhl.logo)
        assertEquals("https://a.espncdn.com/i/leaguelogos/soccer/500-dark/23.png", League.PremierLeague.logo)
        // No two Leagues share a logo, so a mixed row's cards tell them apart.
        assertEquals(League.entries.size, League.entries.map { it.logo }.toSet().size)
    }

    @Test fun `F1 is an event League, its Games a Grand Prix's sessions, and on in no Region until the Viewer turns it on`() {
        assertEquals(listOf(League.F1, League.Nascar, League.IndyCar, League.Pga, League.Lpga), League.entries.filter { it.events })
        assertEquals(listOf("F1", "Formula 1", "Formula One"), League.F1.names)
        assertEquals("Sky Sports F1", League.F1.network)
        for (region in listOf("CA", "US", "GB", "FR", "DE", "ES", "IT", "MX", "JP")) assertFalse(League.F1 in League.defaults(region))
    }

    @Test fun `NASCAR Cup is an event League with no League network, its other series none of its names, and on in no Region until the Viewer turns it on`() {
        assertEquals(listOf("NASCAR"), League.Nascar.names)
        assertNull(League.Nascar.network)
        assertEquals(listOf("Xfinity", "O'Reilly", "Truck", "Trucks", "Whelen", "Modified", "ARCA", "Pinty's", "Canada Series", "Mexico Series", "Euro Series"), League.Nascar.otherSeries)
        for (region in listOf("CA", "US", "GB", "FR", "DE", "ES", "IT", "MX", "JP")) assertFalse(League.Nascar in League.defaults(region))
    }

    @Test fun `IndyCar is an event League, its Games its races, with no League network, and on in no Region until the Viewer turns it on`() {
        // Never "Indy" alone: the Provider's Indy Fuel (ECHL) and Indy NXT (the feeder series) slots aren't IndyCar's.
        assertEquals(listOf("IndyCar", "Indy Car"), League.IndyCar.names)
        assertNull(League.IndyCar.network)
        assertEquals(listOf("Indy NXT"), League.IndyCar.otherSeries)
        for (region in listOf("CA", "US", "GB", "FR", "DE", "ES", "IT", "MX", "JP")) assertFalse(League.IndyCar in League.defaults(region))
    }

    @Test fun `the PGA Tour is an event League, its Games a tournament's rounds, on Golf Channel, and on in no Region until the Viewer turns it on`() {
        assertEquals(listOf("PGA Tour", "PGA", "Golf"), League.Pga.names)
        assertEquals("Golf Channel", League.Pga.network)
        for (region in listOf("CA", "US", "GB", "FR", "DE", "ES", "IT", "MX", "JP")) assertFalse(League.Pga in League.defaults(region))
    }

    @Test fun `the LPGA is an event League, its Games a tournament's rounds, on Golf Channel, and on in no Region until the Viewer turns it on`() {
        assertEquals(listOf("LPGA", "LPGA Tour", "Golf"), League.Lpga.names)
        assertEquals("Golf Channel", League.Lpga.network)
        for (region in listOf("CA", "US", "GB", "FR", "DE", "ES", "IT", "MX", "JP")) assertFalse(League.Lpga in League.defaults(region))
    }

    @Test fun `UFC is a fight-card League, its Games fight cards, and on in no Region until the Viewer turns it on`() {
        assertEquals(listOf(League.Ufc), League.entries.filter { it.fights })
        assertFalse(League.Ufc.events)
        assertEquals(listOf("UFC"), League.Ufc.names)
        assertEquals("UFC", League.Ufc.network)
        for (region in listOf("CA", "US", "GB", "FR", "DE", "ES", "IT", "MX", "JP")) assertFalse(League.Ufc in League.defaults(region))
    }

    @Test fun `a Leagues choice saved under the first release's ESPN slugs still finds its Leagues`() {
        assertEquals(
            listOf(League.Mls, League.PremierLeague, League.ChampionsLeague, League.LaLiga, League.Bundesliga, League.SerieA, League.Ligue1, League.NcaaFootball, League.NcaaMensBasketball),
            listOf("usa.1", "eng.1", "uefa.champions", "esp.1", "ger.1", "ita.1", "fra.1", "college-football", "mens-college-basketball").map(League::byKey),
        )
        assertEquals(League.Nhl, League.byKey("nhl"))
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
