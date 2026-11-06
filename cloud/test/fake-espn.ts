import type { FakeTmdb } from "./fake-tmdb";

/** ESPN's site API root, as the Worker's `ESPN_API` says. */
const API = "https://site.api.espn.com/apis/site/v2/sports/";

/**
 * Stands in for ESPN's scoreboard API, served through [tmdb]'s fetch stand-in. Days are added with
 * [scoreboard] and a League's teams with [teams]; [requests] records what reached ESPN. Like ESPN,
 * it takes one day (`dates=YYYYMMDD`) and refuses a date range (400) and a browser-like User-Agent (403).
 */
export class FakeEspn {
  readonly requests: { url: URL; userAgent: string | null }[] = [];
  /** Answers every request with this HTTP status instead, as when ESPN is down. */
  failWith: number | null = null;
  /** Answers every request with HTTP 200 and a JSON `null` body, as ESPN has been seen to. */
  answerNull = false;
  private readonly days = new Map<string, object[]>();
  private readonly teamLists = new Map<string, object[]>();
  private readonly leaderboards = new Map<string, object>();

  constructor(tmdb: FakeTmdb) {
    tmdb.alsoServe(API, (url, init) => {
      const userAgent = new Headers(init?.headers).get("User-Agent");
      this.requests.push({ url, userAgent });
      if (userAgent !== null && /Mozilla|Chrome|Safari/.test(userAgent)) return new Response("Access Denied", { status: 403 });
      if (this.failWith !== null) return new Response("", { status: this.failWith });
      if (this.answerNull) return Response.json(null);
      if (url.pathname === "/apis/site/v2/sports/golf/leaderboard") {
        const board = this.leaderboards.get(url.searchParams.get("league") ?? "");
        return board ? Response.json(board) : new Response("Not found", { status: 404 });
      }
      const match = /^\/apis\/site\/v2\/sports\/(.+)\/(scoreboard|teams)$/.exec(url.pathname);
      if (!match) return new Response("Not found", { status: 404 });
      const [, path, what] = match;
      if (what === "teams") return Response.json({ sports: [{ leagues: [{ teams: (this.teamLists.get(path) ?? []).map((t) => ({ team: t })) }] }] });
      const dates = url.searchParams.get("dates");
      if (dates !== null && !/^\d{8}$/.test(dates)) return new Response("", { status: 400 });
      const day = dates ?? new Date().toISOString().slice(0, 10).replaceAll("-", "");
      const logo = `https://a.espncdn.com/i/teamlogos/leagues/500/${path.split("/").pop()}.png`;
      return Response.json({ leagues: [{ slug: path, logos: [{ href: logo, rel: ["full", "default"] }, { href: logo.replace("http", "https"), rel: ["full", "dark"] }] }], day: { date: `${day.slice(0, 4)}-${day.slice(4, 6)}-${day.slice(6)}` }, events: this.days.get(`${path}/${day}`) ?? [] });
    });
  }

  /** ESPN's scoreboard of the League at [path] (`hockey/nhl`) on [day] (`YYYY-MM-DD`): [events], each shaped like ESPN's ({@link event}). Replaces the day. */
  scoreboard(path: string, day: string, ...events: object[]) {
    this.days.set(`${path}/${day.replaceAll("-", "")}`, events);
  }

  /** ESPN's list of the League's teams at [path]: [teams], each shaped like ESPN's ({@link team}). */
  teams(path: string, ...teams: object[]) {
    this.teamLists.set(path, teams);
  }

  /** ESPN's golf leaderboard for the tour [league] (`pga`): [board], shaped like ESPN's ({@link leaderboard}). */
  leaderboard(league: string, board: object) {
    this.leaderboards.set(league, board);
  }

  /** The scoreboard requests that reached ESPN, as `hockey/nhl/2026-11-01`, the teams requests as `hockey/nhl/teams`, and the leaderboard's as `golf/leaderboard?league=pga`. */
  get asked(): string[] {
    return this.requests.map(({ url }) => {
      const path = url.pathname.replace("/apis/site/v2/sports/", "");
      if (path === "golf/leaderboard") return `${path}?league=${url.searchParams.get("league")}`;
      if (path.endsWith("/teams")) return path;
      const dates = url.searchParams.get("dates") ?? "";
      return `${path.replace(/\/scoreboard$/, "")}/${dates.slice(0, 4)}-${dates.slice(4, 6)}-${dates.slice(6)}`;
    });
  }
}

