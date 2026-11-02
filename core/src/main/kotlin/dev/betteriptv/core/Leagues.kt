package dev.betteriptv.core

/**
 * A League the sports hub offers (CONTEXT.md), in the order Settings lists them. Its [key] is
 * ESPN's slug for it, stable across releases: Settings keeps the Leagues on by key, and the cloud's
 * Games call asks for Leagues by it.
 */
enum class League(val key: String) {
    Nhl("nhl"),
    Nfl("nfl"),
    Nba("nba"),
    Mlb("mlb"),
    Cfl("cfl"),
    Mls("usa.1"),
    PremierLeague("eng.1"),
    ChampionsLeague("uefa.champions"),
    LaLiga("esp.1"),
    Bundesliga("ger.1"),
    SerieA("ita.1"),
    Ligue1("fra.1"),
    NcaaFootball("college-football"),
    NcaaMensBasketball("mens-college-basketball"),
    Wnba("wnba");

    companion object {
        /** The League with this [key], or null for one this release doesn't offer. */
        fun byKey(key: String): League? = entries.firstOrNull { it.key == key }

        /**
         * The Leagues on until the Viewer chooses, by [region] (ISO 3166): Canada's seven from
         * CONTEXT.md; the United States its big four with college football and basketball and MLS;
         * Britain and Ireland the Premier League, the Champions League and the two American Leagues
         * seen most there; France, Germany and Austria, Spain and Italy their own top flight with the
         * Champions League, the Premier League where it isn't their own, and the NBA; Mexico the NFL,
         * MLB, NBA and Champions League; anywhere else the four Leagues watched most widely: the
         * Premier League, the Champions League, the NBA and the NFL.
         */
        fun defaults(region: String): Set<League> = when (region.uppercase()) {
            "CA" -> setOf(Nhl, Nfl, Nba, Mlb, Cfl, Mls, PremierLeague)
            "US" -> setOf(Nfl, Nba, Mlb, Nhl, NcaaFootball, NcaaMensBasketball, Mls)
            "GB", "IE" -> setOf(PremierLeague, ChampionsLeague, Nfl, Nba)
            "FR" -> setOf(Ligue1, ChampionsLeague, PremierLeague, Nba)
            "DE", "AT" -> setOf(Bundesliga, ChampionsLeague, PremierLeague, Nba)
            "ES" -> setOf(LaLiga, ChampionsLeague, PremierLeague, Nba)
            "IT" -> setOf(SerieA, ChampionsLeague, PremierLeague, Nba)
            "MX" -> setOf(Nfl, Mlb, Nba, ChampionsLeague)
            else -> setOf(PremierLeague, ChampionsLeague, Nba, Nfl)
        }

        /**
         * The Leagues on: the ones the Viewer [chose] in Settings (an empty set when they turned
         * every one off), or the [region]'s [defaults] while they haven't.
         */
        fun on(chose: Set<League>?, region: String): Set<League> = chose ?: defaults(region)
    }
}
