import type { Env } from "./env";
import { search, TmdbUnavailable, type Kind, type TitleKey } from "./tmdb";

/** Netflix publishes its Top 10 each Tuesday; a day's wait shows a new list within a day. */
const NETFLIX_FRESH_FOR = 24 * 3600_000;

/**
 * The Charts a screen shows, for one Region and App language. Each part is its own source with its
 * own freshness; a part a source can't give right now is empty, and the rest still answer. New
 * parts (TMDB trending, Discover's genre lists) are added beside `top`.
 */
export interface Charts {
  top: TopLists;
}

/** Netflix's Top 10 movies and shows in rank order, the Region's or, when Netflix doesn't list it, the [worldwide] ones. */
export interface TopLists {
  worldwide: boolean;
  movies: TitleKey[];
  shows: TitleKey[];
}

/** Netflix couldn't give its file right now. */
class NetflixUnavailable extends Error {}

/** What the cloud keeps of a Top 10: each name with the TMDB ID it was found as (null: TMDB has none), so no name is looked up twice. */
interface KeptTop {
  worldwide: boolean;
  movies: Named[];
  shows: Named[];
}

interface Named {
  name: string;
  tmdbId: number | null;
}

/** The Charts for [region] (ISO 3166, `CA`) with names in [language] (ISO 639-1). */
export async function charts(env: Env, region: string, language: string): Promise<Charts> {
  void language; // Netflix's lists are the same in every language; Discover's genre names won't be.
  return { top: await top(env, region) };
}

/**
 * [region]'s Top 10, from the cloud's cache when it is under a day old, else from Netflix. When
 * Netflix or TMDB can't answer, the last copy stands; with none, the lists are empty.
 */
async function top(env: Env, region: string): Promise<TopLists> {
  const cached = await env.DB.prepare("SELECT body, fetched_at FROM chart WHERE source = 'netflix' AND key = ?1")
    .bind(region)
    .first<{ body: string; fetched_at: number }>();
  let kept: KeptTop | null = cached ? JSON.parse(cached.body) : null;
  const now = Date.now();
  if (!cached || now - cached.fetched_at > NETFLIX_FRESH_FOR) {
    try {
      const fresh = await fetchTop(env, region, kept);
      await env.DB.prepare("INSERT OR REPLACE INTO chart (source, key, body, fetched_at) VALUES ('netflix', ?1, ?2, ?3)")
        .bind(region, JSON.stringify(fresh), now)
        .run();
      kept = fresh;
    } catch (e) {
      if (!(e instanceof NetflixUnavailable) && !(e instanceof TmdbUnavailable)) throw e;
      console.warn(`Netflix's Top 10 for ${region} couldn't be fetched: ${e.message}`);
    }
  }
  const found = (kind: Kind, names: Named[]) => names.flatMap((n) => (n.tmdbId === null ? [] : [{ kind, tmdbId: n.tmdbId }]));
  return kept ? { worldwide: kept.worldwide, movies: found("movie", kept.movies), shows: found("show", kept.shows) } : { worldwide: false, movies: [], shows: [] };
}

/** Reads [region]'s newest Top 10 from Netflix, else the worldwide one, and finds each name on TMDB, reusing what [before] found. */
async function fetchTop(env: Env, region: string, before: KeptTop | null): Promise<KeptTop> {
  const names = (await regionTop(env, region)) ?? (await worldwideTop(env));
  const known = new Map<string, number | null>();
  before?.movies.forEach((n) => known.set(`movie/${n.name}`, n.tmdbId));
  before?.shows.forEach((n) => known.set(`show/${n.name}`, n.tmdbId));
  const find = (kind: Kind, list: string[]) =>
    Promise.all(
      list.map(async (name): Promise<Named> => {
        const id = known.get(`${kind}/${name}`);
        return { name, tmdbId: id !== undefined ? id : await findOnTmdb(env, kind, name) };
      }),
    );
  return { worldwide: names.worldwide, movies: await find("movie", names.movies), shows: await find("show", names.shows) };
}

/** The TMDB ID of the most popular [kind] TMDB's search finds by [name], or null when it finds none. */
async function findOnTmdb(env: Env, kind: Kind, name: string): Promise<number | null> {
  const found = await search(env, kind, name, "en");
  return found.reduce<(typeof found)[number] | null>((best, f) => (best === null || f.popularity > best.popularity ? f : best), null)?.tmdbId ?? null;
}

interface TopNames {
  worldwide: boolean;
  movies: string[];
  shows: string[];
}

/**
 * [region]'s newest week in Netflix's country file, or null when Netflix doesn't list it. The file
 * (32 MB) is grouped by country, newest week first, so it is read as a stream only until that week
 * ends. Bytes that can't hold the Region's rows are skipped without being decoded, which keeps the
 * Worker inside its CPU limit (#32): Canada's rows start 3.5 MB in.
 */
