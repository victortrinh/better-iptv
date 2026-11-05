import type { Env } from "./env";
import { fetchSeason, TmdbUnavailable, type SeasonEpisode } from "./tmdb";

/** A season's Episodes are kept this long: a still or a runtime is added within days of an Episode airing. */
const FRESH_FOR = 3 * 24 * 3600_000;

/**
 * Season [season] of Show [tmdbId] as TMDB lists it, each Episode with its name in [language], its
 * still and its runtime, from the cloud's cache or else from TMDB. A season TMDB doesn't have has no
 * Episodes. When TMDB can't answer, a stale cached answer still stands; with none, TmdbUnavailable is thrown.
 */
export async function season(env: Env, tmdbId: number, season: number, language: string): Promise<SeasonEpisode[]> {
  const cached = await env.DB.prepare("SELECT body, fetched_at FROM season_episodes WHERE tmdb_id = ?1 AND season = ?2 AND language = ?3")
    .bind(tmdbId, season, language)
    .first<{ body: string; fetched_at: number }>();
  const now = Date.now();
  if (cached && now - cached.fetched_at <= FRESH_FOR) return JSON.parse(cached.body);
  let episodes: SeasonEpisode[];
  try {
    episodes = (await fetchSeason(env, tmdbId, season, language)) ?? [];
  } catch (e) {
    if (e instanceof TmdbUnavailable && cached) return JSON.parse(cached.body);
    throw e;
  }
  await env.DB.prepare("INSERT OR REPLACE INTO season_episodes (tmdb_id, season, language, body, fetched_at) VALUES (?1, ?2, ?3, ?4, ?5)")
    .bind(tmdbId, season, language, JSON.stringify(episodes), now)
    .run();
  return episodes;
}
