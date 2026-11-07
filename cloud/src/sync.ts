import { isProviderKey } from "./files";

/**
 * History sync between a household's TVs (#435, docs/roadmap.md M5). A signed-in device pushes the
 * changes it made since its last push and pulls the Account's since its cursor, in one request.
 *
 * Every push is stamped with the Account's next sequence number (`account.sync_seq`), and a pull
 * answers the rows stamped after the device's cursor with the number it is at now, so a device
 * offline for a week gets everything since. Per row the change with the latest `at` wins, so two
 * TVs watching the same Title end where the last one stopped, and "Remove from Continue watching"
 * is a change too, a tombstone kept [TOMBSTONE_KEPT_MS]. A cursor from before a purged tombstone
 * may have missed that removal: the answer is then everything the Account has, marked `reset`.
 *
 * History keyed by TMDB ID is the same on every Provider; that of an Unmatched Title or a Sports
 * replay, keyed by Listing, is kept per Provider host hash and only ever answered to a device on
 * that Provider.
 *
 * #440 adds profiles, Favourites and settings as more sections of the same request and answer,
 * stamped from the same counter, so one cursor still covers everything.
 */

/** The most changes one push may hold; a device with more pushes them in turns. */
export const MAX_CHANGES = 500;
/** How long a removal is kept: a device offline for longer gets everything again ([reset]). */
export const TOMBSTONE_KEPT_MS = 30 * 24 * 3600_000;

/** One Movie's or Episode's progress as the device keeps it, or its removal. */
export interface ProgressChange {
  /** The device's Title key: `movie/238`, `show/1399`, or by Listing, `movie/listing/123`. */
  title: string;
  /** An Episode's season and number; null for a Movie. */
  season: number | null;
  episode: number | null;
  /** Where the Viewer stopped, in milliseconds, out of [duration] when the player knew it. */
  position: number;
  duration: number | null;
  watched: boolean;
  /** "Remove from Continue watching": the device forgets where the Viewer stopped. */
  removed: boolean;
  /** When the change was made, in milliseconds since the epoch, by the device's clock. */
  at: number;
}

export interface SyncRequest {
  /** The last cursor the device got, 0 when it never synced (or just signed in). */
  cursor: number;
  /** The SHA-256 of the device's Provider's host, or null before a login. */
  provider: string | null;
  progress: ProgressChange[];
}

export interface SyncAnswer {
  cursor: number;
  /** Everything the Account has, not just what changed: the device forgets the rows it pulled before that aren't in it. */
  reset: boolean;
  progress: ProgressChange[];
}

/** The Account the device [deviceHash] is signed in to, or null. */
export async function accountIdOf(db: D1Database, deviceHash: string): Promise<number | null> {
  const row = await db.prepare("SELECT account_id FROM device WHERE token_hash = ?1").bind(deviceHash).first<{ account_id: number | null }>();
  return row?.account_id ?? null;
}

