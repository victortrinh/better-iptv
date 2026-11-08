import type { Env } from "./env";
import { Espn } from "./espn";
import { EVENT_LEAGUES, FIGHT_LEAGUES, type Game, GOLF_LEAGUES, type GameTeam, isLeague, type League, type ScheduleSource, ScheduleUnavailable, type Team } from "./schedule";

/** A day with a Game on, or one starting within [SOON], is read again this often, so "Live" flips within minutes of a Game starting. */
const LIVE_FRESH_FOR = 5 * 60_000;
/** Any other day this often: a schedule changes slowly, and D1's free plan allows 100k rows written a day. */
const QUIET_FRESH_FOR = 3600_000;
/** How long before a Game's start its day is read every [LIVE_FRESH_FOR]: ESPN may say it is on a little early. */
const SOON = 15 * 60_000;
/** A Game known only by its day (noon US Eastern stands for its start) may start earlier: its day is read often from this long before. */
const DAY_ONLY_SOON = 12 * 3600_000;
/** Its teams this often: aliases and logos change between seasons. */
const TEAMS_FRESH_FOR = 7 * 24 * 3600_000;
/** The days after today a League's Games are kept for: the week ahead the hub shows. */
const COMING_DAYS = 7;
/** A Game that started longer ago than this is dropped: ESPN's CFL endpoint answers out of season with a game from 2022. */
const KEEP_GAMES_FOR = 24 * 3600_000;
/**
 * A part the Cron Trigger started this long ago and that is still due waits till then: one that
 * stopped its run (out of CPU, say) or whose source was down doesn't hold up the parts after it.
 * Shorter than a live day's freshness, so a day read on time is read again on time.
 */
const RETRY_AFTER = 2 * 60_000;
/**
 * A League not asked for in 2 days is no longer kept fresh, and its copy is dropped. A device asks
 * every 2 minutes while it shows Games, so a League a household has on stays wanted; one it turned
 * off stops costing D1 writes within days.
 */
const WANTED_FOR = 2 * 24 * 3600_000;
/** How long when a League was asked for is left as it is, so most answers write nothing. */
const ASKED_EVERY = 3600_000;
/**
 * The requests one run of the Cron Trigger may send a schedule source, out of the run's
 * {@link SUBREQUESTS} that the Charts share: twenty-nine Leagues' current days every 5 minutes and
 * their coming days hourly need 17 a minute at most (sized for #354's twenty-nine), a golf League's day two and its tomorrow
 * read as a current day (12 did for the twenty-one before #354).
 */
export const GAMES_SUBREQUESTS = 17;

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
 * Game that started over a day ago is dropped. Each team's aliases are completed from the League's
 * teams when the source listed them, and its logo is theirs: the scoreboard's is for light backgrounds.
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
    // A day or a teams list only tried so far (fetched_at 0) is no copy.
    env.DB.prepare(`SELECT league, day, body FROM game WHERE league IN (${marks}) AND day >= ?${asked.length + 1} AND fetched_at > 0 ORDER BY fetched_at`).bind(...asked, window[0]),
    env.DB.prepare(`SELECT league, body FROM team WHERE league IN (${marks}) AND fetched_at > 0`).bind(...asked),
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
    const complete = (team: GameTeam | undefined): GameTeam | undefined => {
      const known = team && listed?.get(team.id);
      return team && known ? { ...team, aliases: [...new Set([...team.aliases, ...known.aliases])], logo: known.logo ?? team.logo } : team;
    };
    const fresh = [...byId.values()]
      .filter((game) => Date.parse(game.start) > now - KEEP_GAMES_FOR)
      .map((game) => (game.event || game.fight ? game : { ...game, home: complete(game.home), away: complete(game.away) }))
      .sort((a, b) => a.start.localeCompare(b.start) || a.id.localeCompare(b.id));
    answer.push({ league, games: fresh });
  }
  return { leagues: answer };
}

/**
 * One part the Cron Trigger reads: how it ranks ([tier], then the oldest first), when it was last
 * started ([triedAt]), the requests it may send ([cost]), [tried], which notes it is being started,
 * and [read], which saves what it read.
 */
interface Part {
  tier: number;
  fetchedAt: number;
  triedAt: number;
  cost: number;
  tried: D1PreparedStatement;
  read: () => Promise<void>;
}

/**
 * One run of the Cron Trigger, each minute, before the Charts' part: reads the days of the wanted
 * Leagues that are missing or due, the current days (yesterday's and today's: ESPN's days run on
 * US Eastern time, so a Game in progress may be on either; a golf League's tomorrow too) first, then the Leagues' teams, then
 * the coming days, missing ones before the oldest. A day is due again [LIVE_FRESH_FOR] after it was
 * read while it holds a Game on or about to start, else [QUIET_FRESH_FOR] after ([dueAt]). Each day
 * is saved as soon as it is read, so a run stopped short keeps what it read. A run sends at most
 * [GAMES_SUBREQUESTS] requests, and says how many it may have sent. A part started in the last
 * [RETRY_AFTER] is passed over. Leagues nobody asked for in [WANTED_FOR] and days gone by are dropped first.
 *
 * Each part costs D1 two row writes: its row's `tried_at` before the read, and the row after it
 * (D1's free plan allows 100k a day, and a row with a key costs two when inserted). A day or a
 * teams list never read yet is inserted as a placeholder, `fetched_at` 0, which [games] leaves out.
 */
