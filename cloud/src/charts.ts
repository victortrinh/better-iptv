import type { Env } from "./env";
import { fetchDiscover, fetchGenres, fetchTrending, search, TmdbUnavailable, type Genre, type Kind, type TitleKey } from "./tmdb";

/** Netflix publishes its Top 10 each Tuesday; a day's wait shows a new list within a day. */
const NETFLIX_FRESH_FOR = 24 * 3600_000;
/** TMDB's weekly trending is asked for once a week, so the row changes weekly, not daily. */
const TRENDING_FRESH_FOR = 7 * 24 * 3600_000;
/** Pages of 20 of each trending list: enough that a row is left once kept to the Provider's Titles. */
const TRENDING_PAGES = 2;
/** TMDB's Discover lists change slowly; read once a week, like trending. */
const DISCOVER_FRESH_FOR = 7 * 24 * 3600_000;
/**
 * Pages of 20 of each genre's Discover list: 300 Titles, so a genre's row still has its classics
 * once kept to the Provider's Titles in the Viewer's languages.
 */
const DISCOVER_PAGES = 15;
/**
 * The requests one answer may send out. A Worker on the free plan may send 50 (D1 queries count
 * too): 10 are left for the device-token check and the reads and writes of the `chart` table. The
 * Top 10 and trending take what they need first; Discover gets the rest.
 */
const SUBREQUESTS = 40;

/**
 * The Charts a screen shows, for one Region and App language. Each part is its own source with its
 * own freshness; a part a source can't give right now is empty, and the rest still answer.
 */
export interface Charts {
  top: TopLists;
  trending: TrendingLists;
  /** Every TMDB genre of movies, then of shows, each with its most popular Titles. */
  genres: GenreList[];
}

/**
 * One genre's most popular Titles of [kind] across TMDB, in Discover's order, with the genre's
 * [name] in the App language. Just the IDs, since the kind is the genre's: a few hundred for each
 * of ~35 genres.
 */
export interface GenreList {
  kind: Kind;
  name: string;
  tmdbIds: number[];
}

/** TMDB's genres of each kind, named in one language, as the cloud keeps them. */
interface KeptGenres {
  movie: Genre[];
  show: Genre[];
}

