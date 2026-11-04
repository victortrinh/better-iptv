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
 *
 * [logo] is the League's own logo, independent of any Game (#350): ESPN's dark-background one,
 * which the League's scoreboard names (`leagues[0].logos`, rel `dark`), for Live's row headings and
 * the cards of rows that mix Leagues. Where ESPN has none (the CFL) or only its default (UFC), it is
 * the icon ESPN's own pages show for the League. Fixed here, by League, so a release's list and its
 * logos never disagree.
 *
 * [sport] is what Settings' League picker groups it under (#353).
 *
 * The EFL Championship, Scottish Premiership, Liga Portugal, 2. Bundesliga, Süper Lig, Women's
 * Super League, NRL and AFL came in #354: two-team Leagues whose ESPN scoreboard was read on
 * 2026-11-04 and whose Event channels the 2026-10-24 survey showed. Like every League outside a
 * Region's [defaults], they are off until the Viewer turns them on.
 */
enum class League(
    val key: String,
    val names: List<String>,
    val sport: Sport,
    val logo: String,
    val network: String? = null,
    val events: Boolean = false,
    val otherSeries: List<String> = emptyList(),
    val fights: Boolean = false,
) {
    // No bare sport word for a team League: the Provider's 593 "hockey:" slots (survey 2026-10-24) are junior, minor, college and European games, none the NHL's.
    Nhl("nhl", listOf("NHL"), Sport.Hockey, logo = "${LEAGUES}nhl.png", network = "NHL Network"),
    Nfl("nfl", listOf("NFL"), Sport.Football, logo = "${LEAGUES}nfl.png", network = "NFL Network"),
    Nba("nba", listOf("NBA"), Sport.Basketball, logo = "${LEAGUES}nba.png", network = "NBA TV"),
    Mlb("mlb", listOf("MLB", "Major League Baseball"), Sport.Baseball, logo = "${LEAGUES}mlb.png", network = "MLB Network"),
    Cfl("cfl", listOf("CFL"), Sport.Football, logo = "${ICONS}ESPN-icon-football.png"),
    Mls("mls", listOf("MLS"), Sport.Soccer, logo = "${SOCCER}19.png"),
    PremierLeague("premier-league", listOf("Premier League", "EPL", "PL"), Sport.Soccer, logo = "${SOCCER}23.png"),
    ChampionsLeague("champions-league", listOf("Champions League", "UCL", "UEFA CL"), Sport.Soccer, logo = "${SOCCER}2.png"),
    LaLiga("la-liga", listOf("La Liga", "LaLiga"), Sport.Soccer, logo = "${SOCCER}15.png"),
    Bundesliga("bundesliga", listOf("Bundesliga"), Sport.Soccer, logo = "${SOCCER}10.png"),
    SerieA("serie-a", listOf("Serie A"), Sport.Soccer, logo = "${SOCCER}12.png"),
    Ligue1("ligue-1", listOf("Ligue 1"), Sport.Soccer, logo = "${SOCCER}9.png"),

    // #354: the two-team Leagues ESPN's scoreboard serves and the 2026-10-24 survey's Event channels showed. Never a bare
    // "Championship" or "Premiership": an Event channel naming another League is none of a League's Games, and NFL, golf and rugby slots say those too.
    EflChampionship("efl-championship", listOf("EFL Championship", "EFL", "Sky Bet Championship"), Sport.Soccer, logo = "${SOCCER}24.png"),
    ScottishPremiership("scottish-premiership", listOf("Scottish Premiership", "SPFL", "Scottish Prem"), Sport.Soccer, logo = "${SOCCER}45.png"),
    LigaPortugal("liga-portugal", listOf("Liga Portugal", "Primeira Liga", "Liga Betclic"), Sport.Soccer, logo = "${SOCCER}14.png"),

    // ESPN's only 2. Bundesliga logo (leaguelogos/soccer/500/97.png) is an old Bundesliga mark reading "BUNDESLIGA", so its tile shows ESPN's soccer icon, as the CFL's shows its football one.
    Bundesliga2("2-bundesliga", listOf("2. Bundesliga", "Bundesliga 2"), Sport.Soccer, logo = "${ICONS}ESPN-icon-soccer.png"),
    SuperLig("super-lig", listOf("Süper Lig"), Sport.Soccer, logo = "${SOCCER}18.png"),

    // Its clubs are the Premier League's (Chelsea, Everton): an Event channel naming either League is the other's none.
    WomensSuperLeague("wsl", listOf("Women's Super League", "Womens Super League", "WSL"), Sport.Soccer, logo = "${SOCCER}2314.png"),
    NcaaFootball("ncaa-football", listOf("NCAA Football", "NCAAF", "College Football", "CFB"), Sport.Football, logo = "${ICONS}NCAASports_Football.png"),
    NcaaMensBasketball("ncaa-basketball", listOf("NCAA Basketball", "NCAAB", "NCAAM", "College Basketball", "CBB"), Sport.Basketball, logo = "${ICONS}NCAASports_MensBasketball.png"),
    Wnba("wnba", listOf("WNBA"), Sport.Basketball, logo = "${LEAGUES}wnba.png"),
    Nrl("nrl", listOf("NRL"), Sport.Rugby, logo = "${LEAGUES}nrl.png"),
    Afl("afl", listOf("AFL"), Sport.AustralianFootball, logo = "${LEAGUES}afl.png"),
    F1("f1", listOf("F1", "Formula 1", "Formula One"), Sport.Motorsport, logo = "${LEAGUES}f1.png", network = "Sky Sports F1", events = true),
    Nascar(
        "nascar", listOf("NASCAR"), Sport.Motorsport, logo = "${ICONS}ESPN-icon-NASCAR.png", events = true,
        otherSeries = listOf("Xfinity", "O'Reilly", "Truck", "Trucks", "Whelen", "Modified", "ARCA", "Pinty's", "Canada Series", "Mexico Series", "Euro Series"),
    ),
    IndyCar("indycar", listOf("IndyCar", "Indy Car"), Sport.Motorsport, logo = "${ESPN}espn/teamlogos/500/indycar_series.png", events = true, otherSeries = listOf("Indy NXT")),

    // The Provider's golf slots name no tour, only `Golf:` (catalog 2026-11-03), so the other tours' and the amateurs' are told apart by name.
    Pga(
        "pga", listOf("PGA Tour", "PGA", "Golf"), Sport.Golf, logo = "${LEAGUES}pgatour.png", network = "Golf Channel", events = true,
        otherSeries = listOf("LPGA", "Korn Ferry", "PGA Tour Champions", "Champions Tour", "Senior", "Amateur", "DP World", "LIV", "European Tour"),
    ),
    Lpga(
        "lpga", listOf("LPGA", "LPGA Tour", "Golf"), Sport.Golf, logo = "${LEAGUES}lpga.png", network = "Golf Channel", events = true,
        otherSeries = listOf("Korn Ferry", "Champions Tour", "Senior", "Amateur", "DP World", "LIV", "Ladies European Tour", "European Tour", "Epson Tour"),
    ),
    Ufc("ufc", listOf("UFC"), Sport.Fighting, logo = "${ESPN}teamlogos/leagues/500/ufc.png", network = "UFC", fights = true);

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

/**
 * A League's sport: what Settings' League picker groups the Leagues under, in this order (#353).
 * The picker shows only the sports that have a League. Rugby's is the NRL, rugby league's; the
 * AFL's Australian football is a sport of its own (#354).
 */
enum class Sport { Hockey, Football, Basketball, Baseball, Soccer, Motorsport, Golf, Fighting, Rugby, AustralianFootball }

/** Where ESPN keeps the [League.logo]s. */
private const val ESPN = "https://a.espncdn.com/i/"
private const val LEAGUES = "${ESPN}teamlogos/leagues/500-dark/"
private const val SOCCER = "${ESPN}leaguelogos/soccer/500-dark/"
private const val ICONS = "https://a.espncdn.com/redesign/assets/img/icons/"
