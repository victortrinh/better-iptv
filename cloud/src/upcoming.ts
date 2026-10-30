import type { Env } from "./env";
import { fetchUpcoming, TmdbUnavailable, type UpcomingEpisode } from "./tmdb";

/** A Show's upcoming Episodes are kept this long: an air date moves, and a new one is announced, within days. */
const FRESH_FOR = 12 * 3600_000;

/**
 * The Episodes Show [tmdbId] has yet to air, from the next one to the end of its season, named in
 * [language], from the cloud's cache or else from TMDB. A Show TMDB doesn't have has none. When
 * TMDB can't answer, a stale cached answer still stands; with none, TmdbUnavailable is thrown.
 */
export async function upcoming(env: Env, tmdbId: number, language: string): Promise<UpcomingEpisode[]> {
  const cached = await env.DB.prepare("SELECT body, fetched_at FROM upcoming_episodes WHERE tmdb_id = ?1 AND language = ?2")
    .bind(tmdbId, language)
    .first<{ body: string; fetched_at: number }>();
  const now = Date.now();
  if (cached && now - cached.fetched_at <= FRESH_FOR) return JSON.parse(cached.body);
  let episodes: UpcomingEpisode[];
  try {
    episodes = (await fetchUpcoming(env, tmdbId, language)) ?? [];
  } catch (e) {
    if (e instanceof TmdbUnavailable && cached) return JSON.parse(cached.body);
    throw e;
  }
  await env.DB.prepare("INSERT OR REPLACE INTO upcoming_episodes (tmdb_id, language, body, fetched_at) VALUES (?1, ?2, ?3, ?4)")
    .bind(tmdbId, language, JSON.stringify(episodes), now)
    .run();
  return episodes;
}