/** A team as ESPN's scoreboard and teams list name it. */
export interface TeamSpec {
  id: string;
  displayName: string;
  shortDisplayName: string;
  abbreviation: string;
  /** None for the NRL's and the AFL's teams, which ESPN names by nickname or place alone. */
  location: string | null;
  name: string;
  nickname?: string | null;
  logo?: string;
  color?: string;
  alternateColor?: string;
}

/**
 * An event shaped like ESPN's scoreboard gives it, [home] against [away] at [date] (UTC ISO 8601)
 * in [state] (`pre`, `in`, `post`), with [broadcasts] as `geoBroadcasts` (`National`, `Home` or
 * `Away` market, region `us`). Like ESPN's, it carries a score, a period and a clock, which the
 * cloud must never pass on. [statusName] is ESPN's `status.type.name` (`STATUS_POSTPONED`…).
 */
export function event(
  id: string,
  date: string,
  state: "pre" | "in" | "post",
  home: TeamSpec,
  away: TeamSpec,
  broadcasts: { name: string; market?: "National" | "Home" | "Away" }[] = [],
  statusName?: string,
): object {
  const competitor = (homeAway: "home" | "away", team: TeamSpec, score: string) => ({
    id: team.id,
    uid: `s:70~l:90~t:${team.id}`,
    type: "team",
    order: homeAway === "home" ? 0 : 1,
    homeAway,
    score,
    team: { id: team.id, uid: `s:70~l:90~t:${team.id}`, displayName: team.displayName, shortDisplayName: team.shortDisplayName, abbreviation: team.abbreviation, location: team.location, name: team.name, nickname: team.nickname ?? null, logo: team.logo ?? `https://a.espncdn.com/i/teamlogos/500/scoreboard/${team.abbreviation.toLowerCase()}.png`, ...(team.color === undefined ? {} : { color: team.color }), ...(team.alternateColor === undefined ? {} : { alternateColor: team.alternateColor }) },
    statistics: [],
    records: [{ name: "overall", summary: "5-3-1" }],
  });
  const typeName = statusName ?? { pre: "STATUS_SCHEDULED", in: "STATUS_IN_PROGRESS", post: "STATUS_FINAL" }[state];
  return {
    id,
    uid: `s:70~l:90~e:${id}`,
    date,
    name: `${away.displayName} at ${home.displayName}`,
    shortName: `${away.abbreviation} @ ${home.abbreviation}`,
    season: { year: 2026, type: 2 },
    status: {
      clock: state === "in" ? 754.0 : 0.0,
      displayClock: state === "in" ? "12:34" : "0:00",
      period: state === "pre" ? 0 : 2,
      type: { id: "2", name: typeName, state, completed: state === "post", description: "In Progress", detail: "2nd Period", shortDetail: "2nd" },
    },
    competitions: [
      {
        id,
        date,
        neutralSite: false,
        competitors: [competitor("home", home, state === "pre" ? "0" : "3"), competitor("away", away, state === "pre" ? "0" : "1")],
        broadcasts: [{ market: "national", names: broadcasts.filter((b) => (b.market ?? "National") === "National").map((b) => b.name) }],
        geoBroadcasts: broadcasts.map((b) => ({
          type: { id: "1", shortName: "TV" },
          market: { id: "1", type: b.market ?? "National" },
          media: { shortName: b.name },
          lang: "en",
          region: "us",
        })),
        status: { clock: 0, period: 2, type: { state } },
      },
    ],
  };
}

/** A team shaped like ESPN's teams list gives it, with its logos in ESPN's order (default first), and one for dark backgrounds unless [dark] is false. */
export function team(spec: TeamSpec, { dark = true } = {}): object {
  const { logo, ...rest } = spec;
  const file = spec.abbreviation.toLowerCase();
  return {
    ...rest,
    slug: spec.displayName.toLowerCase().replaceAll(" ", "-"),
    isActive: true,
    logos: [
      { href: logo ?? `https://a.espncdn.com/i/teamlogos/500/${file}.png`, rel: ["full", "default"], width: 500, height: 500 },
      ...(dark ? [{ href: `https://a.espncdn.com/i/teamlogos/500-dark/${file}.png`, rel: ["full", "dark"], width: 500, height: 500 }] : []),
      { href: `https://a.espncdn.com/i/teamlogos/500/scoreboard/${file}.png`, rel: ["full", "scoreboard"], width: 500, height: 500 },
    ],
  };
}

