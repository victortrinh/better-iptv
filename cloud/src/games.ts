import type { Env } from "./env";
import { Espn } from "./espn";
import { type Game, type GameTeam, isLeague, type League, type ScheduleSource, ScheduleUnavailable, type Team } from "./schedule";

/** A wanted League's current days are read again this often, so "Live" flips within minutes of a Game starting. */
const CURRENT_FRESH_FOR = 5 * 60_000;
/** Its coming days this often: a schedule for the week ahead changes slowly. */
const COMING_FRESH_FOR = 3600_000;
/** Its teams this often: aliases and logos change between seasons. */
const TEAMS_FRESH_FOR = 7 * 24 * 3600_000;
/** The days after today a League's Games are kept for: the week ahead the hub shows. */
const COMING_DAYS = 7;
/** A Game that started longer ago than this is dropped: ESPN's CFL endpoint answers out of season with a game from 2022. */
const KEEP_GAMES_FOR = 24 * 3600_000;
/**
 * A part the Cron Trigger started this long ago and that is still due waits till then: one that
 * stopped its run (out of CPU, say) or whose source was down doesn't hold up the parts after it.
 * Shorter than the current days' freshness, so a day read on time is read again on time.
 */
const RETRY_AFTER = 2 * 60_000;
/** A League not asked for in 30 days is no longer kept fresh, and its copy is dropped. */
const WANTED_FOR = 30 * 24 * 3600_000;
/** How long when a League was asked for is left as it is, so most answers write nothing. */
const ASKED_EVERY = 3600_000;
/**
 * The requests one run of the Cron Trigger may send a schedule source, out of the run's
 * {@link SUBREQUESTS} that the Charts share: fifteen Leagues' current days every 5 minutes and
 * their coming days hourly need 8 a minute at most.
 */
export const GAMES_SUBREQUESTS = 12;

/** One League's Games in the answer to `/v1/games`: today's and the week ahead's, by start. */
export interface LeagueGames {
  league: League;
  games: Game[];
}

/** The schedule source serving [league]: ESPN's scoreboard API for every League in M3; another may take a League over here. */
export function scheduleSource(env: Env, _league: League): ScheduleSource {
  return new Espn(env);
}

/**
 * The Games of [leagues] for today and the next [COMING_DAYS] days, as the cloud keeps them. Only
 * D1 is read, never the source: the Cron Trigger ([fillGames]) reads it, so an answer costs little.
 * The Leagues are noted as wanted, so a new one is read within the next minutes; till then, it is
 * absent from the answer, as is any League without a copy. A copy stands however stale it is. A
 * Game that started over a day ago is dropped. Each team's aliases and logo are completed from the
 * League's teams when the source listed them.
 */
export async function games(env: Env, leagues: League[]): Promise<{ leagues: LeagueGames[] }> {
  const now = Date.now();
  const asked = [...new Set(leagues)];
  if (asked.length === 0) return { leagues: [] };
  const marks = asked.map((_, i) => `?${i + 1}`).join(", ");
  const want = asked.map((league) =>
    env.DB.prepare("INSERT INTO game_wanted (league, asked_at) VALUES (?1, ?2) ON CONFLICT (league) DO UPDATE SET asked_at = excluded.asked_at WHERE asked_at < ?3")
      .bind(league, now, now - ASKED_EVERY),
  );
  const window = days(now);
  const results = await env.DB.batch([
    ...want,
    env.DB.prepare(`SELECT league, day, body FROM game WHERE league IN (${marks}) AND day >= ?${asked.length + 1} ORDER BY fetched_at`).bind(...asked, window[0]),
    env.DB.prepare(`SELECT league, body FROM team WHERE league IN (${marks})`).bind(...asked),
  ]);
  const rows = results[asked.length].results as { league: string; day: string; body: string }[];
  const teams = new Map((results[asked.length + 1].results as { league: string; body: string }[]).map((r) => [r.league, new Map((JSON.parse(r.body) as Team[]).map((t) => [t.id, t]))]));

  const answer: LeagueGames[] = [];
  for (const league of asked) {
    const kept = rows.filter((r) => r.league === league && window.includes(r.day));
    if (kept.length === 0) continue;
    // A day read later wins when a Game moved between days.
    const byId = new Map<string, Game>();
    for (const row of kept) for (const game of JSON.parse(row.body) as Game[]) byId.set(game.id, game);
    const listed = teams.get(league);
    const complete = (team: GameTeam): GameTeam => {
      const known = listed?.get(team.id);
      return known ? { ...team, aliases: [...new Set([...team.aliases, ...known.aliases])], logo: team.logo ?? known.logo } : team;
    };
    const fresh = [...byId.values()]
      .filter((game) => Date.parse(game.start) > now - KEEP_GAMES_FOR)
      .map((game) => ({ ...game, home: complete(game.home), away: complete(game.away) }))
      .sort((a, b) => a.start.localeCompare(b.start) || a.id.localeCompare(b.id));
    answer.push({ league, games: fresh });
  }
  return { leagues: answer };
}

/** One part the Cron Trigger reads: its [key] in `game_try`, how it ranks ([tier], then the oldest first), and [read], which saves what it read. */
interface Part {
  key: string;
  tier: number;
  fetchedAt: number;
  read: () => Promise<void>;
}

