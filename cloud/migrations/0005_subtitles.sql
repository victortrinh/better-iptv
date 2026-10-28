-- What the subtitle services found for one Movie or Episode in one language: a JSON array of offers, best first.
-- season and episode are 0 for a Movie.
CREATE TABLE subtitle_search (
  kind TEXT NOT NULL,
  tmdb_id INTEGER NOT NULL,
  season INTEGER NOT NULL,
  episode INTEGER NOT NULL,
  language TEXT NOT NULL,
  body TEXT NOT NULL,
  fetched_at INTEGER NOT NULL,
  PRIMARY KEY (kind, tmdb_id, season, episode, language)
);

-- A downloaded subtitle file, as UTF-8 text, by the offer id the search gave it. A file never changes, so it is kept.
CREATE TABLE subtitle_file (
  id TEXT PRIMARY KEY,
  format TEXT NOT NULL,
  body TEXT NOT NULL,
  fetched_at INTEGER NOT NULL
);

-- A subtitle service's signed-in session (OpenSubtitles' login token and the API host it names), reused until it expires.
CREATE TABLE service_session (
  service TEXT PRIMARY KEY,
  token TEXT NOT NULL,
  base_url TEXT NOT NULL,
  fetched_at INTEGER NOT NULL
);