export const leafs: TeamSpec = { id: "21", displayName: "Toronto Maple Leafs", shortDisplayName: "Maple Leafs", abbreviation: "TOR", location: "Toronto", name: "Maple Leafs", nickname: null };
export const habs: TeamSpec = { id: "10", displayName: "Montreal Canadiens", shortDisplayName: "Canadiens", abbreviation: "MTL", location: "Montreal", name: "Canadiens", nickname: null };
export const bruins: TeamSpec = { id: "1", displayName: "Boston Bruins", shortDisplayName: "Bruins", abbreviation: "BOS", location: "Boston", name: "Bruins", nickname: null };
export const sens: TeamSpec = { id: "14", displayName: "Ottawa Senators", shortDisplayName: "Senators", abbreviation: "OTT", location: "Ottawa", name: "Senators", nickname: null };
export const bombers: TeamSpec = { id: "3", displayName: "Winnipeg Blue Bombers", shortDisplayName: "Blue Bombers", abbreviation: "WPG", location: "Winnipeg", name: "Blue Bombers" };
export const argos: TeamSpec = { id: "7", displayName: "Toronto Argonauts", shortDisplayName: "Argonauts", abbreviation: "TOR", location: "Toronto", name: "Argonauts" };
export const broncos: TeamSpec = { id: "7", displayName: "Denver Broncos", shortDisplayName: "Broncos", abbreviation: "DEN", location: "Denver", name: "Broncos" };
export const texans: TeamSpec = { id: "34", displayName: "Houston Texans", shortDisplayName: "Texans", abbreviation: "HOU", location: "Houston", name: "Texans" };

/** One Game's two teams in each League #354 and #375 added, as ESPN's scoreboards named them on 2026-11-04 (the NRL's and the AFL's from their grand finals; the Europa League's on 2026-11-06, from 2026-11-05). */
export const added: Record<string, { path: string; home: TeamSpec; away: TeamSpec }> = {
  "europa-league": {
    path: "soccer/uefa.europa",
    home: { id: "103", displayName: "AC Milan", shortDisplayName: "Milan", abbreviation: "MIL", location: "AC Milan", name: "AC Milan", color: "e4002b", alternateColor: "ffffff" },
    away: { id: "622", displayName: "Ferencvaros", shortDisplayName: "Ferencvaros", abbreviation: "FER", location: "Ferencvaros", name: "Ferencvaros", color: "239B56", alternateColor: "000000" },
  },
  "efl-championship": {
    path: "soccer/eng.2",
    home: { id: "347", displayName: "Cardiff City", shortDisplayName: "Cardiff", abbreviation: "CAR", location: "Cardiff City", name: "Cardiff City", color: "0000fa", alternateColor: "c6d4db" },
    away: { id: "371", displayName: "West Ham United", shortDisplayName: "West Ham", abbreviation: "WHU", location: "West Ham United", name: "West Ham United", color: "7c2c3b", alternateColor: "F1E7E0" },
  },
  "liga-portugal": {
    path: "soccer/por.1",
    home: { id: "5297", displayName: "FC Famalicao", shortDisplayName: "FC Famalicao", abbreviation: "FCF", location: "FC Famalicao", name: "FC Famalicao" },
    away: { id: "15784", displayName: "Arouca", shortDisplayName: "Arouca", abbreviation: "FCA", location: "Arouca", name: "Arouca" },
  },
  "2-bundesliga": {
    path: "soccer/ger.2",
    home: { id: "3536", displayName: "Dynamo Dresden", shortDisplayName: "D Dresden", abbreviation: "SGD", location: "Dynamo Dresden", name: "Dynamo Dresden" },
    away: { id: "3428", displayName: "SpVgg Greuther Fürth", shortDisplayName: "Greuther Furth", abbreviation: "SGF", location: "SpVgg Greuther Fürth", name: "SpVgg Greuther Fürth" },
  },
  wsl: {
    path: "soccer/eng.w.1",
    // ESPN writes their short names with a trailing space.
    home: { id: "18244", displayName: "Chelsea", shortDisplayName: "Chelsea ", abbreviation: "CHE", location: "Chelsea", name: "Chelsea" },
    away: { id: "18245", displayName: "Everton", shortDisplayName: "Everton ", abbreviation: "EVE", location: "Everton", name: "Everton" },
  },
  nrl: {
    path: "rugby-league/3",
    home: { id: "13", displayName: "Roosters", shortDisplayName: "Roosters", abbreviation: "SYD", location: null, name: "Roosters" },
    away: { id: "6", displayName: "Knights", shortDisplayName: "Knights", abbreviation: "NEW", location: null, name: "Knights" },
  },
  afl: {
    path: "australian-football/afl",
    home: { id: "6", displayName: "Fremantle", shortDisplayName: "Fremantle", abbreviation: "FRE", location: null, name: "Fremantle" },
    away: { id: "2", displayName: "Brisbane Lions", shortDisplayName: "Brisbane Lions", abbreviation: "BL", location: null, name: "Brisbane Lions" },
  },
};

