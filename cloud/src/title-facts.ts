import type { Env } from "./env";
import type { Kind, TitleKey } from "./title";

/**
 * Title facts from Wikidata (#476, ADR 0014): what no Provider field holds about a Title, by its Title
 * number. Wikidata's data is CC0. The Wikidata Query Service is asked once per batch, by the TMDB movie
 * (P4947) and show (P4983) ID properties, for the original language (P364, through its ISO 639-1 code
 * P218), the IMDb ID (P345) and the English and French Wikipedia article names (sitelinks), as
 * scripts/survey_wikidata.py does. Every answer is cached in D1 for good, "no item" included.
 */

/** At most this many Titles per request: the survey's batch size, which the Query Service answers in a few seconds. */
export const MAX_FACTS_BATCH = 300;

/**
 * The most Titles cached a UTC day. D1's free plan allows 100k rows written a day, a cached Title
 * costs two (its row and its key), and the Games cron needs its share: a first Sync of a 58k-Title
 * catalog once spent 130k in an hour. Past it, the day's remaining answers are given but not kept,
 * and Wikidata is asked about those Titles again another day.
 */
export const FACTS_CACHED_A_DAY = 25_000;

/** How long one Wikidata query may take before the batch is answered from the cache alone. The service's own limit is 60 s. */
const WIKIDATA_TIMEOUT_MS = 25_000;

/** Wikimedia asks every client to name itself with a way to reach it. */
export const USER_AGENT = "Zugig/1.0 (https://github.com/victortrinh/better-iptv) Cloudflare Worker";

/** What Wikidata knows of a Title, each null when its item lacks it. */
export interface TitleFacts extends TitleKey {
  /** The original language as a primary subtag (`ko`), from P364 through P218. */
  originalLanguage: string | null;
  /** `tt0068646`. */
  imdbId: string | null;
  /** The English Wikipedia article's name, as Wikipedia shows it ("The Godfather"; the pageviews API writes it `The_Godfather`). */
  enwiki: string | null;
  frwiki: string | null;
}

export interface TitleFactsBatch {
  /** The Titles Wikidata has an item for, in the order asked. */
  titles: TitleFacts[];
  /** The Titles Wikidata has no item for. */
  notFound: TitleKey[];
  /** The Titles Wikidata couldn't answer for right now; the device asks again. */
  unavailable: TitleKey[];
}

/** The TMDB ID property Wikidata files each kind under. */
const PROPERTY: Record<Kind, string> = { movie: "P4947", show: "P4983" };

/**
 * The facts of [keys], from the cache or else from Wikidata in one query. What Wikidata answers (an
 * item or none) is cached before returning, so a repeat request doesn't reach it, up to
 * [FACTS_CACHED_A_DAY] Titles a day.
 */
export async function titleFacts(env: Env, keys: TitleKey[]): Promise<TitleFactsBatch> {
  const cached = await readCache(env.DB, keys);
  const missing = keys.filter((key) => !cached.has(id(key)));
  const fetched = missing.length === 0 ? new Map<string, TitleFacts | null>() : await fetchFacts(env, missing);
  if (fetched !== null && fetched.size > 0) {
    const now = Date.now();
    const day = new Date(now).toISOString().slice(0, 10);
    const used = (await env.DB.prepare("SELECT titles FROM facts_cached WHERE day = ?1").bind(day).first<{ titles: number }>())?.titles ?? 0;
    const kept = missing.slice(0, Math.max(0, FACTS_CACHED_A_DAY - used));
    if (kept.length > 0) {
      const insert = env.DB.prepare(
        "INSERT OR REPLACE INTO title_facts (kind, tmdb_id, found, original_language, imdb_id, enwiki, frwiki, fetched_at) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8)",
      );
      await env.DB.batch([
        ...kept.map((key) => {
          const facts = fetched.get(id(key)) ?? null;
          return insert.bind(key.kind, key.tmdbId, facts ? 1 : 0, facts?.originalLanguage ?? null, facts?.imdbId ?? null, facts?.enwiki ?? null, facts?.frwiki ?? null, now);
        }),
        env.DB.prepare("INSERT INTO facts_cached (day, titles) VALUES (?1, ?2) ON CONFLICT (day) DO UPDATE SET titles = titles + excluded.titles").bind(day, kept.length),
      ]);
    }
  }
  const batch: TitleFactsBatch = { titles: [], notFound: [], unavailable: [] };
  for (const key of keys) {
    const answer = cached.has(id(key)) ? cached.get(id(key)) : fetched === null ? undefined : fetched.get(id(key)) ?? null;
    if (answer) batch.titles.push(answer);
    else if (answer === null) batch.notFound.push(key);
    else batch.unavailable.push(key);
  }
  return batch;
}

/** The IMDb ID the cache has for a Title, for the subtitles search; null when it has none, or the Title was never asked. */
export async function cachedImdbId(db: D1Database, kind: Kind, tmdbId: number): Promise<string | null> {
  const row = await db.prepare("SELECT imdb_id FROM title_facts WHERE kind = ?1 AND tmdb_id = ?2").bind(kind, tmdbId).first<{ imdb_id: string | null }>();
  return row?.imdb_id ?? null;
}

