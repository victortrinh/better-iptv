import type { Env } from "./env";
import { fetchDiscover, fetchGenres, fetchTrending, search, TmdbUnavailable, type Genre, type Kind, type TitleKey } from "./tmdb";

/** Netflix publishes its Top 10 each Tuesday; reading its files daily shows a new list within a day. */
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
 * The requests one run of the Cron Trigger may send out. A Worker on the free plan may send 50 an
 * invocation, a Cron Trigger's too; 10 are left spare. Parts are read in order while what each may
 * send still fits.
 */
const SUBREQUESTS = 40;
/** The names a Region's Top 10 has at most: 10 films and 10 shows. */
const REGION_NAMES = 20;
/** The names the worldwide Top 10 has at most: English and non-English films and shows, 10 each. */
const WORLDWIDE_NAMES = 40;
/**
 * A part the Cron Trigger started this long ago and that is still due waits till then: one that
 * stopped its run (out of CPU, say) or whose source was down doesn't hold up the parts after it.
 */
const RETRY_AFTER = 15 * 60_000;
/** A Region or App language not asked for in 30 days is no longer kept fresh. */
const WANTED_FOR = 30 * 24 * 3600_000;
/** How long when a Region or language was asked for is left as it is, so most answers write nothing. */
const ASKED_EVERY = 3600_000;
/** The key of the worldwide Top 10, among the Regions'. */
const WORLDWIDE = "*";
/** How many Regions' weeks the country file's scan saves at a time. */
const SAVE_EVERY = 25;
/** How much of Netflix's country file the scan reads at a time. */
const READ_SIZE = 1 << 20;

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

/** A Top 10 as Netflix names it: the newest week of a Region (`netflix-week`), or of the world. */
interface TopNames {
  worldwide: boolean;
  movies: string[];
  shows: string[];
}

/**
 * The Charts for [region] (ISO 3166, `CA`) with names in [language] (ISO 639-1), as the cloud keeps
 * them. Only D1 is read, never TMDB or Netflix, so an answer costs little CPU however stale they are
 * (#214): the Cron Trigger ([fill]) reads the sources. The Region and language are noted as wanted,
 * so a new one is read within the next minutes; till then, a Region gets the worldwide Top 10, and a
 * language the genres named in English.
 */
export async function charts(env: Env, region: string, language: string): Promise<Charts> {
  const now = Date.now();
  const want = (part: string, key: string) =>
    env.DB.prepare("INSERT INTO chart_wanted (part, key, asked_at) VALUES (?1, ?2, ?3) ON CONFLICT (part, key) DO UPDATE SET asked_at = excluded.asked_at WHERE asked_at < ?4")
      .bind(part, key, now, now - ASKED_EVERY);
  const [, , { results }] = await env.DB.batch<{ source: string; key: string; body: string }>([
    want("region", region),
    want("language", language),
    env.DB.prepare("SELECT source, key, body FROM chart WHERE (source = 'netflix' AND key IN (?1, ?3)) OR (source = 'trending' AND key = '') OR (source = 'genres' AND key IN (?2, 'en')) OR source = 'discover'")
      .bind(region, language, WORLDWIDE),
  ]);
  const kept = <T>(source: string, key: string): T | null => {
    const row = results.find((r) => r.source === source && r.key === key);
    return row ? JSON.parse(row.body) : null;
  };

  // A Region's own Top 10 once it was read; a copy kept before #214 of the worldwide one under the Region's key is passed over.
  const own = kept<KeptTop>("netflix", region);
  const top = own && !own.worldwide ? own : kept<KeptTop>("netflix", WORLDWIDE);
  const found = (kind: Kind, names: Named[]) => names.flatMap((n) => (n.tmdbId === null ? [] : [{ kind, tmdbId: n.tmdbId }]));

  const names = kept<KeptGenres>("genres", language) ?? kept<KeptGenres>("genres", "en");
  const lists = new Map(results.filter((r) => r.source === "discover").map((r) => [r.key, r.body]));
  const genres = names
    ? (["movie", "show"] as const).flatMap((kind) =>
        names[kind].map((genre) => ({ kind, name: genre.name, tmdbIds: JSON.parse(lists.get(`${kind}/${genre.id}`) ?? "[]") as number[] })),
      )
    : [];

  return {
    top: top ? { worldwide: top.worldwide, movies: found("movie", top.movies), shows: found("show", top.shows) } : { worldwide: false, movies: [], shows: [] },
    trending: kept<TrendingLists>("trending", "") ?? { all: [], movies: [], shows: [] },
    genres,
  };
}

