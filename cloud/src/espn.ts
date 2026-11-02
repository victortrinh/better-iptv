import type { Env } from "./env";
import { type Game, type GameState, type GameTeam, type League, type ScheduleSource, ScheduleUnavailable, type Team } from "./schedule";

/**
 * Each League's path under ESPN's site API (`sports/<path>/scoreboard`, `/teams`). All fifteen
 * checked 2026-11-01 with a plain fetch.
 */
const PATHS: Record<League, string> = {
  nhl: "hockey/nhl",
  nfl: "football/nfl",
  nba: "basketball/nba",
  mlb: "baseball/mlb",
  cfl: "football/cfl",
  mls: "soccer/usa.1",
  "premier-league": "soccer/eng.1",
  "champions-league": "soccer/uefa.champions",
  "la-liga": "soccer/esp.1",
  bundesliga: "soccer/ger.1",
  "serie-a": "soccer/ita.1",
  "ligue-1": "soccer/fra.1",
  "ncaa-football": "football/college-football",
  "ncaa-basketball": "basketball/mens-college-basketball",
  wnba: "basketball/wnba",
};

/** ESPN's `status.type.state`, as the app says it. */
const STATES: Record<string, GameState> = { pre: "upcoming", in: "live", post: "over" };

/** Games that won't be played as scheduled are left out rather than shown as over. */
const NOT_PLAYED = new Set(["STATUS_POSTPONED", "STATUS_CANCELED", "STATUS_CANCELLED"]);

/**
 * ESPN's public scoreboard API as a schedule source, for every League in M3. It answers a plain
 * fetch and refuses a browser-like User-Agent, so none is sent; it takes one day at a time
 * (`?dates=YYYYMMDD`, a range gets 400), its days running on US Eastern time. Its answers carry
 * scores and periods, which never leave here: a Game is built field by field.
 */
export class Espn implements ScheduleSource {
  constructor(private readonly env: Env) {}

  async games(league: League, day: string): Promise<Game[]> {
    const scoreboard = (await this.get(`${PATHS[league]}/scoreboard?dates=${day.replaceAll("-", "")}`)) as Scoreboard;
    return (scoreboard.events ?? []).flatMap((event) => {
      const game = readGame(league, event);
      return game ? [game] : [];
    });
  }

  async teams(league: League): Promise<Team[]> {
    const listed = (await this.get(`${PATHS[league]}/teams`)) as TeamList;
    return (listed.sports ?? []).flatMap((sport) => sport.leagues ?? []).flatMap((l) => l.teams ?? []).flatMap(({ team }) => {
      if (!team?.id) return [];
      const logo = team.logos?.find((l) => l.rel?.includes("default"))?.href ?? team.logos?.[0]?.href ?? null;
      return [{ id: String(team.id), aliases: aliases(team), logo }];
    });
  }

  private async get(path: string): Promise<unknown> {
    let response: Response;
    try {
      response = await fetch(`${this.env.ESPN_API}/${path}`);
    } catch (e) {
      throw new ScheduleUnavailable(`ESPN couldn't be reached for ${path}: ${e instanceof Error ? e.message : e}`);
    }
    if (!response.ok) throw new ScheduleUnavailable(`ESPN answered HTTP ${response.status} for ${path}`);
    try {
      return await response.json();
    } catch {
      throw new ScheduleUnavailable(`ESPN answered something other than JSON for ${path}`);
    }
  }
}

/** [event] as a Game, or null when it is no Game the hub can show: no two teams, no start, not to be played. */
function readGame(league: League, event: Event): Game | null {
  const competition = event.competitions?.[0];
  const type = event.status?.type;
  const state = type?.state === undefined ? undefined : STATES[type.state];
  if (!competition || !event.id || state === undefined || (type?.name !== undefined && NOT_PLAYED.has(type.name))) return null;
  const home = competition.competitors?.find((c) => c.homeAway === "home")?.team;
  const away = competition.competitors?.find((c) => c.homeAway === "away")?.team;
  const start = new Date(event.date ?? "");
  if (!home?.id || !away?.id || Number.isNaN(start.getTime())) return null;
  const broadcasters = (competition.geoBroadcasts ?? [])
    .filter((b) => b.market?.type === "National")
    .map((b) => b.media?.shortName?.trim() ?? "")
    .filter((name) => name !== "");
  return { id: String(event.id), league, start: start.toISOString(), state, home: readTeam(home), away: readTeam(away), broadcasters: [...new Set(broadcasters)] };
}

function readTeam(team: EspnTeam): GameTeam {
  const names = aliases(team);
  return {
    id: String(team.id),
    name: text(team.displayName) ?? names[0] ?? "",
    shortName: text(team.shortDisplayName) ?? text(team.displayName) ?? names[0] ?? "",
    abbreviation: text(team.abbreviation) ?? "",
    aliases: names,
    logo: text(team.logo) ?? null,
  };
}

/** Every name ESPN gives a team, each once, in the order the device should try them. */
const aliases = (team: EspnTeam): string[] =>
  [...new Set([team.displayName, team.shortDisplayName, team.abbreviation, team.location, team.name, team.nickname].map(text).filter((n): n is string => n !== null))];

const text = (value: unknown): string | null => (typeof value === "string" && value.trim() !== "" ? value.trim() : null);

/** What the app reads of ESPN's answers; the rest, scores among it, is never looked at. */
interface Scoreboard {
  events?: Event[];
}

interface Event {
  id?: string | number;
  date?: string;
  status?: { type?: { name?: string; state?: string } };
  competitions?: {
    competitors?: { homeAway?: string; team?: EspnTeam }[];
    geoBroadcasts?: { market?: { type?: string }; media?: { shortName?: string } }[];
  }[];
}

interface EspnTeam {
  id?: string | number;
  displayName?: unknown;
  shortDisplayName?: unknown;
  abbreviation?: unknown;
  location?: unknown;
  name?: unknown;
  nickname?: unknown;
  logo?: unknown;
  logos?: { href?: string; rel?: string[] }[];
}

interface TeamList {
  sports?: { leagues?: { teams?: { team?: EspnTeam }[] }[] }[];
}
