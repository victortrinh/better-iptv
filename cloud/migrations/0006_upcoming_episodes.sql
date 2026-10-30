-- A Show's Episodes TMDB lists as not aired yet, in one language: a JSON array, in order.
CREATE TABLE upcoming_episodes (
  tmdb_id INTEGER NOT NULL,
  language TEXT NOT NULL,
  body TEXT NOT NULL,
  fetched_at INTEGER NOT NULL,
  PRIMARY KEY (tmdb_id, language)
);
