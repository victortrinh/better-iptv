import type { Env } from "./env";

/** Movie and show TMDB IDs are separate number spaces, so a Title is keyed by kind and ID. */
export type Kind = "movie" | "show";

export interface TitleKey {
  kind: Kind;
  tmdbId: number;
}

/**
 * What the app shows about a Title, in one language. Image references are TMDB paths
 * (`/abc.jpg`): the device picks a size and loads them from TMDB's image CDN, which needs no key.
 */
export interface TitleMetadata extends TitleKey {
  originalLanguage: string | null;
  originalTitle: string;
  /** The Title's name in every language TMDB has, for Mismatch checks and search aliases. */
  titles: { language: string; region: string; title: string }[];
  /** Other names it is known by (working titles, regional names). */
  alternativeTitles: string[];
  /** Descriptions in the requested language and in English, the app's fallback. */
  descriptions: { language: string; region: string; text: string }[];
  /** Genre names in the requested language. */
  genres: string[];
  popularity: number;
  /** `YYYY-MM-DD`: the release date of a Movie, the first air date of a Show. */
  releaseDate: string | null;
  runtimeMinutes: number | null;
  posterPath: string | null;
  backdropPath: string | null;
  cast: { name: string; character: string; profilePath: string | null }[];
  imdbId: string | null;
  /** YouTube video IDs, official trailers first. */
  trailers: string[];
}

/** TMDB has no Title with this kind and ID. */
export const NOT_FOUND = null;

/** TMDB couldn't answer right now (rate limit, outage); worth asking again later. */
export class TmdbUnavailable extends Error {}

const CAST_SHOWN = 12;

/**
 * Fetches one Title in [language] (ISO 639-1) with a single TMDB request: details, translations,
 * alternative titles, credits, videos and external IDs.
 */
export async function fetchTitle(env: Env, key: TitleKey, language: string): Promise<TitleMetadata | typeof NOT_FOUND> {
  const path = key.kind === "movie" ? "movie" : "tv";
  const url = new URL(`${env.TMDB_API}/${path}/${key.tmdbId}`);
  url.searchParams.set("language", language);
  url.searchParams.set("append_to_response", "translations,alternative_titles,credits,videos,external_ids");
  url.searchParams.set("include_video_language", `${language},en`);
  let response: Response;
  try {
    response = await fetch(url, { headers: { Authorization: `Bearer ${env.TMDB_TOKEN}`, Accept: "application/json" } });
  } catch (e) {
    throw new TmdbUnavailable(String(e));
  }
  if (response.status === 404) return NOT_FOUND;
  if (!response.ok) throw new TmdbUnavailable(`TMDB answered HTTP ${response.status}`);
  return read(key, language, (await response.json()) as TmdbTitle);
}

function read(key: TitleKey, language: string, t: TmdbTitle): TitleMetadata {
  const translations = t.translations?.translations ?? [];
  const runtime = t.runtime ?? t.episode_run_time?.[0] ?? null;
  return {
    kind: key.kind,
    tmdbId: key.tmdbId,
    originalLanguage: t.original_language || null,
    originalTitle: t.original_title ?? t.original_name ?? "",
    titles: translations
      .map((tr) => ({ language: tr.iso_639_1, region: tr.iso_3166_1, title: (tr.data?.title ?? tr.data?.name ?? "").trim() }))
      .filter((tr) => tr.title !== ""),
    alternativeTitles: [...new Set((t.alternative_titles?.titles ?? t.alternative_titles?.results ?? []).map((a) => a.title.trim()).filter((a) => a !== ""))],
    descriptions: translations
      .filter((tr) => tr.iso_639_1 === language || tr.iso_639_1 === "en")
      .map((tr) => ({ language: tr.iso_639_1, region: tr.iso_3166_1, text: (tr.data?.overview ?? "").trim() }))
      .filter((d) => d.text !== ""),
    genres: (t.genres ?? []).map((g) => g.name),
    popularity: t.popularity ?? 0,
    releaseDate: (t.release_date ?? t.first_air_date) || null,
    runtimeMinutes: runtime || null,
    posterPath: t.poster_path ?? null,
    backdropPath: t.backdrop_path ?? null,
    cast: (t.credits?.cast ?? [])
      .sort((a, b) => (a.order ?? 0) - (b.order ?? 0))
      .slice(0, CAST_SHOWN)
      .map((c) => ({ name: c.name, character: c.character ?? "", profilePath: c.profile_path ?? null })),
    imdbId: (t.imdb_id ?? t.external_ids?.imdb_id) || null,
    trailers: (t.videos?.results ?? [])
      .filter((v) => v.site === "YouTube" && v.type === "Trailer")
      .sort((a, b) => Number(b.official ?? false) - Number(a.official ?? false))
      .map((v) => v.key),
  };
}

/** The parts of TMDB's movie and TV details responses the app uses. */
interface TmdbTitle {
  title?: string;
  name?: string;
  original_title?: string;
  original_name?: string;
  original_language?: string;
  genres?: { id: number; name: string }[];
  popularity?: number;
  release_date?: string;
  first_air_date?: string;
  runtime?: number | null;
  episode_run_time?: number[];
  poster_path?: string | null;
  backdrop_path?: string | null;
  imdb_id?: string | null;
  translations?: { translations: { iso_639_1: string; iso_3166_1: string; data?: { title?: string; name?: string; overview?: string } }[] };
  alternative_titles?: { titles?: { title: string }[]; results?: { title: string }[] };
  credits?: { cast?: { name: string; character?: string; profile_path?: string | null; order?: number }[] };
  videos?: { results?: { key: string; site: string; type: string; official?: boolean }[] };
  external_ids?: { imdb_id?: string | null };
}
