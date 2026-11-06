import type { Env } from "./env";
import { EVENT_LEAGUES, FIGHT_LEAGUES, type Game, type GameFighter, GOLF_LEAGUES, type GameSession, type GameState, type GameTeam, type League, type ScheduleSource, ScheduleUnavailable, type Team } from "./schedule";

/**
 * Each League's path under ESPN's site API (`sports/<path>/scoreboard`, `/teams`). The first fifteen team
 * Leagues checked 2026-11-01 with a plain fetch, F1, NASCAR Cup, IndyCar, the PGA Tour, the LPGA and UFC on 2026-11-03,
 * #354's on 2026-11-04 and the Europa League on 2026-11-06: each answers two-team Games (`competitors`, home and away) and a teams list.
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
  afl: "australian-football/afl",
  f1: "racing/f1",
  nascar: "racing/nascar-premier",
  indycar: "racing/irl",
  pga: "golf/pga",
  lpga: "golf/lpga",
  ufc: "mma/ufc",
};

/** Each golf League's tour on ESPN's leaderboard (`golf/leaderboard?league=<tour>`), checked 2026-11-03. */
const TOURS: Partial<Record<League, string>> = { pga: "pga", lpga: "lpga" };

/** A day, and the hours from a golf tournament's day's local midnight, as ESPN dates it, to noon there: an untimed round's start. */
const DAY = 86_400_000;
const NOON = 12 * 3600_000;

/**
 * The F1 sessions that are Games, by ESPN's session type id (`type.id`: 1 practice, 2 qualifying,
 * 3 race, 5 the sprint's qualifying, 6 the sprint; checked 2026-11-03 on three sprint weekends).
 */
const SESSIONS: Record<string, GameSession> = { "2": "qualifying", "3": "race", "6": "sprint" };

/**
 * The event Leagues whose events are each one race, a single competition with no session type
 * (NASCAR Cup: "NASCAR Cup Series at Martinsville", "Daytona 500", "Duel #1"; checked 2026-11-03
 * across the 2026 season; IndyCar: "Grand Prix of Mid-Ohio", "Indianapolis 500"). Their events
 * carry no circuit: the name is all there is to match by.
 */
const ONE_RACE: readonly League[] = ["nascar", "indycar"];

