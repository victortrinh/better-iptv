-- A registered device. Only a hash of its token is kept, so the table can't be used to sign in.
CREATE TABLE device (
  token_hash TEXT PRIMARY KEY,
  registered_at INTEGER NOT NULL
);

-- TMDB's answer for one Title in one language, as the API serves it. Null body: TMDB has no such Title.
CREATE TABLE title_metadata (
  kind TEXT NOT NULL,
  tmdb_id INTEGER NOT NULL,
  language TEXT NOT NULL,
  body TEXT,
  fetched_at INTEGER NOT NULL,
  PRIMARY KEY (kind, tmdb_id, language)
);