/** One part of the Charts the Cron Trigger reads: its [key] in `chart_try`, the most [requests] it sends out, and [read], which saves what it read and says how many requests it sent. */
interface Part {
  key: string;
  requests: number;
  /** Read in a run of its own: Netflix's country file, the heaviest part. */
  alone?: boolean;
  read: () => Promise<number>;
}

/**
 * One run of the Cron Trigger, each minute: reads the parts of the Charts wanted that are missing
 * or stale (#214), in this order: each App language's genre names, TMDB's trending, Netflix's
 * worldwide Top 10, Netflix's country file, each Top 10's names found on TMDB, then Discover's
 * lists, missing ones first, then the oldest. Each part is saved as soon as it is read, so a run
 * stopped short (out of CPU, say) keeps what it read. A run sends at most [SUBREQUESTS] requests
 * out, and reads the country file in a run of its own. A part started in the last [RETRY_AFTER] is
 * passed over: one that stopped a run, or whose source was down, waits while the rest are read.
 */
export async function fill(env: Env): Promise<void> {
  const now = Date.now();
  const [wanted, rows, tries] = await env.DB.batch([
    env.DB.prepare("SELECT part, key FROM chart_wanted WHERE asked_at > ?1").bind(now - WANTED_FOR),
    // Bodies only of the genres' names, which say what Discover lists there are.
    env.DB.prepare("SELECT source, key, fetched_at, CASE source WHEN 'genres' THEN body END AS body FROM chart"),
    env.DB.prepare("SELECT part, tried_at FROM chart_try"),
  ]);
  const asked = (part: string) => (wanted.results as { part: string; key: string }[]).filter((w) => w.part === part).map((w) => w.key);
  const languages = asked("language");
  if (languages.length === 0) return;
  const kept = new Map((rows.results as { source: string; key: string; fetched_at: number; body: string | null }[]).map((r) => [`${r.source}/${r.key}`, r]));
  const fetchedAt = (key: string) => kept.get(key)?.fetched_at ?? null;
  const stale = (key: string, freshFor: number) => (fetchedAt(key) ?? -Infinity) < now - freshFor;
  const tried = new Map((tries.results as { part: string; tried_at: number }[]).map((t) => [t.part, t.tried_at]));

  const parts: Part[] = [];
  for (const language of languages) {
    if (stale(`genres/${language}`, DISCOVER_FRESH_FOR)) parts.push({ key: `genres/${language}`, requests: 2, read: () => readGenres(env, language, now) });
  }
  if (stale("trending/", TRENDING_FRESH_FOR)) parts.push({ key: "trending", requests: 3 * TRENDING_PAGES, read: () => readTrending(env, now) });
  if (stale(`netflix-week/${WORLDWIDE}`, NETFLIX_FRESH_FOR)) parts.push({ key: `netflix-week/${WORLDWIDE}`, requests: 1, read: () => readWorldwide(env, now) });
  if (stale("netflix-scan/", NETFLIX_FRESH_FOR)) parts.push({ key: "netflix-scan", requests: 1, alone: true, read: () => scanCountries(env, now) });
  // A Top 10 whose names were read since they were last found on TMDB.
  for (const region of [WORLDWIDE, ...asked("region")]) {
    const week = fetchedAt(`netflix-week/${region}`);
    if (week !== null && (fetchedAt(`netflix/${region}`) ?? -Infinity) < week) {
      parts.push({ key: `netflix/${region}`, requests: region === WORLDWIDE ? WORLDWIDE_NAMES : REGION_NAMES, read: () => findTop(env, region, now) });
    }
  }
  // Every genre named in any language: their IDs are the same in all of them.
  const lists = new Map<string, { kind: Kind; genreId: number }>();
  for (const row of kept.values()) {
    if (row.source !== "genres" || row.body === null) continue;
    const names: KeptGenres = JSON.parse(row.body);
    for (const kind of ["movie", "show"] as const) names[kind].forEach((g) => lists.set(`${kind}/${g.id}`, { kind, genreId: g.id }));
  }
  [...lists]
    .filter(([key]) => stale(`discover/${key}`, DISCOVER_FRESH_FOR))
    .sort(([a], [b]) => (fetchedAt(`discover/${a}`) ?? -1) - (fetchedAt(`discover/${b}`) ?? -1))
    .forEach(([key, { kind, genreId }]) => parts.push({ key: `discover/${key}`, requests: DISCOVER_PAGES, read: () => readDiscover(env, kind, genreId, now) }));

  let left = SUBREQUESTS;
  let read = 0;
  for (const part of parts) {
    if ((tried.get(part.key) ?? -Infinity) > now - RETRY_AFTER) continue;
    if (part.alone ? read > 0 : part.requests > left) continue;
    await env.DB.prepare("INSERT OR REPLACE INTO chart_try (part, tried_at) VALUES (?1, ?2)").bind(part.key, now).run();
    read++;
    left -= await part.read();
    if (part.alone) break;
  }
}

