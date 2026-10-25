import type { Env } from "./env";
import { fetchTitle, NOT_FOUND, TmdbUnavailable, type TitleKey, type TitleMetadata } from "./tmdb";

/** At most this many Titles per request, which keeps a request within Workers' 50 outgoing fetches. */
export const MAX_BATCH = 50;

/** TMDB answers are kept this long, so popularity and new translations come through eventually. */
const FRESH_FOR = 7 * 24 * 3600_000;
/** A Title TMDB doesn't have is asked about again sooner: it may just have been added. */
const NOT_FOUND_FRESH_FOR = 24 * 3600_000;
/** TMDB allows about 50 requests a second; a batch never sends more than this many at once. */
const PARALLEL_FETCHES = 8;

export interface MetadataBatch {
  /** The Titles TMDB has, in the order asked. */
  titles: TitleMetadata[];
  /** The Titles TMDB doesn't have. */
  notFound: TitleKey[];
  /** The Titles TMDB couldn't answer for right now; ask again later. */
  unavailable: TitleKey[];
}

/**
 * Metadata for [keys] in [language], from the cloud's cache or else from TMDB. What TMDB answers
 * (found or not) is cached before returning, so a repeat request doesn't reach TMDB.
 */
export async function metadata(env: Env, keys: TitleKey[], language: string): Promise<MetadataBatch> {
  const cached = await readCache(env.DB, keys, language);
  const now = Date.now();
  const stale = keys.filter((key) => {
    const entry = cached.get(id(key));
    return entry === undefined || now - entry.fetchedAt > (entry.body === null ? NOT_FOUND_FRESH_FOR : FRESH_FOR);
  });

  const fetched = new Map<string, TitleMetadata | typeof NOT_FOUND>();
  let next = 0;
  await Promise.all(
    Array.from({ length: Math.min(PARALLEL_FETCHES, stale.length) }, async () => {
      while (next < stale.length) {
        const key = stale[next++];
        try {
          fetched.set(id(key), await fetchTitle(env, key, language));
        } catch (e) {
          if (!(e instanceof TmdbUnavailable)) throw e;
        }
      }
    }),
  );
  if (fetched.size > 0) {
    const insert = env.DB.prepare(
      "INSERT OR REPLACE INTO title_metadata (kind, tmdb_id, language, body, fetched_at) VALUES (?1, ?2, ?3, ?4, ?5)",
    );
    await env.DB.batch(
      stale
        .filter((key) => fetched.has(id(key)))
        .map((key) => {
          const body = fetched.get(id(key));
          return insert.bind(key.kind, key.tmdbId, language, body ? JSON.stringify(body) : null, now);
        }),
    );
  }

  const batch: MetadataBatch = { titles: [], notFound: [], unavailable: [] };
  for (const key of keys) {
    // When TMDB couldn't answer, a stale cached answer still beats none.
    const answer = fetched.has(id(key)) ? fetched.get(id(key)) : cached.get(id(key))?.body;
    if (answer) batch.titles.push(answer);
    else if (answer === NOT_FOUND) batch.notFound.push(key);
    else batch.unavailable.push(key);
  }
  return batch;
}

async function readCache(db: D1Database, keys: TitleKey[], language: string) {
  const rows = await db
    .prepare(
      `SELECT kind, tmdb_id, body, fetched_at FROM title_metadata
       WHERE language = ?1 AND kind || '/' || tmdb_id IN (SELECT value FROM json_each(?2))`,
    )
    .bind(language, JSON.stringify(keys.map(id)))
    .all<{ kind: string; tmdb_id: number; body: string | null; fetched_at: number }>();
  const cached = new Map<string, { body: TitleMetadata | null; fetchedAt: number }>();
  for (const row of rows.results) {
    cached.set(`${row.kind}/${row.tmdb_id}`, { body: row.body === null ? null : JSON.parse(row.body), fetchedAt: row.fetched_at });
  }
  return cached;
}

const id = (key: TitleKey) => `${key.kind}/${key.tmdbId}`;
