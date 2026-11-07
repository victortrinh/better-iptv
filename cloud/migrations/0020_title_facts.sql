-- Title facts from Wikidata (#476, ADR 0014), served by POST /v1/title-facts, by Title number (kind and
-- the Provider's TMDB number). Cached for good: a Title asked by any Viewer of any Provider costs Wikidata
-- one query ever. found is 0 when Wikidata has no item for the number, and the other columns are then
-- null. original_language is a primary subtag (P364 through P218), imdb_id Wikidata's (P345), enwiki and
-- frwiki the English and French Wikipedia article names (sitelinks), each null when the item lacks it.
-- The subtitles endpoint reads imdb_id from here.
CREATE TABLE title_facts (
  kind TEXT NOT NULL,
  tmdb_id INTEGER NOT NULL,
  found INTEGER NOT NULL,
  original_language TEXT,
  imdb_id TEXT,
  enwiki TEXT,
  frwiki TEXT,
  fetched_at INTEGER NOT NULL,
  PRIMARY KEY (kind, tmdb_id)
);