const save = (env: Env, source: string, key: string, body: unknown, now: number) =>
  env.DB.prepare("INSERT OR REPLACE INTO chart (source, key, body, fetched_at) VALUES (?1, ?2, ?3, ?4)").bind(source, key, JSON.stringify(body), now);

/** TMDB's genres of both kinds, named in [language], shared by every Region. When TMDB can't answer, the kept names stand. */
async function readGenres(env: Env, language: string, now: number): Promise<number> {
  try {
    const [movie, show] = await Promise.all([fetchGenres(env, "movie", language), fetchGenres(env, "show", language)]);
    await save(env, "genres", language, { movie, show } satisfies KeptGenres, now).run();
  } catch (e) {
    if (!(e instanceof TmdbUnavailable)) throw e;
    console.warn(`TMDB's genres in ${language} couldn't be fetched: ${e.message}`);
  }
  return 2;
}

/**
 * One genre's most popular movies or shows, kept on its own for a week and shared by every
 * language. When TMDB can't answer, the kept list stands.
 */
async function readDiscover(env: Env, kind: Kind, genreId: number, now: number): Promise<number> {
  try {
    const { tmdbIds, requests } = await fetchDiscover(env, kind, genreId, DISCOVER_PAGES);
    await save(env, "discover", `${kind}/${genreId}`, tmdbIds, now).run();
    return requests;
  } catch (e) {
    if (!(e instanceof TmdbUnavailable)) throw e;
    console.warn(`TMDB's Discover couldn't be fetched: ${e.message}`);
    return DISCOVER_PAGES;
  }
}

/** TMDB's weekly trending lists, the same for every Region and language. When TMDB can't answer, the last copy stands. */
async function readTrending(env: Env, now: number): Promise<number> {
  try {
    const [all, movies, shows] = await Promise.all([fetchTrending(env, "all", TRENDING_PAGES), fetchTrending(env, "movie", TRENDING_PAGES), fetchTrending(env, "show", TRENDING_PAGES)]);
    await save(env, "trending", "", { all, movies, shows } satisfies TrendingLists, now).run();
  } catch (e) {
    if (!(e instanceof TmdbUnavailable)) throw e;
    console.warn(`TMDB's trending couldn't be fetched: ${e.message}`);
  }
  return 3 * TRENDING_PAGES;
}

/**
 * Finds [region]'s Top 10 names (`netflix-week`) on TMDB, reusing what any Top 10 kept found, and
 * keeps it as `netflix`. When TMDB can't answer, the last copy stands.
 */
async function findTop(env: Env, region: string, now: number): Promise<number> {
  const { results } = await env.DB.prepare("SELECT source, body FROM chart WHERE source = 'netflix' OR (source = 'netflix-week' AND key = ?1)")
    .bind(region)
    .all<{ source: string; body: string }>();
  const week = results.find((r) => r.source === "netflix-week");
  if (!week) return 0;
  const names: TopNames = JSON.parse(week.body);
  const known = new Map<string, number | null>();
  for (const r of results.filter((r) => r.source === "netflix")) {
    const top: KeptTop = JSON.parse(r.body);
    top.movies.forEach((n) => known.set(`movie/${n.name}`, n.tmdbId));
    top.shows.forEach((n) => known.set(`show/${n.name}`, n.tmdbId));
  }
  let sent = 0;
  const find = (kind: Kind, list: string[]) =>
    Promise.all(
      list.map(async (name): Promise<Named> => {
        const id = known.get(`${kind}/${name}`);
        if (id !== undefined) return { name, tmdbId: id };
        sent++;
        return { name, tmdbId: await findOnTmdb(env, kind, name) };
      }),
    );
  try {
    const [movies, shows] = await Promise.all([find("movie", names.movies), find("show", names.shows)]);
    await save(env, "netflix", region, { worldwide: names.worldwide, movies, shows } satisfies KeptTop, now).run();
  } catch (e) {
    if (!(e instanceof TmdbUnavailable)) throw e;
    console.warn(`Netflix's Top 10 for ${region} couldn't be found on TMDB: ${e.message}`);
  }
  return sent;
}

