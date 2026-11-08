import { createScheduledController, env } from "cloudflare:test";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import worker from "../src/index";
import { LEAGUES } from "../src/schedule";
import { GAMES_SUBREQUESTS } from "../src/games";
import { added, argos, bombers, broncos, bruins, event, FakeEspn, fightCard, grandPrix, habs, indyCarRace, leaderboard, leafs, sens, stockCarRace, team, texans, top14, tournament } from "./fake-espn";
import { FakeWeb } from "./fake-web";
import { FakeWikipedia } from "./fake-wikipedia";

/** Seam 2: the cloud's HTTP API, run in the Workers runtime with the web it reaches faked. */
let web: FakeWeb;
beforeEach(async () => {
  // Each test starts with no devices and empty tables.
  await env.DB.batch(
    ["device", "title_facts", "file_read", "unknown_prefix", "tier_tag_candidate", "game", "team", "game_wanted", "trending_day", "trending_wanted"].map((table) =>
      env.DB.prepare(`DELETE FROM ${table}`),
    ),
  );
  web = new FakeWeb();
});
afterEach(() => {
  web.restore();
  vi.useRealTimers();
});

const call = (method: string, path: string, body?: unknown, token?: string) =>
  worker.fetch(
    new Request(`https://cloud.example${path}`, {
      method,
      headers: { "Content-Type": "application/json", ...(token ? { Authorization: `Bearer ${token}` } : {}) },
      body: body === undefined ? undefined : JSON.stringify(body),
    }),
    env,
  );

async function registerDevice(): Promise<string> {
  const response = await call("POST", "/v1/devices");
  expect(response.status).toBe(201);
  return ((await response.json()) as { token: string }).token;
}

const aProvider = { provider: "a".repeat(64) };

describe("device tokens", () => {
  it("rejects a request without a token, or with one no device was given", async () => {
    expect((await call("POST", "/v1/provider-table", aProvider)).status).toBe(401);
    const madeUp = "A".repeat(43);
    expect((await call("POST", "/v1/provider-table", aProvider, madeUp)).status).toBe(401);
    expect(vi.mocked(globalThis.fetch)).not.toHaveBeenCalled();
  });

  it("accepts every registered device's own token", async () => {
    const living = await registerDevice();
    const bedroom = await registerDevice();
    expect(living).not.toBe(bedroom);
    expect((await call("POST", "/v1/provider-table", aProvider, living)).status).toBe(200);
    expect((await call("POST", "/v1/provider-table", aProvider, bedroom)).status).toBe(200);
  });

  it("keeps only a hash of each token", async () => {
    const token = await registerDevice();
    const stored = await env.DB.prepare("SELECT token_hash FROM device").all<{ token_hash: string }>();
    expect(stored.results.map((row) => row.token_hash)).not.toContain(token);
  });
});

/** M6 (#481, ADR 0014): the cloud keeps no TMDB data and needs no TMDB key, as TMDB's terms ask when use ends. */
describe("without TMDB", () => {
  const removed: [string, unknown][] = [
    ["/v1/metadata", { language: "en", titles: [{ kind: "movie", tmdbId: 238 }] }],
    ["/v1/match", { listings: [{ kind: "movie", name: "The Godfather", year: 1972, language: "en", tmdbId: null }] }],
    ["/v1/search", { query: "der pate" }],
    ["/v1/upcoming", { tmdbId: 1399, language: "en" }],
    ["/v1/season", { tmdbId: 1399, season: 2, language: "en" }],
    ["/v1/charts", { region: "CA", language: "en" }],
  ];

  it.each(removed)("answers %s with 404, reaching nothing", async (path, body) => {
    const token = await registerDevice();
    const response = await call("POST", path, body, token);
    expect(response.status).toBe(404);
    expect(await response.json()).toEqual({ error: "No such endpoint." });
    expect(vi.mocked(globalThis.fetch)).not.toHaveBeenCalled();
  });

  it("keeps no table of TMDB's metadata, name lookups, searches, credits, upcoming or season Episodes, or Charts", async () => {
    const tables = (await env.DB.prepare("SELECT name FROM sqlite_master WHERE type = 'table'").all<{ name: string }>()).results.map((row) => row.name);
    for (const gone of ["title_metadata", "title_lookup", "title_search", "person_credits", "upcoming_episodes", "season_episodes", "chart", "chart_wanted", "chart_try"]) {
      expect(tables).not.toContain(gone);
    }
    // What stays: the Wikidata facts subtitles read the IMDb ID from, and Trending's days.
    expect(tables).toEqual(expect.arrayContaining(["device", "title_facts", "trending_day", "game", "file_read"]));
  });

  it("has the Cron Trigger read the Games and Wikipedia's most viewed articles, and nothing of the Charts", async () => {
    vi.useFakeTimers({ toFake: ["Date"] });
    vi.setSystemTime(new Date("2026-11-07T08:00:00Z"));
    const espn = new FakeEspn(web);
    const wikipedia = new FakeWikipedia(web);
    espn.scoreboard("hockey/nhl", "2026-11-07");
    wikipedia.top("en", "2026-11-06", [["Jailer_3", 812_345]]);
    const token = await registerDevice();
    expect((await call("POST", "/v1/games", { leagues: ["nhl"] }, token)).status).toBe(200);
    expect((await call("POST", "/v1/trending", { language: "en" }, token)).status).toBe(200);
    await worker.scheduled(createScheduledController({ cron: "* * * * *", scheduledTime: Date.now() }), env);
    expect(espn.requests.length).toBeGreaterThan(0);
    expect(wikipedia.asked("en")).toContain("2026-11-06");
    expect(web.unexpected).toEqual([]);
  });
});

