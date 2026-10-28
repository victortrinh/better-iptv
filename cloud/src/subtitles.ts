import type { Env } from "./env";
import * as opensubtitles from "./opensubtitles";
import * as subdl from "./subdl";
import { type Found, NOT_FOUND, type Searched, type ServiceFile, ServiceUnavailable, SUBTITLE_FORMATS } from "./subtitle-service";
import type { Kind } from "./tmdb";

/**
 * Subtitles downloaded from OpenSubtitles and Subdl, for a Version without embedded ones in the
 * Viewer's language. Searches are cached per Movie or Episode and language, and every downloaded
 * file is kept, so a file is downloaded from a service only once. The services' keys live only here.
 */

/** At most this many languages per search. */
export const MAX_LANGUAGES = 5;

/** The best offers kept per language: the app shows three, and a file that fails to download leaves others. */
const PER_LANGUAGE = 5;

/** A search's answer is kept this long, so new subtitles for a recent film come through. */
const FRESH_FOR = 7 * 24 * 3600_000;
/** A search that found nothing is asked again sooner: subtitles for a new release come within days. */
const NOTHING_FRESH_FOR = 24 * 3600_000;

/** The largest file kept: a feature film's SRT is about 100 KB. */
const MAX_FILE = 1024 * 1024;

export type Source = "opensubtitles" | "subdl";

const SERVICES: Record<Source, { configured(env: Env): boolean; search(env: Env, asked: Searched, languages: string[]): Promise<Found[]>; download(env: Env, ref: string): Promise<ServiceFile | null> }> = {
  opensubtitles,
  subdl,
};

/** What the app asks for: a Movie, or an Episode (season and episode null for a Movie), in [languages] (ISO 639-1). */
export interface SubtitleQuestion {
  kind: Kind;
  tmdbId: number;
  season: number | null;
  episode: number | null;
  languages: string[];
}

/** A subtitle file to offer the Viewer. [id] downloads it. */
export interface Offer {
  id: string;
  language: string;
  source: Source;
  release: string;
  hearingImpaired: boolean;
  downloads: number | null;
}

export interface SubtitleSearch {
  /** The best offers in each language, in the order the languages were asked, best first. */
  subtitles: Offer[];
  /** The services that couldn't answer right now: there may be more to find later. */
  unavailable: Source[];
}

/** A subtitle file as UTF-8 text, in one of [SUBTITLE_FORMATS]. */
export interface SubtitleFile {
  format: string;
  text: string;
}

/** Reads the cached searches of one Movie or Episode in a JSON array of languages, by primary key. */
export const SEARCH_CACHE_QUERY = `SELECT language, body, fetched_at FROM subtitle_search
  WHERE kind = ?1 AND tmdb_id = ?2 AND season = ?3 AND episode = ?4 AND language IN (SELECT value FROM json_each(?5))`;

/** The offers for [asked], from the cache or else from the services the cloud has keys for. */
export async function searchSubtitles(env: Env, asked: SubtitleQuestion): Promise<SubtitleSearch> {
  const season = asked.season ?? 0;
  const episode = asked.episode ?? 0;
  const { results } = await env.DB.prepare(SEARCH_CACHE_QUERY).bind(asked.kind, asked.tmdbId, season, episode, JSON.stringify(asked.languages)).all<{ language: string; body: string; fetched_at: number }>();
  const cached = new Map(results.map((row) => [row.language, { offers: JSON.parse(row.body) as Offer[], fetchedAt: row.fetched_at }]));
  const now = Date.now();
  const stale = asked.languages.filter((language) => {
    const entry = cached.get(language);
    return entry === undefined || now - entry.fetchedAt > (entry.offers.length === 0 ? NOTHING_FRESH_FOR : FRESH_FOR);
  });

  const found = new Map<string, Offer[]>();
  const unavailable: Source[] = [];
  if (stale.length > 0) {
    const searched: Searched = { tmdbId: asked.tmdbId, imdbId: await imdbId(env.DB, asked.kind, asked.tmdbId), season: asked.season, episode: asked.episode };
    const sources = (Object.keys(SERVICES) as Source[]).filter((source) => SERVICES[source].configured(env));
    const answers = await Promise.all(
      sources.map(async (source) => {
        try {
          return (await SERVICES[source].search(env, searched, stale)).map((f) => ({ ...f, source }));
        } catch (e) {
          if (!(e instanceof ServiceUnavailable)) throw e;
          unavailable.push(source);
          return [];
        }
      }),
    );
    for (const language of stale) found.set(language, best(answers.flat().filter((f) => f.language === language)));
    // With a service down, its offers would be missing for a week: what the others found is served, not kept.
    if (unavailable.length === 0) {
      const insert = env.DB.prepare(
        "INSERT OR REPLACE INTO subtitle_search (kind, tmdb_id, season, episode, language, body, fetched_at) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7)",
      );
      await env.DB.batch(stale.map((language) => insert.bind(asked.kind, asked.tmdbId, season, episode, language, JSON.stringify(found.get(language)), now)));
    }
  }
  return {
    subtitles: asked.languages.flatMap((language) => {
      const fresh = found.get(language);
      // When a service couldn't answer, a stale cached answer that has offers beats a thinner new one.
      const old = cached.get(language)?.offers;
      return fresh && (unavailable.length === 0 || !old || fresh.length >= old.length) ? fresh : old ?? [];
    }),
    unavailable,
  };
}

