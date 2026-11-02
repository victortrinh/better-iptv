-- A League's Games on one day (UTC `YYYY-MM-DD`), as its schedule source last gave them, as JSON (#252).
CREATE TABLE game (
  league TEXT NOT NULL,
  day TEXT NOT NULL,
  body TEXT NOT NULL,
  fetched_at INTEGER NOT NULL,
  PRIMARY KEY (league, day)
);

-- A League's teams as its source lists them, as JSON: aliases and logos for its Games.
CREATE TABLE team (
  league TEXT PRIMARY KEY,
  body TEXT NOT NULL,
  fetched_at INTEGER NOT NULL
);

-- The Leagues /v1/games was asked for, and when each last was: the Cron Trigger keeps their Games fresh.
CREATE TABLE game_wanted (
  league TEXT PRIMARY KEY,
  asked_at INTEGER NOT NULL
);

-- When the Cron Trigger last started each part it fills (`nhl/2026-11-01`, `nhl/teams`), so a
-- part that stopped its run, or whose source was down, steps aside a while.
CREATE TABLE game_try (
  part TEXT PRIMARY KEY,
  tried_at INTEGER NOT NULL
);