/** The series' name an event's name starts with, which the League's logo beside its short name already says. */
const SERIES = /^NASCAR Cup Series\s+(at\s+)?/i;

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
    if (GOLF_LEAGUES.includes(league)) {
      const logo = defaultLogo(scoreboard.leagues?.[0]?.logos);
      // Tee times are only on the leaderboard: asked only while a tournament is on or ahead.
      const on = scoreboard.events.some((event) => (event.competitions?.[0]?.status ?? event.status)?.type?.state !== "post");
      const teeTimes = on ? await this.teeTimes(TOURS[league] ?? league) : new Map<string, number>();
      return scoreboard.events.flatMap((event) => readRounds(league, event, logo, teeTimes));
    }
    if (EVENT_LEAGUES.includes(league)) {
      const logo = defaultLogo(scoreboard.leagues?.[0]?.logos);
      return scoreboard.events.flatMap((event) => readSessions(league, event, logo));
    }
    if (FIGHT_LEAGUES.includes(league)) {
      return scoreboard.events.flatMap((event) => {
        const game = readCard(league, event);
        return game ? [game] : [];
      });
    }
    return scoreboard.events.flatMap((event) => {
      const game = readGame(league, event);
      return game ? [game] : [];
    });
  }

  async teams(league: League): Promise<Team[]> {
    if (EVENT_LEAGUES.includes(league) || FIGHT_LEAGUES.includes(league)) return [];
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

  /**
   * The first tee time of each round ESPN's leaderboard of [tour] has them for, by `<event id>/<round>`
   * (ms since the epoch): it gives each golfer's for the round on or next, a day ahead at most. Its
   * positions, scores and earnings are never read.
   */
  private async teeTimes(tour: string): Promise<Map<string, number>> {
    const path = `golf/leaderboard?league=${tour}`;
    const board = (await this.get(path)) as Leaderboard | null;
    if (!Array.isArray(board?.events)) throw new ScheduleUnavailable(`ESPN answered no events for ${path}`);
    const first = new Map<string, number>();
    for (const event of board.events) {
      for (const golfer of event.competitions?.[0]?.competitors ?? []) {
        const at = Date.parse(golfer.status?.teeTime ?? "");
        const round = golfer.status?.period;
        if (!event.id || typeof round !== "number" || Number.isNaN(at)) continue;
        const key = `${event.id}/${round}`;
        first.set(key, Math.min(at, first.get(key) ?? Infinity));
      }
    }
    return first;
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
 * [event], a Grand Prix weekend or a race ({@link ONE_RACE}), as a Game per session that is one
 * ({@link SESSIONS}), each with its own start, state and Broadcasters, naming the event instead of
 * teams. ESPN answers every day of a weekend with the whole weekend; the same session read on two days is the same Game. The
 * drivers ESPN lists, in finishing order, are never read.
 */
function readSessions(league: League, event: EspnEvent, logo: string | null): Game[] {
  const name = text(event.name);
  if (!event.id || name === null) return [];
  const oneRace = ONE_RACE.includes(league);
  const shortName = oneRace ? (text(event.shortName) ?? name).replace(SERIES, "") || name : (text(event.shortName) ?? name);
  const names = league === "nascar" ? raceAliases(shortName) : eventAliases(name, event.circuit?.address);
  return (event.competitions ?? []).flatMap((competition) => {
    const session = oneRace ? "race" : SESSIONS[String(competition.type?.id ?? "")];
    const type = competition.status?.type;
    const state = type?.state === undefined ? undefined : STATES[type.state];
    const start = new Date(competition.date ?? "");
    if (!competition.id || !session || state === undefined || (type?.name !== undefined && NOT_PLAYED.has(type.name)) || Number.isNaN(start.getTime())) return [];
    return [{ id: String(competition.id), league, start: start.toISOString(), state, event: { name, shortName, session, aliases: names, logo }, broadcasters: national(competition) }];
  });
}

/**
 * [event], a golf tournament, as a Game per round, one a day from its first to its last (the
 * final round), naming the tournament instead of teams. ESPN dates a tournament at the local
 * midnights of its first and last days and times no round, so a round starts at the first tee time
 * in [teeTimes] once the leaderboard has one, and until then at noon on its day, [dayOnly]. ESPN's
 * status says which round is on: those before it are over, those after upcoming, and it is live
 * from its first tee time till ESPN says its play is complete. ESPN answers every day of a
 * tournament with all of it; the same round read on two days is the same Game. The field and its
 * scores are never read.
 */
function readRounds(league: League, event: EspnEvent, logo: string | null, teeTimes: Map<string, number>): Game[] {
  const name = text(event.name);
  const first = Date.parse(event.date ?? "");
  const last = Date.parse(event.endDate ?? event.date ?? "");
  const competition = event.competitions?.[0];
  const type = (competition?.status ?? event.status)?.type;
  const state = type?.state === undefined ? undefined : STATES[type.state];
  if (!event.id || name === null || Number.isNaN(first) || Number.isNaN(last) || state === undefined || (type?.name !== undefined && NOT_PLAYED.has(type.name))) return [];
  const on = (competition?.status ?? event.status)?.period ?? 0;
  const complete = type?.name === "STATUS_PLAY_COMPLETE";
  const rounds = Math.min(Math.max(Math.round((last - first) / DAY) + 1, 1), 5);
  const shortName = text(event.shortName) ?? name;
  const names = tournamentAliases(name);
  const now = Date.now();
  return Array.from({ length: rounds }, (_, i): Game => {
    const round = i + 1;
    const teeTime = teeTimes.get(`${event.id}/${round}`);
    const start = teeTime ?? first + i * DAY + NOON;
    const roundState: GameState =
      state !== "live" ? state : round < on || (round === on && complete) ? "over" : round > on || (teeTime !== undefined && teeTime > now) ? "upcoming" : "live";
    const session: GameSession = round === rounds ? "final-round" : (`round-${round}` as GameSession);
    return {
      id: `${event.id}-${round}`,
      league,
      start: new Date(start).toISOString(),
      ...(teeTime === undefined ? { dayOnly: true as const } : {}),
      state: roundState,
      event: { name, shortName, session, aliases: names, logo },
      broadcasters: competition ? national(competition) : [],
    };
  });
}

/** The words a golf tournament's name ends with that name no tournament alone. */
const GENERIC = /^(the|championships?|open|classic|invitational|tournament|challenge|cup|pro-am|at|of)$/i;

/**
 * What an Event channel may call a golf tournament named [name]: the name, then, without what
 * follows "presented by" (`pres. by`), ever fewer of its first words, a sponsor's first, while a
 * word is left that isn't a generic one ({@link GENERIC}): "VidantaWorld Mexico Open", "Mexico
 * Open", never "Open", which every Open is called.
 */
function tournamentAliases(name: string): string[] {
  const words = name.replace(/\s+(pres\.|presented)\s+by\b.*$/i, "").split(/\s+/).filter((w) => w !== "");
  const shorter = words.map((_, i) => words.slice(i)).filter((rest) => rest.some((w) => !GENERIC.test(w))).map((rest) => rest.join(" "));
  return [...new Set([name, ...shorter])];
}

/**
 * [event], a fight card, as one Game from its first bout's start to its last, named after its main
 * event: the last bout ESPN lists, whose start is the main card's and whose Broadcasters are the
 * card's. Of every bout, only the main event's two fighters are read, by name and athlete id
 * (for a headshot): never a winner, a record, a bout's status or another bout's fighters, which
 * give a result away.
 */
function readCard(league: League, event: EspnEvent): Game | null {
  const name = text(event.name);
  const bouts = event.competitions ?? [];
  const main = bouts[bouts.length - 1];
  const type = event.status?.type;
  const state = type?.state === undefined ? undefined : STATES[type.state];
  if (!event.id || name === null || !main || state === undefined || (type?.name !== undefined && NOT_PLAYED.has(type.name))) return null;
  const starts = bouts.map((b) => Date.parse(b.date ?? "")).filter((t) => !Number.isNaN(t));
  const mainCard = Date.parse(main.date ?? "");
  if (starts.length === 0 || Number.isNaN(mainCard)) return null;
  // The main event as the card's name gives it ("UFC 334: Gane vs. Hokit"), one surname a side.
  const sides = name.replace(/^[^:]*:\s*/, "").split(/\s+vs\.?\s+/i).map((side) => side.trim());
  const fighters = (main.competitors ?? []).flatMap((c) => {
    const full = text(c.athlete?.displayName);
    if (!c.id || full === null) return [];
    const side = sides.length === 2 ? sides.findIndex((s) => fold(full).endsWith(` ${fold(s)}`) || fold(full) === fold(s)) : -1;
    const shortName = side >= 0 ? sides[side] : full.split(/\s+/).slice(1).join(" ") || full;
    const fighter: GameFighter = { name: full, shortName, aliases: [...new Set([full, shortName])], headshot: `${HEADSHOTS}${encodeURIComponent(String(c.id))}.png&w=200&h=146` };
    return [{ fighter, side: side >= 0 ? side : Number.MAX_SAFE_INTEGER }];
  });
  if (fighters.length !== 2) return null;
  fighters.sort((a, b) => a.side - b.side);
  return {
    id: String(event.id),
    league,
    start: new Date(Math.min(...starts)).toISOString(),
    state,
    fight: { name, shortName: text(event.shortName) ?? name, mainCard: new Date(mainCard).toISOString(), fighters: fighters.map((f) => f.fighter) },
    broadcasters: national(main),
  };
}

/** ESPN's headshots of MMA fighters by athlete id, resized to a card's half (not in the scoreboard; checked 2026-11-03, an unknown id answers 404). */
const HEADSHOTS = "https://a.espncdn.com/combiner/i?img=/i/headshots/mma/players/full/";

/** [name] as names are compared: lower case, accents dropped. */
const fold = (name: string): string => name.normalize("NFD").replace(/\p{M}/gu, "").toLowerCase();

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
    const folded = fold(n);
    if (seen.has(folded)) return false;
    seen.add(folded);
    return true;
  });
}