/** A driver as ESPN's racing scoreboard lists one among a session's competitors. */
const driver = (id: string, name: string, order: number) => ({ id, type: "athlete", order, winner: order === 1, athlete: { displayName: name, shortName: name, flag: { alt: "Netherlands" } } });

/**
 * A Grand Prix weekend shaped like ESPN's F1 scoreboard gives it (`racing/f1`, checked 2026-11-03):
 * one event named after the Grand Prix ([name], [shortName]) at a circuit in [city], [country],
 * dated at its first session, with a competition per session in [sessions]: ESPN's session type
 * (`FP1`, `SS` for the sprint qualifying, `SR` for the sprint, `Qual`, `Race`), its start and its
 * state. Each lists drivers in finishing order, as ESPN does: the result, which the cloud must
 * never pass on. Every session is on [broadcast] in the US.
 */
export function grandPrix(
  id: string,
  name: string,
  shortName: string,
  city: string,
  country: string,
  sessions: { type: "FP1" | "FP2" | "FP3" | "SS" | "SR" | "Qual" | "Race"; id: string; date: string; state: "pre" | "in" | "post"; statusName?: string }[],
  broadcast = "Apple TV",
): object {
  const typeIds = { FP1: "1", FP2: "1", FP3: "1", Qual: "2", Race: "3", SS: "5", SR: "6" };
  const status = (state: "pre" | "in" | "post", statusName?: string) => ({
    clock: 0,
    displayClock: "0:00",
    period: state === "pre" ? 0 : 41,
    type: { id: "1", name: statusName ?? { pre: "STATUS_SCHEDULED", in: "STATUS_IN_PROGRESS", post: "STATUS_FINAL" }[state], state, completed: state === "post", description: "Scheduled" },
  });
  const last = sessions[sessions.length - 1];
  return {
    id,
    uid: `s:2000~l:2030~e:${id}`,
    date: sessions[0].date,
    endDate: last.date,
    name,
    shortName,
    season: { year: 2026, type: 2 },
    circuit: { id: "617", fullName: "Autodromo Jose Carlos Pace", address: { city, country } },
    status: status(last.state),
    competitions: sessions.map((s) => ({
      id: s.id,
      uid: `s:2000~l:2030~e:${id}~c:${s.id}`,
      date: s.date,
      startDate: s.date,
      type: { id: typeIds[s.type], abbreviation: s.type },
      status: status(s.state, s.statusName),
      competitors: [driver("4665", "Max Verstappen", 1), driver("868", "Lewis Hamilton", 2)],
      broadcasts: [{ market: "national", names: [broadcast] }],
      geoBroadcasts: [{ type: { id: "1", shortName: "TV" }, market: { id: "1", type: "National" }, media: { shortName: broadcast }, lang: "en", region: "us" }],
    })),
  };
}

/**
 * A NASCAR Cup race shaped like ESPN's scoreboard gives it (`racing/nascar-premier`, checked
 * 2026-11-03): one event named [name] ("NASCAR Cup Series at Martinsville", "Daytona 500"), its
 * short name the same, no circuit, and one competition with no session type, listing drivers in
 * finishing order: the result, which the cloud must never pass on. [broadcasts] carry it in the US.
 */