/** TMDB's trending this week, in its order: movies and shows mixed ([all]), and each kind alone. */
export interface TrendingLists {
  all: TitleKey[];
  movies: TitleKey[];
  shows: TitleKey[];
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

/** How many requests one answer's sources have sent out so far. */
interface Sent {
  requests: number;
}

/** The Charts for [region] (ISO 3166, `CA`) with names in [language] (ISO 639-1). */
export async function charts(env: Env, region: string, language: string): Promise<Charts> {
  const sent: Sent = { requests: 0 };
  const [topLists, trendingLists] = await Promise.all([top(env, region, sent), trending(env, sent)]);
  // Discover's lists go last: they read what the Top 10 and trending left of the budget.
  return { top: topLists, trending: trendingLists, genres: await genres(env, language, SUBREQUESTS - sent.requests) };
}

/**
 * Each TMDB genre's most popular movies or shows, with the genre's name in [language], sending at
 * most [budget] requests to TMDB. Each (kind, genre) list is kept on its own for a week and shared by
 * every language; the genres' names are kept per language. Lists missing or over a week old are read
 * again, missing ones first, then the oldest, as many as [budget] allows; the rest are served as kept,
 * or empty when never read, and later answers fill them in. When TMDB can't answer, the kept copies
 * stand; without the genres' names, there are no genres.
 */
async function genres(env: Env, language: string, budget: number): Promise<GenreList[]> {
  const { results } = await env.DB.prepare("SELECT source, key, body, fetched_at FROM chart WHERE source = 'discover' OR (source = 'genres' AND key = ?1)")
    .bind(language)
    .all<{ source: string; key: string; body: string; fetched_at: number }>();
  const now = Date.now();
  let left = budget;
  const writes: D1PreparedStatement[] = [];
  const save = (source: string, key: string, body: unknown) =>
    writes.push(env.DB.prepare("INSERT OR REPLACE INTO chart (source, key, body, fetched_at) VALUES (?1, ?2, ?3, ?4)").bind(source, key, JSON.stringify(body), now));

  const keptNames = results.find((r) => r.source === "genres");
  let names: KeptGenres | null = keptNames ? JSON.parse(keptNames.body) : null;
  if ((!keptNames || now - keptNames.fetched_at > DISCOVER_FRESH_FOR) && left >= 2) {
    left -= 2;
    try {
      const [movie, show] = await Promise.all([fetchGenres(env, "movie", language), fetchGenres(env, "show", language)]);
      names = { movie, show };
      save("genres", language, names);
    } catch (e) {
      if (!(e instanceof TmdbUnavailable)) throw e;
      console.warn(`TMDB's genres in ${language} couldn't be fetched: ${e.message}`);
    }
  }
  if (names === null) return [];

  const kept = new Map(results.filter((r) => r.source === "discover").map((r) => [r.key, { tmdbIds: JSON.parse(r.body) as number[], fetchedAt: r.fetched_at }]));
  const all = (["movie", "show"] as const).flatMap((kind) => names[kind].map((genre) => ({ kind, genre, key: `${kind}/${genre.id}` })));
  const stale = all
    .filter(({ key }) => !kept.has(key) || now - kept.get(key)!.fetchedAt > DISCOVER_FRESH_FOR)
    .sort((a, b) => (kept.get(a.key)?.fetchedAt ?? -1) - (kept.get(b.key)?.fetchedAt ?? -1));
  // One list at a time, so the requests a short genre doesn't need go to the next one.
  for (const { kind, genre, key } of stale) {
    if (left < DISCOVER_PAGES) break;
    left -= DISCOVER_PAGES;
    try {
      const { tmdbIds, requests } = await fetchDiscover(env, kind, genre.id, DISCOVER_PAGES);
      left += DISCOVER_PAGES - requests;
      kept.set(key, { tmdbIds, fetchedAt: now });
      save("discover", key, tmdbIds);
    } catch (e) {
      if (!(e instanceof TmdbUnavailable)) throw e;
      console.warn(`TMDB's Discover couldn't be fetched: ${e.message}`);
      break;
    }
  }
  if (writes.length > 0) await env.DB.batch(writes);
  return all.map(({ kind, genre, key }) => ({ kind, name: genre.name, tmdbIds: kept.get(key)?.tmdbIds ?? [] }));
}

/**
 * TMDB's weekly trending lists, the same for every Region and language, from the cloud's cache
 * when under a week old, else from TMDB. When TMDB can't answer, the last copy stands; with none,
 * the lists are empty.
 */
async function trending(env: Env, sent: Sent): Promise<TrendingLists> {
  const cached = await env.DB.prepare("SELECT body, fetched_at FROM chart WHERE source = 'trending' AND key = ''").first<{ body: string; fetched_at: number }>();
  const now = Date.now();
  if (cached && now - cached.fetched_at <= TRENDING_FRESH_FOR) return JSON.parse(cached.body);
  // At most: a list can end before its last page.
  sent.requests += 3 * TRENDING_PAGES;
  try {
    const [all, movies, shows] = await Promise.all([fetchTrending(env, "all", TRENDING_PAGES), fetchTrending(env, "movie", TRENDING_PAGES), fetchTrending(env, "show", TRENDING_PAGES)]);
    const fresh: TrendingLists = { all, movies, shows };
    await env.DB.prepare("INSERT OR REPLACE INTO chart (source, key, body, fetched_at) VALUES ('trending', '', ?1, ?2)").bind(JSON.stringify(fresh), now).run();
    return fresh;
  } catch (e) {
    if (!(e instanceof TmdbUnavailable)) throw e;
    console.warn(`TMDB's trending couldn't be fetched: ${e.message}`);
    return cached ? JSON.parse(cached.body) : { all: [], movies: [], shows: [] };
  }
}

/**
 * [region]'s Top 10, from the cloud's cache when it is under a day old, else from Netflix. When
 * Netflix or TMDB can't answer, the last copy stands; with none, the lists are empty.
 */
async function top(env: Env, region: string, sent: Sent): Promise<TopLists> {
  const cached = await env.DB.prepare("SELECT body, fetched_at FROM chart WHERE source = 'netflix' AND key = ?1")
    .bind(region)
    .first<{ body: string; fetched_at: number }>();
  let kept: KeptTop | null = cached ? JSON.parse(cached.body) : null;
  const now = Date.now();
  if (!cached || now - cached.fetched_at > NETFLIX_FRESH_FOR) {
    try {
      const fresh = await fetchTop(env, region, kept, sent);
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
async function fetchTop(env: Env, region: string, before: KeptTop | null, sent: Sent): Promise<KeptTop> {
  const names = (await regionTop(env, region, sent)) ?? (await worldwideTop(env, sent));
  const known = new Map<string, number | null>();
  before?.movies.forEach((n) => known.set(`movie/${n.name}`, n.tmdbId));
  before?.shows.forEach((n) => known.set(`show/${n.name}`, n.tmdbId));
  const find = (kind: Kind, list: string[]) =>
    Promise.all(
      list.map(async (name): Promise<Named> => {
        const id = known.get(`${kind}/${name}`);
        return { name, tmdbId: id !== undefined ? id : await findOnTmdb(env, kind, name, sent) };
      }),
    );
  return { worldwide: names.worldwide, movies: await find("movie", names.movies), shows: await find("show", names.shows) };
}

/** The TMDB ID of the most popular [kind] TMDB's search finds by [name], or null when it finds none. */
async function findOnTmdb(env: Env, kind: Kind, name: string, sent: Sent): Promise<number | null> {
  sent.requests++;
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
async function regionTop(env: Env, region: string, sent: Sent): Promise<TopNames | null> {
  const needle = new TextEncoder().encode(`\t${region}\t`);
  const decoder = new TextDecoder();
  const top: TopNames = { worldwide: false, movies: [], shows: [] };
  let week: string | null = null;
  let column: Columns | null = null;
  for await (let run of runs(env, "all-weeks-countries.tsv", sent)) {
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
async function worldwideTop(env: Env, sent: Sent): Promise<TopNames> {
  const viewed = { movies: [] as { name: string; views: number }[], shows: [] as { name: string; views: number }[] };
  const decoder = new TextDecoder();
  let week: string | null = null;
  let column: Columns | null = null;
  read: for await (const run of runs(env, "all-weeks-global.tsv", sent)) {
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
async function* runs(env: Env, file: string, sent: Sent): AsyncGenerator<Uint8Array> {
  let response: Response;
  sent.requests++;
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