export async function fillGames(env: Env): Promise<number> {
  const now = Date.now();
  const window = days(now);
  const [wanted, rows, teams] = await env.DB.batch([
    env.DB.prepare("SELECT league FROM game_wanted WHERE asked_at > ?1").bind(now - WANTED_FOR),
    env.DB.prepare("SELECT league, day, fetched_at, due_at, tried_at FROM game"),
    env.DB.prepare("SELECT league, fetched_at, tried_at FROM team"),
  ]);
  const leagues = (wanted.results as { league: string }[]).map((w) => w.league).filter(isLeague);
  const kept = rows.results as { league: string; day: string; fetched_at: number; due_at: number; tried_at: number }[];
  const teamsKept = teams.results as { league: string; fetched_at: number; tried_at: number }[];

  const stale = kept.filter((r) => !window.includes(r.day) || !(leagues as string[]).includes(r.league));
  const staleTeams = teamsKept.filter((r) => !(leagues as string[]).includes(r.league));
  if (stale.length > 0 || staleTeams.length > 0) {
    await env.DB.batch([
      ...stale.map((r) => env.DB.prepare("DELETE FROM game WHERE league = ?1 AND day = ?2").bind(r.league, r.day)),
      ...staleTeams.map((r) => env.DB.prepare("DELETE FROM team WHERE league = ?1").bind(r.league)),
    ]);
  }
  if (leagues.length === 0) return 0;

  const dayRows = new Map(kept.map((r) => [`${r.league}/${r.day}`, r]));
  const teamRows = new Map(teamsKept.map((r) => [r.league, r]));
  const parts: Part[] = [];
  for (const league of leagues) {
    window.forEach((day, i) => {
      // A golf round may tee off the US evening before its tournament's first day as ESPN dates it (in Asia, a day ahead): tomorrow is current for golf.
      const current = i <= 1 || (i === 2 && GOLF_LEAGUES.includes(league));
      const row = dayRows.get(`${league}/${day}`);
      if ((row?.due_at ?? 0) <= now) {
        // A golf League's day may ask the leaderboard too.
        const cost = GOLF_LEAGUES.includes(league) ? 2 : 1;
        parts.push({
          tier: current ? 0 : 2,
          fetchedAt: row?.fetched_at ?? 0,
          triedAt: row?.tried_at ?? 0,
          cost,
          tried: env.DB.prepare("INSERT INTO game (league, day, body, fetched_at, due_at, tried_at) VALUES (?1, ?2, '[]', 0, 0, ?3) ON CONFLICT (league, day) DO UPDATE SET tried_at = excluded.tried_at").bind(league, day, now),
          read: () => readDay(env, league, day, now),
        });
      }
    });
    const row = teamRows.get(league);
    // An event or fight-card League has no teams to read.
    if (!EVENT_LEAGUES.includes(league) && !FIGHT_LEAGUES.includes(league) && (row?.fetched_at ?? 0) < now - TEAMS_FRESH_FOR) {
      parts.push({
        tier: 1,
        fetchedAt: row?.fetched_at ?? 0,
        triedAt: row?.tried_at ?? 0,
        cost: 1,
        tried: env.DB.prepare("INSERT INTO team (league, body, fetched_at, tried_at) VALUES (?1, '[]', 0, ?2) ON CONFLICT (league) DO UPDATE SET tried_at = excluded.tried_at").bind(league, now),
        read: () => readTeams(env, league, now),
      });
    }
  }
  parts.sort((a, b) => a.tier - b.tier || a.fetchedAt - b.fetchedAt);

  let sent = 0;
  for (const part of parts) {
    if (sent + part.cost > GAMES_SUBREQUESTS) break;
    if (part.triedAt > now - RETRY_AFTER) continue;
    await part.tried.run();
    sent += part.cost;
    await part.read();
  }
  return sent;
}

/**
 * When a day whose Games are [games], read at [now], is read again: in [LIVE_FRESH_FOR] while a
 * Game is on, or starts within [SOON] (a Game past its start that ESPN still calls upcoming is
 * about to start too), or is known only by its day and that day is near; else at the earliest of
 * [SOON] before the next Game's start and [QUIET_FRESH_FOR] from now. So a day is read often only
 * around its Games, and hourly the rest of the time.
 */
export function dueAt(games: Game[], now: number): number {
  let due = now + QUIET_FRESH_FOR;
  for (const game of games) {
    if (game.state !== "upcoming" && game.state !== "live") continue;
    const start = Date.parse(game.start);
    if (game.state === "live" || (game.dayOnly && start <= now + DAY_ONLY_SOON)) return now + LIVE_FRESH_FOR;
    due = Math.min(due, Math.max(now + LIVE_FRESH_FOR, start - SOON));
  }
  return due;
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

/** Reads and keeps [league]'s Games on [day], due again at [dueAt]. When the source can't answer, the kept day stands. */
async function readDay(env: Env, league: League, day: string, now: number): Promise<void> {
  try {
    const found = await scheduleSource(env, league).games(league, day);
    await env.DB.prepare("UPDATE game SET body = ?3, fetched_at = ?4, due_at = ?5 WHERE league = ?1 AND day = ?2").bind(league, day, JSON.stringify(found), now, dueAt(found, now)).run();
  } catch (e) {
    if (!(e instanceof ScheduleUnavailable)) throw e;
    console.warn(`${league}'s Games on ${day} couldn't be fetched: ${e.message}`);
  }
}

/** Reads and keeps [league]'s teams. When the source can't answer, the kept list stands. */
async function readTeams(env: Env, league: League, now: number): Promise<void> {
  try {
    const listed = await scheduleSource(env, league).teams(league);
    await env.DB.prepare("UPDATE team SET body = ?2, fetched_at = ?3 WHERE league = ?1").bind(league, JSON.stringify(listed), now).run();
  } catch (e) {
    if (!(e instanceof ScheduleUnavailable)) throw e;
    console.warn(`${league}'s teams couldn't be fetched: ${e.message}`);
  }
}
