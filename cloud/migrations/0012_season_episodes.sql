-- One season of a Show as TMDB lists it, in one language: a JSON array of its Episodes' names, stills and runtimes, in order.
CREATE TABLE season_episodes (
  tmdb_id INTEGER NOT NULL,
  season INTEGER NOT NULL,
  language TEXT NOT NULL,
  body TEXT NOT NULL,
  fetched_at INTEGER NOT NULL,
  PRIMARY KEY (tmdb_id, season, language)
);