/** The SPARQL asked for a batch: one `VALUES` row per Title, its kind, its property and its number. */
export function query(keys: TitleKey[]): string {
  const values = keys.map((key) => `("${key.kind}" wdt:${PROPERTY[key.kind]} "${key.tmdbId}")`).join(" ");
  return `SELECT ?kind ?id (SAMPLE(?imdb) AS ?imdbId) (SAMPLE(?langCode) AS ?lang) (SAMPLE(?enName) AS ?enwiki) (SAMPLE(?frName) AS ?frwiki) WHERE {
  VALUES (?kind ?prop ?id) { ${values} }
  ?item ?prop ?id .
  OPTIONAL { ?item wdt:P345 ?imdb }
  OPTIONAL { ?item wdt:P364 ?langItem . ?langItem wdt:P218 ?langCode }
  OPTIONAL { ?en schema:about ?item ; schema:isPartOf <https://en.wikipedia.org/> ; schema:name ?enName }
  OPTIONAL { ?fr schema:about ?item ; schema:isPartOf <https://fr.wikipedia.org/> ; schema:name ?frName }
}
GROUP BY ?kind ?id`;
}

/**
 * Asks Wikidata about [keys] in one query: each Title's facts, or null for one Wikidata has no item
 * for. Null as a whole when Wikidata didn't answer (an error, a timeout, something unreadable).
 */
async function fetchFacts(env: Env, keys: TitleKey[]): Promise<Map<string, TitleFacts | null> | null> {
  let response: Response;
  try {
    response = await fetch(env.WIKIDATA_SPARQL, {
      method: "POST",
      headers: { "User-Agent": USER_AGENT, Accept: "application/sparql-results+json", "Content-Type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({ query: query(keys), format: "json" }),
      signal: AbortSignal.timeout(WIKIDATA_TIMEOUT_MS),
    });
  } catch {
    return null;
  }
  if (!response.ok) return null;
  let bindings: Binding[];
  try {
    bindings = ((await response.json()) as { results?: { bindings?: Binding[] } }).results?.bindings ?? [];
  } catch {
    return null;
  }
  const found = new Map<string, TitleFacts | null>();
  for (const key of keys) found.set(id(key), null);
  for (const binding of bindings) {
    const kind = value(binding, "kind");
    const tmdbId = Number(value(binding, "id"));
    if ((kind !== "movie" && kind !== "show") || !Number.isSafeInteger(tmdbId) || !found.has(`${kind}/${tmdbId}`)) continue;
    const language = value(binding, "lang")?.toLowerCase() ?? null;
    const imdb = value(binding, "imdbId");
    found.set(`${kind}/${tmdbId}`, {
      kind,
      tmdbId,
      originalLanguage: language !== null && /^[a-z]{2,3}$/.test(language) ? language : null,
      imdbId: imdb !== null && /^tt\d{1,12}$/.test(imdb) ? imdb : null,
      enwiki: text(value(binding, "enwiki")),
      frwiki: text(value(binding, "frwiki")),
    });
  }
  return found;
}

type Binding = Record<string, { type: string; value: string } | undefined>;

const value = (binding: Binding, name: string): string | null => binding[name]?.value ?? null;

/** An article name, kept to Wikipedia's limit. */
const text = (name: string | null): string | null => (name !== null && name.trim() !== "" && name.length <= 255 ? name : null);

/**
 * Reads the cached facts of a JSON array of [kind, tmdbId] pairs: a join on each key's columns, so
 * SQLite searches the primary key instead of reading the whole table for every batch.
 */
export const CACHE_QUERY = `SELECT t.kind, t.tmdb_id, t.found, t.original_language, t.imdb_id, t.enwiki, t.frwiki FROM json_each(?1) j
  JOIN title_facts t ON t.kind = j.value ->> 0 AND t.tmdb_id = j.value ->> 1`;

async function readCache(db: D1Database, keys: TitleKey[]): Promise<Map<string, TitleFacts | null>> {
  const cached = new Map<string, TitleFacts | null>();
  if (keys.length === 0) return cached;
  const rows = await db
    .prepare(CACHE_QUERY)
    .bind(JSON.stringify(keys.map((key) => [key.kind, key.tmdbId])))
    .all<{ kind: Kind; tmdb_id: number; found: number; original_language: string | null; imdb_id: string | null; enwiki: string | null; frwiki: string | null }>();
  for (const row of rows.results) {
    cached.set(
      `${row.kind}/${row.tmdb_id}`,
      row.found ? { kind: row.kind, tmdbId: row.tmdb_id, originalLanguage: row.original_language, imdbId: row.imdb_id, enwiki: row.enwiki, frwiki: row.frwiki } : null,
    );
  }
  return cached;
}

const id = (key: TitleKey) => `${key.kind}/${key.tmdbId}`;
