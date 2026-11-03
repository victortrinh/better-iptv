import type { Env } from "./env";
import { EVENT_LEAGUES, type Game, type GameSession, type GameState, type GameTeam, type League, type ScheduleSource, ScheduleUnavailable, type Team } from "./schedule";

/**
 * Each League's path under ESPN's site API (`sports/<path>/scoreboard`, `/teams`). The fifteen team
 * Leagues checked 2026-11-01 with a plain fetch, F1 and IndyCar on 2026-11-03.
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
  f1: "racing/f1",
  indycar: "racing/irl",
};

/**
 * The F1 sessions that are Games, by ESPN's session type id (`type.id`: 1 practice, 2 qualifying,
 * 3 race, 5 the sprint's qualifying, 6 the sprint; checked 2026-11-03 on three sprint weekends).
 * A competition with no type is a race: IndyCar's events are each one race, its only competition.
 */
const SESSIONS: Record<string, GameSession> = { "2": "qualifying", "3": "race", "6": "sprint" };

/** ESPN's `status.type.state`, as the app says it. */
const STATES: Record<string, GameState> = { pre: "upcoming", in: "live", post: "over" };

/** Games that won't be played as scheduled are left out rather than shown as over. */
const NOT_PLAYED = new Set(["STATUS_POSTPONED", "STATUS_CANCELED", "STATUS_CANCELLED"]);

/**
 * ESPN's public scoreboard API as a schedule source, for every League offered. It answers a plain
 * fetch and refuses a browser-like User-Agent, so none is sent; it takes one day at a time
 * (`?dates=YYYYMMDD`, a range gets 400), its days running on US Eastern time. Its answers carry
 * scores and periods, which never leave here: a Game is built field by field.
 */
export class Espn implements ScheduleSource {
  constructor(private readonly env: Env) {}

  async games(league: League, day: string): Promise<Game[]> {
    const path = `${PATHS[league]}/scoreboard?dates=${day.replaceAll("-", "")}`;
    const scoreboard = (await this.get(path)) as Scoreboard | null;
    if (!Array.isArray(scoreboard?.events)) throw new ScheduleUnavailable(`ESPN answered no events for ${path}`);
    if (EVENT_LEAGUES.includes(league)) {
      const logo = defaultLogo(scoreboard.leagues?.[0]?.logos);
      return scoreboard.events.flatMap((event) => readSessions(league, event, logo));
    }
    return scoreboard.events.flatMap((event) => {
      const game = readGame(league, event);
      return game ? [game] : [];
    });
  }

  async teams(league: League): Promise<Team[]> {
    if (EVENT_LEAGUES.includes(league)) return [];
    const path = `${PATHS[league]}/teams`;
    const listed = (await this.get(path)) as TeamList | null;
    if (!Array.isArray(listed?.sports)) throw new ScheduleUnavailable(`ESPN answered no teams for ${path}`);
    return listed.sports.flatMap((sport) => sport.leagues ?? []).flatMap((l) => l.teams ?? []).flatMap(({ team }) => {
      if (!team?.id) return [];
      // The logo ESPN marks for dark backgrounds, as the cards' glass is: a black one (the Raiders') vanishes on it.
      const marked = (rel: string) => team.logos?.find((l) => l.rel?.includes(rel))?.href;
      const logo = marked("dark") ?? marked("default") ?? team.logos?.[0]?.href ?? null;
      return [{ id: String(team.id), aliases: aliases(team), logo }];
    });
  }

