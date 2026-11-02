/**
 * The fifteen Leagues the sports hub offers (CONTEXT.md: League), by the id devices and the cloud
 * share. Team sports only: F1 and UFC are a follow-up.
 */
export const LEAGUES = [
  "nhl",
  "nfl",
  "nba",
  "mlb",
  "cfl",
  "mls",
  "premier-league",
  "champions-league",
  "la-liga",
  "bundesliga",
  "serie-a",
  "ligue-1",
  "ncaa-football",
  "ncaa-basketball",
  "wnba",
] as const;

export type League = (typeof LEAGUES)[number];

export const isLeague = (value: unknown): value is League => typeof value === "string" && (LEAGUES as readonly string[]).includes(value);

/** Where a Game stands: not started, in progress, or finished. Never how far along, and never a score. */
export type GameState = "upcoming" | "live" | "over";

/**
 * A team as a Game names it: its [name] (Toronto Maple Leafs), [shortName] (Maple Leafs) and
 * [abbreviation] (TOR), every name it goes by in [aliases] (those three, its location and nickname,
 * no two alike), its [logo] URL, and its [id] at the source, which its entry in the League's teams
 * ({@link Team}) carries too.
 */
export interface GameTeam {
  id: string;
  name: string;
  shortName: string;
  abbreviation: string;
  aliases: string[];
  logo: string | null;
}

/**
 * A scheduled contest of a League (CONTEXT.md: Game): its [id] at the source, when it [start]s (UTC
 * ISO 8601), its [state], both teams, and the names of the national Broadcasters the source says
 * carry it. The device finds its Feeds by the teams' aliases and the Broadcasters' names (#254).
 */
export interface Game {
  id: string;
  league: League;
  start: string;
  state: GameState;
  home: GameTeam;
  away: GameTeam;
  broadcasters: string[];
}

/** A League's team as the source lists it: the names it goes by and a logo, for Games whose day lacks them. */
export interface Team {
  id: string;
  aliases: string[];
  logo: string | null;
}

/**
 * Where a League's schedule comes from. One source serves a League (ESPN's scoreboard API serves
 * every League in M3); another can take a League over without the Games endpoint changing.
 */
export interface ScheduleSource {
  /** The Games of [league] on [day] (`YYYY-MM-DD`, in the source's own reckoning of a day), as the source has them now. */
  games(league: League, day: string): Promise<Game[]>;
  /** Every team of [league], read now and then for aliases and logos. */
  teams(league: League): Promise<Team[]>;
}

/** The source couldn't answer right now (outage, refusal, an unreadable answer); the copy kept stands. */
export class ScheduleUnavailable extends Error {}
