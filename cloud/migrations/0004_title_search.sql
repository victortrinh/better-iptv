-- What TMDB's multi search found for one normalized query: Titles and people, as the cloud's JSON.
CREATE TABLE title_search (
  query TEXT PRIMARY KEY,
  body TEXT NOT NULL,
  fetched_at INTEGER NOT NULL
);
-- The Titles one TMDB person acted in or directed, as a JSON list of {kind, tmdbId}. Null body: TMDB has no such person.
CREATE TABLE person_credits (
  person_id INTEGER PRIMARY KEY,
  body TEXT,
  fetched_at INTEGER NOT NULL
);
