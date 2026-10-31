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
  /** The Title's name in every language TMDB has (the original name where it isn't translated), for Mismatch checks and search aliases. */
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
  /** The Title's logo art (PNG or SVG): the best voted in the requested language, else in English, else null. */
  logoPath: string | null;
  /** The most voted poster with no text on it (no language), for art the app lays the logo over; null when TMDB has none. */
  textlessPosterPath: string | null;
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
 * alternative titles, credits, videos, external IDs, logos and posters.
 */
export async function fetchTitle(env: Env, key: TitleKey, language: string): Promise<TitleMetadata | typeof NOT_FOUND> {
  const path = `${tmdbPath(key.kind)}/${key.tmdbId}`;
  const response = await get(env, path, {
    language,
    append_to_response: "translations,alternative_titles,credits,videos,external_ids,images",
    include_video_language: `${language},en`,
    // `null` brings the language-free images: textless posters.
    include_image_language: `${language},en,null`,
  });
  if (response === NOT_FOUND) return NOT_FOUND;
  return read(key, language, (await response.json()) as TmdbTitle);
}

/** An Episode TMDB lists as not aired yet: its numbers, its air date (`YYYY-MM-DD`) and its name, when TMDB has one. */
export interface UpcomingEpisode {
  season: number;
  episode: number;
  airDate: string;
  name: string | null;
}

/**
 * The Episodes of Show [tmdbId] from the next one TMDB says airs on, to the end of its season, with
 * names in [language] (ISO 639-1): the Show's details, then that season's. Empty when nothing is to
 * air, NOT_FOUND when TMDB has no such Show.
 */
export async function fetchUpcoming(env: Env, tmdbId: number, language: string): Promise<UpcomingEpisode[] | typeof NOT_FOUND> {
  const show = await get(env, `tv/${tmdbId}`, { language });
  if (show === NOT_FOUND) return NOT_FOUND;
  const next = ((await show.json()) as { next_episode_to_air?: TmdbEpisode | null }).next_episode_to_air;
  if (!next?.air_date) return [];
  const season = await get(env, `tv/${tmdbId}/season/${next.season_number}`, { language });
  // A season TMDB can't find (just announced) still has the next Episode.
  const episodes = season === NOT_FOUND ? [next] : (((await season.json()) as { episodes?: TmdbEpisode[] }).episodes ?? []);
  return episodes
    .filter((e) => e.air_date && e.air_date >= next.air_date! && e.episode_number >= next.episode_number)
    .map((e) => ({ season: next.season_number, episode: e.episode_number, airDate: e.air_date!, name: e.name?.trim() || null }));
}

/** One result of TMDB's search: its name in the requested language, as released, and its year. */
export interface Found {
  tmdbId: number;
  title: string;
  originalTitle: string;
  year: number | null;
  popularity: number;
}

/** The first page of TMDB's search for [kind] Titles called [query], with names in [language] (ISO 639-1). */
export async function search(env: Env, kind: Kind, query: string, language: string): Promise<Found[]> {
  const response = await get(env, `search/${tmdbPath(kind)}`, { query, language, include_adult: "false" });
  if (response === NOT_FOUND) return [];
  const { results } = (await response.json()) as { results?: TmdbSearchResult[] };
  return (results ?? []).map((r) => ({
    tmdbId: r.id,
    title: r.title ?? r.name ?? "",
    originalTitle: r.original_title ?? r.original_name ?? "",
    year: Number.parseInt((r.release_date ?? r.first_air_date ?? "").slice(0, 4)) || null,
    popularity: r.popularity ?? 0,
  }));
}

/** What TMDB's multi search found: Titles of either kind and people, each with its popularity. */
export interface MultiFound {
  titles: (TitleKey & { popularity: number })[];
  people: { personId: number; name: string; popularity: number }[];
}

/**
 * The first page of TMDB's search across Movies, Shows and people for [query]. TMDB matches titles
 * in every language and alternative titles, so the language only sets the names it answers with.
 */
export async function searchAll(env: Env, query: string): Promise<MultiFound> {
  const response = await get(env, "search/multi", { query, language: "en", include_adult: "false" });
  const found: MultiFound = { titles: [], people: [] };
  if (response === NOT_FOUND) return found;
  const { results } = (await response.json()) as { results?: TmdbMultiResult[] };
  for (const r of results ?? []) {
    const popularity = r.popularity ?? 0;
    if (r.media_type === "movie" || r.media_type === "tv") found.titles.push({ kind: r.media_type === "movie" ? "movie" : "show", tmdbId: r.id, popularity });
    else if (r.media_type === "person" && r.name) found.people.push({ personId: r.id, name: r.name, popularity });
  }
  return found;
}

/**
 * TMDB's weekly trending list of [kind] (`all` mixes movies and shows), its first [pages] pages of
 * 20, in order. People TMDB lists among them are left out.
 */
export async function fetchTrending(env: Env, kind: "all" | Kind, pages: number): Promise<TitleKey[]> {
  const titles: TitleKey[] = [];
  for (let page = 1; page <= pages; page++) {
    const response = await get(env, `trending/${kind === "all" ? "all" : tmdbPath(kind)}/week`, { language: "en", page: String(page) });
    if (response === NOT_FOUND) break;
    const { results, total_pages } = (await response.json()) as { results?: TmdbMultiResult[]; total_pages?: number };
    for (const r of results ?? []) {
      const type = kind === "all" ? r.media_type : tmdbPath(kind);
      if (type === "movie" || type === "tv") titles.push({ kind: type === "movie" ? "movie" : "show", tmdbId: r.id });
    }
    if (page >= (total_pages ?? 1)) break;
  }
  return titles;
}

