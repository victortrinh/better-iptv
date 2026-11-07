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
 * Since #440 every Profile syncs: progress names its Profile, and [entries] carry the rest of what
 * the Account keeps (the Profiles themselves, Favourites, "Not this game" marks, per-Profile settings
 * and player choices, the household's Region and Household PIN hash), stamped from the same counter,
 * so one cursor still covers everything. Deleting a Profile takes its rows with it everywhere, and a
 * deleted Profile stays deleted: what an offline TV pushes for it later is dropped.
 */

/** The most changes one push may hold; a device with more pushes them in turns. */
export const MAX_CHANGES = 500;
/** How long a removal is kept: a device offline for longer gets everything again ([reset]). */
export const TOMBSTONE_KEPT_MS = 30 * 24 * 3600_000;

/** One Movie's or Episode's progress as the device keeps it, or its removal. */
export interface ProgressChange {
  /** The Profile it is of: the id its device made for it, the same on every TV. */
  profile: string;
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

/**
 * What else the Account keeps (#440), one thing a row, the latest change winning. By [kind]:
 * - `profile`: the Profile [profile] itself ([key] ''), [value] `{"name": "Sam", "kids": false}` as JSON, null once deleted;
 * - `favourite` ([key] a Channel key) and `not_this_game` ([key] the Game key, `|`, the Channel key), per Provider, [value] `"1"` or null;
 * - `setting` ([key] the setting's name) and `title_choices` ([key] a Title key), the Profile's, [value] as the device writes it;
 * - `household` ([profile] null; [key] `region` or `pin`, the Household PIN's salted hash), null when reset.
 */
export interface Entry {
  kind: EntryKind;
  profile: string | null;
  key: string;
  value: string | null;
  at: number;
}

export const ENTRY_KINDS = ["profile", "favourite", "not_this_game", "setting", "title_choices", "household"] as const;
export type EntryKind = (typeof ENTRY_KINDS)[number];
/** The longest value kept: a Profile, a setting, a Title's player choices or the PIN's hash are all far shorter. */
export const MAX_VALUE = 4096;
/** The household's own entries. */
export const HOUSEHOLD_KEYS = ["region", "pin"];

export interface SyncRequest {
  /** The last cursor the device got, 0 when it never synced (or just signed in). */
  cursor: number;
  /** The SHA-256 of the device's Provider's host, or null before a login. */
  provider: string | null;
  /** The Profile the device is on, which the cloud keeps for it; null when it doesn't say. */
  current: string | null;
  progress: ProgressChange[];
  entries: Entry[];
}

export interface SyncAnswer {
  cursor: number;
  /** Everything the Account has, not just what changed: the device forgets the rows it pulled before that aren't in it. */
  reset: boolean;
  progress: ProgressChange[];
  entries: Entry[];
}

/** The Account the device [deviceHash] is signed in to, or null. */
export async function accountIdOf(db: D1Database, deviceHash: string): Promise<number | null> {
  const row = await db.prepare("SELECT account_id FROM device WHERE token_hash = ?1").bind(deviceHash).first<{ account_id: number | null }>();
  return row?.account_id ?? null;
}

/** Keeps [asked]'s changes from device [deviceHash] for Account [accountId], latest change winning, and answers what changed since its cursor. */
export async function sync(db: D1Database, accountId: number, asked: SyncRequest, deviceHash?: string): Promise<SyncAnswer> {
  const now = Date.now();
  const purgeBefore = now - TOMBSTONE_KEPT_MS;
  const changes = asked.progress.map((change) => ({
    profile: change.profile,
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
  const entries = asked.entries.map((entry) => ({
    kind: entry.kind,
    profile: entry.profile ?? "",
    provider: entryProvider(entry, asked.provider),
    key: entry.key,
    value: entry.value,
    at: Math.min(entry.at, now),
  }));
  const statements: D1PreparedStatement[] = [];
  if (deviceHash !== undefined && asked.current !== null) {
    statements.push(db.prepare("UPDATE device SET profile = ?2 WHERE token_hash = ?1").bind(deviceHash, asked.current));
  }
  if (changes.length > 0 || entries.length > 0) {
    statements.push(db.prepare("UPDATE account SET sync_seq = sync_seq + 1 WHERE id = ?1").bind(accountId));
  }
  if (changes.length > 0) {
    // One statement for the whole push: the free plan allows 50 queries per request.
    statements.push(
      db.prepare(
        `INSERT INTO progress (account_id, profile, provider, title, season, episode, position, duration, watched, removed, updated_at, seq)
         SELECT ?1, json_extract(value, '$.profile'), json_extract(value, '$.provider'), json_extract(value, '$.title'), json_extract(value, '$.season'), json_extract(value, '$.episode'),
           json_extract(value, '$.position'), json_extract(value, '$.duration'), json_extract(value, '$.watched'), json_extract(value, '$.removed'), json_extract(value, '$.at'),
           (SELECT sync_seq FROM account WHERE id = ?1)
         FROM json_each(?2) WHERE true
         ON CONFLICT (account_id, profile, provider, title, season, episode) DO UPDATE SET
           position = excluded.position, duration = excluded.duration, watched = excluded.watched, removed = excluded.removed,
           updated_at = excluded.updated_at, seq = excluded.seq
         WHERE excluded.updated_at > progress.updated_at`,
      ).bind(accountId, JSON.stringify(changes)),
    );
  }
  if (entries.length > 0) {
    statements.push(
      db.prepare(
        `INSERT INTO synced (account_id, kind, profile, provider, key, value, updated_at, seq)
         SELECT ?1, json_extract(value, '$.kind'), json_extract(value, '$.profile'), json_extract(value, '$.provider'), json_extract(value, '$.key'),
           json_extract(value, '$.value'), json_extract(value, '$.at'), (SELECT sync_seq FROM account WHERE id = ?1)
         FROM json_each(?2) WHERE true
         ON CONFLICT (account_id, kind, profile, provider, key) DO UPDATE SET value = excluded.value, updated_at = excluded.updated_at, seq = excluded.seq
         WHERE excluded.updated_at > synced.updated_at AND NOT (synced.kind = 'profile' AND synced.value IS NULL)`,
      ).bind(accountId, JSON.stringify(entries)),
    );
  }
  if (changes.length > 0 || entries.length > 0) {
    // A deleted Profile's rows go with it, those just pushed by a TV that hadn't heard too.
    const deleted = "SELECT profile FROM synced WHERE account_id = ?1 AND kind = 'profile' AND value IS NULL";
    statements.push(
      db.prepare(`DELETE FROM synced WHERE account_id = ?1 AND kind != 'profile' AND profile IN (${deleted})`).bind(accountId),
      db.prepare(`DELETE FROM progress WHERE account_id = ?1 AND profile IN (${deleted})`).bind(accountId),
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
      `SELECT profile, title, season, episode, position, duration, watched, removed, updated_at FROM progress
       WHERE account_id = ?1 AND provider IN ('', ?3)
         AND seq > ${SINCE}
       ORDER BY seq`,
    ).bind(accountId, asked.cursor, asked.provider ?? ""),
    db.prepare(
      `SELECT kind, profile, key, value, updated_at FROM synced
       WHERE account_id = ?1 AND provider IN ('', ?3)
         AND seq > ${SINCE}
       ORDER BY seq`,
    ).bind(accountId, asked.cursor, asked.provider ?? ""),
  );
  const results = await db.batch(statements);
  const counter = results[results.length - 3].results[0] as { sync_seq: number; purged_seq: number };
  const rows = results[results.length - 2].results as {
    profile: string; title: string; season: number; episode: number; position: number; duration: number | null; watched: number; removed: number; updated_at: number;
  }[];
  const synced = results[results.length - 1].results as { kind: EntryKind; profile: string; key: string; value: string | null; updated_at: number }[];
  return {
    cursor: counter.sync_seq,
    reset: asked.cursor > 0 && asked.cursor < counter.purged_seq,
    entries: synced.map((row) => ({ kind: row.kind, profile: row.profile === "" ? null : row.profile, key: row.key, value: row.value, at: row.updated_at })),
    progress: rows.map((row) => ({
      profile: row.profile,
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

/** The cursor a pull answers from: 0, everything, when the device's is older than a purged removal. */
const SINCE = "CASE WHEN ?2 > 0 AND ?2 < (SELECT purged_seq FROM account WHERE id = ?1) THEN 0 ELSE ?2 END";

/**
 * Clears the household's PIN, as "Reset the Household PIN" does: every TV forgets it at its next pull,
 * and the next one leaving a Kids profile asks for a new one. Stamped with the cloud's time, so it is
 * later than any PIN set before it.
 */
export async function resetHouseholdPin(db: D1Database, accountId: number): Promise<void> {
  await db.batch([
    db.prepare("UPDATE account SET sync_seq = sync_seq + 1 WHERE id = ?1").bind(accountId),
    db.prepare(
      `INSERT INTO synced (account_id, kind, profile, provider, key, value, updated_at, seq)
       VALUES (?1, 'household', '', '', 'pin', NULL, ?2, (SELECT sync_seq FROM account WHERE id = ?1))
       ON CONFLICT (account_id, kind, profile, provider, key) DO UPDATE SET value = NULL, updated_at = MAX(synced.updated_at + 1, excluded.updated_at), seq = excluded.seq`,
    ).bind(accountId, Date.now()),
  ]);
}

const isListingKey = (title: string) => title.includes("/listing/");

/** The Provider an entry is kept for: its own for a Favourite, a "Not this game" mark and a Listing's choices; '' for the rest. */
const entryProvider = (entry: Entry, provider: string | null) =>
  entry.kind === "favourite" || entry.kind === "not_this_game" || (entry.kind === "title_choices" && isListingKey(entry.key)) ? provider ?? "" : "";

const TITLE_KEY = /^(movie|show)\/(?:[1-9]\d{0,15}|listing\/[1-9]\d{0,15})$/;
/** A Profile's id, as its device made it. */
const PROFILE_ID = /^[0-9a-f]{16}$/;
export const isProfileId = (id: unknown): id is string => typeof id === "string" && PROFILE_ID.test(id);
const isCount = (value: unknown, min: number): value is number => Number.isSafeInteger(value) && (value as number) >= min;

/** Reads a sync request, or says what's wrong with it. */
export function readSyncRequest(body: unknown): SyncRequest | string {
  const { cursor, provider, current, progress, entries } = (body ?? {}) as { cursor?: unknown; provider?: unknown; current?: unknown; progress?: unknown; entries?: unknown };
  if (!isCount(cursor, 0)) return "cursor must be the cursor the last sync answered, or 0.";
  if (provider !== null && provider !== undefined && !isProviderKey(provider)) return "provider must be the SHA-256 of the Provider's host, or null.";
  if (current !== null && current !== undefined && !isProfileId(current)) return "current must be the id of the Profile the device is on, or null.";
  const changes = progress ?? [];
  if (!Array.isArray(changes) || changes.length > MAX_CHANGES) return `progress must be a list of at most ${MAX_CHANGES} changes.`;
  for (const change of changes) {
    const problem = progressProblem(change, provider ?? null);
    if (problem !== null) return problem;
  }
  const others = entries ?? [];
  if (!Array.isArray(others) || others.length > MAX_CHANGES) return `entries must be a list of at most ${MAX_CHANGES} entries.`;
  for (const entry of others) {
    const problem = entryProblem(entry, provider ?? null);
    if (problem !== null) return problem;
  }
  return {
    cursor,
    provider: (provider as string | undefined) ?? null,
    current: (current as string | undefined) ?? null,
    progress: changes as ProgressChange[],
    entries: (others as Entry[]).map(({ kind, profile, key, value, at }) => ({ kind, profile: profile ?? null, key, value: value ?? null, at })),
  };
}

const SETTING_KEY = /^[A-Za-z][A-Za-z.]{0,39}$/;
const CHANNEL_KEY = /^.{1,200}$/s;
/** A Game key (a League's path and its schedule id, no `|`), `|`, then a Channel key. */
const MARK_KEY = /^[^|]{1,100}\|.{1,200}$/s;

function entryProblem(entry: unknown, provider: string | null): string | null {
  if (typeof entry !== "object" || entry === null) return "each entry must be an object.";
  const { kind, profile, key, value, at } = entry as Record<string, unknown>;
  if (typeof kind !== "string" || !(ENTRY_KINDS as readonly string[]).includes(kind)) return `kind must be one of ${ENTRY_KINDS.join(", ")}.`;
  if (kind === "household" ? profile != null : !isProfileId(profile)) return "profile must be the id of the Profile an entry is of, and null for the household's.";
  if (value !== null && value !== undefined && (typeof value !== "string" || value.length > MAX_VALUE)) return `value must be text of at most ${MAX_VALUE} characters, or null.`;
  if (!isCount(at, 1)) return "at must be when the change was made, in milliseconds since the epoch.";
  if (typeof key !== "string") return "key must be text.";
  switch (kind as EntryKind) {
    case "profile":
      if (key !== "") return "a Profile's key is empty.";
      if (value != null && !isProfileValue(value as string)) return "a Profile's value must be {\"name\": text or null, \"kids\": true or false}.";
      return null;
    case "favourite":
    case "not_this_game": {
      if (provider === null) return "Favourites and \"Not this game\" marks need the provider they are of.";
      const shaped = (kind === "favourite" ? CHANNEL_KEY : MARK_KEY).test(key);
      return shaped ? null : kind === "favourite" ? "a Favourite's key must be a Channel key." : "a \"Not this game\" key must be a Game key, '|', then a Channel key.";
    }
    case "setting":
      return SETTING_KEY.test(key) ? null : "a setting's key must be its name.";
    case "title_choices":
      if (!TITLE_KEY.test(key)) return "a Title's choices must be keyed by its Title key.";
      return isListingKey(key) && provider === null ? "a Listing's choices need the provider it is a Listing of." : null;
    case "household":
      return HOUSEHOLD_KEYS.includes(key) ? null : `a household entry must be one of ${HOUSEHOLD_KEYS.join(", ")}.`;
  }
}

function isProfileValue(value: string): boolean {
  try {
    const { name, kids, ...rest } = JSON.parse(value) as Record<string, unknown>;
    return Object.keys(rest).length === 0 && (name === null || (typeof name === "string" && name.length > 0 && name.length <= 40)) && typeof kids === "boolean";
  } catch {
    return false;
  }
}

function progressProblem(change: unknown, provider: unknown): string | null {
  if (typeof change !== "object" || change === null) return "each change must be an object.";
  const { profile, title, season, episode, position, duration, watched, removed, at } = change as Record<string, unknown>;
  if (!isProfileId(profile)) return "profile must be the id of the Profile a change is of.";
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
