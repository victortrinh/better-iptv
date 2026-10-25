-- What TMDB's search made of one cleaned name: the Title it found, or a null tmdb_id when none matched.
-- year is 0 and language '' when the Listing's name has none.
CREATE TABLE title_lookup (
  kind TEXT NOT NULL,
  name TEXT NOT NULL,
  year INTEGER NOT NULL,
  language TEXT NOT NULL,
  tmdb_id INTEGER,
  fetched_at INTEGER NOT NULL,
  PRIMARY KEY (kind, name, year, language)
);