  /** ESPN's JSON answer for [path], or {@link ScheduleUnavailable} when it gave none: an answer it can't be read from is no answer. */
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
function readGame(league: League, event: EspnEvent): Game | null {
  const competition = event.competitions?.[0];
  const type = event.status?.type;
  const state = type?.state === undefined ? undefined : STATES[type.state];
  if (!competition || !event.id || state === undefined || (type?.name !== undefined && NOT_PLAYED.has(type.name))) return null;
  const home = competition.competitors?.find((c) => c.homeAway === "home")?.team;
  const away = competition.competitors?.find((c) => c.homeAway === "away")?.team;
  const start = new Date(event.date ?? "");
  if (!home?.id || !away?.id || Number.isNaN(start.getTime())) return null;
  return { id: String(event.id), league, start: start.toISOString(), state, home: readTeam(home), away: readTeam(away), broadcasters: national(competition) };
}

/**
 * [event], a Grand Prix weekend or an IndyCar race, as a Game per session that is one ({@link
 * SESSIONS}), each with its own start, state and Broadcasters, naming the event instead of teams. ESPN answers every day
 * of a weekend with the whole weekend; the same session read on two days is the same Game. The
 * drivers ESPN lists, in finishing order, are never read.
 */
function readSessions(league: League, event: EspnEvent, logo: string | null): Game[] {
  const name = text(event.name);
  if (!event.id || name === null) return [];
  const shortName = text(event.shortName) ?? name;
  const names = eventAliases(name, event.circuit?.address);
  return (event.competitions ?? []).flatMap((competition) => {
    const session = competition.type === undefined ? "race" : SESSIONS[String(competition.type.id ?? "")];
    const type = competition.status?.type;
    const state = type?.state === undefined ? undefined : STATES[type.state];
    const start = new Date(competition.date ?? "");
    if (!competition.id || !session || state === undefined || (type?.name !== undefined && NOT_PLAYED.has(type.name)) || Number.isNaN(start.getTime())) return [];
    return [{ id: String(competition.id), league, start: start.toISOString(), state, event: { name, shortName, session, aliases: names, logo }, broadcasters: national(competition) }];
  });
}

/**
 * What an Event channel may call an event named [name]: its place, the words before "Grand Prix"
 * (F1's) or after "Grand Prix of" (IndyCar's, the course in brackets and which of a weekend's races
 * it is left off), then ever fewer of them, since a sponsor's come first ("MSC Cruises São Paulo",
 * …, "São Paulo", "Paulo"), never a number alone; a race named for its distance is also called by
 * its place ("Indianapolis 500", "Indianapolis"); then the circuit's city and country, each once
 * however it is cased or accented.
 */
function eventAliases(name: string, address: { city?: unknown; country?: unknown } | undefined): string[] {
  const place = name
    .replace(/\s*\(.*?\)/g, "")
    .replace(/^(Grand Prix|GP)\s+(of|at)\s+/i, "")
    .replace(/\s+Race\s+\d+$/i, "")
    .replace(/\s+(Grand Prix|GP)\b.*$/i, "");
  const words = place.split(/\s+/).filter((w) => w !== "");
  const shorter = words.map((_, i) => words.slice(i).join(" ")).filter((n) => !/^\d+$/.test(n));
  const distance = words.length > 1 && /^\d+$/.test(words[words.length - 1]) ? [words.slice(0, -1).join(" ")] : [];
  const found = [...shorter, ...distance, text(address?.city), text(address?.country)].filter((n): n is string => n !== null);
  const seen = new Set<string>();
  return found.filter((n) => {
    const folded = n.normalize("NFD").replace(/\p{M}/gu, "").toLowerCase();
    if (seen.has(folded)) return false;
    seen.add(folded);
    return true;
  });
}

/** The national Broadcasters ESPN names for [competition], each once. */
function national(competition: EspnCompetition): string[] {
  const names = (competition.geoBroadcasts ?? [])
    .filter((b) => b.market?.type === "National")
    .map((b) => b.media?.shortName?.trim() ?? "")
    .filter((n) => n !== "");
  return [...new Set(names)];
}

/** The logo ESPN marks as the default among [logos], else its first. */
const defaultLogo = (logos: { href?: string; rel?: string[] }[] | undefined): string | null => logos?.find((l) => l.rel?.includes("default"))?.href ?? logos?.[0]?.href ?? null;

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
  leagues?: { logos?: { href?: string; rel?: string[] }[] }[];
  events?: EspnEvent[];
}

/** One of ESPN's scoreboard events: a team League's Game or a Grand Prix weekend, not the glossary's Event channel. */
interface EspnEvent {
  id?: string | number;
  date?: string;
  name?: unknown;
  shortName?: unknown;
  circuit?: { address?: { city?: unknown; country?: unknown } };
  status?: { type?: { name?: string; state?: string } };
  competitions?: EspnCompetition[];
}

/** A team League's game, or one session of a Grand Prix weekend. */
interface EspnCompetition {
  id?: string | number;
  date?: string;
  type?: { id?: string | number };
  status?: { type?: { name?: string; state?: string } };
  competitors?: { homeAway?: string; team?: EspnTeam }[];
  geoBroadcasts?: { market?: { type?: string }; media?: { shortName?: string } }[];
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
