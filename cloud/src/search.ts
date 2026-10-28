import type { Env } from "./env";
import { normalize } from "./match";
import { credits, NOT_FOUND, searchAll, TmdbUnavailable, type MultiFound, type TitleKey } from "./tmdb";

/** The longest query searched; anything longer is no name. */
export const MAX_QUERY = 100;

/** A query's answer is kept a day: what people search for comes and goes with new releases. */
const SEARCH_FRESH_FOR = 24 * 3600_000;
/** A person's credits change slowly. */
const CREDITS_FRESH_FOR = 7 * 24 * 3600_000;
/** Only the most popular people found get their credits fetched, which bounds a query to 4 TMDB requests (ADR 0009). */
const PEOPLE_WITH_CREDITS = 3;

/** What a search found on TMDB: Titles by any of their names, and people with every Title they acted in or directed. */
export interface SearchAnswer {
  titles: (TitleKey & { popularity: number })[];
  people: { name: string; titles: TitleKey[] }[];
}

/**
 * Searches TMDB for [query] (ADR 0009), from the cloud's cache when it searched the same normalized
 * query in the last day. Throws TmdbUnavailable when TMDB can't search and nothing is cached; a
 * person whose credits TMDB can't give right now is left out.
 */
export async function search(env: Env, query: string): Promise<SearchAnswer> {
  const key = normalize(query);
  const now = Date.now();
  const cached = await env.DB.prepare("SELECT body, fetched_at FROM title_search WHERE query = ?1").bind(key).first<{ body: string; fetched_at: number }>();
  let found: MultiFound;
  if (cached && now - cached.fetched_at <= SEARCH_FRESH_FOR) {
    found = JSON.parse(cached.body);
  } else {
    try {
      found = await searchAll(env, query);
      await env.DB.prepare("INSERT OR REPLACE INTO title_search (query, body, fetched_at) VALUES (?1, ?2, ?3)").bind(key, JSON.stringify(found), now).run();
    } catch (e) {
      // When TMDB can't search, yesterday's answer still beats none.
      if (!(e instanceof TmdbUnavailable) || !cached) throw e;
      found = JSON.parse(cached.body);
    }
  }

  const people = [...found.people].sort((a, b) => b.popularity - a.popularity).slice(0, PEOPLE_WITH_CREDITS);
  const answers = await Promise.all(people.map(async (person) => ({ name: person.name, titles: await personCredits(env, person.personId, now) })));
  return {
    titles: found.titles,
    people: answers.filter((person): person is { name: string; titles: TitleKey[] } => person.titles !== null),
  };
}

/** [personId]'s Titles, from the cloud's cache or else from TMDB; null when TMDB can't say right now and nothing is cached. */
async function personCredits(env: Env, personId: number, now: number): Promise<TitleKey[] | null> {
  const cached = await env.DB.prepare("SELECT body, fetched_at FROM person_credits WHERE person_id = ?1").bind(personId).first<{ body: string | null; fetched_at: number }>();
  if (cached && now - cached.fetched_at <= CREDITS_FRESH_FOR) return cached.body === null ? [] : JSON.parse(cached.body);
  let titles: TitleKey[] | typeof NOT_FOUND;
  try {
    titles = await credits(env, personId);
  } catch (e) {
    if (!(e instanceof TmdbUnavailable)) throw e;
    return cached ? (cached.body === null ? [] : JSON.parse(cached.body)) : null;
  }
  await env.DB.prepare("INSERT OR REPLACE INTO person_credits (person_id, body, fetched_at) VALUES (?1, ?2, ?3)")
    .bind(personId, titles === NOT_FOUND ? null : JSON.stringify(titles), now)
    .run();
  return titles ?? [];
}