async function regionTop(env: Env, region: string): Promise<TopNames | null> {
  const needle = new TextEncoder().encode(`\t${region}\t`);
  const decoder = new TextDecoder();
  const top: TopNames = { worldwide: false, movies: [], shows: [] };
  let week: string | null = null;
  let column: Columns | null = null;
  for await (let run of runs(env, "all-weeks-countries.tsv")) {
    if (column === null) {
      const end = run.indexOf(NEWLINE);
      column = columns(decoder.decode(end < 0 ? run : run.subarray(0, end)));
      run = run.subarray(end < 0 ? run.length : end + 1);
    }
    if (!holds(run, needle)) {
      if (week !== null) break; // The Region's block ended where this run starts.
      continue;
    }
    for (const line of decoder.decode(run).split("\n")) {
      const cells = line.split("\t");
      if (cells[column.country_iso2] !== region) {
        if (week !== null) return top;
        continue;
      }
      week ??= cells[column.week];
      if (cells[column.week] !== week) return top;
      (cells[column.category] === "TV" ? top.shows : top.movies).push(cells[column.show_title]);
    }
  }
  return week === null ? null : top;
}

/** Whether [bytes] hold [needle] (`\tCA\t`), found by jumping between its first letters with the native indexOf. */
function holds(bytes: Uint8Array, needle: Uint8Array): boolean {
  for (let at = bytes.indexOf(needle[1], 1); at >= 0; at = bytes.indexOf(needle[1], at + 1)) {
    if (needle.every((b, i) => bytes[at - 1 + i] === b)) return true;
  }
  return false;
}

/**
 * The newest week of Netflix's worldwide file: its English and non-English lists of each kind,
 * merged by weekly views.
 */
async function worldwideTop(env: Env): Promise<TopNames> {
  const viewed = { movies: [] as { name: string; views: number }[], shows: [] as { name: string; views: number }[] };
  const decoder = new TextDecoder();
  let week: string | null = null;
  let column: Columns | null = null;
  read: for await (const run of runs(env, "all-weeks-global.tsv")) {
    for (const line of decoder.decode(run).split("\n")) {
      if (column === null) {
        column = columns(line);
        continue;
      }
      const cells = line.split("\t");
      week ??= cells[column.week];
      if (cells[column.week] !== week) break read;
      const kind = cells[column.category].startsWith("TV") ? viewed.shows : viewed.movies;
      kind.push({ name: cells[column.show_title], views: Number(cells[column.weekly_views]) || 0 });
    }
  }
  const names = (list: { name: string; views: number }[]) => [...new Set(list.sort((a, b) => b.views - a.views).map((r) => r.name))];
  return { worldwide: true, movies: names(viewed.movies), shows: names(viewed.shows) };
}

/** Where each column Netflix's files name in their first line is. */
type Columns = Record<"country_iso2" | "week" | "category" | "show_title" | "weekly_views", number>;

function columns(header: string): Columns {
  const names = header.split("\t");
  const at = (name: string) => names.indexOf(name);
  return { country_iso2: at("country_iso2"), week: at("week"), category: at("category"), show_title: at("show_title"), weekly_views: at("weekly_views") };
}

const NEWLINE = 0x0a;

/**
 * Netflix's Top 10 [file] as it arrives, as UTF-8 runs of whole lines (no trailing newline, empty
 * lines left in), so a run decodes on its own. Stopping early cancels the download, so the rest of
 * the file is never fetched.
 */
async function* runs(env: Env, file: string): AsyncGenerator<Uint8Array> {
  let response: Response;
  try {
    response = await fetch(`${env.NETFLIX_TOP10}/${file}`);
  } catch (e) {
    throw new NetflixUnavailable(String(e));
  }
  if (!response.ok || response.body === null) throw new NetflixUnavailable(`Netflix answered HTTP ${response.status} for ${file}`);
  const reader = response.body.getReader();
  let rest = new Uint8Array(0);
  try {
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      let bytes = value;
      if (rest.length > 0) {
        bytes = new Uint8Array(rest.length + value.length);
        bytes.set(rest);
        bytes.set(value, rest.length);
      }
      const end = bytes.lastIndexOf(NEWLINE);
      if (end < 0) {
        rest = bytes;
        continue;
      }
      rest = bytes.slice(end + 1);
      yield bytes.subarray(0, end);
    }
    if (rest.length > 0) yield rest;
  } catch (e) {
    throw new NetflixUnavailable(String(e));
  } finally {
    await reader.cancel().catch(() => {});
  }
}
