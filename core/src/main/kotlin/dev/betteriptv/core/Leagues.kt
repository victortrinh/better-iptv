package dev.betteriptv.core

/**
 * A League the sports hub offers (CONTEXT.md), in the order Settings lists them. Its [key] is the id
 * the device and the cloud share (cloud/src/schedule.ts's `LEAGUES`), stable across releases:
 * Settings keeps the Leagues on by key, and the cloud's Games call asks for Leagues by it. [names]
 * are what an Event channel's name calls the League (`NHL: Leafs vs Habs`, `EPL | Arsenal v
 * Chelsea`): a Game matches an Event channel naming one of its teams and one of these, so a team
 * League's are never its sport's bare word ("Hockey" names any hockey game; an event League's
 * "Golf" stays, since its slots say nothing else, and [otherSeries] keep the other tours out). [network]
 * is the League network (CONTEXT.md), as a Provider names it (NHL Network, NBA TV): a Game's Feed of last
 * resort when nothing names the Game (CONTEXT.md: Feed), null for a League that has none. An event
 * League ([events]: F1, NASCAR Cup, IndyCar, the PGA Tour and the LPGA, #310) has no teams: its Games are a
 * Grand Prix's sessions, a race or a golf tournament's rounds ([GameEvent]). [otherSeries] name the
 * same sport's other series or tours, whose Event channels name the League too ("NASCAR Whelen
 * Modified Tour at Martinsville Speedway", "Golf: LPGA … Round 1"): one naming any is none of this
 * League's Games. IndyCar is never "Indy" alone: the Provider's Indy Fuel (ECHL) and Indy NXT (its
 * feeder series) slots say that too. A fight-card League ([fights]: UFC, #316) has no teams either:
 * its Games are fight cards ([GameFight]).
 */
enum class League(
    val key: String,
    val names: List<String>,
    val network: String? = null,
    val events: Boolean = false,
    val otherSeries: List<String> = emptyList(),
    val fights: Boolean = false,
) {
    // No bare sport word for a team League: the Provider's 593 "hockey:" slots (survey 2026-10-24) are junior, minor, college and European games, none the NHL's.
    Nhl("nhl", listOf("NHL"), network = "NHL Network"),
    Nfl("nfl", listOf("NFL"), network = "NFL Network"),
    Nba("nba", listOf("NBA"), network = "NBA TV"),
    Mlb("mlb", listOf("MLB", "Major League Baseball"), network = "MLB Network"),
    Cfl("cfl", listOf("CFL")),
    Mls("mls", listOf("MLS")),
    PremierLeague("premier-league", listOf("Premier League", "EPL", "PL")),
    ChampionsLeague("champions-league", listOf("Champions League", "UCL", "UEFA CL")),
    LaLiga("la-liga", listOf("La Liga", "LaLiga")),
    Bundesliga("bundesliga", listOf("Bundesliga")),
    SerieA("serie-a", listOf("Serie A")),
    Ligue1("ligue-1", listOf("Ligue 1")),
    NcaaFootball("ncaa-football", listOf("NCAA Football", "NCAAF", "College Football", "CFB")),
    NcaaMensBasketball("ncaa-basketball", listOf("NCAA Basketball", "NCAAB", "NCAAM", "College Basketball", "CBB")),
    Wnba("wnba", listOf("WNBA")),
    F1("f1", listOf("F1", "Formula 1", "Formula One"), network = "Sky Sports F1", events = true),
    Nascar(
        "nascar", listOf("NASCAR"), events = true,
        otherSeries = listOf("Xfinity", "O'Reilly", "Truck", "Trucks", "Whelen", "Modified", "ARCA", "Pinty's", "Canada Series", "Mexico Series", "Euro Series"),
    ),
    IndyCar("indycar", listOf("IndyCar", "Indy Car"), events = true, otherSeries = listOf("Indy NXT")),

    // The Provider's golf slots name no tour, only `Golf:` (catalog 2026-11-03), so the other tours' and the amateurs' are told apart by name.
    Pga(
        "pga", listOf("PGA Tour", "PGA", "Golf"), network = "Golf Channel", events = true,
        otherSeries = listOf("LPGA", "Korn Ferry", "PGA Tour Champions", "Champions Tour", "Senior", "Amateur", "DP World", "LIV", "European Tour"),
    ),
    Lpga(
        "lpga", listOf("LPGA", "LPGA Tour", "Golf"), network = "Golf Channel", events = true,
        otherSeries = listOf("Korn Ferry", "Champions Tour", "Senior", "Amateur", "DP World", "LIV", "Ladies European Tour", "European Tour", "Epson Tour"),
    ),
    Ufc("ufc", listOf("UFC"), network = "UFC", fights = true);

    companion object {
        /**
         * The League with this [key], or null for one this release doesn't offer. A key an earlier
         * release kept a Leagues choice by ([LEGACY_KEYS]: #253's ESPN slugs, before the keys became
         * the cloud's ids) still finds its League, so the choice survives the update.
         */
        fun byKey(key: String): League? = entries.firstOrNull { it.key == key } ?: LEGACY_KEYS[key]

        /** The keys the first Live tab release (#253) saved Leagues by, to the Leagues they meant. */
        private val LEGACY_KEYS = mapOf(
            "usa.1" to Mls, "eng.1" to PremierLeague, "uefa.champions" to ChampionsLeague, "esp.1" to LaLiga, "ger.1" to Bundesliga,
            "ita.1" to SerieA, "fra.1" to Ligue1, "college-football" to NcaaFootball, "mens-college-basketball" to NcaaMensBasketball,
        )

        /**
         * The Leagues on until the Viewer chooses, by [region] (ISO 3166): Canada's seven from
         * CONTEXT.md, and the other Regions' as settled in `docs/roadmap.md` (M3, "Default Leagues
         * by Region").
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
