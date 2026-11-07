-- Candidate Tier tags (#439): words a device's Sync found before the colon at the start of live Listings'
-- names whose removal makes them the same Channel as another live Listing (`PLUS: TNT SPORT 1 HD` beside
-- `MAX: TNT SPORT 1 SD`). Kept per Provider (a SHA-256 of its host) for the weekly issue, and never served:
-- a wrong Tier tag merges two Channels, so one becomes a Tier tag only as a hand line the curator writes.
-- listing_count is how many Listings the word merged in the latest report; example is one of their names.
CREATE TABLE tier_tag_candidate (
  provider TEXT NOT NULL,
  word TEXT NOT NULL,
  listing_count INTEGER NOT NULL,
  example TEXT NOT NULL,
  first_seen INTEGER NOT NULL,
  last_seen INTEGER NOT NULL,
  PRIMARY KEY (provider, word)
);