export function stockCarRace(id: string, name: string, date: string, state: "pre" | "in" | "post", broadcasts: string[], statusName?: string): object {
  const status = {
    clock: 0,
    displayClock: "0:00",
    period: state === "pre" ? 0 : 412,
    type: { id: "1", name: statusName ?? { pre: "STATUS_SCHEDULED", in: "STATUS_IN_PROGRESS", post: "STATUS_FINAL" }[state], state, completed: state === "post", description: "Scheduled" },
  };
  return {
    id,
    uid: `s:2000~l:2042~e:${id}`,
    date,
    name,
    shortName: name,
    season: { year: 2026, type: 2, slug: "regular-season" },
    circuit: null,
    status,
    competitions: [
      {
        id,
        uid: `s:2000~l:2042~e:${id}~c:${id}`,
        date,
        startDate: date,
        type: null,
        status,
        competitors: [driver("4513", "Kyle Larson", 1), driver("3888", "Denny Hamlin", 2)],
        broadcasts: [{ market: "national", names: broadcasts }],
        geoBroadcasts: broadcasts.map((b) => ({ type: { id: "1", shortName: "TV" }, market: { id: "1", type: "National" }, media: { shortName: b }, lang: "en", region: "us" })),
      },
    ],
  };
}

/**
 * A race shaped like ESPN's IndyCar scoreboard gives it (`racing/irl`, checked 2026-11-03 against
 * St. Petersburg, the Indianapolis 500, Mid-Ohio and Monterey): one event a race, named and short
 * named alike ("Grand Prix of Mid-Ohio"), with no circuit, and a single competition, the race, that
 * has the event's id and no session type. Only race day answers; practice and qualifying aren't
 * listed. Its drivers come in finishing order, as ESPN lists them: the result, never passed on.
 */
export function indyCarRace(id: string, name: string, date: string, state: "pre" | "in" | "post", broadcast = "FOX"): object {
  const status = { period: state === "pre" ? 0 : 95, type: { id: "1", name: { pre: "STATUS_SCHEDULED", in: "STATUS_IN_PROGRESS", post: "STATUS_FINAL" }[state], state, completed: state === "post", description: "Scheduled" } };
  return {
    id,
    uid: `s:2000~l:2040~e:${id}`,
    date,
    name,
    shortName: name,
    season: { year: 2026, type: 2, slug: "regular-season" },
    status,
    competitions: [
      {
        id,
        uid: `s:2000~l:2040~e:${id}~c:${id}`,
        date,
        startDate: date,
        timeValid: true,
        status,
        competitors: [driver("5581", "Pato O'Ward", 1), driver("4569", "Alex Palou", 2)],
        broadcasts: [{ market: "national", names: [broadcast] }],
        broadcast,
        geoBroadcasts: [{ type: { id: "1", shortName: "TV" }, market: { id: "1", type: "National" }, media: { shortName: broadcast }, lang: "en", region: "us" }],
      },
    ],
  };
}

/** A golfer as ESPN's golf scoreboard and leaderboard list one: a name, a flag, a score to par and a position, all the result. */
const golfer = (id: string, name: string, order: number) => ({ id, type: "athlete", order, athlete: { fullName: name, displayName: name, shortName: name, flag: { alt: "USA" } }, score: `-${20 - order}`, linescores: [{ period: 1, value: 67 }] });

/**
 * A tournament shaped like ESPN's golf scoreboard gives it (`golf/pga`, checked 2026-11-03): one
 * event named [name] with one competition and its field, dated at the local (US Eastern) midnights
 * of its first and last days ([date], [endDate]) with `timeValid` false, and no round's time. Its
 * status says which round is on ([period]) and how it stands ([statusName], [state]). The field is
 * listed in leaderboard order, the result the cloud must never pass on. Every round is on Golf Channel.
 */
export function tournament(id: string, name: string, date: string, endDate: string, state: "pre" | "in" | "post", period: number, statusName?: string): object {
  const status = { period, type: { id: "1", name: statusName ?? { pre: "STATUS_SCHEDULED", in: "STATUS_IN_PROGRESS", post: "STATUS_FINAL" }[state], state, completed: state === "post", description: "Scheduled", detail: `Round ${period}` } };
  return {
    id,
    uid: `s:1100~l:1106~e:${id}`,
    date,
    endDate,
    name,
    shortName: name,
    season: { year: 2026, type: 2 },
    status,
    competitions: [
      {
        id,
        date,
        endDate,
        timeValid: false,
        competitors: [golfer("9478", "Scottie Scheffler", 1), golfer("3470", "Rory McIlroy", 2)],
        status,
        broadcasts: [{ market: "national", names: ["Golf Chnl"] }],
        geoBroadcasts: [{ type: { id: "1", shortName: "TV" }, market: { id: "1", type: "National" }, media: { shortName: "Golf Chnl" }, lang: "en", region: "us" }],
      },
    ],
  };
}

