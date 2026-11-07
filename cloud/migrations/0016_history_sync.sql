-- History sync between a household's TVs (#435). Every change an Account's devices push is stamped with the
-- Account's next sync sequence number, and a device pulls what was stamped after its cursor (the last number it got).
-- Each synced table (progress now; profiles, Favourites and settings with #440) stamps from the same counter,
-- so one cursor covers them all. [purged_seq] is the highest number a purged tombstone had: a cursor older than
-- it may have missed a removal, so that device is sent everything again.
ALTER TABLE account ADD COLUMN sync_seq INTEGER NOT NULL DEFAULT 0;
ALTER TABLE account ADD COLUMN purged_seq INTEGER NOT NULL DEFAULT 0;

-- Where the Account stopped in a Movie or an Episode, and whether it Watched it. [title] is the device's Title key:
-- `movie/238` and `show/1399` by TMDB ID, the same for every Provider ([provider] ''), or `movie/listing/123` by
-- Listing, kept per Provider host hash, since a Listing id means nothing on another Provider. An Episode has its
-- season and number; a Movie -1 for both. [removed] is "Remove from Continue watching": a tombstone, kept 30 days.
-- Per row, the change with the latest [updated_at] (the device's clock, never ahead of the cloud's) wins.
CREATE TABLE progress (
  account_id INTEGER NOT NULL REFERENCES account (id) ON DELETE CASCADE,
  provider TEXT NOT NULL,
  title TEXT NOT NULL,
  season INTEGER NOT NULL,
  episode INTEGER NOT NULL,
  position INTEGER NOT NULL,
  duration INTEGER,
  watched INTEGER NOT NULL,
  removed INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  seq INTEGER NOT NULL,
  PRIMARY KEY (account_id, provider, title, season, episode)
);
CREATE INDEX progress_by_seq ON progress (account_id, seq);