describe("Games", () => {
  let espn: FakeEspn;
  const nhl = "hockey/nhl";
  /** A Sunday evening in Toronto: the Leafs are playing, the Bruins play later, and the Senators played the night before. */
  const now = new Date("2026-11-01T20:00:00Z");
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ["Date"] });
    vi.setSystemTime(now);
    espn = new FakeEspn(web);
    espn.scoreboard(nhl, "2026-10-31", event("0", "2026-10-31T23:00:00Z", "post", sens, bruins, [{ name: "NHL Net" }, { name: "TSN5", market: "Home" }]));
    espn.scoreboard(
      nhl,
      "2026-11-01",
      event("1", "2026-11-01T19:00:00Z", "in", leafs, habs, [{ name: "ESPN+" }, { name: "MSG", market: "Away" }, { name: "ESPN+" }]),
      event("2", "2026-11-02T00:00:00Z", "pre", bruins, sens),
    );
    espn.scoreboard(nhl, "2026-11-03", event("3", "2026-11-04T00:00:00Z", "pre", leafs, bruins, [{ name: "TNT" }]), event("4", "2026-11-04T00:00:00Z", "pre", habs, sens, [], "STATUS_POSTPONED"));
    // The teams list knows a nickname the scoreboard doesn't, and the same location again.
    espn.teams(nhl, team({ ...leafs, nickname: "Toronto" }), team({ ...habs, nickname: "Montréal" }), team(bruins), team(sens));
    espn.scoreboard("football/nfl", "2026-11-01", event("100", "2026-11-01T21:25:00Z", "pre", broncos, texans, [{ name: "CBS" }]));
    // Out of season, ESPN's CFL endpoint answers with an old game.
    espn.scoreboard("football/cfl", "2026-10-31", event("200", "2022-11-20T23:00:00Z", "post", bombers, argos, [{ name: "ESPN2" }]), event("201", "2026-10-31T18:00:00Z", "post", argos, bombers));
    espn.scoreboard("football/cfl", "2026-11-01", event("202", "2022-11-20T23:00:00Z", "post", bombers, argos, [{ name: "ESPN2" }]));
  });

  const gamesOf = async (token: string, leagues: string[]) => {
    const response = await call("POST", "/v1/games", { leagues }, token);
    expect(response.status).toBe(200);
    return (await response.json()) as { leagues: { league: string; games: Record<string, unknown>[] }[] };
  };
  /** Every fetch the Worker sent out, to ESPN or anyone. */
  const sentOut = () => vi.mocked(globalThis.fetch).mock.calls.length;
  /** One run of the Cron Trigger, as Cloudflare starts it each minute. */
  const run = () => worker.scheduled(createScheduledController({ cron: "* * * * *", scheduledTime: Date.now() }), env);
  /** The Cron Trigger's next [runs] runs, a minute apart as Cloudflare starts them; the clock is left at the last. */
  const fill = async (runs = 1) => {
    for (let i = 0; i < runs; i++) {
      if (i > 0) vi.setSystemTime(Date.now() + 60_000);
      await run();
    }
  };
  /** Asks for the Games, lets the Cron Trigger fill what that asked for, and asks again. */
  const filled = async (token: string, leagues: string[], runs = 1) => {
    await gamesOf(token, leagues);
    await fill(runs);
    return gamesOf(token, leagues);
  };
  const forget = () => espn.requests.splice(0);
  const ids = (answer: { leagues: { league: string; games: Record<string, unknown>[] }[] }, league: string) => answer.leagues.find((l) => l.league === league)?.games.map((g) => g.id);

  it("answers a League's Games for today and the week ahead, by start: state, both teams with aliases and logo, the national Broadcasters, never a score", async () => {
    const { leagues } = await filled(await registerDevice(), ["nhl"]);

    // The teams list's logo for dark backgrounds, over the scoreboard's.
    const logo = (abbreviation: string) => `https://a.espncdn.com/i/teamlogos/500-dark/${abbreviation.toLowerCase()}.png`;
    const torontoMapleLeafs = { id: "21", name: "Toronto Maple Leafs", shortName: "Maple Leafs", abbreviation: "TOR", aliases: ["Toronto Maple Leafs", "Maple Leafs", "TOR", "Toronto"], logo: logo("TOR") };
    const montrealCanadiens = { id: "10", name: "Montreal Canadiens", shortName: "Canadiens", abbreviation: "MTL", aliases: ["Montreal Canadiens", "Canadiens", "MTL", "Montreal", "Montréal"], logo: logo("MTL") };
    const bostonBruins = { id: "1", name: "Boston Bruins", shortName: "Bruins", abbreviation: "BOS", aliases: ["Boston Bruins", "Bruins", "BOS", "Boston"], logo: logo("BOS") };
    const ottawaSenators = { id: "14", name: "Ottawa Senators", shortName: "Senators", abbreviation: "OTT", aliases: ["Ottawa Senators", "Senators", "OTT", "Ottawa"], logo: logo("OTT") };
    expect(leagues).toEqual([
      {
        league: "nhl",
        games: [
          // Last night's game is still within a day; only the national network is a Broadcaster.
          { id: "0", league: "nhl", start: "2026-10-31T23:00:00.000Z", state: "over", home: ottawaSenators, away: bostonBruins, broadcasters: ["NHL Net"] },
          { id: "1", league: "nhl", start: "2026-11-01T19:00:00.000Z", state: "live", home: torontoMapleLeafs, away: montrealCanadiens, broadcasters: ["ESPN+"] },
          { id: "2", league: "nhl", start: "2026-11-02T00:00:00.000Z", state: "upcoming", home: bostonBruins, away: ottawaSenators, broadcasters: [] },
          // The postponed game is left out.
          { id: "3", league: "nhl", start: "2026-11-04T00:00:00.000Z", state: "upcoming", home: torontoMapleLeafs, away: bostonBruins, broadcasters: ["TNT"] },
        ],
      },
    ]);
    // No field of ESPN's that tells how the game is going: not a score, a period, a clock or a record.
    expect(JSON.stringify(leagues)).not.toMatch(/"(score|period|clock|records?|displayClock)"/i);
  });

  it("gives each team ESPN's colour and alternate colour, as six lowercase hex digits, and none it hasn't or can't be read", async () => {
    // The Leafs have both, the Canadiens a main colour only, written with a hash; the Bruins' alternate is no colour.
    espn.scoreboard(
      nhl,
      "2026-11-01",
      event("1", "2026-11-01T19:00:00Z", "in", { ...leafs, color: "00205B", alternateColor: "ffffff" }, { ...habs, color: "#AF1E2D" }, [{ name: "ESPN+" }]),
      event("2", "2026-11-02T00:00:00Z", "pre", { ...bruins, color: "231f20", alternateColor: "gold" }, sens),
    );
    const { leagues } = await filled(await registerDevice(), ["nhl"]);

    const colours = (id: string) => {
      const game = leagues[0].games.find((g) => g.id === id) as { home: Record<string, unknown>; away: Record<string, unknown> };
      const pick = (t: Record<string, unknown>) => ({ color: t.color, alternateColor: t.alternateColor });
      return { home: pick(game.home), away: pick(game.away) };
    };
    expect(colours("1")).toEqual({ home: { color: "00205b", alternateColor: "ffffff" }, away: { color: "af1e2d", alternateColor: undefined } });
    expect(colours("2")).toEqual({ home: { color: "231f20", alternateColor: undefined }, away: { color: undefined, alternateColor: undefined } });
    // A team without colours carries no field for them.
    expect(Object.keys((leagues[0].games.find((g) => g.id === "2") as { away: object }).away)).not.toContain("color");
  });

  it("gives each team ESPN's logo for dark backgrounds, else its default one, else the scoreboard's", async () => {
    // The Senators have no logo for dark backgrounds; the Bruins aren't in the teams list.
    espn.teams(nhl, team(leafs), team(habs), team(sens, { dark: false }));
    const { leagues } = await filled(await registerDevice(), ["nhl"]);

    const logos = Object.fromEntries(leagues[0].games.flatMap((g) => [g.home, g.away] as { abbreviation: string; logo: string }[]).map((t) => [t.abbreviation, t.logo]));
    expect(logos).toEqual({
      TOR: "https://a.espncdn.com/i/teamlogos/500-dark/tor.png",
      MTL: "https://a.espncdn.com/i/teamlogos/500-dark/mtl.png",
      OTT: "https://a.espncdn.com/i/teamlogos/500/ott.png",
      BOS: "https://a.espncdn.com/i/teamlogos/500/scoreboard/bos.png",
    });
  });

  it("answers #354's and #375's Leagues' Games as two teams', read at each League's ESPN path, with their aliases, colours and Broadcasters", async () => {
    for (const [league, { path, home, away }] of Object.entries(added)) {
      espn.scoreboard(path, "2026-11-01", event(`${league}-1`, "2026-11-01T15:00:00Z", "pre", home, away, league === "efl-championship" || league === "wsl" ? [{ name: "Paramount+" }] : []));
      espn.teams(path, team(home), team(away));
    }
    // Seven Leagues' days and teams take a few runs of the budget.
    const { leagues } = await filled(await registerDevice(), Object.keys(added), 6);

    expect(leagues.map((l) => l.league).sort()).toEqual(Object.keys(added).sort());
    for (const { league, games } of leagues) {
      const { path, home, away } = added[league];
      expect(espn.asked).toContain(`${path}/2026-11-01`);
      expect(espn.asked).toContain(`${path}/teams`);
      expect(games).toHaveLength(1);
      expect(games[0]).toMatchObject({ id: `${league}-1`, league, start: "2026-11-01T15:00:00.000Z", state: "upcoming" });
      expect((games[0].home as { name: string }).name).toBe(home.displayName);
      expect((games[0].away as { name: string }).name).toBe(away.displayName);
    }
    const game = (league: string) => leagues.find((l) => l.league === league)?.games[0] as { home: Record<string, unknown>; away: Record<string, unknown>; broadcasters: string[] };
    // A Championship Game: its teams as an Event channel may name them, ESPN's colours, and its national Broadcaster.
    expect(game("efl-championship").home).toMatchObject({ name: "Cardiff City", shortName: "Cardiff", abbreviation: "CAR", aliases: ["Cardiff City", "Cardiff", "CAR"], color: "0000fa", alternateColor: "c6d4db" });
    expect(game("efl-championship").broadcasters).toEqual(["Paramount+"]);
    // ESPN's trailing spaces are trimmed.
    expect(game("wsl").home).toMatchObject({ shortName: "Chelsea", aliases: ["Chelsea", "CHE"] });
    // The NRL's and the AFL's teams have no location: their nickname or place is the name.
    expect(game("nrl").away).toMatchObject({ name: "Knights", shortName: "Knights", abbreviation: "NEW", aliases: ["Knights", "NEW"] });
    expect(game("afl").away).toMatchObject({ name: "Brisbane Lions", aliases: ["Brisbane Lions", "BL"] });
    expect(JSON.stringify(leagues)).not.toMatch(/"(score|period|clock|records?|displayClock)"/i);
  });

  it("answers Top 14's Games with the names the Provider calls its clubs by, and none of ESPN's abbreviations (#383)", async () => {
    // ESPN's round of 2026-10-24 and 25, moved to this test's days.
    const t = top14;
    espn.scoreboard(
      "rugby/270559",
      "2026-11-01",
      event("t14-1", "2026-11-01T12:30:00Z", "pre", t.vannes, t.clermont),
      event("t14-2", "2026-11-01T14:35:00Z", "pre", t.bayonne, t.lyon),
      event("t14-3", "2026-11-01T14:35:00Z", "pre", t.castres, t.stadeFrancais),
      event("t14-4", "2026-11-01T14:35:00Z", "pre", t.racing, t.montpellier),
      event("t14-5", "2026-11-01T14:35:00Z", "pre", t.toulouse, t.perpignan),
      event("t14-6", "2026-11-01T19:00:00Z", "pre", t.toulon, t.pau),
      event("t14-7", "2026-11-01T20:05:00Z", "pre", t.laRochelle, t.bordeaux),
    );
    espn.teams("rugby/270559", ...Object.values(t).map((c) => team(c)));
    const { leagues } = await filled(await registerDevice(), ["top-14"], 3);

    const games = leagues.find((l) => l.league === "top-14")?.games as { id: string; home: { name: string; abbreviation: string; aliases: string[] }; away: { name: string; abbreviation: string; aliases: string[] } }[];
    const aliases = Object.fromEntries(games.flatMap((g) => [g.home, g.away]).map((team) => [team.name, team.aliases]));
    expect(aliases).toEqual({
      Vannes: ["Vannes"],
      "Clermont Auvergne": ["Clermont Auvergne", "Clermont"],
      Bayonne: ["Bayonne", "Aviron Bayonnais"],
      Lyon: ["Lyon"],
      "Castres Olympique": ["Castres Olympique", "Castres"],
      "Stade Francais Paris": ["Stade Francais Paris", "Stade Francais"],
      "Racing 92": ["Racing 92"],
      "Montpellier Herault": ["Montpellier Herault", "Montpellier"],
      "Stade Toulousain": ["Stade Toulousain", "Toulouse"],
      Perpignan: ["Perpignan"],
      Toulon: ["Toulon"],
      Pau: ["Pau", "Section Paloise"],
      "La Rochelle": ["La Rochelle", "Stade Rochelais"],
      "Bordeaux Begles": ["Bordeaux Begles"],
    });
    // ESPN's abbreviation is still the team's, only never a name to match by.
    expect(games.find((g) => g.id === "t14-5")?.home).toMatchObject({ name: "Stade Toulousain", abbreviation: "STA" });
    expect(espn.asked).toContain("rugby/270559/teams");
  });

  it("gives another League's teams none of Top 14's names, and keeps their abbreviations", async () => {
    // A Ligue 1 club ESPN might one day call "Stade Toulousain" too: the names are Top 14's alone.
    const stade = { ...top14.toulouse, id: "999", abbreviation: "TFC" };
    espn.scoreboard("soccer/fra.1", "2026-11-01", event("l1-1", "2026-11-01T19:45:00Z", "pre", stade, top14.lyon));
    const { leagues } = await filled(await registerDevice(), ["ligue-1"], 2);

    const game = leagues.find((l) => l.league === "ligue-1")?.games[0] as { home: { aliases: string[] } };
    expect(game.home.aliases).toEqual(["Stade Toulousain", "TFC"]);
  });

  it("answers only the Leagues asked for, and reads only the Leagues some device has on", async () => {
    const token = await registerDevice();
    const first = await filled(token, ["nhl"]);

    expect(first.leagues.map((l) => l.league)).toEqual(["nhl"]);
    // Yesterday, today and the seven days after, and the teams: nothing of any other League.
    expect(espn.asked.sort()).toEqual([...Array.from({ length: 9 }, (_, i) => `hockey/nhl/${i === 0 ? "2026-10-31" : `2026-11-0${i}`}`), "hockey/nhl/teams"].sort());
    // A League asked for the first time is absent until the Cron Trigger's next run.
    expect((await gamesOf(token, ["nfl", "nhl"])).leagues.map((l) => l.league)).toEqual(["nhl"]);
    await fill();
    const both = await gamesOf(token, ["nfl", "nhl"]);
    expect(both.leagues.map((l) => l.league)).toEqual(["nfl", "nhl"]);
    expect(ids(both, "nfl")).toEqual(["100"]);
  });

  it("never reaches ESPN while answering, only the Cron Trigger does", async () => {
    const token = await registerDevice();
    const before = sentOut();
    expect(await gamesOf(token, ["nhl"])).toEqual({ leagues: [] });
    expect(sentOut()).toBe(before);
    await fill();
    expect(sentOut()).toBeGreaterThan(before);
    const after = sentOut();
    expect(ids(await gamesOf(token, ["nhl"]), "nhl")).toHaveLength(4);
    expect(sentOut()).toBe(after);
  });

  it("reads a day with a Game on again every 5 minutes, so Live flips, and the other days hourly", async () => {
    const token = await registerDevice();
    await filled(token, ["nhl"]);
    forget();
    vi.setSystemTime(new Date("2026-11-01T20:04:00Z"));
    await fill();
    expect(espn.asked).toEqual([]);

    // The Bruins' game starts.
    espn.scoreboard(nhl, "2026-11-01", event("1", "2026-11-01T19:00:00Z", "post", leafs, habs, [{ name: "ESPN+" }]), event("2", "2026-11-02T00:00:00Z", "in", bruins, sens));
    vi.setSystemTime(new Date("2026-11-01T20:05:30Z"));
    await fill();
    // Only today, where the Leafs are playing: yesterday's game is over, and nothing is due on the coming days.
    expect(espn.asked).toEqual(["hockey/nhl/2026-11-01"]);
    const { leagues } = await gamesOf(token, ["nhl"]);
    expect(leagues[0].games.map((g) => [g.id, g.state])).toEqual([["0", "over"], ["1", "over"], ["2", "live"], ["3", "upcoming"]]);

    forget();
    vi.setSystemTime(new Date("2026-11-01T21:01:00Z"));
    await fill();
    // Today again (the Bruins are on), yesterday and the seven coming days an hour after they were read; the teams keep for a week.
    expect(espn.asked.sort()).toEqual(Array.from({ length: 9 }, (_, i) => `hockey/nhl/${i === 0 ? "2026-10-31" : `2026-11-0${i}`}`).sort());
  });

  it("reads a day often only around its Games: hourly until 15 minutes before one starts, then every 5 minutes until it is over", async () => {
    // The Broncos play at 21:25; the NFL's other days are empty.
    const token = await registerDevice();
    await filled(token, ["nfl"]);
    const readsOf = (day: string) => espn.asked.filter((a) => a === `football/nfl/${day}`).length;

    // Not at 5 past: the game is over an hour away.
    forget();
    for (const at of ["20:05:30", "20:30:00", "20:59:00"]) {
      vi.setSystemTime(new Date(`2026-11-01T${at}Z`));
      await fill();
    }
    expect(espn.asked).toEqual([]);
    // Hourly: read at 21:00, due again 15 minutes before kickoff, then every 5 minutes.
    vi.setSystemTime(new Date("2026-11-01T21:00:30Z"));
    await fill();
    expect(readsOf("2026-11-01")).toBe(1);
    vi.setSystemTime(new Date("2026-11-01T21:06:00Z"));
    await fill();
    expect(readsOf("2026-11-01")).toBe(1);
    vi.setSystemTime(new Date("2026-11-01T21:10:30Z"));
    await fill();
    expect(readsOf("2026-11-01")).toBe(2);
    vi.setSystemTime(new Date("2026-11-01T21:16:00Z"));
    await fill();
    expect(readsOf("2026-11-01")).toBe(3);
    // Kickoff: Live within 5 minutes.
    espn.scoreboard("football/nfl", "2026-11-01", event("100", "2026-11-01T21:25:00Z", "in", broncos, texans, [{ name: "CBS" }]));
    vi.setSystemTime(new Date("2026-11-01T21:26:30Z"));
    await fill(2);
    expect((await gamesOf(token, ["nfl"])).leagues[0].games[0]).toMatchObject({ id: "100", state: "live" });
    // Over: back to hourly.
    espn.scoreboard("football/nfl", "2026-11-01", event("100", "2026-11-01T21:25:00Z", "post", broncos, texans, [{ name: "CBS" }]));
    vi.setSystemTime(new Date("2026-11-02T00:35:00Z"));
    await fill();
    forget();
    vi.setSystemTime(new Date("2026-11-02T00:41:00Z"));
    await fill();
    expect(espn.asked).toEqual([]);
    vi.setSystemTime(new Date("2026-11-02T01:36:00Z"));
    await fill();
    expect(espn.asked).toContain("football/nfl/2026-11-01");
    // The empty days around it were only ever read hourly.
    expect(readsOf("2026-11-03")).toBeLessThanOrEqual(6);
    // Each read cost D1 two row writes, the row before and after it: no try table.
    const tables = await env.DB.prepare("SELECT name FROM sqlite_master WHERE type = 'table' AND name LIKE 'game%'").all<{ name: string }>();
    expect(tables.results.map((t) => t.name).sort()).toEqual(["game", "game_wanted"]);
  });

  it("serves the kept copy while ESPN can't answer, and leaves out a League it never had", async () => {
    const token = await registerDevice();
    const before = await filled(token, ["nhl"]);
    espn.failWith = 503;
    vi.setSystemTime(new Date("2026-11-01T20:10:00Z"));
    await fill();
    expect(espn.asked).toContain("hockey/nhl/2026-11-01");

    const during = await gamesOf(token, ["nhl", "nfl"]);
    expect(during.leagues).toEqual(before.leagues);

    // Up again: the NFL, tried while ESPN was down, is read once its parts' wait is over.
    espn.failWith = null;
    vi.setSystemTime(new Date("2026-11-01T20:13:00Z"));
    await fill();
    expect((await gamesOf(token, ["nhl", "nfl"])).leagues.map((l) => l.league)).toEqual(["nhl", "nfl"]);
  });

  it("takes an answer with no events for no answer: the kept copy stands and the run goes on", async () => {
    const token = await registerDevice();
    const before = await filled(token, ["nhl"]);
    espn.answerNull = true;
    vi.setSystemTime(new Date("2026-11-01T20:10:00Z"));
    await gamesOf(token, ["nhl", "nfl"]);
    await expect(fill()).resolves.toBeUndefined();

    expect(espn.asked).toContain("football/nfl/2026-11-01");
    expect(espn.asked).toContain("football/nfl/teams");
    expect((await gamesOf(token, ["nhl", "nfl"])).leagues).toEqual(before.leagues);
  });

  it("drops Games that started over a day ago, as ESPN's CFL endpoint answers out of season with an old one", async () => {
    const { leagues } = await filled(await registerDevice(), ["cfl"]);
    // Read, with nothing to show: the League is there, with no Games, rather than absent.
    expect(leagues).toEqual([{ league: "cfl", games: [] }]);
  });

  it("stops reading a League nobody asked for in 2 days, and drops its copy", async () => {
    const token = await registerDevice();
    await filled(token, ["nhl"]);
    expect((await env.DB.prepare("SELECT count(*) AS n FROM game").first<{ n: number }>())?.n).toBe(9);
    // Still wanted a day later, though nobody asked again.
    vi.setSystemTime(new Date("2026-11-02T20:00:00Z"));
    forget();
    await fill();
    expect(espn.asked).not.toEqual([]);
    vi.setSystemTime(new Date("2026-11-04T20:00:00Z"));
    forget();
    await fill();

    expect(espn.asked).toEqual([]);
    expect((await env.DB.prepare("SELECT count(*) AS n FROM game").first<{ n: number }>())?.n).toBe(0);
    expect((await env.DB.prepare("SELECT count(*) AS n FROM team").first<{ n: number }>())?.n).toBe(0);
    // Asked again, it is read again.
    expect((await gamesOf(token, ["nhl"])).leagues).toEqual([]);
    await fill();
    expect((await gamesOf(token, ["nhl"])).leagues.map((l) => l.league)).toEqual(["nhl"]);
  });

  it("reaches each League's schedule source at ESPN's path for it, with no user agent, at most GAMES_SUBREQUESTS (17) requests a run", async () => {
    const token = await registerDevice();
    await gamesOf(token, [...LEAGUES]);
    const perRun: number[] = [];
    for (let i = 0; i < 10; i++) {
      const before = espn.requests.length;
      if (i > 0) vi.setSystemTime(Date.now() + 60_000);
      await run();
      perRun.push(espn.requests.length - before);
    }

    expect(Math.max(...perRun)).toBeLessThanOrEqual(GAMES_SUBREQUESTS);
    const paths = {
      nhl: "hockey/nhl",
      nfl: "football/nfl",
      nba: "basketball/nba",
      mlb: "baseball/mlb",
      cfl: "football/cfl",
      mls: "soccer/usa.1",
      "premier-league": "soccer/eng.1",
      "champions-league": "soccer/uefa.champions",
      "europa-league": "soccer/uefa.europa",
      "la-liga": "soccer/esp.1",
      bundesliga: "soccer/ger.1",
      "serie-a": "soccer/ita.1",
      "ligue-1": "soccer/fra.1",
      "efl-championship": "soccer/eng.2",
      "liga-portugal": "soccer/por.1",
      "2-bundesliga": "soccer/ger.2",
      wsl: "soccer/eng.w.1",
      "ncaa-football": "football/college-football",
      "ncaa-basketball": "basketball/mens-college-basketball",
      wnba: "basketball/wnba",
      nrl: "rugby-league/3",
      "top-14": "rugby/270559",
      afl: "australian-football/afl",
      f1: "racing/f1",
      nascar: "racing/nascar-premier",
      indycar: "racing/irl",
      pga: "golf/pga",
      lpga: "golf/lpga",
      ufc: "mma/ufc",
    };
    expect(Object.keys(paths).sort()).toEqual([...LEAGUES].sort());
    for (const path of Object.values(paths)) expect(espn.asked).toContain(`${path}/2026-11-01`);
    // The event Leagues have drivers or golfers and UFC fighters, not teams: no teams list is read for them.
    const noTeams = ["racing/f1", "racing/nascar-premier", "racing/irl", "golf/pga", "golf/lpga", "mma/ufc"];
    for (const path of Object.values(paths).filter((p) => !noTeams.includes(p))) expect(espn.asked).toContain(`${path}/teams`);
    for (const path of noTeams) expect(espn.asked).not.toContain(`${path}/teams`);
    expect(espn.requests.every((r) => r.userAgent === null)).toBe(true);
  });

  it("answers an F1 weekend's Qualifying, Sprint and Race as Games naming the Grand Prix instead of teams: never practice, the sprint qualifying or a result", async () => {
    // ESPN answers each day of the weekend with the whole weekend. (A night race, so its sprint and qualifying are within the day the cloud keeps.)
    const weekend = grandPrix("600057448", "MSC Cruises São Paulo Grand Prix", "MSC Cruises São Paulo GP", "Sao paulo", "Brazil", [
      { type: "FP1", id: "401", date: "2026-10-30T15:30Z", state: "post" },
      { type: "SS", id: "402", date: "2026-10-30T19:30Z", state: "post" },
      { type: "SR", id: "403", date: "2026-10-31T21:00Z", state: "post" },
      { type: "Qual", id: "404", date: "2026-11-01T01:00Z", state: "post" },
      { type: "Race", id: "405", date: "2026-11-01T17:00Z", state: "in" },
    ]);
    for (const day of ["2026-10-31", "2026-11-01"]) espn.scoreboard("racing/f1", day, weekend);
    const nextWeekend = grandPrix("600057449", "Heineken Las Vegas Grand Prix", "Heineken Las Vegas GP", "Las Vegas", "USA", [
      { type: "Qual", id: "501", date: "2026-11-07T06:00Z", state: "pre", statusName: "STATUS_POSTPONED" },
      { type: "Race", id: "502", date: "2026-11-08T06:00Z", state: "pre" },
    ]);
    espn.scoreboard("racing/f1", "2026-11-07", nextWeekend);

    const { leagues } = await filled(await registerDevice(), ["f1"]);

    const logo = "https://a.espncdn.com/i/teamlogos/leagues/500/f1.png";
    const saoPaulo = (session: string) => ({
      name: "MSC Cruises São Paulo Grand Prix",
      shortName: "MSC Cruises São Paulo GP",
      session,
      // What an Event channel may call it: the Grand Prix's name before "Grand Prix", ever shorter (a sponsor's words come first), then the circuit's city and country, each name once however it is accented.
      aliases: ["MSC Cruises São Paulo", "Cruises São Paulo", "São Paulo", "Paulo", "Brazil"],
      logo,
    });
    const lasVegas = { name: "Heineken Las Vegas Grand Prix", shortName: "Heineken Las Vegas GP", session: "race", aliases: ["Heineken Las Vegas", "Las Vegas", "Vegas", "USA"], logo };
    expect(leagues).toEqual([
      {
        league: "f1",
        games: [
          { id: "403", league: "f1", start: "2026-10-31T21:00:00.000Z", state: "over", event: saoPaulo("sprint"), broadcasters: ["Apple TV"] },
          { id: "404", league: "f1", start: "2026-11-01T01:00:00.000Z", state: "over", event: saoPaulo("qualifying"), broadcasters: ["Apple TV"] },
          { id: "405", league: "f1", start: "2026-11-01T17:00:00.000Z", state: "live", event: saoPaulo("race"), broadcasters: ["Apple TV"] },
          // The postponed qualifying is left out.
          { id: "502", league: "f1", start: "2026-11-08T06:00:00.000Z", state: "upcoming", event: lasVegas, broadcasters: ["Apple TV"] },
        ],
      },
    ]);
    // No driver, position or winner: the result never leaves the cloud.
    expect(JSON.stringify(leagues)).not.toMatch(/Verstappen|Hamilton|"(winner|order|athlete|competitors)"/);
  });

  it("answers each NASCAR Cup race as a Game named after its track, the race its one session: never a driver or a result", async () => {
    // ESPN's NASCAR events have one competition, with no session type and no circuit; a day query finds that day's races only.
    espn.scoreboard("racing/nascar-premier", "2026-11-01", stockCarRace("202611010020", "NASCAR Cup Series at Martinsville", "2026-11-01T19:00Z", "in", ["NBC", "HBO Max"]));
    espn.scoreboard("racing/nascar-premier", "2026-11-02", stockCarRace("202611020001", "Daytona 500", "2026-11-02T18:30Z", "pre", ["FOX"]));
    espn.scoreboard(
      "racing/nascar-premier",
      "2026-11-03",
      stockCarRace("202611030044", "Duel #1", "2026-11-03T00:00Z", "pre", ["FS1"], "STATUS_POSTPONED"),
      stockCarRace("202611030045", "NASCAR Cup Series All Star Race", "2026-11-03T22:00Z", "pre", ["FS1"]),
    );

    const { leagues } = await filled(await registerDevice(), ["nascar"]);

    const logo = "https://a.espncdn.com/i/teamlogos/leagues/500/nascar-premier.png";
    expect(leagues).toEqual([
      {
        league: "nascar",
        games: [
          // The short name drops the series' name, which the League's logo beside it says; an Event channel may name the track alone.
          {
            id: "202611010020",
            league: "nascar",
            start: "2026-11-01T19:00:00.000Z",
            state: "live",
            event: { name: "NASCAR Cup Series at Martinsville", shortName: "Martinsville", session: "race", aliases: ["Martinsville"], logo },
            broadcasters: ["NBC", "HBO Max"],
          },
          { id: "202611020001", league: "nascar", start: "2026-11-02T18:30:00.000Z", state: "upcoming", event: { name: "Daytona 500", shortName: "Daytona 500", session: "race", aliases: ["Daytona 500"], logo }, broadcasters: ["FOX"] },
          // The postponed Duel is left out.
          {
            id: "202611030045",
            league: "nascar",
            start: "2026-11-03T22:00:00.000Z",
            state: "upcoming",
            event: { name: "NASCAR Cup Series All Star Race", shortName: "All Star Race", session: "race", aliases: ["All Star Race"], logo },
            broadcasters: ["FS1"],
          },
        ],
      },
    ]);
    expect(JSON.stringify(leagues)).not.toMatch(/Larson|Hamlin|"(winner|order|athlete|competitors)"/);
  });

  it("answers an IndyCar race as a Game naming the race instead of teams: never a result", async () => {
    espn.scoreboard("racing/irl", "2026-11-01", indyCarRace("202611010119", "Grand Prix of Mid-Ohio", "2026-11-01T16:30Z", "in"));
    espn.scoreboard("racing/irl", "2026-11-02", indyCarRace("202611020106", "Indianapolis 500", "2026-11-02T16:00Z", "pre", "FOX"));
    espn.scoreboard("racing/irl", "2026-11-03", indyCarRace("202611034000", "Grand Prix of Indianapolis (Road Course)", "2026-11-03T19:30Z", "pre"));
    espn.scoreboard("racing/irl", "2026-11-04", indyCarRace("202611044252", "Grand Prix of Milwaukee Race 1", "2026-11-04T21:30Z", "pre"));

    const { leagues } = await filled(await registerDevice(), ["indycar"]);

    const logo = "https://a.espncdn.com/i/teamlogos/leagues/500/irl.png";
    // What an Event channel may call a race: its place, without "Grand Prix of", the course or which of the weekend's races it is, and a race named for its distance by its place too.
    const race = (id: string, start: string, state: string, name: string, aliases: string[]) => ({
      id,
      league: "indycar",
      start,
      state,
      event: { name, shortName: name, session: "race", aliases, logo },
      broadcasters: ["FOX"],
    });
    expect(leagues).toEqual([
      {
        league: "indycar",
        games: [
          race("202611010119", "2026-11-01T16:30:00.000Z", "live", "Grand Prix of Mid-Ohio", ["Mid-Ohio"]),
          race("202611020106", "2026-11-02T16:00:00.000Z", "upcoming", "Indianapolis 500", ["Indianapolis 500", "Indianapolis"]),
          race("202611034000", "2026-11-03T19:30:00.000Z", "upcoming", "Grand Prix of Indianapolis (Road Course)", ["Indianapolis"]),
          race("202611044252", "2026-11-04T21:30:00.000Z", "upcoming", "Grand Prix of Milwaukee Race 1", ["Milwaukee"]),
        ],
      },
    ]);
    expect(JSON.stringify(leagues)).not.toMatch(/O'Ward|Palou|"(winner|order|athlete|competitors)"/);
  });

  describe("PGA Tour", () => {
    const logo = "https://a.espncdn.com/i/teamlogos/leagues/500/pga.png";
    const mexicoOpen = (session: string) => ({ name: "VidantaWorld Mexico Open", shortName: "VidantaWorld Mexico Open", session, aliases: ["VidantaWorld Mexico Open", "Mexico Open"], logo });
    // What an Event channel may call it: the name, then ever fewer of its words while one is more than "Championship".
    const wwt = (session: string) => ({ name: "World Wide Technology Championship", shortName: "World Wide Technology Championship", session, aliases: ["World Wide Technology Championship", "Wide Technology Championship", "Technology Championship"], logo });
    const wwtDays = ["2026-11-05", "2026-11-06", "2026-11-07", "2026-11-08", "2026-11-09"];

    it("answers a tournament's rounds as Games, each on its day, at its first tee time once ESPN's leaderboard has it: never a golfer, a score or a position", async () => {
      // Sunday: the Mexico Open's final round is on (ESPN answers each of its days with the whole tournament); next week's hasn't started.
      const sunday = tournament("401850978", "VidantaWorld Mexico Open", "2026-10-29T04:00Z", "2026-11-01T04:00Z", "in", 4);
      for (const day of ["2026-10-31", "2026-11-01"]) espn.scoreboard("golf/pga", day, sunday);
      const next = tournament("401850979", "World Wide Technology Championship", "2026-11-05T05:00Z", "2026-11-08T05:00Z", "pre", 0);
      for (const day of wwtDays) espn.scoreboard("golf/pga", day, next);
      // The leaderboard has the tee times of the round on, the earliest of them first neither in time nor in order.
      espn.leaderboard("pga", leaderboard("401850978", 4, ["2026-11-01T14:50Z", "2026-11-01T12:40Z", "2026-11-01T17:10Z"]));

      const { leagues } = await filled(await registerDevice(), ["pga"], 3);

      expect(leagues).toEqual([
        {
          league: "pga",
          games: [
            // Rounds 1 to 3 started over a day ago.
            { id: "401850978-4", league: "pga", start: "2026-11-01T12:40:00.000Z", state: "live", event: mexicoOpen("final-round"), broadcasters: ["Golf Chnl"] },
            // No tee times yet: each round on its day, at noon US Eastern, with no time to show.
            { id: "401850979-1", league: "pga", start: "2026-11-05T17:00:00.000Z", dayOnly: true, state: "upcoming", event: wwt("round-1"), broadcasters: ["Golf Chnl"] },
            { id: "401850979-2", league: "pga", start: "2026-11-06T17:00:00.000Z", dayOnly: true, state: "upcoming", event: wwt("round-2"), broadcasters: ["Golf Chnl"] },
            { id: "401850979-3", league: "pga", start: "2026-11-07T17:00:00.000Z", dayOnly: true, state: "upcoming", event: wwt("round-3"), broadcasters: ["Golf Chnl"] },
            { id: "401850979-4", league: "pga", start: "2026-11-08T17:00:00.000Z", dayOnly: true, state: "upcoming", event: wwt("final-round"), broadcasters: ["Golf Chnl"] },
          ],
        },
      ]);
      expect(JSON.stringify(leagues)).not.toMatch(/Scheffler|McIlroy|Golfer|"(score|position|earnings|teeTime|athlete|competitors)"/);
    });

    it("ends a round when ESPN says its play is complete, and starts the next at its first tee time", async () => {
      vi.setSystemTime(new Date("2026-11-06T23:00:00Z"));
      const friday = tournament("401850979", "World Wide Technology Championship", "2026-11-05T05:00Z", "2026-11-08T05:00Z", "in", 2, "STATUS_PLAY_COMPLETE");
      for (const day of wwtDays) espn.scoreboard("golf/pga", day, friday);
      espn.leaderboard("pga", leaderboard("401850979", 3, ["2026-11-07T13:05Z", "2026-11-07T18:22Z"]));
      const token = await registerDevice();

      const games = (answer: Awaited<ReturnType<typeof gamesOf>>) => answer.leagues[0].games.map((g) => [g.id, g.start, g.state]);
      expect(games(await filled(token, ["pga"], 3))).toEqual([
        ["401850979-2", "2026-11-06T17:00:00.000Z", "over"],
        ["401850979-3", "2026-11-07T13:05:00.000Z", "upcoming"],
        ["401850979-4", "2026-11-08T17:00:00.000Z", "upcoming"],
      ]);

      // Saturday morning ESPN says round 3 is on before its first tee time: it isn't yet. Then it is.
      vi.setSystemTime(new Date("2026-11-07T12:00:00Z"));
      const saturday = tournament("401850979", "World Wide Technology Championship", "2026-11-05T05:00Z", "2026-11-08T05:00Z", "in", 3);
      for (const day of wwtDays) espn.scoreboard("golf/pga", day, saturday);
      await fill(3);
      expect(games(await gamesOf(token, ["pga"]))[1]).toEqual(["401850979-3", "2026-11-07T13:05:00.000Z", "upcoming"]);
      vi.setSystemTime(new Date("2026-11-07T13:10:00Z"));
      await fill(3);
      expect(games(await gamesOf(token, ["pga"]))[1]).toEqual(["401850979-3", "2026-11-07T13:05:00.000Z", "live"]);
    });

    it("asks the leaderboard only while a tournament is on or ahead", async () => {
      const over = tournament("401850978", "VidantaWorld Mexico Open", "2026-10-29T04:00Z", "2026-11-01T04:00Z", "post", 4);
      for (const day of ["2026-10-31", "2026-11-01"]) espn.scoreboard("golf/pga", day, over);
      await filled(await registerDevice(), ["pga"], 3);
      expect(espn.asked.filter((a) => a.startsWith("golf/leaderboard"))).toEqual([]);
    });

    it("counts the leaderboard against a run's GAMES_SUBREQUESTS requests", async () => {
      // Thursday morning, before the first tee time: the tournament's first day is a current day.
      vi.setSystemTime(new Date("2026-11-05T11:00:00Z"));
      for (const day of wwtDays) espn.scoreboard("golf/pga", day, tournament("401850979", "World Wide Technology Championship", "2026-11-05T05:00Z", "2026-11-08T05:00Z", "pre", 0));
      espn.leaderboard("pga", leaderboard("401850979", 1, ["2026-11-05T13:30Z"]));
      const token = await registerDevice();
      await gamesOf(token, [...LEAGUES]);
      const perRun: number[] = [];
      for (let i = 0; i < 6; i++) {
        const before = espn.requests.length;
        vi.setSystemTime(Date.now() + 60_000);
        await run();
        perRun.push(espn.requests.length - before);
      }
      expect(Math.max(...perRun)).toBeLessThanOrEqual(GAMES_SUBREQUESTS);
      expect(espn.asked).toContain("golf/leaderboard?league=pga");
      expect((await gamesOf(token, ["pga"])).leagues[0].games[0]).toMatchObject({ id: "401850979-1", start: "2026-11-05T13:30:00.000Z" });
    });
  });

  describe("LPGA", () => {
    const logo = "https://a.espncdn.com/i/teamlogos/leagues/500/lpga.png";
    const toto = (session: string) => ({ name: "TOTO Japan Classic", shortName: "TOTO Japan Classic", session, aliases: ["TOTO Japan Classic", "Japan Classic"], logo });
    // ESPN dates it at US Eastern midnights, though it is played in Japan, half a day ahead.
    const totoDays = ["2026-11-05", "2026-11-06", "2026-11-07", "2026-11-08", "2026-11-09"];
    const totoOn = (state: "pre" | "in", period: number) => tournament("401835175", "TOTO Japan Classic", "2026-11-05T05:00Z", "2026-11-08T05:00Z", state, period);

    it("answers a tournament in Asia with its first round on the US evening before its first day, on at its first tee time within minutes", async () => {
      // Wednesday afternoon in Toronto: round 1 tees off at 8 AM Thursday in Japan, 6 PM here. ESPN's Wednesday has no tournament.
      vi.setSystemTime(new Date("2026-11-04T22:50:00Z"));
      for (const day of totoDays) espn.scoreboard("golf/lpga", day, totoOn("pre", 0));
      espn.leaderboard("lpga", leaderboard("401835175", 1, ["2026-11-05T01:12Z", "2026-11-04T23:00Z"]));
      const token = await registerDevice();

      const { leagues } = await filled(token, ["lpga"], 3);

      expect(leagues).toEqual([
        {
          league: "lpga",
          games: [
            { id: "401835175-1", league: "lpga", start: "2026-11-04T23:00:00.000Z", state: "upcoming", event: toto("round-1"), broadcasters: ["Golf Chnl"] },
            { id: "401835175-2", league: "lpga", start: "2026-11-06T17:00:00.000Z", dayOnly: true, state: "upcoming", event: toto("round-2"), broadcasters: ["Golf Chnl"] },
            { id: "401835175-3", league: "lpga", start: "2026-11-07T17:00:00.000Z", dayOnly: true, state: "upcoming", event: toto("round-3"), broadcasters: ["Golf Chnl"] },
            { id: "401835175-4", league: "lpga", start: "2026-11-08T17:00:00.000Z", dayOnly: true, state: "upcoming", event: toto("final-round"), broadcasters: ["Golf Chnl"] },
          ],
        },
      ]);
      expect(JSON.stringify(leagues)).not.toMatch(/Scheffler|McIlroy|Golfer|"(score|position|earnings|teeTime|athlete|competitors)"/);
      // The LPGA's own leaderboard, never the PGA Tour's.
      expect(espn.asked).toContain("golf/leaderboard?league=lpga");
      expect(espn.asked).not.toContain("golf/leaderboard?league=pga");

      // Its first day, tomorrow, is read as often as today, since a golf round may start on the eve of it: Live within minutes, not within the hour.
      vi.setSystemTime(new Date("2026-11-04T23:04:00Z"));
      for (const day of totoDays) espn.scoreboard("golf/lpga", day, totoOn("in", 1));
      await fill();
      expect((await gamesOf(token, ["lpga"])).leagues[0].games[0]).toMatchObject({ id: "401835175-1", state: "live" });
    });

    it("makes a three-round tournament's last round its final round", async () => {
      vi.setSystemTime(new Date("2026-09-24T12:00:00Z"));
      const arkansas = tournament("401835170", "Walmart NW Arkansas Championship pres. by P&G", "2026-09-25T04:00Z", "2026-09-27T04:00Z", "pre", 0);
      for (const day of ["2026-09-25", "2026-09-26", "2026-09-27"]) espn.scoreboard("golf/lpga", day, arkansas);
      espn.leaderboard("lpga", leaderboard("401835170", 1, ["2026-09-25T13:45Z"]));

      const { leagues } = await filled(await registerDevice(), ["lpga"], 3);

      const games = leagues[0].games;
      expect(games.map((g) => [g.id, (g.event as { session: string }).session])).toEqual([
        ["401835170-1", "round-1"],
        ["401835170-2", "round-2"],
        ["401835170-3", "final-round"],
      ]);
      // Without what follows "pres. by", then ever fewer words while one is more than "Championship".
      expect((games[0].event as { aliases: string[] }).aliases).toEqual([
        "Walmart NW Arkansas Championship pres. by P&G",
        "Walmart NW Arkansas Championship",
        "NW Arkansas Championship",
        "Arkansas Championship",
      ]);
    });
  });

  it("answers a UFC fight card as one Game named after its main event, its two fighters in the name's order: never another bout, a winner or a record", async () => {
    const headshot = (id: string) => `https://a.espncdn.com/combiner/i?img=/i/headshots/mma/players/full/${id}.png&w=200&h=146`;
    // Last night's card, over: its result is in ESPN's answer.
    espn.scoreboard(
      "mma/ufc",
      "2026-10-31",
      fightCard("600061420", "UFC Fight Night: Moicano vs. Nolan", "UFC Fight Night", "post", [
        { id: "11", date: "2026-10-31T21:00Z", fighters: [["1", "Jared Gordon"], ["2", "Melquizael Costa"]] },
        { id: "12", date: "2026-11-01T00:00Z", fighters: [["3028863", "Renato Moicano"], ["5144007", "Tom Nolan"]], rounds: 5 },
      ]),
    );
    // Saturday's: prelims at 5 PM Eastern, the main card at 8; ESPN lists the main event last, its fighters in the other order than the name's.
    espn.scoreboard(
      "mma/ufc",
      "2026-11-07",
      fightCard("600061268", "UFC Fight Night: Cortes Acosta vs. Walker", "UFC Fight Night", "pre", [
        { id: "21", date: "2026-11-07T22:00Z", fighters: [["3", "Adrian Yañez"], ["4", "Juan Diaz"]] },
        { id: "22", date: "2026-11-08T01:00Z", fighters: [["5", "Kevin Holland"], ["6", "Uroš Medić"]] },
        { id: "23", date: "2026-11-08T01:00Z", fighters: [["3146944", "Johnny Walker"], ["4903365", "Waldo Cortes Acosta"]], rounds: 5 },
      ]),
      // A card called off is left out.
      fightCard("600061999", "UFC Fight Night: Smith vs. Jones", "UFC Fight Night", "pre", [{ id: "31", date: "2026-11-07T23:00Z", fighters: [["7", "Bob Smith"], ["8", "Tom Jones"]] }], "STATUS_CANCELED"),
    );

    const { leagues } = await filled(await registerDevice(), ["ufc"]);

    expect(leagues).toEqual([
      {
        league: "ufc",
        games: [
          {
            id: "600061420",
            league: "ufc",
            start: "2026-10-31T21:00:00.000Z",
            state: "over",
            fight: {
              name: "UFC Fight Night: Moicano vs. Nolan",
              shortName: "UFC Fight Night",
              mainCard: "2026-11-01T00:00:00.000Z",
              fighters: [
                { name: "Renato Moicano", shortName: "Moicano", aliases: ["Renato Moicano", "Moicano"], headshot: headshot("3028863") },
                { name: "Tom Nolan", shortName: "Nolan", aliases: ["Tom Nolan", "Nolan"], headshot: headshot("5144007") },
              ],
            },
            broadcasters: ["Paramount+"],
          },
          {
            id: "600061268",
            league: "ufc",
            start: "2026-11-07T22:00:00.000Z",
            state: "upcoming",
            fight: {
              name: "UFC Fight Night: Cortes Acosta vs. Walker",
              shortName: "UFC Fight Night",
              mainCard: "2026-11-08T01:00:00.000Z",
              fighters: [
                // A surname of two words, as the event's name gives it.
                { name: "Waldo Cortes Acosta", shortName: "Cortes Acosta", aliases: ["Waldo Cortes Acosta", "Cortes Acosta"], headshot: headshot("4903365") },
                { name: "Johnny Walker", shortName: "Walker", aliases: ["Johnny Walker", "Walker"], headshot: headshot("3146944") },
              ],
            },
            broadcasters: ["Paramount+"],
          },
        ],
      },
    ]);
    // No other bout's fighters, no winner, record or result: the card's outcome never leaves the cloud.
    expect(JSON.stringify(leagues)).not.toMatch(/Gordon|Costa"|Holland|Yañez|Smith|"(winner|order|records?|details|competitors|home|away)"|13-2/);
  });

  it("names a card's fighters by their names when the event's name doesn't give the main event", async () => {
    espn.scoreboard("mma/ufc", "2026-11-01", fightCard("600061500", "UFC 335", "UFC 335", "pre", [{ id: "41", date: "2026-11-02T01:00Z", fighters: [["9", "Ciryl Gane"], ["10", "Josh Hokit"]], rounds: 5 }]));

    const { leagues } = await filled(await registerDevice(), ["ufc"]);

    const fight = leagues[0].games[0].fight as { fighters: { shortName: string; aliases: string[] }[] };
    expect(fight.fighters.map((f) => [f.shortName, f.aliases])).toEqual([
      ["Gane", ["Ciryl Gane", "Gane"]],
      ["Hokit", ["Josh Hokit", "Hokit"]],
    ]);
  });

  it("rejects a malformed request", async () => {
    const token = await registerDevice();
    expect((await call("POST", "/v1/games", { leagues: ["nhl", "boxing"] }, token)).status).toBe(400);
    expect((await call("POST", "/v1/games", { leagues: "nhl" }, token)).status).toBe(400);
    expect((await call("POST", "/v1/games", {}, token)).status).toBe(400);
    expect(await gamesOf(token, [])).toEqual({ leagues: [] });
  });
});