/** The TMDB ID of the most popular [kind] TMDB's search finds by [name], or null when it finds none. */
async function findOnTmdb(env: Env, kind: Kind, name: string): Promise<number | null> {
  const found = await search(env, kind, name, "en");
  return found.reduce<(typeof found)[number] | null>((best, f) => (best === null || f.popularity > best.popularity ? f : best), null)?.tmdbId ?? null;
}

/**
 * Reads Netflix's country file (32 MB) once through and keeps every Region's newest week as
 * `netflix-week`, [SAVE_EVERY] Regions at a time, so a run stopped short keeps the Regions it
 * passed; `netflix-scan` notes the whole file was read. The file is grouped by country, newest week
 * first, so only the first lines of each country are decoded: a chunk whose last line is still the
 * country's before is passed over whole. That keeps the Worker's own work per chunk small, leaving
 * mostly the runtime's handing over of the bytes (#32, #214). When Netflix can't answer, the kept
 * weeks stand.
 */
async function scanCountries(env: Env, now: number): Promise<number> {
  const decoder = new TextDecoder();
  let column: Columns | null = null;
  /** The bytes each line of the country being passed starts with: its cells up to its code's. */
  let country: Uint8Array | null = null;
  /** The country whose newest week is being read, while it is. */
  let reading: (TopNames & { region: string; week: string }) | null = null;
  const done: D1PreparedStatement[] = [];
  const finish = () => {
    if (reading === null) return;
    done.push(save(env, "netflix-week", reading.region, { worldwide: false, movies: reading.movies, shows: reading.shows } satisfies TopNames, now));
    reading = null;
  };
  const line = (bytes: Uint8Array) => {
    if (bytes.length === 0) return;
    if (column === null) {
      column = columns(decoder.decode(bytes));
      return;
    }
    if (reading !== null) {
      const cells = decoder.decode(bytes).split("\t");
      if (cells[column.country_iso2] === reading.region && cells[column.week] === reading.week) {
        (cells[column.category] === "TV" ? reading.shows : reading.movies).push(cells[column.show_title]);
        return;
      }
      finish();
    }
    if (country !== null && startsWith(bytes, 0, country)) return;
    // A country's first line: the first of its newest week.
    let end = -1;
    for (let i = 0; i <= column.country_iso2; i++) end = bytes.indexOf(TAB, end + 1);
    country = bytes.slice(0, end + 1);
    const cells = decoder.decode(bytes).split("\t");
    reading = { worldwide: false, region: cells[column.country_iso2], week: cells[column.week], movies: [], shows: [] };
    (cells[column.category] === "TV" ? reading.shows : reading.movies).push(cells[column.show_title]);
  };

  let response: Response;
  try {
    response = await fetch(`${env.NETFLIX_TOP10}/all-weeks-countries.tsv`);
    if (!response.ok || response.body === null) throw new NetflixUnavailable(`Netflix answered HTTP ${response.status} for all-weeks-countries.tsv`);
  } catch (e) {
    console.warn(`Netflix's country file couldn't be fetched: ${e instanceof Error ? e.message : e}`);
    return 1;
  }
  // Read [READ_SIZE] at a time into one buffer (workerd's readAtLeast), so the Worker's work is per
  // megabyte, not per network chunk of a few kilobytes.
  const reader = response.body.getReader({ mode: "byob" });
  let buffer = new ArrayBuffer(READ_SIZE);
  /** How many bytes at the buffer's start are a line the read before ended in. */
  let kept = 0;
  try {
    for (;;) {
      const { done: ended, value } = await reader.readAtLeast(READ_SIZE - kept, new Uint8Array(buffer, kept));
      if (value) buffer = value.buffer as ArrayBuffer;
      const bytes = new Uint8Array(buffer, 0, kept + (value?.length ?? 0));
      // The file's last line may not end in a newline.
      const last = ended ? bytes.length : bytes.lastIndexOf(NEWLINE);
      const lastStart = last > 0 ? bytes.lastIndexOf(NEWLINE, last - 1) + 1 : 0;
      let at = 0;
      while (at < last) {
        if (reading === null && country !== null && startsWith(bytes, at, country)) {
          // In a country's older weeks. Grouped by country: when the read's last line is still the
          // country's, so is every line before it; else the next country starts in between.
          at = startsWith(bytes, lastStart, country) ? last + 1 : nextBlock(bytes, at, lastStart, country);
          continue;
        }
        const end = bytes.indexOf(NEWLINE, at);
        line(bytes.subarray(at, end < 0 ? last : end));
        at = end < 0 ? last : end + 1;
      }
      if (ended) break;
      bytes.copyWithin(0, last + 1);
      kept = bytes.length - last - 1;
      if (kept === READ_SIZE) throw new NetflixUnavailable(`a line of Netflix's country file is over ${READ_SIZE} bytes`);
      if (done.length >= SAVE_EVERY) await env.DB.batch(done.splice(0));
    }
    finish();
    done.push(env.DB.prepare("INSERT OR REPLACE INTO chart (source, key, body, fetched_at) VALUES ('netflix-scan', '', '{}', ?1)").bind(now));
  } catch (e) {
    console.warn(`Netflix's country file couldn't be read: ${e}`);
  } finally {
    await reader.cancel().catch(() => {});
    if (done.length > 0) await env.DB.batch(done);
  }
  return 1;
}

