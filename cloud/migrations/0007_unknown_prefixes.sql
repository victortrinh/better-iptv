-- Prefixes a device's Sync found that its Prefix table doesn't know, so they get added to it. provider is
-- a SHA-256 of the Provider's host, as for file reads. A Prefix the table has learned is no longer
-- reported, so its last_seen stops moving.
CREATE TABLE unknown_prefix (
  provider TEXT NOT NULL,
  prefix TEXT NOT NULL,
  listing_count INTEGER NOT NULL,
  example TEXT NOT NULL,
  first_seen INTEGER NOT NULL,
  last_seen INTEGER NOT NULL,
  PRIMARY KEY (provider, prefix)
);
