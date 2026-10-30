import type { Env } from "./env";
import { type Found, NOT_FOUND, type Searched, type ServiceFile, ServiceUnavailable, SUBTITLE_FORMATS } from "./subtitle-service";
import { unzip } from "./zip";

/** Whether the cloud has Subdl's API key. */
export const configured = (env: Env) => !!env.SUBDL_API_KEY;

/** The most results Subdl gives in one page. */
const PAGE = 30;

/** Subdl's subtitles for a Movie or Episode in [languages] (ISO 639-1), in Subdl's order. */
export async function search(env: Env, asked: Searched, languages: string[]): Promise<Found[]> {
  const url = new URL(`${env.SUBDL_API}/subtitles`);
  url.searchParams.set("api_key", env.SUBDL_API_KEY!);
  url.searchParams.set("tmdb_id", String(asked.tmdbId));
  if (asked.imdbId) url.searchParams.set("imdb_id", asked.imdbId);
  url.searchParams.set("type", asked.season === null ? "movie" : "tv");
  if (asked.season !== null) {
    url.searchParams.set("season_number", String(asked.season));
    url.searchParams.set("episode_number", String(asked.episode));
  }
  url.searchParams.set("languages", [...new Set(languages.flatMap(serviceLanguages))].join(","));
  url.searchParams.set("subs_per_page", String(PAGE));
  const response = await fetchOrUnavailable(url);
  // Subdl answers a Title it doesn't know with `status: false`, sometimes as HTTP 400 or 404.
  const body = (await response.json().catch(() => null)) as { status?: boolean; subtitles?: SubdlSubtitle[] } | null;
  if (!response.ok && !(body?.status === false && (response.status === 400 || response.status === 404))) {
    console.warn(`Subdl answered HTTP ${response.status} for a search`);
    throw new ServiceUnavailable(`Subdl answered HTTP ${response.status}`);
  }
  if (!body?.status) return [];
  return (body.subtitles ?? []).flatMap((s) => {
    // The url carries the cloud's key (`/subtitle/1-2.zip?api_key=…`): only the zip's path goes into the ref.
    const zip = /^\/subtitle\/([\w.-]+)\.zip$/.exec(new URL(s.url ?? "", env.SUBDL_API).pathname);
    const language = primaryLanguage(s.language ?? "");
    if (!zip || !language) return [];
    // A whole season's zip holds every Episode: the download picks the one asked for.
    const episode = asked.season === null ? "" : `:${asked.season}:${asked.episode}`;
    return [{ ref: `${language}:${zip[1]}${episode}`, language, release: s.release_name ?? s.name ?? "", hearingImpaired: !!s.hi, downloads: null }];
  });
}

/** Downloads the zip a search gave as [ref] and takes its subtitle file: the asked Episode's, for a season's zip. */
export async function download(env: Env, ref: string): Promise<ServiceFile | typeof NOT_FOUND> {
  const [, path, season, episode] = ref.split(":");
  if (!/^[\w.-]+$/.test(path ?? "")) return NOT_FOUND;
  const url = new URL(`${env.SUBDL_DOWNLOADS}/subtitle/${path}.zip`);
  url.searchParams.set("api_key", env.SUBDL_API_KEY!);
  const response = await fetchOrUnavailable(url);
  if (response.status === 404) return NOT_FOUND;
  if (!response.ok) throw new ServiceUnavailable(`Subdl answered HTTP ${response.status} for a download`);
  let files;
  try {
    files = unzip(new Uint8Array(await response.arrayBuffer())).filter((f) => SUBTITLE_FORMATS.includes(extension(f.name)));
  } catch {
    return NOT_FOUND;
  }
  if (season !== undefined && files.length > 1) {
    const s = Number(season);
    const e = Number(episode);
    const named = new RegExp(`(?:s0*${s}[ ._-]*e0*${e}|\\b0*${s}x0*${e})(?!\\d)`, "i");
    files = files.filter((f) => named.test(f.name));
  }
  // A zip with a film in parts (CD1, CD2) or several cuts: the longest file is likeliest the whole film.
  const file = files.sort((a, b) => b.size - a.size)[0];
  if (!file) return NOT_FOUND;
  return { name: file.name, bytes: await file.read() };
}

const extension = (name: string) => name.slice(name.lastIndexOf(".") + 1).toLowerCase();

async function fetchOrUnavailable(url: URL): Promise<Response> {
  try {
    return await fetch(url);
  } catch (e) {
    throw new ServiceUnavailable(String(e));
  }
}

/** Subdl's codes for a language: Brazilian Portuguese and traditional Chinese have their own. */
function serviceLanguages(language: string): string[] {
  if (language === "pt") return ["PT", "BR_PT"];
  if (language === "zh") return ["ZH", "ZH_BG"];
  return [language.toUpperCase()];
}

/** `BR_PT` is `pt`, `ZH_BG` is `zh`. */
function primaryLanguage(code: string): string {
  const lower = code.toLowerCase();
  if (lower === "br_pt") return "pt";
  return /^[a-z]{2}/.exec(lower)?.[0] ?? "";
}

/** The parts of a Subdl search result the cloud uses. */
interface SubdlSubtitle {
  release_name?: string;
  name?: string;
  language?: string;
  hi?: boolean;
  url?: string;
}