/**
 * One run of the Cron Trigger, each minute, before the Charts' part: reads the days of the wanted
 * Leagues that are missing or stale, the current days (yesterday's and today's: ESPN's days run on
 * US Eastern time, so a Game in progress may be on either) first, then the Leagues' teams, then
 * the coming days, missing ones before the oldest. Each day is saved as soon as it is read, so a
 * run stopped short keeps what it read. A run sends at most [GAMES_SUBREQUESTS] requests, and says
 * how many it sent. A part started in the last [RETRY_AFTER] is passed over. Leagues nobody asked
 * for in [WANTED_FOR], and days gone by, are dropped first.
 */
export async function fillGames(env: Env): Promise<number> {
  const now = Date.now();
  const window = days(now);
  const [wanted, rows, teams, tries] = await env.DB.batch([
    env.DB.prepare("SELECT league FROM game_wanted WHERE asked_at > ?1").bind(now - WANTED_FOR),
    env.DB.prepare("SELECT league, day, fetched_at FROM game"),
    env.DB.prepare("SELECT league, fetched_at FROM team"),
    env.DB.prepare("SELECT part, tried_at FROM game_try"),
  ]);
  const leagues = (wanted.results as { league: string }[]).map((w) => w.league).filter(isLeague);
  const kept = rows.results as { league: string; day: string; fetched_at: number }[];
  const teamsKept = teams.results as { league: string; fetched_at: number }[];

  const stale = kept.filter((r) => !window.includes(r.day) || !(leagues as string[]).includes(r.league));
  const staleTeams = teamsKept.filter((r) => !(leagues as string[]).includes(r.league));
  if (stale.length > 0 || staleTeams.length > 0) {
    await env.DB.batch([
      ...stale.map((r) => env.DB.prepare("DELETE FROM game WHERE league = ?1 AND day = ?2").bind(r.league, r.day)),
      ...staleTeams.map((r) => env.DB.prepare("DELETE FROM team WHERE league = ?1").bind(r.league)),
    ]);
  }
  if (leagues.length === 0) return 0;

  const fetchedAt = new Map(kept.map((r) => [`${r.league}/${r.day}`, r.fetched_at]));
  const teamsFetchedAt = new Map(teamsKept.map((r) => [r.league, r.fetched_at]));
  const tried = new Map((tries.results as { part: string; tried_at: number }[]).map((t) => [t.part, t.tried_at]));
  const parts: Part[] = [];
  for (const league of leagues) {
    window.forEach((day, i) => {
      const current = i <= 1;
      const at = fetchedAt.get(`${league}/${day}`) ?? -1;
      if (at < now - (current ? CURRENT_FRESH_FOR : COMING_FRESH_FOR)) {
        parts.push({ key: `${league}/${day}`, tier: current ? 0 : 2, fetchedAt: at, read: () => readDay(env, league, day, now) });
      }
    });
    const at = teamsFetchedAt.get(league) ?? -1;
    if (at < now - TEAMS_FRESH_FOR) parts.push({ key: `${league}/teams`, tier: 1, fetchedAt: at, read: () => readTeams(env, league, now) });
  }
  parts.sort((a, b) => a.tier - b.tier || a.fetchedAt - b.fetchedAt);

  let sent = 0;
  for (const part of parts) {
    if (sent >= GAMES_SUBREQUESTS) break;
    if ((tried.get(part.key) ?? -Infinity) > now - RETRY_AFTER) continue;
    await env.DB.prepare("INSERT OR REPLACE INTO game_try (part, tried_at) VALUES (?1, ?2)").bind(part.key, now).run();
    sent++;
    await part.read();
  }
  return sent;
}

/**
 * The days the cloud keeps of a League, `YYYY-MM-DD` in UTC: yesterday's, today's and the next
 * [COMING_DAYS]. The first two are the current days: ESPN's days run on US Eastern time, so a Game
 * in progress in the evening there is on UTC's yesterday after midnight UTC.
 */
function days(now: number): string[] {
  const today = new Date(now);
  today.setUTCHours(0, 0, 0, 0);
  return Array.from({ length: COMING_DAYS + 2 }, (_, i) => new Date(today.getTime() + (i - 1) * 86_400_000).toISOString().slice(0, 10));
}

/** Reads and keeps [league]'s Games on [day]. When the source can't answer, the kept day stands. */
async function readDay(env: Env, league: League, day: string, now: number): Promise<void> {
  try {
    const found = await scheduleSource(env, league).games(league, day);
    await env.DB.prepare("INSERT OR REPLACE INTO game (league, day, body, fetched_at) VALUES (?1, ?2, ?3, ?4)").bind(league, day, JSON.stringify(found), now).run();
  } catch (e) {
    if (!(e instanceof ScheduleUnavailable)) throw e;
    console.warn(`${league}'s Games on ${day} couldn't be fetched: ${e.message}`);
  }
}

/** Reads and keeps [league]'s teams. When the source can't answer, the kept list stands. */
async function readTeams(env: Env, league: League, now: number): Promise<void> {
  try {
    const listed = await scheduleSource(env, league).teams(league);
    await env.DB.prepare("INSERT OR REPLACE INTO team (league, body, fetched_at) VALUES (?1, ?2, ?3)").bind(league, JSON.stringify(listed), now).run();
  } catch (e) {
    if (!(e instanceof ScheduleUnavailable)) throw e;
    console.warn(`${league}'s teams couldn't be fetched: ${e.message}`);
  }
}