describe("file reads", () => {
  const provider = "a".repeat(64);
  const file = { audio: [{ language: "fre", codec: "audio/eac3", channels: 6 }], subtitles: [], width: 1920, height: 800, hdr: null, bitrate: 8_000_000 };

  it("keeps what one device read for every Viewer of the same Provider", async () => {
    const living = await registerDevice();
    expect((await call("POST", "/v1/file-reads", { provider, files: [{ listingId: 12, file }] }, living)).status).toBe(204);

    const bedroom = await registerDevice();
    const response = await call("POST", "/v1/file-reads/lookup", { provider, listings: [12, 13] }, bedroom);
    expect(response.status).toBe(200);
    expect(await response.json()).toEqual({ files: [{ listingId: 12, file }] });
    // Another Provider's Listing 12 is another file.
    const other = await call("POST", "/v1/file-reads/lookup", { provider: "b".repeat(64), listings: [12] }, bedroom);
    expect(await other.json()).toEqual({ files: [] });
  });

  it("takes a hash of the Provider's host, never the host itself", async () => {
    const token = await registerDevice();
    const response = await call("POST", "/v1/file-reads", { provider: "provider.example:8080", files: [{ listingId: 12, file }] }, token);
    expect(response.status).toBe(400);
  });

  it("refuses reads that aren't small objects", async () => {
    const token = await registerDevice();
    expect((await call("POST", "/v1/file-reads", { provider, files: [{ listingId: 12, file: "x" }] }, token)).status).toBe(400);
    const huge = { audio: Array.from({ length: 500 }, () => ({ language: "eng", codec: "audio/eac3", channels: 6 })) };
    expect((await call("POST", "/v1/file-reads", { provider, files: [{ listingId: 12, file: huge }] }, token)).status).toBe(400);
    expect((await call("POST", "/v1/file-reads/lookup", { provider, listings: [0] }, token)).status).toBe(400);
  });
});

