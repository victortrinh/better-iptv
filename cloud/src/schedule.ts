/**
 * The Leagues the sports hub offers (CONTEXT.md: League), by the id devices and the cloud share:
 * fifteen team Leagues, whose Games are two teams', the event Leagues (#310), whose Games have
 * no teams: F1's are a Grand Prix's sessions, NASCAR Cup's its races, and UFC, a fight-card League
 * (#316), whose Games are fight cards, named after their main event's two fighters.
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
  "f1",
  "nascar",
  "ufc",
] as const;

export type League = (typeof LEAGUES)[number];

/** The event Leagues: their Games have no teams, but an event ({@link GameEvent}). */
export const EVENT_LEAGUES: readonly League[] = ["f1", "nascar"];

/** The fight-card Leagues: their Games have no teams, but a fight card ({@link GameFight}). */
export const FIGHT_LEAGUES: readonly League[] = ["ufc"];

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

/** Which of a Grand Prix weekend's sessions an event League's Game is. Practice and the sprint's qualifying are no Games. */
export type GameSession = "qualifying" | "sprint" | "race";

/**
 * What an event League's Game is part of, in place of two teams: the event's [name] (Pirelli
 * British Grand Prix) and [shortName] as the source gives them, sponsor and all, the [session], the
 * names an Event channel may call it by in [aliases] (São Paulo, Brazil), and the League's [logo].
 */
export interface GameEvent {
  name: string;
  shortName: string;
  session: GameSession;
  aliases: string[];
  logo: string | null;
}

/**
 * One of a fight card's main-event fighters: their [name] (Waldo Cortes Acosta), [shortName] (the
 * surname the card's name gives, Cortes Acosta), the names an Event channel may call them by in
 * [aliases] (those two), and their [headshot] URL, which may not exist.
 */
export interface GameFighter {
  name: string;
  shortName: string;
  aliases: string[];
  headshot: string | null;
}

/**
 * A fight card, a fight-card League's Game in place of two teams: its [name] (UFC 334: Gane vs.
 * Hokit) and [shortName] (UFC 334) as the source gives them, when its [mainCard] starts (UTC ISO
 * 8601), and its main event's two [fighters], in the name's order. Never another bout, a result or a record.
 */
export interface GameFight {
  name: string;
  shortName: string;
  mainCard: string;
  fighters: GameFighter[];
}

/**
 * A scheduled contest of a League (CONTEXT.md: Game): its [id] at the source, when it [start]s (UTC
 * ISO 8601), its [state], both teams (a team League's), its [event] (an event League's) or its
 * [fight] (a fight-card League's), and the names of the national Broadcasters the source says carry
 * it. The device finds its Feeds by the teams', the event's or the fighters' aliases and the
 * Broadcasters' names (#254).
 */
export interface Game {
  id: string;
  league: League;
  start: string;
  state: GameState;
  home?: GameTeam;
  away?: GameTeam;
  event?: GameEvent;
  fight?: GameFight;
  broadcasters: string[];
}

/** A League's team as the source lists it: the names it goes by, for Games whose day lacks them, and its logo for dark backgrounds, which Games show over the day's. */
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
  /** Every team of [league], read now and then for aliases and logos; none for an event or fight-card League. */
  teams(league: League): Promise<Team[]>;
}

/** The source couldn't answer right now (outage, refusal, an unreadable answer); the copy kept stands. */
export class ScheduleUnavailable extends Error {}
