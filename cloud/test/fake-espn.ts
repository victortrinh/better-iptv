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
  private readonly days = new Map<string, object[]>();
  private readonly teamLists = new Map<string, object[]>();

  constructor(tmdb: FakeTmdb) {
    tmdb.alsoServe(API, (url, init) => {
      const userAgent = new Headers(init?.headers).get("User-Agent");
      this.requests.push({ url, userAgent });
      if (userAgent !== null && /Mozilla|Chrome|Safari/.test(userAgent)) return new Response("Access Denied", { status: 403 });
      if (this.failWith !== null) return new Response("", { status: this.failWith });
      const match = /^\/apis\/site\/v2\/sports\/(.+)\/(scoreboard|teams)$/.exec(url.pathname);
      if (!match) return new Response("Not found", { status: 404 });
      const [, path, what] = match;
      if (what === "teams") return Response.json({ sports: [{ leagues: [{ teams: (this.teamLists.get(path) ?? []).map((t) => ({ team: t })) }] }] });
      const dates = url.searchParams.get("dates");
      if (dates !== null && !/^\d{8}$/.test(dates)) return new Response("", { status: 400 });
      const day = dates ?? new Date().toISOString().slice(0, 10).replaceAll("-", "");
      return Response.json({ leagues: [{ slug: path }], day: { date: `${day.slice(0, 4)}-${day.slice(4, 6)}-${day.slice(6)}` }, events: this.days.get(`${path}/${day}`) ?? [] });
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

  /** The scoreboard requests that reached ESPN, as `hockey/nhl/2026-11-01`, and the teams requests as `hockey/nhl/teams`. */
  get asked(): string[] {
    return this.requests.map(({ url }) => {
      const path = url.pathname.replace("/apis/site/v2/sports/", "");
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
  location: string;
  name: string;
  nickname?: string | null;
  logo?: string;
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
    team: { id: team.id, uid: `s:70~l:90~t:${team.id}`, displayName: team.displayName, shortDisplayName: team.shortDisplayName, abbreviation: team.abbreviation, location: team.location, name: team.name, nickname: team.nickname ?? null, logo: team.logo ?? `https://a.espncdn.com/i/teamlogos/500/scoreboard/${team.abbreviation.toLowerCase()}.png` },
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

/** A team shaped like ESPN's teams list gives it, with its logos in ESPN's order (default first). */
export function team(spec: TeamSpec): object {
  const { logo, ...rest } = spec;
  const file = spec.abbreviation.toLowerCase();
  return {
    ...rest,
    slug: spec.displayName.toLowerCase().replaceAll(" ", "-"),
    isActive: true,
    logos: [
      { href: logo ?? `https://a.espncdn.com/i/teamlogos/500/${file}.png`, rel: ["full", "default"], width: 500, height: 500 },
      { href: `https://a.espncdn.com/i/teamlogos/500-dark/${file}.png`, rel: ["full", "dark"], width: 500, height: 500 },
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