describe("unknown Prefixes", () => {
  const provider = "a".repeat(64);
  const rows = () =>
    env.DB.prepare("SELECT prefix, listing_count, example, first_seen, last_seen FROM unknown_prefix ORDER BY prefix").all().then((r) => r.results);

  it("keeps what each Sync reports, and when each Prefix was first and last seen", async () => {
    const token = await registerDevice();
    vi.useFakeTimers({ toFake: ["Date"] });
    vi.setSystemTime(new Date("2026-10-30T08:00:00Z"));
    const first = { provider, prefixes: [{ prefix: "BR", listingCount: 260, example: "BR - Cold Pursuit (2019)" }] };
    expect((await call("POST", "/v1/unknown-prefixes", first, token)).status).toBe(204);

    vi.setSystemTime(new Date("2026-10-31T08:00:00Z"));
    const second = { provider, prefixes: [{ prefix: "BR", listingCount: 261, example: "BR - Sonic the Hedgehog (2020)" }, { prefix: "CD", listingCount: 1, example: "CD - Call Me Kat (US)" }] };
    expect((await call("POST", "/v1/unknown-prefixes", second, token)).status).toBe(204);

    const day1 = Date.parse("2026-10-30T08:00:00Z"), day2 = Date.parse("2026-10-31T08:00:00Z");
    expect(await rows()).toEqual([
      { prefix: "BR", listing_count: 261, example: "BR - Sonic the Hedgehog (2020)", first_seen: day1, last_seen: day2 },
      { prefix: "CD", listing_count: 1, example: "CD - Call Me Kat (US)", first_seen: day2, last_seen: day2 },
    ]);
  });

  it("takes a hash of the Provider's host and well-formed Prefixes", async () => {
    const token = await registerDevice();
    const report = (body: unknown) => call("POST", "/v1/unknown-prefixes", body, token).then((r) => r.status);
    expect(await report({ provider: "provider.example:8080", prefixes: [] })).toBe(400);
    expect(await report({ provider, prefixes: [{ prefix: "BR", listingCount: 0, example: "BR - X" }] })).toBe(400);
    expect(await report({ provider, prefixes: [{ prefix: "", listingCount: 1, example: "X" }] })).toBe(400);
    expect(await report({ provider, prefixes: "BR" })).toBe(400);
  });

  const candidates = () =>
    env.DB.prepare("SELECT provider, word, listing_count, example, first_seen, last_seen FROM tier_tag_candidate ORDER BY provider, word").all().then((r) => r.results);

  it("keeps each Provider's candidate Tier tags with when each was first and last seen (#439)", async () => {
    const token = await registerDevice();
    const other = "b".repeat(64);
    vi.useFakeTimers({ toFake: ["Date"] });
    vi.setSystemTime(new Date("2026-11-02T08:00:00Z"));
    const first = { provider, prefixes: [], tierTags: [{ word: "PLUS", listingCount: 40, example: "PLUS: TNT SPORT 1 HD" }] };
    expect((await call("POST", "/v1/unknown-prefixes", first, token)).status).toBe(204);
    expect((await call("POST", "/v1/unknown-prefixes", { provider: other, prefixes: [], tierTags: [{ word: "PLUS", listingCount: 2, example: "PLUS: SKY ONE" }] }, token)).status).toBe(204);

    vi.setSystemTime(new Date("2026-11-09T08:00:00Z"));
    const second = {
      provider,
      prefixes: [{ prefix: "BR", listingCount: 2, example: "BR - Cold Pursuit (2019)" }],
      tierTags: [{ word: "PLUS", listingCount: 42, example: "PLUS: TNT SPORT 2 HD" }, { word: "MAX", listingCount: 12, example: "MAX: TNT SPORT 1 SD" }],
    };
    expect((await call("POST", "/v1/unknown-prefixes", second, token)).status).toBe(204);

    const week1 = Date.parse("2026-11-02T08:00:00Z"), week2 = Date.parse("2026-11-09T08:00:00Z");
    expect(await candidates()).toEqual([
      { provider, word: "MAX", listing_count: 12, example: "MAX: TNT SPORT 1 SD", first_seen: week2, last_seen: week2 },
      { provider, word: "PLUS", listing_count: 42, example: "PLUS: TNT SPORT 2 HD", first_seen: week1, last_seen: week2 },
      { provider: other, word: "PLUS", listing_count: 2, example: "PLUS: SKY ONE", first_seen: week1, last_seen: week1 },
    ]);
    expect((await rows()).map((r) => r.prefix)).toContain("BR");
  });

  it("takes a report without candidate Tier tags, as older apps send, and only well-formed ones", async () => {
    const token = await registerDevice();
    const report = (body: unknown) => call("POST", "/v1/unknown-prefixes", body, token).then((r) => r.status);
    expect(await report({ provider, prefixes: [{ prefix: "BR", listingCount: 1, example: "BR - X" }] })).toBe(204);
    expect(await report({ provider, prefixes: [], tierTags: "PLUS" })).toBe(400);
    expect(await report({ provider, prefixes: [], tierTags: [{ word: "PLUS", listingCount: 0, example: "PLUS: X" }] })).toBe(400);
    expect(await report({ provider, prefixes: [], tierTags: [{ word: "TWO WORDS", listingCount: 1, example: "X" }] })).toBe(400);
    expect(await report({ provider, prefixes: [], tierTags: Array.from({ length: 51 }, (_, i) => ({ word: `W${i}`, listingCount: 1, example: "X" })) })).toBe(400);
  });
});