/**
 * Where the first line in [bytes] after [from] that doesn't begin with [start] begins, found by
 * halving: the line at [from] begins with it, the line at [to] doesn't, and the lines that do come
 * first.
 */
function nextBlock(bytes: Uint8Array, from: number, to: number, start: Uint8Array): number {
  for (;;) {
    let probe = bytes.lastIndexOf(NEWLINE, (from + to) >> 1) + 1;
    if (probe <= from) probe = bytes.indexOf(NEWLINE, from) + 1;
    if (probe >= to) return to;
    if (startsWith(bytes, probe, start)) from = probe;
    else to = probe;
  }
}

function startsWith(bytes: Uint8Array, at: number, start: Uint8Array): boolean {
  if (at + start.length > bytes.length) return false;
  for (let i = 0; i < start.length; i++) if (bytes[at + i] !== start[i]) return false;
  return true;
}

/**
 * The newest week of Netflix's worldwide file: its English and non-English lists of each kind,
 * merged by weekly views, kept as `netflix-week` for every Region Netflix doesn't list. When Netflix
 * can't answer, the kept week stands.
 */
async function readWorldwide(env: Env, now: number): Promise<number> {
  const viewed = { movies: [] as { name: string; views: number }[], shows: [] as { name: string; views: number }[] };
  const decoder = new TextDecoder();
  let week: string | null = null;
  let column: Columns | null = null;
  try {
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
  } catch (e) {
    if (!(e instanceof NetflixUnavailable)) throw e;
    console.warn(`Netflix's worldwide Top 10 couldn't be fetched: ${e.message}`);
    return 1;
  }
  const names = (list: { name: string; views: number }[]) => [...new Set(list.sort((a, b) => b.views - a.views).map((r) => r.name))];
  await save(env, "netflix-week", WORLDWIDE, { worldwide: true, movies: names(viewed.movies), shows: names(viewed.shows) } satisfies TopNames, now).run();
  return 1;
}

/** Where each column Netflix's files name in their first line is. */
type Columns = Record<"country_iso2" | "week" | "category" | "show_title" | "weekly_views", number>;

function columns(header: string): Columns {
  const names = header.split("\t");
  const at = (name: string) => names.indexOf(name);
  return { country_iso2: at("country_iso2"), week: at("week"), category: at("category"), show_title: at("show_title"), weekly_views: at("weekly_views") };
}

const NEWLINE = 0x0a;
const TAB = 0x09;

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
