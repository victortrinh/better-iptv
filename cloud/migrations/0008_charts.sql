-- One source's Charts as the cloud last fetched them, as its JSON: for source 'netflix', the Top 10 of the Region in key.
CREATE TABLE chart (
  source TEXT NOT NULL,
  key TEXT NOT NULL,
  body TEXT NOT NULL,
  fetched_at INTEGER NOT NULL,
  PRIMARY KEY (source, key)
);