/**
 * The best of one language's offers: ones for the hard of hearing (which caption sounds) after the
 * others, and within each OpenSubtitles' most downloaded first, then Subdl's in its order.
 */
function best(found: (Found & { source: Source })[]): Offer[] {
  const rank = (f: Found & { source: Source }) => (f.hearingImpaired ? 2 : 0) + (f.source === "subdl" ? 1 : 0);
  return found
    .map((f, order) => ({ f, order }))
    .sort((a, b) => rank(a.f) - rank(b.f) || (b.f.downloads ?? 0) - (a.f.downloads ?? 0) || a.order - b.order)
    .slice(0, PER_LANGUAGE)
    .map(({ f }) => ({ id: `${f.source}:${f.ref}`, language: f.language, source: f.source, release: f.release, hearingImpaired: f.hearingImpaired, downloads: f.downloads }));
}

/** The Title's IMDb ID, from TMDB's metadata the cloud has cached in any language; null when it has none. */
async function imdbId(db: D1Database, kind: Kind, tmdbId: number): Promise<string | null> {
  const row = await db.prepare("SELECT body ->> '$.imdbId' AS imdb FROM title_metadata WHERE kind = ?1 AND tmdb_id = ?2 AND body IS NOT NULL LIMIT 1")
    .bind(kind, tmdbId)
    .first<{ imdb: string | null }>();
  return row?.imdb ?? null;
}

/** Whether [id] is shaped like an offer's id. */
export const isOfferId = (id: unknown): id is string => typeof id === "string" && id.length <= 200 && /^(opensubtitles|subdl):[a-z]{2}:[\w.:-]+$/.test(id);

/**
 * The subtitle file a search offered as [id], from the cloud's copy or else downloaded from its
 * service. NOT_FOUND when the service no longer has it or it isn't a subtitle the app plays; throws
 * [ServiceUnavailable] when the service can't give it right now.
 */
export async function downloadSubtitle(env: Env, id: string): Promise<SubtitleFile | typeof NOT_FOUND> {
  const kept = await env.DB.prepare("SELECT format, body FROM subtitle_file WHERE id = ?1").bind(id).first<{ format: string; body: string }>();
  if (kept) return { format: kept.format, text: kept.body };
  const [source, language] = id.split(":") as [Source, string];
  const ref = id.slice(source.length + 1);
  const service = SERVICES[source];
  if (!service.configured(env)) throw new ServiceUnavailable(`the cloud has no keys for ${source}`);
  const file = await service.download(env, ref);
  if (file === NOT_FOUND) return NOT_FOUND;
  const format = file.name.slice(file.name.lastIndexOf(".") + 1).toLowerCase();
  if (!SUBTITLE_FORMATS.includes(format) || file.bytes.length > MAX_FILE) return NOT_FOUND;
  const subtitle = { format: format === "ssa" ? "ass" : format, text: decode(file.bytes, language) };
  await env.DB.prepare("INSERT OR REPLACE INTO subtitle_file (id, format, body, fetched_at) VALUES (?1, ?2, ?3, ?4)")
    .bind(id, subtitle.format, subtitle.text, Date.now())
    .run();
  return subtitle;
}

/**
 * A subtitle file's text. Most are UTF-8, but older ones are in their language's Windows code page:
 * a file that isn't valid UTF-8 (or UTF-16, by its byte order mark) is read in that one.
 */
export function decode(bytes: Uint8Array, language: string): string {
  if (bytes[0] === 0xff && bytes[1] === 0xfe) return new TextDecoder("utf-16le").decode(bytes);
  if (bytes[0] === 0xfe && bytes[1] === 0xff) return new TextDecoder("utf-16be").decode(bytes);
  try {
    // Leaves out a byte order mark.
    return new TextDecoder("utf-8", { fatal: true, ignoreBOM: false }).decode(bytes);
  } catch {
    return new TextDecoder(CODE_PAGES[language] ?? "windows-1252").decode(bytes);
  }
}

/** The Windows code page each language's older subtitles are in; Western European languages are in windows-1252. */
const CODE_PAGES: Record<string, string> = {
  ar: "windows-1256", fa: "windows-1256",
  bg: "windows-1251", mk: "windows-1251", ru: "windows-1251", sr: "windows-1251", uk: "windows-1251",
  cs: "windows-1250", hr: "windows-1250", hu: "windows-1250", pl: "windows-1250", ro: "windows-1250", sk: "windows-1250", sl: "windows-1250", bs: "windows-1250",
  el: "windows-1253", he: "windows-1255", tr: "windows-1254", vi: "windows-1258", th: "windows-874",
  et: "windows-1257", lt: "windows-1257", lv: "windows-1257",
  zh: "gbk", ja: "shift_jis", ko: "euc-kr",
};
