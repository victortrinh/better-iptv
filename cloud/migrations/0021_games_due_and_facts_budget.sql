-- The Games cron writes less (D1's free plan allows 100k rows written a day, and the cron alone was
-- spending three quarters of it). A day's row now carries when the cron last started reading it
-- (tried_at, what game_try kept) and when it is due again (due_at: minutes after a read while a Game
-- is on or about to start, an hour otherwise; src/games.ts dueAt), so a read costs two row writes, not
-- four. A day or a teams list the cron has started but never read is a placeholder, fetched_at 0.
ALTER TABLE game ADD COLUMN tried_at INTEGER NOT NULL DEFAULT 0;
ALTER TABLE game ADD COLUMN due_at INTEGER NOT NULL DEFAULT 0;
ALTER TABLE team ADD COLUMN tried_at INTEGER NOT NULL DEFAULT 0;
DROP TABLE game_try;

-- How many Titles' facts were cached each UTC day (src/title-facts.ts FACTS_CACHED_A_DAY): a first Sync
-- of a 58k-Title catalog asked for them all at once and spent 130k row writes in an hour.
CREATE TABLE facts_cached (
  day TEXT PRIMARY KEY,
  titles INTEGER NOT NULL
);
