import type { Env } from "./env";
import { metadata } from "./metadata";
import { search as searchTmdb, TmdbUnavailable, type Kind, type TitleMetadata } from "./tmdb";

/** At most this many Listings per request: each may need a details and a search request, within Workers' 50 outgoing fetches. */
export const MAX_MATCH_BATCH = 25;

/** A Listing to Match: its cleaned name and year, its Likely language, and the TMDB ID the Provider gave it, if any. */
export interface MatchQuestion {
  kind: Kind;
  name: string;
  year: number | null;
  language: string | null;
  tmdbId: number | null;
}

/** The Title a Listing belongs to; a null tmdbId when TMDB has none by that name. */
export interface MatchAnswer {
  kind: Kind;
  tmdbId: number | null;
}

/** A lookup's answer is kept this long, so new Titles on TMDB come through eventually. */
const FRESH_FOR = 30 * 24 * 3600_000;
/** A name TMDB found nothing for is searched again sooner: the Title may just have been added. */
const NOT_FOUND_FRESH_FOR = 24 * 3600_000;
/** TMDB allows about 50 requests a second; a batch never sends more than this many searches at once. */
const PARALLEL_SEARCHES = 8;

/** The language TMDB details are fetched in for a check: any would do, since every one carries all the titles. */
const CHECK_LANGUAGE = "en";

/**
 * Answers [questions] in order. A Listing keeps its TMDB ID when its name is one of TMDB's titles
 * for it, in any language; otherwise it is a Mismatch (or has no ID) and is looked up by name.
 */
export async function match(env: Env, questions: MatchQuestion[]): Promise<(MatchAnswer | null)[]> {
  const ids = questions.filter((q) => q.tmdbId !== null).map((q) => ({ kind: q.kind, tmdbId: q.tmdbId as number }));
  const checked = ids.length > 0 ? await metadata(env, ids, CHECK_LANGUAGE) : { titles: [], notFound: [], unavailable: [] };
  const titles = new Map(checked.titles.map((t) => [`${t.kind}/${t.tmdbId}`, t]));
  const unavailable = new Set(checked.unavailable.map((key) => `${key.kind}/${key.tmdbId}`));
  const answers: (MatchAnswer | null | undefined)[] = questions.map((q) => {
    if (q.tmdbId === null) return undefined;
    const key = `${q.kind}/${q.tmdbId}`;
    if (unavailable.has(key)) return null;
    const title = titles.get(key);
    return title && namedAs(title, q.name) ? { kind: q.kind, tmdbId: q.tmdbId } : undefined;
  });
  const looked = await lookUp(env, questions.filter((_, i) => answers[i] === undefined));
  return answers.map((answer, i) => (answer !== undefined ? answer : looked.get(lookupKey(questions[i])) ?? null));
}

/** A cached lookup's key, `[kind, normalized name, year or 0, language or ""]` as JSON: the same search, whoever asks. */
type LookupKey = [Kind, string, number, string];

const lookupKey = (q: MatchQuestion) => JSON.stringify([q.kind, normalize(q.name), q.year ?? 0, q.language ?? ""] satisfies LookupKey);

/**
 * Looks up [questions] by name, from the cloud's cache or else from TMDB's search, caching what TMDB
 * answers. Questions TMDB couldn't answer right now are left out.
 */
async function lookUp(env: Env, questions: MatchQuestion[]): Promise<Map<string, MatchAnswer>> {
  const answers = new Map<string, MatchAnswer>();
  if (questions.length === 0) return answers;
  const now = Date.now();
  const keys = new Map(questions.map((q) => [lookupKey(q), q]));
  const rows = await env.DB.prepare(
    `SELECT kind, name, year, language, tmdb_id, fetched_at FROM title_lookup
     WHERE json_array(kind, name, year, language) IN (SELECT value FROM json_each(?1))`,
  )
    .bind(JSON.stringify([...keys.keys()]))
    .all<{ kind: Kind; name: string; year: number; language: string; tmdb_id: number | null; fetched_at: number }>();
  for (const row of rows.results) {
    if (now - row.fetched_at <= (row.tmdb_id === null ? NOT_FOUND_FRESH_FOR : FRESH_FOR)) {
      answers.set(JSON.stringify([row.kind, row.name, row.year, row.language] satisfies LookupKey), { kind: row.kind, tmdbId: row.tmdb_id });
    }
  }

  const wanted = [...keys].filter(([key]) => !answers.has(key));
  const searched: [string, MatchAnswer][] = [];
  let next = 0;
  await Promise.all(
    Array.from({ length: Math.min(PARALLEL_SEARCHES, wanted.length) }, async () => {
      while (next < wanted.length) {
        const [key, q] = wanted[next++];
        try {
          searched.push([key, { kind: q.kind, tmdbId: await search(env, q) }]);
        } catch (e) {
          if (!(e instanceof TmdbUnavailable)) throw e;
        }
      }
    }),
  );
  if (searched.length > 0) {
    const insert = env.DB.prepare(
      "INSERT OR REPLACE INTO title_lookup (kind, name, year, language, tmdb_id, fetched_at) VALUES (?1, ?2, ?3, ?4, ?5, ?6)",
    );
    await env.DB.batch(
      searched.map(([key, answer]) => {
        const [kind, name, year, language] = JSON.parse(key) as LookupKey;
        return insert.bind(kind, name, year, language, answer.tmdbId, now);
      }),
    );
    for (const [key, answer] of searched) answers.set(key, answer);
  }
  return answers;
}

/**
 * Whether [name] is one of [title]'s names, or holds one of at least two words: Providers number
 * top lists (`02. The Godfather`) and put two languages' names together (`Le Parrain - The Godfather`).
 */
function namedAs(title: TitleMetadata, name: string): boolean {
  const wanted = ` ${normalize(name)} `;
  return [title.originalTitle, ...title.titles.map((t) => t.title), ...title.alternativeTitles].some((known) => {
    const folded = normalize(known);
    if (folded === "") return false;
    return wanted === ` ${folded} ` || (folded.includes(" ") && wanted.includes(` ${folded} `));
  });
}

/**
 * TMDB's Title of [q.kind] whose name in [q.language] or as released is [q.name], released within
 * a year of [q.year]; the most popular one when several are. Null when none is.
 */
async function search(env: Env, q: MatchQuestion): Promise<number | null> {
  const wanted = normalize(q.name);
  const hits = (await searchTmdb(env, q.kind, q.name, q.language ?? "en")).filter(
    (found) =>
      (normalize(found.title) === wanted || normalize(found.originalTitle) === wanted) &&
      (q.year === null || (found.year !== null && Math.abs(found.year - q.year) <= 1)),
  );
  return hits.sort((a, b) => b.popularity - a.popularity)[0]?.tmdbId ?? null;
}

/**
 * A name with case, accents and punctuation folded away, so `Lilo & Stitch` is `lilo stitch`, and
 * an acronym's letters joined, so `M.B.B.S.` is `mbbs`. The device folds names the same way (core Matching.kt).
 */
export function normalize(name: string): string {
  return name
    .normalize("NFKD")
    .replace(/\p{M}+/gu, "")
    .toLowerCase()
    .replace(/[^\p{L}\p{N}]+/gu, " ")
    .trim()
    .replace(/(?<![\p{L}\p{N}])(\p{L}) (?=\p{L}(?![\p{L}\p{N}]))/gu, "$1");
}