/**
 * What an Event channel may call a NASCAR race whose short name is [shortName] ("Martinsville", "Daytona
 * 500", "Clash at Bowman Gray"): the short name, and the track it is "at" when it names one.
 */
function raceAliases(shortName: string): string[] {
  const track = / at (.+)$/i.exec(shortName)?.[1];
  return track ? [shortName, track] : [shortName];
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
    ...colour("color", team.color),
    ...colour("alternateColor", team.alternateColor),
  };
}

/** [value] as `{[key]: rrggbb}` when it is a hex colour as ESPN writes one (`00205B`, a `#` allowed), else nothing. */
const colour = <K extends string>(key: K, value: unknown): Partial<Record<K, string>> => {
  const hex = text(value)?.replace(/^#/, "").toLowerCase();
  return hex && /^[0-9a-f]{6}$/.test(hex) ? ({ [key]: hex } as Record<K, string>) : {};
};

/** Every name ESPN gives a team, each once, in the order the device should try them. */
const aliases = (team: EspnTeam): string[] =>
  [...new Set([team.displayName, team.shortDisplayName, team.abbreviation, team.location, team.name, team.nickname].map(text).filter((n): n is string => n !== null))];

const text = (value: unknown): string | null => (typeof value === "string" && value.trim() !== "" ? value.trim() : null);

/** What the app reads of ESPN's answers; the rest, scores among it, is never looked at. */
interface Scoreboard {
  leagues?: { logos?: { href?: string; rel?: string[] }[] }[];
  events?: EspnEvent[];
}

/** One of ESPN's scoreboard events: a team League's Game, a Grand Prix weekend, a golf tournament or a fight card, not the glossary's Event channel. */
interface EspnEvent {
  id?: string | number;
  date?: string;
  endDate?: string;
  name?: unknown;
  shortName?: unknown;
  circuit?: { address?: { city?: unknown; country?: unknown } } | null;
  status?: EspnStatus;
  competitions?: EspnCompetition[];
}

/** Where a Game stands: [period] is a golf tournament's round on, and never leaves here. */
interface EspnStatus {
  period?: number;
  type?: { name?: string; state?: string };
}

/** What the app reads of ESPN's golf leaderboard: who tees off when, in which round. Positions and scores are never looked at. */
interface Leaderboard {
  events?: { id?: string | number; competitions?: { competitors?: { status?: { period?: unknown; teeTime?: string } }[] }[] }[];
}

/** A team League's game, one session of a Grand Prix weekend, a whole golf tournament, or one bout of a fight card. */
interface EspnCompetition {
  id?: string | number;
  date?: string;
  type?: { id?: string | number };
  status?: EspnStatus;
  competitors?: { id?: string | number; homeAway?: string; team?: EspnTeam; athlete?: { displayName?: unknown } }[];
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
  color?: unknown;
  alternateColor?: unknown;
}

interface TeamList {
  sports?: { leagues?: { teams?: { team?: EspnTeam }[] }[] }[];
}