/** Keeps [asked]'s changes for Account [accountId], latest change winning, and answers what changed since its cursor. */
export async function sync(db: D1Database, accountId: number, asked: SyncRequest): Promise<SyncAnswer> {
  const now = Date.now();
  const purgeBefore = now - TOMBSTONE_KEPT_MS;
  const changes = asked.progress.map((change) => ({
    provider: isListingKey(change.title) ? asked.provider : "",
    title: change.title,
    season: change.season ?? -1,
    episode: change.episode ?? -1,
    position: change.removed ? 0 : change.position,
    duration: change.removed ? null : change.duration,
    watched: change.removed || !change.watched ? 0 : 1,
    removed: change.removed ? 1 : 0,
    // A device whose clock runs ahead mustn't win every change after its own.
    at: Math.min(change.at, now),
  }));
  const statements: D1PreparedStatement[] = [];
  if (changes.length > 0) {
    statements.push(
      db.prepare("UPDATE account SET sync_seq = sync_seq + 1 WHERE id = ?1").bind(accountId),
      // One statement for the whole push: the free plan allows 50 queries per request.
      db.prepare(
        `INSERT INTO progress (account_id, provider, title, season, episode, position, duration, watched, removed, updated_at, seq)
         SELECT ?1, json_extract(value, '$.provider'), json_extract(value, '$.title'), json_extract(value, '$.season'), json_extract(value, '$.episode'), json_extract(value, '$.position'), json_extract(value, '$.duration'),
           json_extract(value, '$.watched'), json_extract(value, '$.removed'), json_extract(value, '$.at'), (SELECT sync_seq FROM account WHERE id = ?1)
         FROM json_each(?2) WHERE true
         ON CONFLICT (account_id, provider, title, season, episode) DO UPDATE SET
           position = excluded.position, duration = excluded.duration, watched = excluded.watched, removed = excluded.removed,
           updated_at = excluded.updated_at, seq = excluded.seq
         WHERE excluded.updated_at > progress.updated_at`,
      ).bind(accountId, JSON.stringify(changes)),
    );
  }
  statements.push(
    db.prepare(
      `UPDATE account SET purged_seq = MAX(purged_seq, COALESCE(
         (SELECT MAX(seq) FROM progress WHERE account_id = ?1 AND removed = 1 AND updated_at < ?2), 0)) WHERE id = ?1`,
    ).bind(accountId, purgeBefore),
    db.prepare("DELETE FROM progress WHERE account_id = ?1 AND removed = 1 AND updated_at < ?2").bind(accountId, purgeBefore),
    db.prepare("SELECT sync_seq, purged_seq FROM account WHERE id = ?1").bind(accountId),
    db.prepare(
      `SELECT title, season, episode, position, duration, watched, removed, updated_at FROM progress
       WHERE account_id = ?1 AND provider IN ('', ?3)
         AND seq > CASE WHEN ?2 > 0 AND ?2 < (SELECT purged_seq FROM account WHERE id = ?1) THEN 0 ELSE ?2 END
       ORDER BY seq`,
    ).bind(accountId, asked.cursor, asked.provider ?? ""),
  );
  const results = await db.batch(statements);
  const counter = results[results.length - 2].results[0] as { sync_seq: number; purged_seq: number };
  const rows = results[results.length - 1].results as {
    title: string; season: number; episode: number; position: number; duration: number | null; watched: number; removed: number; updated_at: number;
  }[];
  return {
    cursor: counter.sync_seq,
    reset: asked.cursor > 0 && asked.cursor < counter.purged_seq,
    progress: rows.map((row) => ({
      title: row.title,
      season: row.season < 0 ? null : row.season,
      episode: row.episode < 0 ? null : row.episode,
      position: row.position,
      duration: row.duration,
      watched: row.watched === 1,
      removed: row.removed === 1,
      at: row.updated_at,
    })),
  };
}

const isListingKey = (title: string) => title.includes("/listing/");

const TITLE_KEY = /^(movie|show)\/(?:[1-9]\d{0,15}|listing\/[1-9]\d{0,15})$/;
const isCount = (value: unknown, min: number): value is number => Number.isSafeInteger(value) && (value as number) >= min;

/** Reads a sync request, or says what's wrong with it. */
export function readSyncRequest(body: unknown): SyncRequest | string {
  const { cursor, provider, progress } = (body ?? {}) as { cursor?: unknown; provider?: unknown; progress?: unknown };
  if (!isCount(cursor, 0)) return "cursor must be the cursor the last sync answered, or 0.";
  if (provider !== null && provider !== undefined && !isProviderKey(provider)) return "provider must be the SHA-256 of the Provider's host, or null.";
  const changes = progress ?? [];
  if (!Array.isArray(changes) || changes.length > MAX_CHANGES) return `progress must be a list of at most ${MAX_CHANGES} changes.`;
  for (const change of changes) {
    const problem = progressProblem(change, provider ?? null);
    if (problem !== null) return problem;
  }
  return { cursor, provider: (provider as string | undefined) ?? null, progress: changes as ProgressChange[] };
}

function progressProblem(change: unknown, provider: unknown): string | null {
  if (typeof change !== "object" || change === null) return "each change must be an object.";
  const { title, season, episode, position, duration, watched, removed, at } = change as Record<string, unknown>;
  if (typeof title !== "string" || !TITLE_KEY.test(title)) return "title must be a Title key, such as \"movie/238\", \"show/1399\" or \"movie/listing/123\".";
  if (isListingKey(title) && provider === null) return "a Listing's progress needs the provider it is a Listing of.";
  const show = title.startsWith("show/");
  if (show ? !isCount(season, 0) || !isCount(episode, 0) : season != null || episode != null) {
    return "an Episode's change must give its season and episode, and a Movie's neither.";
  }
  if (!isCount(position, 0)) return "position must be a number of milliseconds.";
  if (duration !== null && duration !== undefined && !isCount(duration, 1)) return "duration must be a number of milliseconds, or null.";
  if (typeof watched !== "boolean" || typeof removed !== "boolean") return "watched and removed must be true or false.";
  if (!isCount(at, 1)) return "at must be when the change was made, in milliseconds since the epoch.";
  return null;
}
