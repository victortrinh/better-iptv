-- Trending (#477, ADR 0014): Wikipedia's most viewed articles of a day, as the pageviews API (CC0) gives them, for
-- each wiki (`en`, `fr`) a device wants. articles is the day's list as JSON, [{"name": "Jailer 3", "views": 812345}],
-- most viewed first, names as Wikipedia shows them (spaces, not underscores); null while the API hasn't published the
-- day yet, tried_at saying when it was last asked. Seven days are kept; the cron drops older ones.
CREATE TABLE trending_day (
  wiki TEXT NOT NULL,
  day TEXT NOT NULL,
  articles TEXT,
  tried_at INTEGER NOT NULL,
  PRIMARY KEY (wiki, day)
);

-- The App languages devices asked Trending in, and when last: a wiki is read while one asked in the last 30 days.
CREATE TABLE trending_wanted (
  wiki TEXT PRIMARY KEY,
  asked_at INTEGER NOT NULL
);