/** The Titles [personId] acted in or directed, by TMDB's combined credits; NOT_FOUND when TMDB has no such person. */
export async function credits(env: Env, personId: number): Promise<TitleKey[] | typeof NOT_FOUND> {
  const response = await get(env, `person/${personId}/combined_credits`, { language: "en" });
  if (response === NOT_FOUND) return NOT_FOUND;
  const { cast, crew } = (await response.json()) as { cast?: TmdbCredit[]; crew?: (TmdbCredit & { job?: string })[] };
  const titles = new Map<string, TitleKey>();
  for (const credit of [...(cast ?? []), ...(crew ?? []).filter((c) => c.job === "Director")]) {
    if (credit.media_type !== "movie" && credit.media_type !== "tv") continue;
    const kind: Kind = credit.media_type === "movie" ? "movie" : "show";
    titles.set(`${kind}/${credit.id}`, { kind, tmdbId: credit.id });
  }
  return [...titles.values()];
}

const tmdbPath = (kind: Kind) => (kind === "movie" ? "movie" : "tv");

/** GETs [path] from TMDB with the cloud's key; NOT_FOUND on a 404. */
async function get(env: Env, path: string, params: Record<string, string>): Promise<Response | typeof NOT_FOUND> {
  const url = new URL(`${env.TMDB_API}/${path}`);
  for (const [name, value] of Object.entries(params)) url.searchParams.set(name, value);
  const headers: Record<string, string> = { Accept: "application/json" };
  // TMDB takes its short API key as a query parameter and its read access token as a bearer token.
  if (/^[0-9a-f]{32}$/.test(env.TMDB_TOKEN)) url.searchParams.set("api_key", env.TMDB_TOKEN);
  else headers.Authorization = `Bearer ${env.TMDB_TOKEN}`;
  let response: Response;
  try {
    response = await fetch(url, { headers });
  } catch (e) {
    throw new TmdbUnavailable(String(e));
  }
  if (response.status === 404) return NOT_FOUND;
  if (!response.ok) {
    // Shows up in the Worker's logs; TMDB's message names the problem (a wrong key, a rate limit), never the key.
    console.warn(`TMDB answered HTTP ${response.status} for ${path}: ${(await response.text()).slice(0, 200)}`);
    throw new TmdbUnavailable(`TMDB answered HTTP ${response.status}`);
  }
  return response;
}

function read(key: TitleKey, language: string, t: TmdbTitle): TitleMetadata {
  const translations = t.translations?.translations ?? [];
  const runtime = t.runtime ?? t.episode_run_time?.[0] ?? null;
  const originalTitle = t.original_title ?? t.original_name ?? "";
  return {
    kind: key.kind,
    tmdbId: key.tmdbId,
    originalLanguage: t.original_language || null,
    originalTitle,
    // TMDB leaves a translation's title empty when it is the original one: France calls Ballerina "Ballerina", Québec "Ballerine".
    titles: translations
      .map((tr) => ({ language: tr.iso_639_1, region: tr.iso_3166_1, title: (tr.data?.title ?? tr.data?.name ?? "").trim() || originalTitle }))
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
    logoPath: bestLogo(t.images?.logos ?? [], language),
    textlessPosterPath: mostVoted((t.images?.posters ?? []).filter((p) => p.iso_639_1 == null || p.iso_639_1 === "xx"))?.file_path ?? null,
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

/** The most voted logo in [language], else in English; logos without a language are text-free marks, so none of those. */
function bestLogo(logos: TmdbImage[], language: string): string | null {
  for (const wanted of [language, "en"]) {
    const best = mostVoted(logos.filter((l) => l.iso_639_1 === wanted && /\.(png|svg)$/i.test(l.file_path)));
    if (best) return best.file_path;
  }
  return null;
}

function mostVoted(images: TmdbImage[]): TmdbImage | undefined {
  return [...images].sort((a, b) => (b.vote_average ?? 0) - (a.vote_average ?? 0) || (b.vote_count ?? 0) - (a.vote_count ?? 0))[0];
}

/** The parts of a TMDB image the cloud uses. */
interface TmdbImage {
  file_path: string;
  iso_639_1?: string | null;
  vote_average?: number;
  vote_count?: number;
}

/** The parts of a TMDB movie or TV search result the cloud uses. */
interface TmdbSearchResult {
  id: number;
  title?: string;
  name?: string;
  original_title?: string;
  original_name?: string;
  release_date?: string;
  first_air_date?: string;
  popularity?: number;
}

/** The parts of a TMDB multi search result the cloud uses. */
interface TmdbMultiResult {
  id: number;
  media_type?: string;
  name?: string;
  popularity?: number;
}

/** The parts of one of a person's TMDB credits the cloud uses. */
interface TmdbCredit {
  id: number;
  media_type?: string;
}

/** The parts of a TMDB Episode the cloud uses. */
interface TmdbEpisode {
  season_number: number;
  episode_number: number;
  air_date?: string | null;
  name?: string;
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
  images?: { logos?: TmdbImage[]; posters?: TmdbImage[] };
}