/**
 * ESPN's golf leaderboard (`golf/leaderboard?league=pga`, checked 2026-11-03) for tournament [id]:
 * the only place with tee times, each player's for the round on or next ([period]), at [teeTimes]
 * one by one; positions, scores and earnings too, which never leave the cloud.
 */
export function leaderboard(id: string, period: number, teeTimes: string[]): object {
  return {
    events: [
      {
        id,
        competitions: [
          {
            id,
            competitors: teeTimes.map((teeTime, i) => ({
              ...golfer(String(100 + i), `Golfer ${i}`, i + 1),
              earnings: 100_000,
              status: { period, teeTime, startHole: 1, thru: 0, position: { displayName: `T${i + 1}` }, type: { state: "pre", name: "STATUS_SCHEDULED" } },
            })),
          },
        ],
      },
    ],
  };
}

/** A fighter as ESPN's MMA scoreboard lists one in a bout: their athlete id, corner, the result and their record, which the cloud must never pass on. */
const fighter = (id: string, name: string, order: number, winner: boolean) => ({
  id,
  type: "athlete",
  order,
  winner,
  athlete: { fullName: name, displayName: name, shortName: `${name[0]}. ${name.split(" ").slice(1).join(" ")}`, flag: { alt: "France", rel: ["country-flag"] } },
  records: [{ name: "overall", abbreviation: "TOT", type: "total", summary: winner ? "13-2-0" : "12-3-0" }],
});

/**
 * A UFC fight card shaped like ESPN's MMA scoreboard gives it (`mma/ufc`, checked 2026-11-03): one
 * event named after its main event ([name]: "UFC 334: Gane vs. Hokit", [shortName] "UFC 334"),
 * dated at its first bout, with a competition per bout in [bouts], the main event last: each
 * with its two fighters (id and name, as ESPN lists them, not always in the event name's order), its
 * start and its state. The first fighter listed wins, with a record that says so, as ESPN gives the
 * result: the cloud must never pass it on. Every bout is on [broadcast] in the US.
 */
export function fightCard(
  id: string,
  name: string,
  shortName: string,
  state: "pre" | "in" | "post",
  bouts: { id: string; date: string; fighters: [string, string][]; rounds?: number }[],
  statusName?: string,
  broadcast = "Paramount+",
): object {
  const status = (s: "pre" | "in" | "post", n?: string) => ({
    clock: 0,
    displayClock: "-",
    period: s === "pre" ? 0 : 3,
    type: { id: "1", name: n ?? { pre: "STATUS_SCHEDULED", in: "STATUS_IN_PROGRESS", post: "STATUS_FINAL" }[s], state: s, completed: s === "post", description: "Scheduled" },
  });
  return {
    id,
    uid: `s:3301~l:3321~e:${id}`,
    date: bouts[0].date,
    name,
    shortName,
    season: { year: 2026, type: 2 },
    venues: [{ id: "1830", fullName: "Madison Square Garden", address: { city: "New York", state: "NY", country: "USA" } }],
    status: status(state, statusName),
    competitions: bouts.map((b) => ({
      id: b.id,
      uid: `s:3301~l:3321~e:${id}~c:${b.id}`,
      date: b.date,
      startDate: b.date,
      type: { id: "982", abbreviation: "Heavyweight" },
      format: { regulation: { periods: b.rounds ?? 3 } },
      status: status(state),
      competitors: b.fighters.map(([fid, fname], i) => fighter(fid, fname, i + 1, i === 0 && state === "post")),
      details: [{ type: { text: "Unofficial Winner Kotko" } }],
      broadcasts: [{ market: "national", names: [broadcast] }],
      geoBroadcasts: [{ type: { id: "1", shortName: "TV" }, market: { id: "1", type: "National" }, media: { shortName: broadcast }, lang: "en", region: "us" }],
    })),
  };
}
